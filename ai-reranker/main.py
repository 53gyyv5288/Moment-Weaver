"""ai-reranker 独立 FastAPI 服务（sentence-transformers 版本）。

端口 9001（与主 AI 服务 8000 隔离）。
模型 BAAI/bge-reranker-v2-m3，CPU + PyTorch。

为什么从 FlagEmbedding 迁到 sentence-transformers：
  FlagEmbedding 1.2.x 同时引入了两个互斥依赖：
    - 内部 import transformers.trainer → 4.41+ 的 EncoderDecoderCache
    - 内部调 tokenizer.prepare_for_model() → 4.41+ 移除
  任何 transformers 版本都不能同时满足。sentence-transformers 的 CrossEncoder
  没有这两个问题，且本身就是 bge-reranker-v2-m3 的官方推荐用法。

环境变量：
  RERANKER_MODEL  默认 BAAI/bge-reranker-v2-m3
  RERANKER_PORT   默认 9001

依赖：
  pip install "fastapi>=0.111" "uvicorn>=0.30" "sentence-transformers>=3.0" \
              "pydantic>=2.7" "tenacity>=8.3"
  # torch 用 CPU 版：pip install --extra-index-url https://download.pytorch.org/whl/cpu torch
"""
from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
log = logging.getLogger("ai-reranker")

RERANKER_MODEL = os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
RERANKER_PORT = int(os.getenv("RERANKER_PORT", "9001"))


# 懒加载 reranker（在 lifespan 中执行）
_reranker = None


def _load_reranker():
    global _reranker
    if _reranker is not None:
        return _reranker
    from sentence_transformers import CrossEncoder
    t0 = time.perf_counter()
    log.info("Loading reranker model: %s (this may take 10-30s on first run)", RERANKER_MODEL)
    _reranker = CrossEncoder(RERANKER_MODEL)
    # 预热：跑一次空打分让模型充分加载到内存
    try:
        _reranker.predict([("warmup", "warmup")])
    except Exception as e:  # noqa: BLE001
        log.warning("warmup failed (non-fatal): %s", e)
    log.info("Reranker model loaded in %.2fs", time.perf_counter() - t0)
    return _reranker


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 懒加载：先尝试预加载；如果失败（模型未拉），启动时只打 warning
    try:
        _load_reranker()
    except Exception as e:  # noqa: BLE001
        log.warning("Initial reranker load failed: %s. Will retry on first request.", e)
    yield


app = FastAPI(
    title="ai-reranker",
    description="独立 Rerank 服务 (bge-reranker-v2-m3, sentence-transformers backend)",
    version="0.2.0",
    lifespan=lifespan,
)


class RerankRequest(BaseModel):
    query: str = Field(..., min_length=1)
    documents: list[str] = Field(..., min_length=1)
    top_k: int | None = None  # 默认全返回
    normalize: bool = True


class RerankItem(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: list[RerankItem]
    cost_s: float


def _do_rerank(req: RerankRequest) -> RerankResponse:
    rr = _load_reranker()
    pairs = [(req.query, d) for d in req.documents]
    t0 = time.perf_counter()
    try:
        scores = rr.predict(pairs)
    except Exception as e:  # noqa: BLE001
        log.exception("rerank failed")
        raise HTTPException(status_code=500, detail=f"rerank failed: {e}") from e
    cost = time.perf_counter() - t0
    if hasattr(scores, "__iter__"):
        scores = [float(s) for s in scores]
    else:
        scores = [float(scores)]
    # 排序
    indexed = list(enumerate(scores))
    indexed.sort(key=lambda x: -x[1])
    if req.top_k is not None:
        indexed = indexed[: max(1, req.top_k)]
    log.info("rerank: n_in=%d n_out=%d cost=%.3fs", len(req.documents), len(indexed), cost)
    return RerankResponse(
        results=[RerankItem(index=i, score=s) for i, s in indexed],
        cost_s=round(cost, 4),
    )


@app.post("/rerank", response_model=RerankResponse)
async def rerank(req: RerankRequest) -> RerankResponse:
    if not req.documents:
        raise HTTPException(status_code=400, detail="documents 不能为空")
    return _do_rerank(req)


@app.get("/healthz")
async def healthz() -> dict:
    """健康检查：模型是否加载。"""
    ok = _reranker is not None
    return {"status": "ok" if ok else "model_not_loaded", "model": RERANKER_MODEL}


@app.get("/")
async def root() -> dict:
    return {"service": "ai-reranker", "model": RERANKER_MODEL, "port": RERANKER_PORT}
