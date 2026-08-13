"""FastAPI 入口。"""
import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routers import asset, health, interview, narrative, summarize, share_preview, notify, moderation, summarize_rolling
from app.rag import embedder as rag_embedder
from app.rag import milvus_client as rag_milvus
from app.rag import reranker_client as rag_reranker
from app.rag.routers import rag as rag_router

settings = get_settings()

# 把 app.* 命名空间的日志（narrative / llm / summarize 等）显式开起来。
# 没这段的话，log.warning(...) / log.info(...) 不会打到 stdout，
# 排查 AI 调用链时什么都看不到。
logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    stream=sys.stdout,
)
# uvicorn 的 access log 用 --access-log 开（默认开），但 app.* 的 logger 走自己的 handler，
# 把 propagate=True 确保 uvicorn 启动横幅、reloader 之类也走同一份格式
logging.getLogger("app").setLevel(getattr(logging, settings.log_level.upper(), logging.INFO))


@asynccontextmanager
async def lifespan(app: FastAPI):
    # RAG：配置 DashScope、连 Milvus、建 collection、健康检查 reranker、预热
    try:
        rag_embedder.configure()
        rag_milvus.ensure_collections()
        rag_milvus.warmup()
        rag_ok = await rag_reranker.healthcheck()
        logging.getLogger("app").info(
            "RAG init: reranker_healthcheck=%s collections=%s",
            rag_ok,
            rag_milvus.get_client().list_collections(),
        )
    except Exception as e:  # noqa: BLE001
        # RAG 启动失败不阻塞主 AI 启动（采访 / 叙事不受影响），只是 RAG 路由会降级
        logging.getLogger("app").warning("RAG init failed (continuing without RAG): %s", e)
    yield
    # 关闭钩子
    try:
        rag_milvus.close_client()
    except Exception:  # noqa: BLE001
        pass


app = FastAPI(
    title="Moment Weaver AI",
    version="0.1.0",
    description="AI 采访对话、叙事生成、图像基础分析",
    lifespan=lifespan,
)

# CORS（开发期放行本地前端；生产由 Nginx 统一收口）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router, tags=["health"])
app.include_router(interview.router, prefix="/api/v1/interview", tags=["interview"])
app.include_router(narrative.router, prefix="/api/v1/narrative", tags=["narrative"])
app.include_router(asset.router, prefix="/api/v1/asset", tags=["asset"])
app.include_router(summarize.router, prefix="/api/v1/summarize", tags=["summarize"])
# M7+ STM：滚动摘要端点（短上下文压缩，Java STM 调用）
app.include_router(summarize_rolling.router, prefix="/api/v1/summarize", tags=["summarize"])
# M5-A.4: 分享预览 OG / 通知文案 / 内容审核
app.include_router(share_preview.router, prefix="/api/v1/share-preview", tags=["share-preview"])
app.include_router(notify.router, prefix="/api/v1/notify", tags=["notify"])
app.include_router(moderation.router, prefix="/api/v1/moderation", tags=["moderation"])
# M6+ RAG（plan §4.1）
app.include_router(rag_router.router, prefix="/api/v1/rag", tags=["rag"])


@app.get("/")
async def root():
    return {
        "service": settings.app_name,
        "docs": "/docs",
        "healthz": "/healthz",
    }
