"""Milvus client: collection 声明 + 启动时建表 + hybrid_search + upsert + delete。

两个集合（plan §2）：
  - interview_chunks：跨 session 回忆 + 成稿 grounding
  - asset_chunks：时间线素材检索

设计要点：
  - chunk_text (small) 用于 BM25 + dense 索引
  - parent_text (big)   仅存储，不索引；检索命中 small 后回溯返回
  - subject_id + revoked_at 是每次 query 的**必带 filter**
  - chunk_id 是幂等键：upsert 自动覆盖

注：Milvus 2.4 + pymilvus 使用 MilvusClient（高层 SDK）。
    混合检索（dense + sparse BM25）用 collection.hybrid_search()，
    需要函数化 BM25（Function）— 本项目用 sparse vector 字段（已计算好的 BM25 sparse）
    + dense vector 一起召回，再按 RRF / weighted rerank 合并。
"""
from __future__ import annotations

import logging
import time
from typing import Any

from pymilvus import (
    AnnSearchRequest,
    CollectionSchema,
    DataType,
    FieldSchema,
    Function,
    FunctionType,
    MilvusClient,
    RRFRanker,
)
# pymilvus 2.5.0 顶层 __init__ 没导出 IndexParams（存在于 milvus_client/index.py）
from pymilvus.milvus_client.index import IndexParams

from app.config import get_settings

log = logging.getLogger(__name__)


_client: MilvusClient | None = None


def get_client() -> MilvusClient:
    """懒初始化全局 Milvus 客户端。"""
    global _client
    if _client is None:
        s = get_settings()
        _client = MilvusClient(
            uri=s.milvus_uri,
            token=s.milvus_token or None,
            db_name=s.milvus_db or "default",
        )
        log.info("Milvus client connected: uri=%s db=%s", s.milvus_uri, s.milvus_db)
    return _client


def close_client() -> None:
    global _client
    _client = None


# ============ Collection schemas ============

def _interview_schema() -> Any:
    return {
        "fields": [
            # chunk_id 直接做主键：Milvus upsert 语义是「按主键 delete+insert」，
            # 必须能显式提供主键值，所以不能用 auto_id。这也正是幂等键的语义。
            {"field_name": "chunk_id", "data_type": DataType.VARCHAR, "max_length": 128,
             "is_primary": True, "auto_id": False},
            {"field_name": "vector", "data_type": DataType.FLOAT_VECTOR, "dim": 1024},
            {"field_name": "sparse_bm25", "data_type": DataType.SPARSE_FLOAT_VECTOR},
            {"field_name": "chunk_text", "data_type": DataType.VARCHAR, "max_length": 2048,
             "enable_analyzer": True, "analyzer_params": {"type": "standard"}},
            {"field_name": "parent_text", "data_type": DataType.VARCHAR, "max_length": 16384},
            {"field_name": "subject_id", "data_type": DataType.VARCHAR, "max_length": 64},
            {"field_name": "session_id", "data_type": DataType.VARCHAR, "max_length": 64},
            {"field_name": "role", "data_type": DataType.VARCHAR, "max_length": 32},
            {"field_name": "created_at", "data_type": DataType.INT64},
            {"field_name": "parent_id", "data_type": DataType.VARCHAR, "max_length": 128},
            {"field_name": "is_curated_for_facts", "data_type": DataType.BOOL},
            {"field_name": "revoked_at", "data_type": DataType.INT64, "nullable": True},
        ],
        # Function 让 Milvus 在写入时自动从 chunk_text 计算 sparse_bm25
        "functions": [{
            "name": "bm25_fn",
            "function_type": FunctionType.BM25,
            "input_field_names": ["chunk_text"],
            "output_field_names": ["sparse_bm25"],
        }],
    }


