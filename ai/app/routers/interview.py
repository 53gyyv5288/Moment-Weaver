"""AI 采访对话路由（M2 真实流式）。"""
import json
import logging
from typing import AsyncIterator

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.llm import LlmError, stream_chat
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


@router.post("/stream")
async def stream(_req: InterviewRequest) -> StreamingResponse:
    """流式调 LLM，输出纯文本 token（SSE 格式：data: <token>\\n\\n）。"""

    msgs = ensure_system_message([m.model_dump() for m in _req.messages])
    log.info(
        "interview stream start: session=%s subject=%s msgs=%d",
        _req.session_id, _req.subject_hint, len(msgs),
    )

    async def event_source() -> AsyncIterator[bytes]:
        try:
            async for token in stream_chat(msgs):
                # 用 JSON 序列化以避免换行/引号等字符破坏 SSE 协议
                yield f"data: {json.dumps(token, ensure_ascii=False)}\n\n".encode("utf-8")
        except LlmError as e:
            log.exception("LLM error")
            err = json.dumps({"error": str(e)}, ensure_ascii=False)
            yield f"data: {err}\n\n".encode("utf-8")
        # 结束标记
        yield b"data: [DONE]\n\n"

    return StreamingResponse(event_source(), media_type="text/event-stream")


@router.post("/")
async def interview_non_stream(_req: InterviewRequest) -> dict:
    """非流式版本（兼容老调用）：攒齐所有 token 再返回。"""
    msgs = ensure_system_message([m.model_dump() for m in _req.messages])
    chunks: list[str] = []
    try:
        async for token in stream_chat(msgs):
            chunks.append(token)
    except LlmError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e
    return {
        "session_id": _req.session_id,
        "reply": "".join(chunks),
    }
