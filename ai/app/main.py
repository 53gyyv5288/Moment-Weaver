"""FastAPI 入口。"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.routers import asset, health, interview, narrative

settings = get_settings()


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


@app.get("/")
async def root():
    return {
        "service": settings.app_name,
        "docs": "/docs",
        "healthz": "/healthz",
    }
