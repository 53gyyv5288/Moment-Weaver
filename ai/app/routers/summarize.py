"""采访摘要路由（M3）。"""
import json
import logging
import re

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.llm import LlmError, chat
from app.services.prompts import build_summary_messages

log = logging.getLogger(__name__)
router = APIRouter()


class InterviewMessage(BaseModel):
    role: str
    content: str


class SummarizeRequest(BaseModel):
    session_id: str = Field(default="")
    subject_hint: str | None = None
    messages: list[InterviewMessage]


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


def _coerce_summary(obj: dict) -> dict:
    """把 LLM 可能写歪的字段名 / 缺字段做兜底。"""
    title = (obj.get("title") or obj.get("标题") or "").strip()
    if not title:
        title = "本次采访"

    raw_quotes = obj.get("goldenQuotes") or obj.get("golden_quotes") or obj.get("金句") or []
    if isinstance(raw_quotes, str):
        raw_quotes = [raw_quotes]
    golden_quotes = [str(q).strip() for q in raw_quotes if str(q).strip()][:5]

    raw_moments = obj.get("keyMoments") or obj.get("key_moments") or obj.get("关键时刻") or []
    key_moments = []
    for km in raw_moments[:5]:
        if not isinstance(km, dict):
            continue
        ts = (km.get("timestamp") or km.get("时间") or "").strip()
        text = (km.get("text") or km.get("内容") or km.get("描述") or "").strip()
        if not text:
            continue
        key_moments.append({"timestamp": ts, "text": text})
    if not key_moments:
        key_moments = [{"timestamp": "", "text": "暂无关键时刻"}]

    return {
        "title": title[:30],
        "goldenQuotes": golden_quotes,
        "keyMoments": key_moments,
    }


@router.post("")
async def summarize(req: SummarizeRequest) -> dict:
    """同步生成结构化摘要。"""
    if not req.messages:
        raise HTTPException(status_code=400, detail="messages 不能为空")

    msgs = build_summary_messages(
        [m.model_dump() for m in req.messages],
        subject_hint=req.subject_hint or "",
    )
    log.info(
        "summarize start: session=%s subject=%s raw_msgs=%d",
        req.session_id, req.subject_hint, len(req.messages),
    )
    try:
        raw = await chat(msgs, temperature=0.4, max_tokens=1024)
        obj = _extract_json(raw)
        summary = _coerce_summary(obj)
    except LlmError as e:
        log.warning("summarize LLM error (treated as peak): %s", e)
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e
    except ValueError as e:
        log.error("summarize parse failed (treated as peak): %s; raw=%s", e, raw[:400])
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e
    except Exception as e:
        # 网络异常、httpx 超时、连接被拒等都归到"高峰期"提示，避免暴露技术细节
        log.exception("summarize unexpected error (treated as peak)")
        raise HTTPException(
            status_code=503,
            detail="高峰期，请稍后再试",
        ) from e

    log.info(
        "summarize ok: session=%s title=%r quotes=%d moments=%d",
        req.session_id, summary["title"], len(summary["goldenQuotes"]), len(summary["keyMoments"]),
    )
    return {"summary": summary, "session_id": req.session_id}