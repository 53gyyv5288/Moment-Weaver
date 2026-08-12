"""Query rewrite：用同 LLM 做指代消解 + 时间归一化。

目的：
  - 把「他之前说过什么？」→ 「受访者在历次采访中说过的关键事实」
  - 把「刚才那个地方」→ 具体地点名词
  - 把相对时间「去年」「上个月」→ 绝对时间（用今天为锚）

软超时：settings.rag_query_rewrite_timeout_s（默认 0.6s）—— 超时直接用原 query。
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)


SYSTEM_PROMPT = """你是中文查询重写助手，专做"家族口述史"产品里的检索 query 改写。
输入：用户问题（可能含代词 / 相对时间 / 模糊指代）。
输出：改写后的 query（纯文本，不要 JSON，不要解释）。

改写原则：
- 代词「他/她/这位老人/这位长辈/他/她」→ 改写为「受访者」或留 query 原貌（Milvus 会按 subject_id filter）
- 相对时间「去年/上个月/刚才」→ 换算成绝对日期（YYYY-MM 或 YYYY-MM-DD）
- 保留关键名词、地点、事件
- 长度 ≤ 80 字
- 不要输出任何标点之外的解释
"""


def _today_anchor() -> str:
    return datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d")


async def rewrite_query(query: str, *, subject_hint: str = "") -> str | None:
    """用 LLM 改写 query。失败 / 超时 → 返回 None，调用方用原 query。"""
    s = get_settings()
    if not s.rag_query_rewrite_enabled:
        return None
    timeout = s.rag_query_rewrite_timeout_s
    user_msg = f"当前日期：{_today_anchor()}\n人物上下文：{subject_hint or '（无）'}\n原 query：{query}\n\n请输出改写后的 query："
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ]
    payload = {
        "model": s.llm_model,
        "messages": messages,
        "stream": False,
        "temperature": 0.0,
        "max_tokens": 100,
    }
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {s.llm_api_key}",
    }
    url = s.llm_base_url.rstrip("/") + "/chat/completions"
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(url, json=payload, headers=headers)
        if resp.status_code != 200:
            log.debug("query_rewrite upstream %s, fallback to raw", resp.status_code)
            return None
        obj = resp.json()
        content = obj.get("choices", [{}])[0].get("message", {}).get("content", "")
        rewritten = (content or "").strip()
        if not rewritten or len(rewritten) > 200:
            return None
        log.debug("query rewrite: %r -> %r", query[:50], rewritten[:50])
        return rewritten
    except (httpx.HTTPError, asyncio.TimeoutError) as e:
        log.debug("query_rewrite failed: %s", e)
        return None