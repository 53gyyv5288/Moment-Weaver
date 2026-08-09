"""LLM 客户端：OpenAI 兼容协议异步流式调用。"""
import asyncio
import json
import logging
from typing import AsyncIterator, Literal

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)

# 重试策略：仅在「还没向客户端吐出任何 token」时触发。
# 已经吐过的内容如果中断，重试会让前端看到重复文字，体验更差。
_LLM_RETRY_MAX = 1
_LLM_RETRY_BACKOFF_S = 0.5

TokenKind = Literal["text", "think"]


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


class _ThinkRouter:
    """
    把 LLM 流式输出里的 <think>...</think> 块与可见文本分流。

    背景：MiniMax-M3 / DeepSeek-R1 / QwQ 等「思考模式」模型在最终回答之前
    会先输出一段内部推理（放在 <think>...</think> 里）。采访场景下我们希望
    既保留思考链（持久化 + 给用户看），又把它和最终回答分开路由。

    与旧的 _ThinkStripper 区别：本类**不再丢弃** think 块，而是在内部累计
    think 内容，通过 feed() 实时返回可见文本增量，flush() 时一次性给出
    所有 think 内容，让调用方决定怎么存、怎么渲染。

    关键点：LLM 是按 token 流式推送的，标签可能被切成多段送到（"`<t`" + `"hink>`"），
    所以必须用 buffer 攒一下，不能在单 token 内做完整匹配。
    """
    _OPEN = "<think>"
    _CLOSE = "</think>"
    _MAX_TAG_LEN = max(len(_OPEN), len(_CLOSE))  # 9

    def __init__(self) -> None:
        self._buf = ""
        self._in_think = False
        self._think_buf = ""

    def feed(self, token: str) -> str:
        """把一个 token 喂进来，返回应该向客户端吐出的可见文本（可能为空串）。

        think 块内部的内容会累积到 self._think_buf，**不会**出现在返回值里。
        调用方可在合适时机（如流结束、或者每 N 个 think 字符 flush 一次）调用
        drain_think() 取出 think 增量。
        """
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
                    # 还没看到结束：把当前缓冲里这段 think 内容累计起来，
                    # 但要预留 _MAX_TAG_LEN-1 个字符给标签切割兜底。
                    safe_end = max(i, n - (self._MAX_TAG_LEN - 1))
                    if safe_end > i:
                        self._think_buf += self._buf[i:safe_end]
                        i = safe_end
                    break
                else:
                    # </think> 之前的所有字符都是 think 内容
                    if j > i:
                        self._think_buf += self._buf[i:j]
                    self._in_think = False
                    i = j + len(self._CLOSE)
        self._buf = self._buf[i:]
        return "".join(out)

    def drain_think(self) -> str:
        """取出并清空当前累计的 think 增量（增量式，避免一次性大块搬运）。"""
        out = self._think_buf
        self._think_buf = ""
        return out

    def flush(self) -> tuple[str, str]:
        """流结束时调用，返回 (残留可见文本, 残留 think 文本)。

        - 如果还在 think 模式（模型忘了写 </think>），把缓冲里剩余内容也算作 think。
        - 否则把缓冲里残留的可见内容吐出来。
        """
        tail_text = ""
        tail_think = ""
        if self._in_think:
            # 末尾没收尾的 think 块：把它一并当作思考链内容
            tail_think = self._buf
            self._buf = ""
        else:
            tail_text = self._buf
            self._buf = ""
        # think 缓冲里也可能还残留（feed 内部的兜底保留的字符）
        tail_think += self._think_buf
        self._think_buf = ""
        return tail_text, tail_think


# 旧 stripper 保留给非采访路径（summarize / narrative 等）使用——
# 这些场景我们只想要最终回答，不关心 think。
class _ThinkStripper:
    """
    过滤 LLM 流式输出里的 <think>...</think> 块（推理链 / chain-of-thought）。

    给 narrative / summarize / share_preview 等「不需要把思考链暴露给用户」
    的非采访场景使用。采访对话流请用 _ThinkRouter。
    """
    _OPEN = _ThinkRouter._OPEN
    _CLOSE = _ThinkRouter._CLOSE
    _MAX_TAG_LEN = _ThinkRouter._MAX_TAG_LEN

    def __init__(self) -> None:
        self._buf = ""
        self._in_think = False

    def feed(self, token: str) -> str:
        self._buf += token
        out: list[str] = []
        i = 0
        n = len(self._buf)
        while i < n:
            if not self._in_think:
                j = self._buf.find(self._OPEN, i)
                if j == -1:
                    safe_end = max(i, n - (self._MAX_TAG_LEN - 1))
                    if safe_end > i:
                        out.append(self._buf[i:safe_end])
                        i = safe_end
                    break
                else:
                    if j > i:
                        out.append(self._buf[i:j])
                    self._in_think = True
                    i = j + len(self._OPEN)
            else:
                j = self._buf.find(self._CLOSE, i)
                if j == -1:
                    i = n
                    break
                else:
                    self._in_think = False
                    i = j + len(self._CLOSE)
        self._buf = self._buf[i:]
        return "".join(out)

    def flush(self) -> str:
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

    本函数只 yield 可见文本（think 块被丢弃），适用于 narrative / summarize 等
    不需要把思考链暴露给用户的场景。采访对话请用 stream_chat_with_think()。
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


