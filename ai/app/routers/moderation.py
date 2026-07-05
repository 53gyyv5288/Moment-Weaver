"""内容审核（M5-A.4）。

双策略：
  1) 自建敏感词库（fast、零依赖）→ 命中即 medium / high
  2) LLM 自检（仅在词库未命中、且 LLM 可用时跑）→ 给出 risk_level / categories

返回结构化结果，由调用方决定：
  - safe: 放行
  - low: 可放行；记录到审计日志
  - medium: 阻断 / 转人工
  - high: 阻断 + 立即通知运营
"""
import logging
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

from app.services.llm import LlmError, chat
from app.services.prompts import MODERATION_SYSTEM, MODERATION_USER_TEMPLATE
from app.services.sensitive_words import match as sensitive_match
from app.services.json_extract import coerce_text, extract_json

log = logging.getLogger(__name__)
router = APIRouter()


RiskLevel = Literal["safe", "low", "medium", "high"]


class ModerationRequest(BaseModel):
    text: str
    context: str = "家族口述史平台（成稿 / 采访 / 素材描述）"


def _evidence_from_sensitive(hits: list[str]) -> list[str]:
    return [f"命中敏感词：{w}" for w in hits[:5]]


@router.post("/text")
async def moderate_text(req: ModerationRequest) -> dict:
    text = (req.text or "").strip()
    if not text:
        return {
            "risk_level": "safe",
            "categories": [],
            "evidence": [],
            "suggestion": "文本为空",
        }

    # 1) 敏感词快速通道
    hits = sensitive_match(text)
    if hits:
        return {
            "risk_level": "high",
            "categories": ["sensitive_word"],
            "evidence": _evidence_from_sensitive(hits),
            "suggestion": "建议屏蔽：命中内置敏感词",
        }

    # 2) LLM 自检
    try:
        user_prompt = MODERATION_USER_TEMPLATE.format(text=text[:2000], context=req.context)
        messages = [
            {"role": "system", "content": MODERATION_SYSTEM},
            {"role": "user", "content": user_prompt},
        ]
        raw = await chat(messages, temperature=0.1, max_tokens=512)
        obj = extract_json(raw)
        if not isinstance(obj, dict):
            raise ValueError("LLM 返回非 dict")
        risk = coerce_text(obj.get("risk_level") or obj.get("riskLevel"), default="low", max_len=10)
        if risk not in ("safe", "low", "medium", "high"):
            risk = "low"
        categories = obj.get("categories") or []
        if not isinstance(categories, list):
            categories = []
        categories = [str(c) for c in categories if c][:10]
        evidence = obj.get("evidence") or []
        if not isinstance(evidence, list):
            evidence = []
        evidence = [str(e) for e in evidence if e][:5]
        suggestion = coerce_text(obj.get("suggestion"), default="可放行", max_len=80)
    except (LlmError, ValueError) as e:
        # LLM 失败时按"低风险 + 建议人工复核"兜底
        log.warning("moderation LLM fallback: %s", e)
        return {
            "risk_level": "low",
            "categories": ["llm_unavailable"],
            "evidence": [],
            "suggestion": "LLM 不可用，已放行；建议人工复核",
        }

    return {
        "risk_level": risk,
        "categories": categories,
        "evidence": evidence,
        "suggestion": suggestion,
    }