def _asset_schema() -> Any:
    return {
        "fields": [
            # chunk_id 直接做主键：Milvus upsert 语义是「按主键 delete+insert」，
            # 必须能显式提供主键值，所以不能用 auto_id。这也正是幂等键的语义。
            {"field_name": "chunk_id", "data_type": DataType.VARCHAR, "max_length": 128,
             "is_primary": True, "auto_id": False},
            {"field_name": "vector", "data_type": DataType.FLOAT_VECTOR, "dim": 1024},
            {"field_name": "sparse_bm25", "data_type": DataType.SPARSE_FLOAT_VECTOR},
            {"field_name": "chunk_text", "data_type": DataType.VARCHAR, "max_length": 2048,
             "enable_analyzer": True, "analyzer_params": {"type": "standard"}},
            {"field_name": "parent_text", "data_type": DataType.VARCHAR, "max_length": 16384},
            {"field_name": "subject_id", "data_type": DataType.VARCHAR, "max_length": 64},
            {"field_name": "asset_id", "data_type": DataType.INT64},
            {"field_name": "kind", "data_type": DataType.VARCHAR, "max_length": 16},
            {"field_name": "taken_at", "data_type": DataType.INT64},
            {"field_name": "file_url", "data_type": DataType.VARCHAR, "max_length": 512},
            {"field_name": "revoked_at", "data_type": DataType.INT64, "nullable": True},
        ],
        "functions": [{
            "name": "bm25_fn",
            "function_type": FunctionType.BM25,
            "input_field_names": ["chunk_text"],
            "output_field_names": ["sparse_bm25"],
        }],
    }


_INTERVIEW_INDEX_PARAMS = [
    {"field_name": "vector", "index_type": "HNSW",
     "metric_type": "COSINE",
     "params": {"M": 16, "efConstruction": 200}},
    # sparse_bm25 是 BM25 Function 的输出字段，metric_type 必须是 BM25。
    # （注：若服务端 < 2.5，Function 不被识别、该字段退化为普通稀疏向量，
    #   那时才需要 IP —— 但本项目要求 Milvus >= 2.5，见 docker-compose.yml）
    {"field_name": "sparse_bm25", "index_type": "SPARSE_INVERTED_INDEX",
     "metric_type": "BM25",
     "params": {"inverted_index_algo": "DAAT_MAXSCORE"}},
    {"field_name": "subject_id", "index_type": "INVERTED"},
    {"field_name": "session_id", "index_type": "INVERTED"},
    {"field_name": "revoked_at", "index_type": "INVERTED"},
    {"field_name": "is_curated_for_facts", "index_type": "INVERTED"},
]

_ASSET_INDEX_PARAMS = [
    {"field_name": "vector", "index_type": "HNSW",
     "metric_type": "COSINE",
     "params": {"M": 16, "efConstruction": 200}},
    # Milvus 2.5+: sparse vector 索引的 metric_type 只接受 IP
    {"field_name": "sparse_bm25", "index_type": "SPARSE_INVERTED_INDEX",
     "metric_type": "BM25",
     "params": {"inverted_index_algo": "DAAT_MAXSCORE"}},
    {"field_name": "subject_id", "index_type": "INVERTED"},
    {"field_name": "kind", "index_type": "INVERTED"},
    {"field_name": "taken_at", "index_type": "INVERTED"},
    {"field_name": "revoked_at", "index_type": "INVERTED"},
]


# ============ Per-collection output_fields ============
#
# 为什么不能共用一份：interview_chunks 没有 asset_id/kind/taken_at，
# asset_chunks 没有 session_id/role/is_curated_for_facts。
# Milvus 在 output_fields 里写不存在的字段会直接报
# "field <x> not exist"，整个 hybrid_search 失败 —— 即使该字段
# 在另一集合里存在。
# 每个集合只暴露它真实有的字段，避免误用。
_OUTPUT_FIELDS_BY_COLLECTION: dict[str, list[str]] = {
    "interview_chunks": [
        "chunk_id", "chunk_text", "parent_text",
        "subject_id", "session_id", "role",
        "created_at", "parent_id", "is_curated_for_facts",
    ],
    "asset_chunks": [
        "chunk_id", "chunk_text", "parent_text",
        "subject_id", "asset_id", "kind",
        "taken_at", "file_url",
    ],
}


