"""Ingest pipeline：把 InterviewMessage / Asset 切片 → embed → upsert 到 Milvus。

触发源（plan §1）：
  ① Spring Event（@TransactionalEventListener AFTER_COMMIT）→ 调
     /api/v1/rag/ingest/interview_chunks（路由层负责）
  ② 一次性 backfill 脚本（ai/scripts/backfill_rag.py）—— 全量灌库
  ③ 定时 reconciliation（v1.5，先 stub）—— 每 30 min 拉 created_at > last_sync

幂等：chunk_id 唯一 → upsert 自动覆盖；可重跑。
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime
from typing import Any

from app.config import get_settings
from app.rag import chunker, embedder, milvus_client
from app.rag.schemas import ChunkUpsert, IngestRequest

log = logging.getLogger(__name__)


# ============ Common upsert helpers ============

async def _embed_and_upsert(rows: list[dict], *, collection: str) -> int:
    if not rows:
        return 0
    # 1) 一次性 embed 所有 chunk_text（批处理已在 embedder 内做）
    texts = [r["chunk_text"] for r in rows]
    vectors = await embedder.embed_texts(texts)
    for r, v in zip(rows, vectors):
        r["vector"] = v
        # sparse_bm25 由 Milvus Function 在写入时计算，无需客户端填
    # 2) upsert
    if collection == "interview_chunks":
        return milvus_client.upsert_interview_chunks(rows)
    elif collection == "asset_chunks":
        return milvus_client.upsert_asset_chunks(rows)
    raise ValueError(f"unknown collection: {collection}")


# ============ Interview ============

async def ingest_interview_session(
    *, subject_id: str, session_id: str,
    messages: list[dict],
    created_at_ms: int | None = None,
    is_curated_for_facts: bool = False,
    family_id: int = 0,
    family_member_id: int = 0,
) -> int:
    """单 session 灌库。Spring Event 触发。

    messages：Mongo 里的 InterviewMessage 列表（dict 形式，含 role/content/thinking）。

    V15：family_id / family_member_id 写到每条 chunk，跨 family 隔离 + 共享授权。
    0 = 个人项目 / 匿名 subject / 旧 collection 降级。
    """
    s = get_settings()
    raw = chunker.interview_chunks(messages, session_id=session_id)
    if not raw:
        log.debug("ingest_interview_session: %s no chunks, skip", session_id)
        return 0
    ts = created_at_ms or int(time.time() * 1000)
    rows: list[dict] = []
    for c in raw:
        rows.append({
            "chunk_text": c["chunk_text"],
            "parent_text": c["parent_text"],
            "subject_id": subject_id,
            "session_id": session_id,
            "role": c["metadata"].get("role", "user+assistant"),
            "created_at": ts,
            "chunk_id": c["chunk_id"],
            "parent_id": c["parent_id"],
            "is_curated_for_facts": is_curated_for_facts,
            "revoked_at": None,
            # V15
            "family_id": family_id,
            "family_member_id": family_member_id,
        })
    inserted = await _embed_and_upsert(rows, collection=s.milvus_collection_interview)
    log.info("ingest_interview_session: session=%s subject=%s chunks=%d",
             session_id, subject_id, inserted)
    return inserted


# ============ Asset ============

async def ingest_asset(asset: dict, *, linked_messages: list[dict] | None = None) -> int:
    """单 Asset 灌库。Spring Event 触发。

    V15：family_id / family_member_id 由 Spring 调用方从 Subject 查好后传入。
    """
    s = get_settings()
    raw = chunker.asset_chunks(asset, linked_messages=linked_messages)
    if not raw:
        return 0
    subject_id = str(asset.get("subjectId") or "")
    if not subject_id:
        raise ValueError(f"asset {asset.get('id')} missing subjectId")
    # V15：从 asset dict 读 family 字段（Spring ingest 端在写时附带）
    family_id = int(asset.get("familyId") or 0)
    family_member_id = int(asset.get("familyMemberId") or 0)
    rows: list[dict] = []
    for c in raw:
        taken_at = _coerce_ms(c["metadata"].get("taken_at"))
        rows.append({
            "chunk_text": c["chunk_text"],
            "parent_text": c["parent_text"],
            "subject_id": subject_id,
            "asset_id": int(asset.get("id")),
            "kind": c["metadata"].get("kind", "image"),
            "taken_at": taken_at,
            "file_url": c["metadata"].get("file_url", ""),
            "chunk_id": c["chunk_id"],
            "revoked_at": None,
            # V15
            "family_id": family_id,
            "family_member_id": family_member_id,
        })
    inserted = await _embed_and_upsert(rows, collection=s.milvus_collection_asset)
    log.info("ingest_asset: asset=%s subject=%s chunks=%d",
             asset.get("id"), subject_id, inserted)
    return inserted


# ============ Bulk (from FastAPI ingest endpoint) ============

async def ingest_bulk(req: IngestRequest) -> int:
    """FastAPI 端点用：ChunkUpsert 列表 → embed → upsert。"""
    if not req.chunks:
        return 0
    s = get_settings()
    if req.collection == s.milvus_collection_interview:
        rows = [_interview_row(c, is_curated=req.is_curated_for_facts) for c in req.chunks]
        return await _embed_and_upsert(rows, collection=s.milvus_collection_interview)
    if req.collection == s.milvus_collection_asset:
        rows = [_asset_row(c) for c in req.chunks]
        return await _embed_and_upsert(rows, collection=s.milvus_collection_asset)
    raise ValueError(f"unknown collection: {req.collection}")


def _interview_row(c: ChunkUpsert, *, is_curated: bool) -> dict:
    md = c.metadata or {}
    return {
        "chunk_text": c.chunk_text,
        "parent_text": c.parent_text,
        "subject_id": c.subject_id,
        "session_id": md.get("session_id", ""),
        "role": md.get("role", "user+assistant"),
        "created_at": int(md.get("created_at_ms") or time.time() * 1000),
        "chunk_id": c.chunk_id,
        "parent_id": c.parent_id or "",
        "is_curated_for_facts": bool(is_curated or md.get("is_curated_for_facts")),
        "revoked_at": md.get("revoked_at"),
        # V15：从 metadata 透传；缺省 0 = 退回 subject 粒度
        "family_id": int(md.get("family_id") or 0),
        "family_member_id": int(md.get("family_member_id") or 0),
    }


def _asset_row(c: ChunkUpsert) -> dict:
    md = c.metadata or {}
    return {
        "chunk_text": c.chunk_text,
        "parent_text": c.parent_text,
        "subject_id": c.subject_id,
        "asset_id": int(md.get("asset_id", 0)),
        "kind": md.get("kind", "image"),
        "taken_at": _coerce_ms(md.get("taken_at")),
        "file_url": md.get("file_url", ""),
        "chunk_id": c.chunk_id,
        "revoked_at": md.get("revoked_at"),
        # V15
        "family_id": int(md.get("family_id") or 0),
        "family_member_id": int(md.get("family_member_id") or 0),
    }


def _coerce_ms(v: Any) -> int:
    if v is None:
        return 0
    if isinstance(v, (int, float)):
        # 兼容秒/毫秒
        return int(v) if v > 10**12 else int(v * 1000)
    if isinstance(v, str):
        try:
            return int(datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp() * 1000)
        except Exception:  # noqa: BLE001
            return 0
    return 0