async def stream_chat_with_think(
    messages: list[dict],
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
) -> AsyncIterator[tuple[TokenKind, str]]:
    """
    流式调用 OpenAI 兼容 /chat/completions 端点，同时把 <think>...</think>
    内容与最终回答分开路由。

    yield 形如 ("text", "<token>") 或 ("think", "<token>")。
    - text: 用户能看到的正文片段，按 LLM 推送顺序逐 chunk 给出。
    - think: 模型思考链片段，仅出现在 <think>...</think> 块内。多个
      think 片段可能跨多个 yield 到达，需要调用方自行拼成完整 thinking 字符串。

    适用：采访对话——既要流式把正文给前端，又要持久化思考链。
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
                router = _ThinkRouter()
                async for line in resp.aiter_lines():
                    if not line:
                        continue
                    if line.startswith("data:"):
                        data = line[5:].strip()
                    else:
                        data = line.strip()
                    if data == "[DONE]":
                        break
                    try:
                        obj = json.loads(data)
                    except json.JSONDecodeError:
                        log.debug("skip non-json line: %r", data[:80])
                        continue
                    choice = (obj.get("choices") or [{}])[0]
                    delta = choice.get("delta") or choice.get("message") or {}
                    token = delta.get("content")
                    if not token:
                        continue
                    # 先把 think 增量和 text 增量都取出来（顺序：think 先于 text 输出，
                    # 因为 text 是 feed 返回的"新增可见"，think 是路由内部累计的）。
                    visible = router.feed(token)
                    # feed 内部已经把完整 think 段累进 self._think_buf；这里 drain
                    # 出来，避免下次 feed 时被新 token 覆盖前的"上一次增量"丢失。
                    think_inc = router.drain_think()
                    if think_inc:
                        yield ("think", think_inc)
                    if visible:
                        yield ("text", visible)
                # 流结束：把缓冲里残留的尾巴（含可能没收尾的 think）都吐出来
                tail_text, tail_think = router.flush()
                if tail_think:
                    yield ("think", tail_think)
                if tail_text:
                    yield ("text", tail_text)
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


async def chat(
    messages: list[dict],
    *,
    temperature: float | None = None,
    max_tokens: int | None = None,
    extra_body: dict | None = None,
) -> str:
    """
    非流式 chat：攒齐所有 token 后一次性返回完整文本。
    复用 stream_chat_with_retry 的重试 + think-stripper 行为；
    不同点在于不开 stream，HTTP body 一次拿回来。

    适用：摘要、关键词抽取、改写——结果需要 JSON 解析等「整段语义」操作时。

    extra_body：透传给 LLM 的额外参数（OpenAI 兼容协议的 extra_body 字段）。
    例如 {"chat_template_kwargs": {"enable_thinking": False}} 用来关掉推理模型的思考链。
    """
    s = get_settings()
    url = s.llm_base_url.rstrip("/") + "/chat/completions"
    payload: dict = {
        "model": s.llm_model,
        "messages": messages,
        "stream": False,
        "temperature": temperature if temperature is not None else s.llm_temperature,
        "max_tokens": max_tokens if max_tokens is not None else s.llm_max_tokens,
    }
    if extra_body:
        payload.update(extra_body)
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {s.llm_api_key}",
    }
    timeout = httpx.Timeout(s.llm_timeout_s, connect=10.0)
    last_err: BaseException | None = None
    for attempt in range(_LLM_RETRY_MAX + 1):
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                resp = await client.post(url, json=payload, headers=headers)
                if resp.status_code != 200:
                    body = resp.text[:200]
                    raise LlmError(f"LLM {resp.status_code}: {body}")
                obj = resp.json()
                # DEBUG: 完整 LLM 响应，方便排查 content 为空 / reasoning_content 走丢等问题
                log.debug("LLM chat raw response: %s", json.dumps(obj, ensure_ascii=False)[:2000])
                choice = (obj.get("choices") or [{}])[0]
                msg = choice.get("message") or {}
                content = msg.get("content") or ""
                # 非流式也可能包含 <think>，但这里完整拿到后用 stripper 处理更稳妥
                stripper = _ThinkStripper()
                visible = stripper.feed(content)
                tail = stripper.flush()
                result = (visible + tail).strip()
                # 兜底：推理模型（MiniMax-M3 / DeepSeek-R1 / QwQ）的
                # reasoning_content 字段有时装着「真正的答案」，content 是空
                if not result:
                    reasoning = msg.get("reasoning_content")
                    if reasoning and reasoning.strip():
                        log.info(
                            "LLM chat content empty, fallback to reasoning_content (%d chars)",
                            len(reasoning),
                        )
                        result = reasoning.strip()
                return result
        except (LlmError, httpx.HTTPError) as e:
            last_err = e
            if attempt < _LLM_RETRY_MAX and _should_retry(e):
                log.warning(
                    "LLM chat failed (attempt %d/%d), retrying in %.1fs: %s",
                    attempt + 1, _LLM_RETRY_MAX + 1, _LLM_RETRY_BACKOFF_S, e,
                )
                await asyncio.sleep(_LLM_RETRY_BACKOFF_S)
                continue
            break
    raise LlmError(f"LLM chat failed: {last_err}")
