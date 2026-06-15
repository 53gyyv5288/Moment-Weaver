"""叙事成稿路由（M4 阶段 B：模板 + AI 生成 + 单章节重写）。

端点（main.py 的 prefix="/api/v1/narrative"）：
  POST /generate           整篇生成
  POST /regenerate-section 单章节重写
"""
import json
import logging
import re

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.llm import LlmError, chat
from app.services.templates import get as get_template

log = logging.getLogger(__name__)
router = APIRouter()


# ============ Request / Response models ============

class FactItem(BaseModel):
    factId: str
    source: str  # interview | asset_caption | note
    text: str
    subjectId: str | None = None
    timestamp: str | None = None  # ISO 字符串


class SubjectItem(BaseModel):
    subjectId: str
    name: str
    relation: str | None = None
    birthYear: int | None = None


class NarrativeRequest(BaseModel):
    templateId: str  # person-template-v1 | family-template-v1
    subjects: list[SubjectItem] = Field(default_factory=list)
    facts: list[FactItem] = Field(default_factory=list)


class SectionOut(BaseModel):
    sectionId: str
    sectionTitle: str
    content: str
    factsUsed: list[str] = Field(default_factory=list)


class NarrativeResponse(BaseModel):
    templateId: str
    title: str
    sections: list[SectionOut]


class RegenerateRequest(BaseModel):
    templateId: str
    sectionId: str
    sectionTitle: str
    currentContent: str
    style: str  # warmer | concise | vivid | formal
    subjects: list[SubjectItem] = Field(default_factory=list)
    facts: list[FactItem] = Field(default_factory=list)


class RegenerateResponse(BaseModel):
    sectionId: str
    content: str


# ============ helpers ============

_VALID_STYLES = {"warmer", "concise", "vivid", "formal"}


def _extract_json(text: str) -> dict:
    """
    尽量鲁棒地从 LLM 输出里抠出 JSON。
    优先级：
      1) 整段本身就是合法 JSON
      2) 首段 {...}（处理「以下是 JSON：{...}」类话痨模型）
      3) 兜底抛错
    """
    text = text.strip()
    if not text:
        raise ValueError("LLM 输出为空")
    # 1) 直接 parse
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # 2) 截取首段大括号
    m = re.search(r"\{[\s\S]*\}", text)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError as e:
            log.warning("LLM output JSON extract failed: %s; raw[:200]=%s", e, text[:200])
    raise ValueError(f"无法从 LLM 输出解析 JSON：{text[:200]}")


def _coerce_title(obj: dict) -> str:
    title = (obj.get("title") or obj.get("标题") or "").strip()
    if not title:
        return "未命名成稿"
    return title[:25]


# ============ endpoints ============

@router.post("/generate", response_model=NarrativeResponse)
async def generate(req: NarrativeRequest) -> NarrativeResponse:
    """整篇生成。返回 title + sections（按模板顺序）。"""
    try:
        tpl = get_template(req.templateId)
    except KeyError as e:
        raise HTTPException(status_code=400, detail=str(e))

    if not req.subjects:
        raise HTTPException(status_code=400, detail="subjects 不能为空")
    if not req.facts:
        raise HTTPException(status_code=400, detail="facts 不能为空；没有事实无法生成")

    facts_dicts = [f.model_dump() for f in req.facts]
    subjects_dicts = [s.model_dump() for s in req.subjects]
    msgs = tpl.build_messages(facts_dicts, subjects_dicts)

    log.info(
        "narrative.generate start: template=%s subjects=%d facts=%d",
        req.templateId, len(subjects_dicts), len(facts_dicts),
    )
    try:
        # 家族成稿 2+ subjects + 5 章节比单人物复杂得多。
        # MiniMax-M3 等推理模型思考链会消耗大量 max_tokens，
        # 16384 不足以让家族模板输出完整 JSON（finish_reason=length 被截断）。
        # 同时传 chat_template_kwargs 试图关闭 reasoning（HF/Qwen3 风格参数）。
        extra_body: dict = {}
        # 通用：尝试关掉 reasoning。MiniMax-M3 / DeepSeek-R1 / QwQ 都认这个键
        extra_body["chat_template_kwargs"] = {"enable_thinking": False}
        raw = await chat(
            msgs, temperature=0.3, max_tokens=32768, extra_body=extra_body or None,
        )
        obj = _extract_json(raw)
        title = _coerce_title(obj)
        sections = tpl.coerce_sections(obj)
    except LlmError as e:
        log.warning("narrative.generate LLM error: %s", e)
        raise HTTPException(status_code=503, detail="AI 高峰期，请稍后再试") from e
    except ValueError as e:
        log.error("narrative.generate parse failed: %s; raw=%s", e, raw[:400])
        raise HTTPException(status_code=503, detail="AI 输出解析失败，请重试") from e
    except Exception as e:
        log.exception("narrative.generate unexpected error")
        raise HTTPException(status_code=503, detail="AI 高峰期，请稍后再试") from e

    log.info(
        "narrative.generate ok: template=%s title=%r sections=%d",
        req.templateId, title, len(sections),
    )
    return NarrativeResponse(
        templateId=req.templateId,
        title=title,
        sections=[SectionOut(**s) for s in sections],
    )


@router.post("/regenerate-section", response_model=RegenerateResponse)
async def regenerate_section(req: RegenerateRequest) -> RegenerateResponse:
    """单章节重写（按风格）。"""
    try:
        tpl = get_template(req.templateId)
    except KeyError as e:
        raise HTTPException(status_code=400, detail=str(e))

    if req.style not in _VALID_STYLES:
        raise HTTPException(
            status_code=400,
            detail=f"style 必须是 {sorted(_VALID_STYLES)} 之一",
        )

    facts_dicts = [f.model_dump() for f in req.facts]
    subjects_dicts = [s.model_dump() for s in req.subjects]
    msgs = tpl.build_rewrite_messages(
        section_id=req.sectionId,
        section_title=req.sectionTitle,
        current_content=req.currentContent,
        style=req.style,
        facts=facts_dicts,
        subjects=subjects_dicts,
    )

    log.info(
        "narrative.regenerate start: template=%s section=%s style=%s",
        req.templateId, req.sectionId, req.style,
    )
    try:
        # 单章节重写也要给足 token：思考链 + 一段 200~400 字输出 ≈ 4000 token 起步
        extra_body = {"chat_template_kwargs": {"enable_thinking": False}}
        content = await chat(
            msgs, temperature=0.4, max_tokens=4096, extra_body=extra_body,
        )
    except LlmError as e:
        log.warning("narrative.regenerate LLM error: %s", e)
        raise HTTPException(status_code=503, detail="AI 高峰期，请稍后再试") from e
    except Exception as e:
        log.exception("narrative.regenerate unexpected error")
        raise HTTPException(status_code=503, detail="AI 高峰期，请稍后再试") from e

    # 清理：去首尾空白；去掉模型偶尔包的外层 markdown ``` 标记
    content = content.strip()
    if content.startswith("```") and content.endswith("```"):
        content = content.strip("`").strip()
    log.info(
        "narrative.regenerate ok: section=%s chars=%d",
        req.sectionId, len(content),
    )
    return RegenerateResponse(sectionId=req.sectionId, content=content)
