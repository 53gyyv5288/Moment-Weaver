"""RAG FastAPI routes.

POST /api/v1/rag/search       在线检索（Spring 三处集成点都用它）
POST /api/v1/rag/ingest       批量 ingest（一次性 backfill 用）

Internal debug endpoints（dev only）：
GET  /api/v1/rag/_collections         列出 collection + row 数
GET  /api/v1/rag/_healthcheck         Milvus + reranker 连通性
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException

from app.rag import embedder, milvus_client, pipeline_ingest, pipeline_retrieve, reranker_client
from app.rag.schemas import IngestRequest, IngestResponse, SearchRequest, SearchResponse

log = logging.getLogger(__name__)
router = APIRouter()


@router.post("/search", response_model=SearchResponse)
async def search(req: SearchRequest) -> SearchResponse:
    """Spring 端三处（采访 / 时间线 / 成稿）都调这个端点。

    失败/超时降级到空 chunks，不抛 5xx。"""
    if not req.query.strip():
        raise HTTPException(status_code=400, detail="query 不能为空")
    if not req.subject_id:
        raise HTTPException(status_code=400, detail="subject_id 必填")
    try:
        return await pipeline_retrieve.search(req)
    except Exception as e:  # noqa: BLE001
        log.exception("rag search unhandled error")
        raise HTTPException(status_code=503, detail=f"rag search failed: {e}") from e


@router.post("/ingest", response_model=IngestResponse)
async def ingest(req: IngestRequest) -> IngestResponse:
    """批量 ingest（backfill 脚本用）。Spring 事件触发用单条 ingest_*（pipeline_ingest 模块）。"""
    try:
        inserted = await pipeline_ingest.ingest_bulk(req)
        return IngestResponse(inserted=inserted, collection=req.collection)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:  # noqa: BLE001
        log.exception("rag ingest failed")
        raise HTTPException(status_code=503, detail=f"rag ingest failed: {e}") from e


@router.get("/_collections")
async def list_collections() -> dict:
    try:
        client = milvus_client.get_client()
        return {
            "collections": client.list_collections(),
        }
    except Exception as e:  # noqa: BLE001
        raise HTTPException(status_code=503, detail=str(e)) from e


@router.get("/_healthcheck")
async def healthcheck() -> dict:
    """Milvus + reranker 连通性（dev 用）。"""
    milvus_ok = False
    try:
        client = milvus_client.get_client()
        client.list_collections()
        milvus_ok = True
    except Exception as e:  # noqa: BLE001
        log.debug("milvus healthcheck failed: %s", e)
    reranker_ok = await reranker_client.healthcheck()
    return {"milvus": milvus_ok, "reranker": reranker_ok}