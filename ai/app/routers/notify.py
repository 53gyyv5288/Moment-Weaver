"""通知文案生成（M5-A.4）。

策略：
  1) 先查 notify_templates 模板渲染（不依赖 LLM）
  2) 模板渲染成功即可返回
  3) MOMENT_NOTIFY_AI_ENABLED=true 时再调 LLM 微调
     （M5 暂不开启，避免 LLM 失控时通知被改坏）
"""
import logging
import os
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

from app.services.llm import LlmError, chat
from app.services.notify_templates import render as render_template
from app.services.prompts import NOTIFY_COMPOSE_SYSTEM, NOTIFY_COMPOSE_USER_TEMPLATE
from app.services.json_extract import coerce_text, extract_json

log = logging.getLogger(__name__)
router = APIRouter()


Channel = Literal["inbox"]


class NotifyComposeRequest(BaseModel):
    event_type: str
    channel: Channel = "inbox"
    template_vars: dict | None = None
    # 兜底：模板里没有 event_type 时返回 UNKNOWN
    template_title: str | None = None
    template_body: str | None = None


def _vars_text(vars: dict | None) -> str:
    if not vars:
        return "（无）"
    return "\n".join(f"- {k}: {v}" for k, v in vars.items())


@router.post("/compose")
async def notify_compose(req: NotifyComposeRequest) -> dict:
    # 1) 模板渲染
    tpl_title, tpl_body = render_template(req.event_type, req.template_vars)

    # 2) 是否走 AI 微调
    ai_enabled = os.environ.get("MOMENT_NOTIFY_AI_ENABLED", "false").lower() in ("1", "true", "yes")
    if not ai_enabled:
        return {
            "title": tpl_title,
            "body": tpl_body,
            "event_type": req.event_type,
            "channel": req.channel,
            "ai_polished": False,
        }

    # 3) AI 微调
    try:
        user_prompt = NOTIFY_COMPOSE_USER_TEMPLATE.format(
            event_type=req.event_type,
            template_title=tpl_title,
            template_body=tpl_body,
            vars_text=_vars_text(req.template_vars),
        )
        messages = [
            {"role": "system", "content": NOTIFY_COMPOSE_SYSTEM},
            {"role": "user", "content": user_prompt},
        ]
        raw = await chat(messages, temperature=0.4, max_tokens=256)
        obj = extract_json(raw)
        if not isinstance(obj, dict):
            raise ValueError("LLM 返回非 dict")
        title = coerce_text(obj.get("title"), default=tpl_title, max_len=20)
        body = coerce_text(obj.get("body"), default=tpl_body, max_len=60)
    except (LlmError, ValueError) as e:
        log.warning("notify_compose LLM fallback: %s", e)
        title, body = tpl_title, tpl_body
        return {
            "title": title,
            "body": body,
            "event_type": req.event_type,
            "channel": req.channel,
            "ai_polished": False,
        }

    return {
        "title": title,
        "body": body,
        "event_type": req.event_type,
        "channel": req.channel,
        "ai_polished": True,
    }
