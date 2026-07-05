"""FastAPI 入口。"""
import logging
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routers import asset, health, interview, narrative, summarize, share_preview, notify, moderation

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
    # 启动钩子：可在此初始化 DeepSeek client、Redis 池等
    yield
    # 关闭钩子


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
# M5-A.4: 分享预览 OG / 通知文案 / 内容审核
app.include_router(share_preview.router, prefix="/api/v1/share-preview", tags=["share-preview"])
app.include_router(notify.router, prefix="/api/v1/notify", tags=["notify"])
app.include_router(moderation.router, prefix="/api/v1/moderation", tags=["moderation"])


@app.get("/")
async def root():
    return {
        "service": settings.app_name,
        "docs": "/docs",
        "healthz": "/healthz",
    }
