"""Retrieve pipeline：query → rewrite → embed → hybrid_search → rerank → small-to-big。

3 个场景（plan §4.3）：
  - interview       跨 session 回忆（filter: subject_id；无 is_curated 限制）
  - timeline        时间线素材检索（asset_chunks 集合）
  - narrative_facts 成稿 grounding（filter: is_curated_for_facts == true）
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from app.config import get_settings
from app.rag import authorization, embedder, milvus_client, query_rewrite, reranker_client
from app.rag.schemas import EvidenceChunk, Scenario, SearchRequest, SearchResponse

log = logging.getLogger(__name__)


# 每个场景 → (collection, extra_filter)
_SCENARIO_CONFIG: dict[str, tuple[str, str | None]] = {
    "interview": ("interview_chunks", None),
    "timeline": ("asset_chunks", None),
    "narrative_facts": ("interview_chunks", "is_curated_for_facts == true"),
}


async def search(req: SearchRequest) -> SearchResponse:
    """统一入口。失败/超时降级到空结果（不抛）。"""
    s = get_settings()
    top_k = req.top_k or s.rag_top_k

    # 0) Authorization 防御性校验
    try:
        await authorization.check_subject_authorization(
            subject_id=req.subject_id, user_id=req.user_id,
        )
    except authorization.RagAuthError as e:
        log.warning("rag auth rejected: subject=%s user=%s err=%s",
                    req.subject_id, req.user_id, e)
        return SearchResponse(chunks=[], debug={"auth_rejected": str(e)})

    # 1) Query rewrite（软超时）
    debug: dict[str, Any] = {}
    rewritten = await query_rewrite.rewrite_query(req.query, subject_hint="")
    effective_query = rewritten or req.query
    if rewritten:
        debug["rewritten_query"] = rewritten

    # 2) Embed query
    t0 = time.perf_counter()
    q_vec = await embedder.embed_query(effective_query)
    debug["embed_cost_s"] = round(time.perf_counter() - t0, 3)

    # 3) Hybrid search（dense + BM25）
    collection, extra_filter = _SCENARIO_CONFIG[req.scenario]
    t1 = time.perf_counter()
    try:
        hits = milvus_client.hybrid_search(
            collection=collection,
            query_dense=q_vec,
            # BM25 Function 字段吃原始文本，由服务端分词 —— 不在客户端造稀疏向量
            query_text=effective_query,
            subject_id=req.subject_id,
            top_k=top_k,
            extra_filter=extra_filter or req.extra_filter,
            # output_fields 不传 → milvus_client 按集合自动选合法的字段
        )
    except Exception as e:  # noqa: BLE001
        log.exception("hybrid_search failed")
        return SearchResponse(chunks=[], rewritten_query=rewritten,
                              debug={**debug, "hybrid_error": str(e)})
    debug["hybrid_cost_s"] = round(time.perf_counter() - t1, 3)
    debug["hybrid_hits"] = len(hits)
    if not hits:
        return SearchResponse(chunks=[], rewritten_query=rewritten, debug=debug)

    # 4) Rerank —— 用 chunk_text（small-to-big 模式本意）+ 截断 100 字
    # 实测：max=100 chars → 10 条 ≈ 0.5s；max=320 → 1.5s；max=500 → 2.5s+（超时）
    # 关键：rerank 耗时由 max 长度决定（attention 二次方），不是 avg。
    # 100 字符 (~25 token) 足够 bge-reranker 判断主题相关；最终 evidence
    # 返回完整 parent_text 给 LLM，LLM 拿到的内容不变。
    # 兜底：若 chunk_text 缺失/空，回退 parent_text（防御性，老数据可能有缺字段）。
    chunk_texts = [
        (h["entity"].get("chunk_text", "") or h["entity"].get("parent_text", ""))[:100]
        for h in hits
    ]
    t2 = time.perf_counter()
    scored = await reranker_client.rerank(effective_query, chunk_texts,
                                          top_k=s.rag_top_k_rerank)
    debug["rerank_cost_s"] = round(time.perf_counter() - t2, 3)
    debug["rerank_used"] = scored is not None
    # Debug：实际送到 reranker 的 payload 规模（验证截断生效 + 排查超时）
    if chunk_texts:
        log.info("RAG rerank payload: n=%d, avg_len=%.0f chars, max_len=%d",
                 len(chunk_texts),
                 sum(len(t) for t in chunk_texts) / len(chunk_texts),
                 max(len(t) for t in chunk_texts))

    # 5) 拼装结果（small-to-big：用 small 命中回溯到 parent）
    evidence: list[EvidenceChunk] = []
    if scored is not None:
        # rerank 返回按 score 降序
        for orig_idx, rscore in scored:
            hit = hits[orig_idx]
            ent = hit["entity"]
            evidence.append(EvidenceChunk(
                chunk_id=ent.get("chunk_id") or hit.get("id") and str(hit["id"]),
                parent_text=ent.get("parent_text", "") or ent.get("chunk_text", ""),
                chunk_text=ent.get("chunk_text"),
                score=float(rscore),
                metadata={
                    "subject_id": ent.get("subject_id"),
                    "session_id": ent.get("session_id"),
                    "asset_id": ent.get("asset_id"),
                    "kind": ent.get("kind"),
                    "taken_at": ent.get("taken_at"),
                    "role": ent.get("role"),
                    "is_curated_for_facts": ent.get("is_curated_for_facts"),
                    "original_distance": hit.get("distance"),
                },
            ))
    else:
        # 降级：按 hybrid 顺序取 top_k_rerank
        for hit in hits[: s.rag_top_k_rerank]:
            ent = hit["entity"]
            evidence.append(EvidenceChunk(
                chunk_id=ent.get("chunk_id") or str(hit.get("id", "")),
                parent_text=ent.get("parent_text", "") or ent.get("chunk_text", ""),
                chunk_text=ent.get("chunk_text"),
                score=float(hit.get("score", 0.0)),
                metadata={
                    "subject_id": ent.get("subject_id"),
                    "session_id": ent.get("session_id"),
                    "asset_id": ent.get("asset_id"),
                    "kind": ent.get("kind"),
                    "taken_at": ent.get("taken_at"),
                    "role": ent.get("role"),
                    "is_curated_for_facts": ent.get("is_curated_for_facts"),
                },
            ))
    return SearchResponse(chunks=evidence, rewritten_query=rewritten, debug=debug)


# ============ Fire-and-forget helper（Spring 异步调用）============
#
# InterviewService.streamMessage 不会等 RAG，所以需要一个「非阻塞超时」的版本。
# 这里用 asyncio.wait_for + 超时降级。

async def search_with_soft_timeout(req: SearchRequest,
                                   timeout_s: float | None = None) -> SearchResponse:
    """对 search() 加软超时。超时后返回空结果（debug.soft_timeout=True）。"""
    s = get_settings()
    timeout = timeout_s if timeout_s is not None else s.rag_query_rewrite_timeout_s * 4  # 2.4s
    try:
        return await asyncio.wait_for(search(req), timeout=timeout)
    except asyncio.TimeoutError:
        log.warning("rag search soft-timeout (%.2fs): subject=%s query=%r",
                    timeout, req.subject_id, req.query[:30])
        return SearchResponse(chunks=[], debug={"soft_timeout": True, "timeout_s": timeout})