def default_output_fields(collection: str) -> list[str]:
    """按集合返回合法的 output_fields；缺省按 interview_chunks。"""
    return _OUTPUT_FIELDS_BY_COLLECTION.get(
        collection,
        _OUTPUT_FIELDS_BY_COLLECTION["interview_chunks"],
    )


# ============ Init / lifecycle ============

def ensure_collections() -> None:
    """启动时调用：建集合（如不存在）+ 建索引 + load。"""
    client = get_client()
    s = get_settings()
    _ensure_one(client, s.milvus_collection_interview,
                _interview_schema(), _INTERVIEW_INDEX_PARAMS)
    _ensure_one(client, s.milvus_collection_asset,
                _asset_schema(), _ASSET_INDEX_PARAMS)


def _ensure_one(client: MilvusClient, name: str,
                schema: dict, index_params_list: list[dict]) -> None:
    if client.has_collection(name):
        log.info("Milvus collection %s already exists", name)
    else:
        log.info("Creating Milvus collection %s ...", name)
        # 把 dict-style schema 装成 pymilvus 2.5+ 的 CollectionSchema 对象
        # （create_collection 2.5+ 会调 schema.verify() 校验，dict 没这个方法）
        # 注意 FieldSchema 2.5+ 参数名是 name / dtype（不是 field_name / data_type）
        fields = []
        for f in schema["fields"]:
            kwargs = {k: v for k, v in f.items() if k not in ("field_name", "data_type")}
            fields.append(FieldSchema(name=f["field_name"], dtype=f["data_type"], **kwargs))
        functions = [Function(**f) for f in schema.get("functions", [])]
        if functions:
            schema_obj = CollectionSchema(fields=fields, functions=functions)
        else:
            schema_obj = CollectionSchema(fields=fields)
        client.create_collection(
            collection_name=name,
            schema=schema_obj,
            consistency_level="Strong",
        )
        log.info("Created Milvus collection %s", name)
    # 索引（create_collection 已自动建主键索引；其他字段需显式建）
    # 逐个字段单独建：一次性传全部的话，任一字段失败会导致其余字段也建不上，
    # 而且看不出是哪个字段的问题。
    existing = set()
    try:
        existing = set(client.list_indexes(collection_name=name))
    except Exception as e:  # noqa: BLE001
        log.debug("list_indexes %s failed: %s", name, e)
    for idx in index_params_list:
        field = idx["field_name"]
        if field in existing:
            continue
        ip = IndexParams()
        ip.add_index(
            field_name=field,
            index_type=idx["index_type"],
            metric_type=idx.get("metric_type", ""),
            params=idx.get("params") or {},
        )
        try:
            client.create_index(collection_name=name, index_params=ip)
        except Exception as e:  # noqa: BLE001
            # 不能吞：索引建不上 → load 会失败 → 整个检索链路瘫痪，
            # 但 get_collection_stats 仍能读到行数，极易误判成「数据没写进去」。
            log.error("create_index FAILED: collection=%s field=%s: %s", name, field, e)
            raise
    # load（Milvus 容器重启后 load 状态会丢失，每次启动都要重新 load）
    client.load_collection(name)
    log.info("Milvus collection %s loaded", name)


def warmup() -> None:
    """执行一次轻量查询，预热 OS cache + ANN 索引。"""
    client = get_client()
    s = get_settings()
    for c in (s.milvus_collection_interview, s.milvus_collection_asset):
        try:
            # 用 zero vector + 假 subject_id 跑一次 hybrid_search
            zero = [0.0] * s.embedding_dim
            req_dense = AnnSearchRequest(
                data=[zero], anns_field="vector",
                param={"metric_type": "COSINE"}, limit=1,
            )
            # BM25 Function 字段：传原始文本，服务端负责分词打分
            req_sparse = AnnSearchRequest(
                data=["预热"], anns_field="sparse_bm25",
                param={"metric_type": "BM25"}, limit=1,
            )
            client.hybrid_search(
                collection_name=c,
                reqs=[req_dense, req_sparse],
                ranker=RRFRanker(),
                limit=1,
                output_fields=["chunk_id"],
                filter='subject_id == "__warmup__"',
            )
        except Exception as e:  # noqa: BLE001
            log.warning("warmup %s skipped: %s", c, e)


