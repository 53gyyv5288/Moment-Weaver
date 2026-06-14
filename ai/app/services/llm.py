"""LLM 客户端：OpenAI 兼容协议异步流式调用。"""
import json
import logging
from typing import AsyncIterator

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)


class LlmError(RuntimeError):
    pass


async def stream_chat(
    messages: list[dict],
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
) -> AsyncIterator[str]:
    """
    流式调用 OpenAI 兼容 /chat/completions 端点。
    每个 yield 是一段 token 文本（不含 "data: " 前缀）。
    兼容 DeepSeek / 通义千问 DashScope / Ollama / vLLM / LM Studio。
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
                        yield token
        except httpx.HTTPError as e:
            raise LlmError(f"LLM HTTP error: {e}") from e
