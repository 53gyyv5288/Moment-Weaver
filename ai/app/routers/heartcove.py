"""AI 心声信箱对话路由（M13+）。

端点：
  POST /heartcove/stream   流式生成 AI 回应（SSE）
  POST /heartcove/summary  生成对话滚动摘要（短期记忆 → 中期记忆）

输入由 Spring Boot module-heartcove 提供：
  - subject_id, session_id
  - persona_summary (AI 抽取自采访素材)
  - related_quotes (从 interview_message 检索得到)
  - recent_dialog (会话内最近 8 条消息)
  - user_msg (用户当前输入)
  - style_tone (温和长辈 / 平实日常 / 简洁克制)

设计原则：
  1. 不进入 milvus——本路由不调用任何向量库；素材由 Spring Boot 端注入。
  2. 不知道话术池 + 危机干预全部在 Python 侧预生成。
  3. SSE 输出 token / done / error 三个事件，与 interview.py 一致。
"""

import logging
from typing import AsyncIterator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.heartcove_phrases import (
    detect_unknown_type,
    get_crisis_interrupt,
    pick_phrase,
)
from app.services.heartcove_prompts import (
    HEARTCOVE_PROMPT_VERSION,
    build_system_prompt,
)
from app.services.llm import LlmError, stream_chat_with_retry

log = logging.getLogger(__name__)
router = APIRouter()


# ---- 请求 / 响应 schema ----

class QuoteItem(BaseModel):
    content: str
    source: str = ""


class DialogItem(BaseModel):
    role: str       # user | assistant
    content: str


class HeartcoveStreamRequest(BaseModel):
    session_id: str = Field(default="")
    subject_id: str = Field(default="")
    display_name: str = Field(default="先辈")
    age_hint: str = Field(default="长辈")          # 如 "80 岁" / "出生于 1942 年"
    style_tone: str = Field(default="温和长辈")    # 温和长辈 / 平实日常 / 简洁克制
    persona_summary: str = Field(default="")
    recent_dialog: list[DialogItem] = Field(default_factory=list)
    related_quotes: list[QuoteItem] = Field(default_factory=list)
    user_msg: str


class HeartcoveSummaryRequest(BaseModel):
    session_id: str = Field(default="")
    subject_id: str = Field(default="")
    recent_dialog: list[DialogItem] = Field(default_factory=list)
    last_summary: str = Field(default="")


class HeartcoveSummaryResponse(BaseModel):
    summary: str
    version: str = HEARTCOVE_PROMPT_VERSION


def _safe(text: str) -> str:
    return text.replace("\r", "").replace("\n", " ")


# ---- 不知道话术池注入（每类预生成 5 句） ----

def _build_unknown_phrases() -> dict:
    return {
        "modern_topic": [pick_phrase("modern_topic") for _ in range(5)],
        "no_material":  [pick_phrase("no_material")  for _ in range(5)],
        "emotion_support": [pick_phrase("emotion_support") for _ in range(5)],
        "boundary":     [pick_phrase("boundary")     for _ in range(5)],
        "crisis_interrupt": get_crisis_interrupt(),
    }


# ---- 流式对话 ----

