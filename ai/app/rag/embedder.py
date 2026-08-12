"""DashScope text-embedding-v3 封装。

特点：
  - 批处理（batch_size=settings.embedding_batch_size）
  - 重试（tenacity，指数退避）
  - QPS 保护：asyncio.Semaphore 控制并发
  - 失败时 raise EmbeddingError；调用方决定是否降级

注：DashScope text-embedding-v3 单请求最多 25 文本，max 2048 chars/文本；
    本项目 chunk_text 已经限到 2048。
"""
from __future__ import annotations

import asyncio
import logging
import time

import dashscope
from dashscope import TextEmbedding
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from app.config import get_settings

log = logging.getLogger(__name__)


class EmbeddingError(RuntimeError):
    pass


_sem: asyncio.Semaphore | None = None  # 懒初始化


def _get_sem() -> asyncio.Semaphore:
    global _sem
    if _sem is None:
        s = get_settings()
        # 简化：QPS 转并发数。DashScope 没有官方并发上限，按 batch + 0.5s/RTT 估
        _sem = asyncio.Semaphore(max(1, int(s.embedding_qps_limit / 8)))
    return _sem


def configure() -> None:
    """启动时调一次，注入 API key。"""
    s = get_settings()
    if s.dashscope_api_key:
        dashscope.api_key = s.dashscope_api_key
        log.info("DashScope API key configured for embeddings")
    else:
        log.warning("DASHSCOPE_API_KEY not set — embedding calls will fail")


@retry(
    reraise=True,
    retry=retry_if_exception_type((EmbeddingError, ConnectionError, TimeoutError)),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=0.5, min=0.5, max=4.0),
)
async def _embed_batch(batch: list[str]) -> list[list[float]]:
    """调 DashScope 一次，返回 N 条向量。"""
    s = get_settings()
    if not s.dashscope_api_key:
        raise EmbeddingError("DASHSCOPE_API_KEY 未配置")
    sem = _get_sem()
    async with sem:
        # dashscope SDK 是同步的，放到默认线程池
        loop = asyncio.get_running_loop()

        def _call() -> list[list[float]]:
            resp = TextEmbedding.call(
                model=s.embedding_model,
                input=batch,
                dimension=s.embedding_dim,  # text-embedding-v3 支持自定义维度
            )
            if resp.status_code != 200:
                raise EmbeddingError(
                    f"DashScope {resp.status_code}: {getattr(resp, 'message', '')[:200]}"
                )
            embeddings = [e["embedding"] for e in resp.output["embeddings"]]
            if len(embeddings) != len(batch):
                raise EmbeddingError(
                    f"embedding 数量不匹配：请求 {len(batch)} 返回 {len(embeddings)}"
                )
            # 维度校验
            for i, vec in enumerate(embeddings):
                if len(vec) != s.embedding_dim:
                    raise EmbeddingError(
                        f"chunk[{i}] 维度={len(vec)} != settings={s.embedding_dim}"
                    )
            return embeddings

        t0 = time.perf_counter()
        try:
            result = await asyncio.wait_for(
                loop.run_in_executor(None, _call),
                timeout=30.0,
            )
        except asyncio.TimeoutError as e:
            raise EmbeddingError("DashScope embedding timeout") from e
        except EmbeddingError:
            raise
        except Exception as e:
            raise EmbeddingError(f"DashScope 调用异常: {e}") from e
        log.debug("embedding batch ok: n=%d dim=%d cost=%.2fs",
                  len(batch), s.embedding_dim, time.perf_counter() - t0)
        return result


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量嵌入，自动切片到 batch_size。空串返回零向量。"""
    if not texts:
        return []
    s = get_settings()
    out: list[list[float] | None] = [None] * len(texts)  # type: ignore[list-item]
    # 收集非空索引
    work: list[tuple[int, str]] = []
    for i, t in enumerate(texts):
        t2 = (t or "").strip()
        if not t2:
            out[i] = [0.0] * s.embedding_dim
        else:
            work.append((i, t2[:2000]))  # 限 2000 留余量
    # 切片 batch
    bs = s.embedding_batch_size
    for start in range(0, len(work), bs):
        batch = work[start:start + bs]
        idxs = [b[0] for b in batch]
        strs = [b[1] for b in batch]
        vecs = await _embed_batch(strs)
        for i, v in zip(idxs, vecs):
            out[i] = v
    # 兜底：任何 None 视为零向量（极端异常时）
    for i, v in enumerate(out):
        if v is None:
            log.warning("embed_texts: index %d got None, fallback to zero vector", i)
            out[i] = [0.0] * s.embedding_dim
    return out  # type: ignore[list-item]


async def embed_query(query: str) -> list[float]:
    """单条 query 嵌入（在线检索用）。"""
    vecs = await embed_texts([query])
    return vecs[0]