"""通用 JSON 抽取工具（M5 提取）。

之前 narrative / summarize / 等路由各自实现了一份 _extract_json，
行为高度重复；M5 一次性收进本模块，避免后续 LLM 输出格式调整时要改 N 处。
"""
from __future__ import annotations

import json
import logging
import re
from typing import Any

log = logging.getLogger(__name__)


def extract_json(text: str) -> Any:
    """
    尽量鲁棒地从 LLM 输出里抠出 JSON。
    优先级：
      1) 整段本身就是合法 JSON
      2) 首段 {...} 或 [...]（处理「以下是 JSON：{...}」类话痨模型）
      3) 截掉 <think>...</think> 推理块再试一次
      4) 兜底抛错
    """
    text = (text or "").strip()
    if not text:
        raise ValueError("LLM 输出为空")

    # 1) 直接 parse
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # 3) 去掉 <think>...</think> 推理块
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", text).strip()
    if cleaned and cleaned != text:
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            pass

    # 2) 截取首段大括号 / 中括号
    for pattern in (r"\{[\s\S]*\}", r"\[[\s\S]*\]"):
        m = re.search(pattern, text)
        if m:
            try:
                return json.loads(m.group(0))
            except json.JSONDecodeError as e:
                log.warning("JSON extract via %s failed: %s; raw[:200]=%s", pattern, e, text[:200])

    raise ValueError(f"无法从 LLM 输出解析 JSON：{text[:200]}")


def coerce_text(value: Any, *, default: str = "", max_len: int | None = None) -> str:
    """把 LLM 输出里可能写歪的字段统一收成 str。"""
    if value is None:
        return default
    s = str(value).strip()
    if max_len and len(s) > max_len:
        s = s[:max_len]
    return s or default
