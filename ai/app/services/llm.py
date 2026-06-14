"""LLM 客户端：OpenAI 兼容协议异步流式调用。"""
import asyncio
import json
import logging
from typing import AsyncIterator

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)

# 重试策略：仅在「还没向客户端吐出任何 token」时触发。
# 已经吐过的内容如果中断，重试会让前端看到重复文字，体验更差。
_LLM_RETRY_MAX = 1
_LLM_RETRY_BACKOFF_S = 0.5


class LlmError(RuntimeError):
    pass


def _should_retry(exc: BaseException) -> bool:
    """连接错误 / 超时 / 429 / 5xx 才重试；4xx 鉴权之类不重试（重试也救不回来）。"""
    if isinstance(exc, (httpx.ConnectError, httpx.ReadTimeout, httpx.WriteTimeout,
                        httpx.PoolTimeout, httpx.RemoteProtocolError)):
        return True
    if isinstance(exc, LlmError):
        msg = str(exc)
        # 上游 5xx 或 429 限流：值得再试一次
        if "LLM 429" in msg or "LLM 5" in msg or "LLM HTTP error" in msg:
            return True
    return False


class _ThinkStripper:
    """
    过滤 LLM 流式输出里的 <think>...</think> 块（推理链 / chain-of-thought）。

    背景：MiniMax-M3 / DeepSeek-R1 / QwQ 等「思考模式」模型在最终回答之前
    会先输出一段内部推理（放在 <think>...</think> 里）。如果不过滤，
    前端会直接把这些内心戏渲染给长辈看，体验极差。

    关键点：LLM 是按 token 流式推送的，标签可能被切成多段送到（"`<t`" + `"hink>`"），
    所以必须用 buffer 攒一下，不能在单 token 内做完整匹配。
    """
    _OPEN = "<think>"
    _CLOSE = "</think>"
    _MAX_TAG_LEN = max(len(_OPEN), len(_CLOSE))  # 9

    def __init__(self) -> None:
        self._buf = ""
        self._in_think = False

    def feed(self, token: str) -> str:
        """把一个 token 喂进来，返回应该向客户端吐出的可见文本（可能为空串）。"""
        self._buf += token
        out: list[str] = []
        i = 0
        n = len(self._buf)
        while i < n:
            if not self._in_think:
                # 在可见区：寻找 <think>
                j = self._buf.find(self._OPEN, i)
                if j == -1:
                    # 缓冲里没看到 <think>，保留最后 _MAX_TAG_LEN-1 个字符（防标签被切）
                    safe_end = max(i, n - (self._MAX_TAG_LEN - 1))
                    if safe_end > i:
                        out.append(self._buf[i:safe_end])
                        i = safe_end
                    break
                else:
                    # 把 <think> 之前的内容吐出来
                    if j > i:
                        out.append(self._buf[i:j])
                    self._in_think = True
                    i = j + len(self._OPEN)
            else:
                # 在 think 块内：寻找 </think>
                j = self._buf.find(self._CLOSE, i)
                if j == -1:
                    # 还没看到结束；整段都先吞掉
                    i = n
                    break
                else:
                    self._in_think = False
                    i = j + len(self._CLOSE)
        self._buf = self._buf[i:]
        return "".join(out)

    def flush(self) -> str:
        """流结束时调用。
        - 如果还在 think 模式（模型忘了写 </think>），整段视为私有不吐。
        - 否则把缓冲里残留的可见内容吐出来。
        """
        if self._in_think:
            return ""
        out = self._buf
        self._buf = ""
        return out


async def stream_chat(
    messages: list[dict],
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
) -> AsyncIterator[str]:
    """
    流式调用 OpenAI 兼容 /chat/completions 端点。
    每个 yield 是一段 token 文本（不含 "data: " 前缀）。
    兼容 DeepSeek / 通义千问 DashScope / Ollama / vLLM / LM Studio / MiniMax-M3。
    """
    s = get_settings()
    url = s.llm_base_url.rstrip("/") + "/chat/completions"
    payload = {
        "model": s.llm_model,
        "messages": messages,
        "stream": True,
        "temperature": temperature if temperature is not None else s.llm_temperature,
        "max_tokens": max_tokens if max_tokens is not None else s.llm_max_tokens,
    }
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {s.llm_api_key}",
    }

    timeout = httpx.Timeout(s.llm_timeout_s, connect=10.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        try:
            async with client.stream("POST", url, json=payload, headers=headers) as resp:
                if resp.status_code != 200:
                    body = await resp.aread()
                    raise LlmError(f"LLM {resp.status_code}: {body[:200].decode(errors='ignore')}")
                stripper = _ThinkStripper()
                async for line in resp.aiter_lines():
                    if not line:
                        continue
                    if line.startswith("data:"):
                        data = line[5:].strip()
                    else:
                        # 某些实现直接传 JSON 不带 data: 前缀，兜底
                        data = line.strip()
                    if data == "[DONE]":
                        break
                    try:
                        obj = json.loads(data)
                    except json.JSONDecodeError:
                        # 某些代理会在 line 里塞注释或心跳，直接跳过
                        log.debug("skip non-json line: %r", data[:80])
                        continue
                    choice = (obj.get("choices") or [{}])[0]
                    delta = choice.get("delta") or choice.get("message") or {}
                    token = delta.get("content")
                    if token:
                        visible = stripper.feed(token)
                        if visible:
                            yield visible
                # 流结束（正常 / 异常 / [DONE]），把缓冲里残留的可见尾巴吐出来
                tail = stripper.flush()
                if tail:
                    yield tail
        except httpx.HTTPError as e:
            raise LlmError(f"LLM HTTP error: {e}") from e


async def stream_chat_with_retry(
    messages: list[dict],
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
) -> AsyncIterator[str]:
    """
    stream_chat 的「首字节前 1 次重试」包装。

    为什么不无脑重试整个流：
      - 流已经开始吐出 token 给前端了，重试会让用户看到重复内容
      - 所以只在「还没 yield 过任何 token」时，对可重试的异常重试 1 次
    """
    for attempt in range(_LLM_RETRY_MAX + 1):  # 1 次原始 + 最多 1 次重试
        yielded_any = False
        try:
            async for token in stream_chat(
                messages,
                temperature=temperature,
                max_tokens=max_tokens,
            ):
                yielded_any = True
                yield token
            return  # 正常结束
        except (LlmError, httpx.HTTPError) as e:
            if yielded_any:
                # 已经吐过 token，重试会导致重复内容，直接抛
                raise
            if attempt < _LLM_RETRY_MAX and _should_retry(e):
                log.warning(
                    "LLM stream failed before first byte (attempt %d/%d), retrying in %.1fs: %s",
                    attempt + 1, _LLM_RETRY_MAX + 1, _LLM_RETRY_BACKOFF_S, e,
                )
                await asyncio.sleep(_LLM_RETRY_BACKOFF_S)
                continue
            # 不可重试的异常，或已达重试上限
            if attempt >= _LLM_RETRY_MAX:
                raise LlmError(f"LLM stream failed after {_LLM_RETRY_MAX} retry: {e}") from e
            raise
