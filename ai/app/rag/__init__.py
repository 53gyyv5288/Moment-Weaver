"""RAG retrieval-augmented generation for Moment Weaver.

Subsystems:
  - chunker            small-to-big chunking for interview & asset
  - embedder           DashScope text-embedding-v3 (batch + retry + QPS guard)
  - milvus_client      collection definitions, hybrid_search, upsert, delete
  - reranker_client    httpx → ai-reranker:9001 /rerank (with circuit breaker)
  - query_rewrite      LLM-driven coreference / time normalization
  - pipeline_ingest    offline ingest (full + incremental + reconciliation hook)
  - pipeline_retrieve  online retrieve (hybrid + rerank + small-to-big)
  - authorization      Authorization.status check + revoked_at writeback
  - routers.rag        FastAPI surface: /api/v1/rag/search, /api/v1/rag/ingest
"""

# 显式 import 子模块，让 `from app.rag import X` 这种语法可用
# （__all__ 单独写只对 `from app.rag import *` 生效）
from . import (  # noqa: F401
    authorization,
    chunker,
    embedder,
    milvus_client,
    pipeline_ingest,
    pipeline_retrieve,
    query_rewrite,
    reranker_client,
)

__all__ = [
    "authorization",
    "chunker",
    "embedder",
    "milvus_client",
    "pipeline_ingest",
    "pipeline_retrieve",
    "query_rewrite",
    "reranker_client",
]