@router.post("/stream")
async def stream(req: HeartcoveStreamRequest) -> StreamingResponse:
    """心声信箱流式对话。SSE 协议：
      event: token
      data: <text token>

      event: meta          (附带 unknown_type 与 source 引用)
      data: {json}

      event: error
      data: <msg>

      event: done
      data:
    """
    unknown_phrases = _build_unknown_phrases()

    # 1. 危机检测：最高优先级
    unknown_type = detect_unknown_type(req.user_msg)
    if unknown_type == "crisis_detected":
        async def crisis_event_source() -> AsyncIterator[bytes]:
            interrupt = get_crisis_interrupt()
            yield f"event: token\ndata: {_safe(interrupt)}\n\n".encode("utf-8")
            yield b'event: meta\ndata: {"unknown_type":"crisis_detected"}\n\n'
            yield b"event: done\ndata:\n\n"
        return StreamingResponse(crisis_event_source(), media_type="text/event-stream")

    # 2. 命中不知道话术池（modern_topic / boundary）→ 短路返回一句
    if unknown_type in ("modern_topic", "boundary"):
        phrase = pick_phrase(unknown_type)
        async def short_event_source() -> AsyncIterator[bytes]:
            yield f"event: token\ndata: {_safe(phrase)}\n\n".encode("utf-8")
            yield f'event: meta\ndata: {{"unknown_type":"{unknown_type}"}}\n\n'.encode("utf-8")
            yield b"event: done\ndata:\n\n"
        return StreamingResponse(short_event_source(), media_type="text/event-stream")

    # 3. 无召回到相关原话 → no_material 兜底
    has_quote = bool(req.related_quotes)
    if not has_quote and unknown_type is None:
        # 既没现代词/边界词，也没召回原话：直接走 no_material
        unknown_type = "no_material"
        phrase = pick_phrase("no_material")
        async def no_material_source() -> AsyncIterator[bytes]:
            yield f"event: token\ndata: {_safe(phrase)}\n\n".encode("utf-8")
            yield f'event: meta\ndata: {{"unknown_type":"{unknown_type}"}}\n\n'.encode("utf-8")
            yield b"event: done\ndata:\n\n"
        return StreamingResponse(no_material_source(), media_type="text/event-stream")

    # 4. 走 LLM 流式生成
    sys_prompt = build_system_prompt(
        display_name=req.display_name,
        age_hint=req.age_hint,
        style_tone=req.style_tone,
        persona_summary=req.persona_summary,
        recent_dialog=[m.model_dump() for m in req.recent_dialog],
        related_quotes=[q.model_dump() for q in req.related_quotes],
        unknown_phrases=unknown_phrases,
    )
    msgs = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": req.user_msg},
    ]
    log.info(
        "heartcove stream start: session=%s subject=%s quotes=%d recent=%d",
        req.session_id, req.subject_id, len(req.related_quotes), len(req.recent_dialog),
    )

    async def event_source() -> AsyncIterator[bytes]:
        try:
            async for token in stream_chat_with_retry(msgs):
                yield f"event: token\ndata: {_safe(token)}\n\n".encode("utf-8")
            # 附带 meta
            import json
            meta = {
                "unknown_type": unknown_type,
                "source_quote_ids": [i for i in range(len(req.related_quotes))],
            }
            yield f"event: meta\ndata: {json.dumps(meta, ensure_ascii=False)}\n\n".encode("utf-8")
        except LlmError as e:
            log.exception("heartcove LLM error")
            err_msg = _safe(str(e))
            yield f"event: error\ndata: {err_msg}\n\n".encode("utf-8")
        yield b"event: done\ndata:\n\n"

    return StreamingResponse(event_source(), media_type="text/event-stream")


# ---- 滚动摘要 ----

@router.post("/summary", response_model=HeartcoveSummaryResponse)
async def summarize(req: HeartcoveSummaryRequest) -> HeartcoveSummaryResponse:
    """把最近 8 条对话压缩成一段滚动摘要（中期记忆）。

    设计：固定 prompt，输出 ≤ 200 字，纯文本。
    """
    if not req.recent_dialog:
        return HeartcoveSummaryResponse(summary=req.last_summary or "")

    sys_prompt = (
        "你是对话摘要助手。请根据以下对话与既往摘要，生成一段不超过 200 字的滚动摘要，"
        "覆盖：用户的情感状态、提到的人/事/物、对话进展。不要添加原对话中没有的内容。"
        "使用第三人称「用户」描述用户，使用「先辈」描述被复刻方。"
    )
    user_payload = (
        f"【既往摘要】\n{req.last_summary or '（无）'}\n\n"
        f"【最近对话】\n" +
        "\n".join(f"{('用户' if m.role=='user' else '先辈')}: {m.content}" for m in req.recent_dialog)
    )
    msgs = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": user_payload},
    ]
    chunks: list[str] = []
    try:
        async for tok in stream_chat_with_retry(msgs):
            chunks.append(tok)
    except LlmError as e:
        log.exception("heartcove summary LLM error")
        return HeartcoveSummaryResponse(summary=req.last_summary or "")
    return HeartcoveSummaryResponse(summary="".join(chunks).strip())