# ============ Operations ============

def _ensure_subject_filter(subject_id: str, extra: str | None) -> str:
    """强制带 subject_id + revoked_at IS NULL。"""
    parts = [f'subject_id == "{subject_id}"', "revoked_at IS NULL"]
    if extra:
        parts.append(f"({extra})")
    return " AND ".join(parts)


def upsert_interview_chunks(rows: list[dict]) -> int:
    """rows 每条应已含 vector + sparse_bm25(Milvus 自动算) + 标量字段。
    chunk_id 唯一：upsert 自动覆盖。
    """
    if not rows:
        return 0
    client = get_client()
    s = get_settings()
    client.upsert(
        collection_name=s.milvus_collection_interview,
        data=rows,
    )
    return len(rows)


def upsert_asset_chunks(rows: list[dict]) -> int:
    if not rows:
        return 0
    client = get_client()
    s = get_settings()
    client.upsert(
        collection_name=s.milvus_collection_asset,
        data=rows,
    )
    return len(rows)


def hybrid_search(
    *,
    collection: str,
    query_dense: list[float],
    query_text: str,
    subject_id: str,
    top_k: int,
    extra_filter: str | None = None,
    output_fields: list[str] | None = None,
    ef: int = 64,
) -> list[dict]:
    """混合检索：dense ANN + sparse BM25，RRF 融合。

    query_text 直接传原始查询文本（不是稀疏向量）：sparse_bm25 是 BM25 Function
    的输出字段，Milvus 服务端会用同一个 analyzer 把查询文本转成稀疏向量，
    保证与写入时的分词口径一致。客户端自己构造稀疏向量会被拒绝。
    """
    client = get_client()
    if output_fields is None:
        # 必须按集合选：interview_chunks 没有 asset_id/kind/taken_at，
        # asset_chunks 没有 session_id/role/is_curated_for_facts。
        output_fields = default_output_fields(collection)
    req_dense = AnnSearchRequest(
        data=[query_dense],
        anns_field="vector",
        param={"metric_type": "COSINE", "ef": ef},
        limit=top_k,
    )
    req_sparse = AnnSearchRequest(
        data=[query_text],
        anns_field="sparse_bm25",
        param={"metric_type": "BM25"},
        limit=top_k,
    )
    flt = _ensure_subject_filter(subject_id, extra_filter)
    t0 = time.perf_counter()
    results = client.hybrid_search(
        collection_name=collection,
        reqs=[req_dense, req_sparse],
        ranker=RRFRanker(k=60),
        limit=top_k,
        output_fields=output_fields,
        filter=flt,
    )
    log.debug("hybrid_search %s top_k=%d cost=%.3fs",
              collection, top_k, time.perf_counter() - t0)
    # results[0] 是单 query 的 list[Hit]
    if not results:
        return []
    out: list[dict] = []
    for hit in results[0]:
        entity = hit.get("entity", {}) or {}
        out.append({
            "id": hit.get("id"),
            "distance": hit.get("distance"),
            "score": hit.get("score", 1.0 - hit.get("distance", 0.0)),
            "entity": entity,
        })
    return out


def mark_revoked(collection: str, chunk_id: str, revoked_at_ms: int) -> None:
    """Authorization 撤销后写回 revoked_at。filter 防御是首选，这里是事后收敛。"""
    client = get_client()
    client.upsert(
        collection_name=collection,
        data=[{"chunk_id": chunk_id, "revoked_at": revoked_at_ms}],
    )


# ============ Query-rewrite helper ============

# 注：曾经这里有个 encode_query_sparse()，在客户端构造稀疏向量。
# 那是 Milvus 2.4 时代的退化方案（2.4 不支持 BM25 Function，只能返回空稀疏向量，
# 等于放弃了 sparse 召回）。升到 2.5 后 BM25 Function 生效，检索时直接把原始
# 查询文本传给 hybrid_search(query_text=...) 即可，服务端会用与写入相同的
# analyzer 分词打分 —— 口径一致，且 sparse 召回真正可用。