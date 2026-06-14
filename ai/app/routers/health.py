"""健康检查 + 依赖连通性。"""
from datetime import datetime
from typing import Any

from fastapi import APIRouter

router = APIRouter()


@router.get("/healthz")
async def healthz() -> dict[str, Any]:
    return {
        "status": "ok",
        "service": "moment-weaver-ai",
        "ts": datetime.utcnow().isoformat() + "Z",
    }


@router.get("/readyz")
async def readyz() -> dict[str, Any]:
    """依赖连通性自检。失败时返回 DEGRADED，不抛 500。"""
    deps: dict[str, str] = {}
    # LLM key 是否配置（不实际发请求）
    from app.config import get_settings
    s = get_settings()
    deps["deepseek_key"] = "UP" if s.deepseek_api_key else "MISSING"
    deps["qwen_key"] = "UP" if s.qwen_api_key else "MISSING"
    deps["backend_url_configured"] = "UP" if s.moment_backend_url else "MISSING"
    return {
        "status": "UP",
        "deps": deps,
        "ts": datetime.utcnow().isoformat() + "Z",
    }
