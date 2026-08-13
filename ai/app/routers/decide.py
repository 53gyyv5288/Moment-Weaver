"""Adaptive RAG 决策路由（M9+ Phase 1）。"""
from __future__ import annotations

import asyncio
import logging

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.services.retrieval_decider import (
    RetrievalDecision,
    decide_retrieval,
    is_obvious_chitchat,
)

log = logging.getLogger(__name__)
router = APIRouter()

# 硬超时：MiniMax-M3 一次简单 JSON 判定典型耗时 500-1500ms，
# 设 1.5s 给主路径同款模型留出余量；同时小于 Java 端 3000ms 兜底，确保不会让上游空等
_DECIDER_TIMEOUT_S = 1.5


class DecideRequest(BaseModel):
    session_id: str = Field(default="")
    user_content: str
    recent_context: str = ""
    rolling_summary: str = ""


class DecideResponse(BaseModel):
    decision: RetrievalDecision
    fallback: bool = False  # True = 走了启发式黑名单（未调 LLM）


@router.post("/retrieval", response_model=DecideResponse)
async def decide(req: DecideRequest) -> DecideResponse:
    """判定是否需要 RAG。

    协议：
      - request.user_content: 用户消息原文
      - request.recent_context: 最近几轮对话（可空）
      - request.rolling_summary: 滚动摘要（可空）
      - response.decision: RetrievalDecision（schema 见 retrieval_decider.py）
      - response.fallback: True 表示未走 LLM（启发式黑名单命中）

    失败语义：
      - LLM 抛错 / 超时 → decision is RetrievalDecision(need_retrieval=True, confidence=0.0)
        让上游 Java 走原貌（不调 RAG = 崩体验是检索慢/有干扰；调 RAG = 更稳）。
      - 但如果启发式命中 → 返回 decision(need_retrieval=False)，fallback=True。
    """
    # Layer 0：黑名单命中就不调 LLM（已经覆盖 70% 闲聊，省 token + 延迟）
    if is_obvious_chitchat(req.user_content):
        return DecideResponse(
            decision=RetrievalDecision(
                need_retrieval=False,
                tool="rag_search",
                confidence=0.85,
                rationale="chitchat detected (heuristic black-list)",
            ),
            fallback=True,
        )

    # Layer 1：调 LLM
    try:
        result = await asyncio.wait_for(
            decide_retrieval(
                user_content=req.user_content,
                rolling_summary=req.rolling_summary,
                recent_context=req.recent_context,
            ),
            timeout=_DECIDER_TIMEOUT_S,
        )
    except asyncio.TimeoutError:
        log.warning(
            "decide timeout (%.0fms) sid=%s user=%s",
            _DECIDER_TIMEOUT_S * 1000,
            req.session_id,
            (req.user_content or "")[:30],
        )
        # 兜底：让上游走原貌
        return DecideResponse(
            decision=RetrievalDecision(
                need_retrieval=True,
                tool="rag_search",
                confidence=0.0,
                rationale="decider timeout, fall back to retrieval",
            ),
            fallback=True,
        )

    if result is None:
        # LLM 异常或解析失败：兜底走原貌
        return DecideResponse(
            decision=RetrievalDecision(
                need_retrieval=True,
                tool="rag_search",
                confidence=0.0,
                rationale="decider parse failed, fall back to retrieval",
            ),
            fallback=True,
        )

    return DecideResponse(decision=result, fallback=False)
