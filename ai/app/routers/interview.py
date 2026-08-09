"""AI 采访对话路由（M2 真实流式）。"""
import logging
from typing import AsyncIterator

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.llm import LlmError, stream_chat_with_retry, stream_chat_with_think
from app.services.prompts import ensure_system_message

log = logging.getLogger(__name__)
router = APIRouter()


class InterviewMessage(BaseModel):
    role: str  # system | user | assistant
    content: str


class InterviewRequest(BaseModel):
    session_id: str = Field(default="")
    subject_hint: str | None = None
    messages: list[InterviewMessage]


def _safe(text: str) -> str:
    """SSE data 行内容兜底：去掉 \\r、把 \\n 换成空格，防止被解析成新 frame。"""
    return text.replace("\r", "").replace("\n", " ")


@router.post("/stream")
async def stream(_req: InterviewRequest) -> StreamingResponse:
    """流式调 LLM，输出 SSE 多事件流。

    协议：
      event: token
      data: <text token>

      event: thinking
      data: <think token>

      event: error
      data: <msg>

      event: done
      data:

    Spring Boot 端用 ServerSentEvent 解析后按 event 名转发给前端 SSE。
    """

    msgs = ensure_system_message([m.model_dump() for m in _req.messages])
    log.info(
        "interview stream start: session=%s subject=%s msgs=%d",
        _req.session_id, _req.subject_hint, len(msgs),
    )

    async def event_source() -> AsyncIterator[bytes]:
        try:
            async for kind, token in stream_chat_with_think(msgs):
                # 直接吐 token 原文。SSE 协议只要求 data 内容以 \\n 结尾、不含 \\r：
                # 中文字符天然没有 \\n；极端场景（如上游意外带换行）做兜底替换，
                # 防止被解析成新的 SSE frame。
                # 不要用 json.dumps() 包成 "..."：那会让 token 自带外层引号，
                # 下游 WebClient 把 data 字段原样吐回 Flux<String> 时会把引号
                # 一起 append 到消息正文，造成 "您""2004年""在""普"... 这种污染。
                safe_token = _safe(token)
                if kind == "think":
                    yield f"event: thinking\ndata: {safe_token}\n\n".encode("utf-8")
                else:
                    yield f"event: token\ndata: {safe_token}\n\n".encode("utf-8")
        except LlmError as e:
            log.exception("LLM error")
            err_msg = _safe(str(e))
            yield f"event: error\ndata: {err_msg}\n\n".encode("utf-8")
        # 标准结束事件：Spring 端看到 event:done 就关流。
        yield b"event: done\ndata:\n\n"

    return StreamingResponse(event_source(), media_type="text/event-stream")


@router.post("/")
async def interview_non_stream(_req: InterviewRequest) -> dict:
    """非流式版本（兼容老调用）：攒齐所有 token 再返回。"""
    msgs = ensure_system_message([m.model_dump() for m in _req.messages])
    chunks: list[str] = []
    try:
        async for token in stream_chat_with_retry(msgs):
            chunks.append(token)
    except LlmError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e
    return {
        "session_id": _req.session_id,
        "reply": "".join(chunks),
    }