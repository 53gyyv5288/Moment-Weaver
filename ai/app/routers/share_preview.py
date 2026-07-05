"""分享预览 OG 文案生成（M5-A.4）。"""
import logging
from typing import Literal

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.json_extract import coerce_text, extract_json
from app.services.llm import LlmError, chat
from app.services.prompts import SHARE_OG_SYSTEM, SHARE_OG_USER_TEMPLATE

log = logging.getLogger(__name__)
router = APIRouter()


class Section(BaseModel):
    title: str
    content: str = ""


class SharePreviewRequest(BaseModel):
    draftId: str = ""
    title: str
    style: Literal["warm", "neutral", "headline"] = "warm"
    sections: list[Section] = Field(default_factory=list)


def _build_sections_text(sections: list[Section], max_sections: int = 3, max_chars: int = 600) -> str:
    if not sections:
        return "（无章节摘要）"
    chunks: list[str] = []
    for s in sections[:max_sections]:
        snippet = (s.content or "").strip().replace("\n", " ")
        if len(snippet) > max_chars:
            snippet = snippet[:max_chars] + "…"
        chunks.append(f"《{s.title}》{snippet}")
    return "\n".join(chunks)


@router.post("/summary")
async def share_preview_summary(req: SharePreviewRequest) -> dict:
    """
    同步生成 OG 分享卡片文案。
    LLM 失败 / 输出解析失败时，回退到无 AI 的兜底版本（用标题 + 第一段）。
    """
    style_label = {"warm": "温暖", "neutral": "克制", "headline": "标题党风格克制使用"}[req.style]
    sections_text = _build_sections_text(req.sections)

    user_prompt = SHARE_OG_USER_TEMPLATE.format(
        title=req.title or "（未命名）",
        style=style_label,
        sections_text=sections_text,
    )
    messages = [
        {"role": "system", "content": SHARE_OG_SYSTEM},
        {"role": "user", "content": user_prompt},
    ]

    try:
        raw = await chat(messages, temperature=0.5, max_tokens=512)
        obj = extract_json(raw)
        if not isinstance(obj, dict):
            raise ValueError("LLM 返回非 dict")
        og_title = coerce_text(obj.get("og_title") or obj.get("ogTitle"), default=req.title, max_len=30)
        og_desc = coerce_text(obj.get("og_description") or obj.get("ogDescription"), default=req.title, max_len=80)
        og_image = coerce_text(obj.get("og_image_prompt") or obj.get("ogImagePrompt"), default="", max_len=120)
        key_quote = coerce_text(obj.get("key_quote") or obj.get("keyQuote"), default="", max_len=60)
    except (LlmError, ValueError) as e:
        log.warning("share_preview LLM fallback: %s", e)
        # 兜底：title 截前 30 字，desc 用前 80 字
        og_title = (req.title or "（未命名）")[:30]
        first_section = (req.sections[0].content if req.sections else "") or ""
        og_desc = first_section[:80] if first_section else og_title
        og_image = ""
        key_quote = ""

    return {
        "og_title": og_title,
        "og_description": og_desc,
        "og_image_prompt": og_image,
        "key_quote": key_quote,
        "draft_id": req.draftId,
    }
