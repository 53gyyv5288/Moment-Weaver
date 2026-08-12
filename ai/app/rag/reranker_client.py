"""Reranker 客户端：调 ai-reranker 独立服务（端口 9001）。

特点：
  - 简单 httpx 调 POST /rerank
  - 自带超时（settings.reranker_timeout_s）
  - 断路器：连续失败 N 次后短路一段时
  - 失败时返回 None，调用方降级到 Milvus 排序
"""
from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from typing import Iterable

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)


class RerankerError(RuntimeError):
    pass


class _CircuitBreaker:
    """最小可用断路器：滑动窗口失败次数超阈值 → OPEN 一段时间。"""

    def __init__(self, *, fail_threshold: int = 5, window_s: float = 30.0,
                 cooldown_s: float = 15.0):
        self.fail_threshold = fail_threshold
        self.window_s = window_s
        self.cooldown_s = cooldown_s
        self._fail_stamps: deque[float] = deque()
        self._opened_at: float | None = None

    def allow(self) -> bool:
        if self._opened_at is None:
            return True
        if time.monotonic() - self._opened_at >= self.cooldown_s:
            # 半开：允许一次探测
            self._opened_at = None
            self._fail_stamps.clear()
            return True
        return False

    def record_success(self) -> None:
        self._fail_stamps.clear()
        self._opened_at = None

    def record_failure(self) -> None:
        now = time.monotonic()
        self._fail_stamps.append(now)
        # 滑窗淘汰
        while self._fail_stamps and now - self._fail_stamps[0] > self.window_s:
            self._fail_stamps.popleft()
        if len(self._fail_stamps) >= self.fail_threshold:
            self._opened_at = now
            log.warning("reranker circuit breaker OPEN (cooldown=%.1fs)", self.cooldown_s)


_cb = _CircuitBreaker()


async def rerank(query: str, documents: list[str],
                 top_k: int | None = None) -> list[tuple[int, float]] | None:
    """rerank 一组 documents。返回 [(index_in_documents, score)]，按 score 降序。

    失败 / 断路 / 不可用时返回 None，调用方降级。
    """
    if not documents:
        return []
    if not _cb.allow():
        log.debug("reranker circuit breaker is OPEN, skip")
        return None
    s = get_settings()
    t0 = time.perf_counter()
    try:
        async with httpx.AsyncClient(timeout=s.reranker_timeout_s) as client:
            resp = await client.post(
                f"{s.reranker_url.rstrip('/')}/rerank",
                json={"query": query, "documents": documents},
            )
        if resp.status_code != 200:
            raise RerankerError(f"reranker {resp.status_code}: {resp.text[:200]}")
        body = resp.json()
        # 期望 [{index, score}, ...]；兼容 {"results": [...]} 包裹
        if isinstance(body, dict) and "results" in body:
            body = body["results"]
        if not isinstance(body, list):
            raise RerankerError(f"reranker 返回结构异常：{type(body)}")
        scored = [(int(item["index"]), float(item["score"])) for item in body]
        scored.sort(key=lambda x: -x[1])
        if top_k is not None:
            scored = scored[:top_k]
        _cb.record_success()
        log.debug("reranker ok: n=%d top_k=%d cost=%.2fs",
                  len(documents), top_k or len(scored), time.perf_counter() - t0)
        return scored
    except (httpx.HTTPError, RerankerError, asyncio.TimeoutError) as e:
        _cb.record_failure()
        log.warning("reranker call failed (cost=%.2fs): %s", time.perf_counter() - t0, e)
        return None


async def healthcheck() -> bool:
    """启动时打一次 /healthz。"""
    s = get_settings()
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            r = await client.get(f"{s.reranker_url.rstrip('/')}/healthz")
            return r.status_code == 200
    except Exception:  # noqa: BLE001
        return False