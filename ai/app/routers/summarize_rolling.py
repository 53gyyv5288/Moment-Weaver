"""滚动摘要路由（M7+ STM）。

与 summarize.py 的区别：
  - summarize.py     → 整段对话生成结构化摘要（title/goldenQuotes/keyMoments）
  - summarize_rolling → 旧 summary + 被淘汰的 K/2 条原文 → 新的纯文本摘要

prompt 与 chat() 复用现有服务，不重新接 LLM。
"""
import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import get_settings
from app.services.llm import LlmError, chat
from app.services.prompts import (
    build_condense_summary_messages,
    build_rolling_summary_messages,
)

log = logging.getLogger(__name__)
router = APIRouter()


class EvictedMessage(BaseModel):
    """即将从 recent 列表淘汰的 K/2 条原文。"""
    role: str
    content: str


class RollingSummarizeRequest(BaseModel):
    session_id: str = Field(default="")
    old_summary: str | None = None
    evicted_messages: list[EvictedMessage]
    # 可选：覆盖默认 max_tokens（STM 服务按配置传）
    max_tokens: int | None = None


@router.post("/rolling")
async def rolling_summary(req: RollingSummarizeRequest) -> dict:
    """生成新的滚动摘要。

    输入：旧摘要（可空）+ 被淘汰的 N 条原文（user/assistant）。
    输出：整合后的新摘要纯文本。
    """
    if not req.evicted_messages:
        raise HTTPException(status_code=400, detail="evicted_messages 不能为空")

    msgs = build_rolling_summary_messages(
        old_summary=req.old_summary or "",
        evicted=[m.model_dump() for m in req.evicted_messages],
    )

    settings = get_settings()
    cap = req.max_tokens if req.max_tokens and req.max_tokens > 0 else settings.llm_rolling_summary_max_tokens

    log.info(
        "rolling_summary start: session=%s evicted=%d old_summary_len=%d cap=%d",
        req.session_id, len(req.evicted_messages),
        len(req.old_summary) if req.old_summary else 0,
        cap,
    )
    try:
        summary = await chat(
            msgs,
            temperature=0.3,  # 比 full summary (0.1) 稍高，允许措辞灵活
            max_tokens=cap,
        )
    except LlmError as e:
        log.warning("rolling_summary LLM error (treated as peak): %s", e)
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e
    except Exception as e:
        log.exception("rolling_summary unexpected error")
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e

    cleaned = (summary or "").strip()
    log.info(
        "rolling_summary ok: session=%s new_summary_len=%d",
        req.session_id, len(cleaned),
    )
    return {"summary": cleaned, "session_id": req.session_id}


# ============ M8+ STM：summary 二次压缩 ============
# 当 STM summary 因滚动累积超过硬限（默认 1500 字符）时，Java 端会调这个端点
# 仅压 summary 自身（不喂新对话），目标缩回 400-600 字。

class CondenseSummarizeRequest(BaseModel):
    session_id: str = Field(default="")
    summary: str = Field(min_length=1)
    max_tokens: int | None = None


@router.post("/condense")
async def condense_summary(req: CondenseSummarizeRequest) -> dict:
    """把过长的 summary 二次压缩（仅压 summary 自身）。

    输入：当前 summary 纯文本。
    输出：压缩后的新 summary 纯文本。
    """
    if not req.summary or not req.summary.strip():
        raise HTTPException(status_code=400, detail="summary 不能为空")

    msgs = build_condense_summary_messages(req.summary)

    settings = get_settings()
    cap = req.max_tokens if req.max_tokens and req.max_tokens > 0 else settings.llm_rolling_summary_max_tokens

    log.info(
        "condense_summary start: session=%s summary_len=%d cap=%d",
        req.session_id, len(req.summary), cap,
    )
    try:
        summary = await chat(
            msgs,
            temperature=0.2,  # 比 rolling (0.3) 更低：二次压缩更稳
            max_tokens=cap,
        )
    except LlmError as e:
        log.warning("condense_summary LLM error (treated as peak): %s", e)
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e
    except Exception as e:
        log.exception("condense_summary unexpected error")
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e

    cleaned = (summary or "").strip()
    log.info(
        "condense_summary ok: session=%s old_len=%d new_len=%d",
        req.session_id, len(req.summary), len(cleaned),
    )
    return {"summary": cleaned, "session_id": req.session_id}