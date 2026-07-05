"""敏感词库（M5-A.4 审核兜底）。

策略：
  - 启动时一次性加载 data/sensitive_words.json 到内存
  - 命中即返回（不区分大小写、去掉常见变体字符）
  - 词库空 / 文件不存在 → 退化为"无敏感词"

未来接阿里云绿网时，可在本模块加 GreenClient.match(text)。
"""
from __future__ import annotations

import json
import logging
import re
from functools import lru_cache
from pathlib import Path

log = logging.getLogger(__name__)

# 词库文件路径，相对 AI 服务工作目录
_DEFAULT_WORDS_FILE = Path(__file__).resolve().parent.parent.parent / "data" / "sensitive_words.json"

# 常见变体字符（全角 / 半角 标点混淆用）
_NORMALIZE_RE = re.compile(r"[\s\u3000\-\.\,\!\?\(\)\[\]\{\}\"\'\`\～\！\。\，\；\？\（\）\【\】\「\」\『\』\《\》]+")


def _normalize(text: str) -> str:
    """去掉空格 / 常见变体标点，便于跨形态命中。"""
    return _NORMALIZE_RE.sub("", text or "")


@lru_cache(maxsize=1)
def _load_words() -> tuple[str, ...]:
    """加载词库；启动期调一次，结果缓存。"""
    path = _DEFAULT_WORDS_FILE
    if not path.exists():
        log.warning("sensitive_words.json not found at %s, matcher is empty", path)
        return ()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            log.warning("sensitive_words.json must be a list, got %s", type(data))
            return ()
        # 过滤空 / 非字符串
        words = tuple(w.strip() for w in data if isinstance(w, str) and w.strip())
        log.info("Loaded %d sensitive words", len(words))
        return words
    except Exception as e:
        log.warning("sensitive_words.json load failed: %s; matcher empty", e)
        return ()


def match(text: str) -> list[str]:
    """
    在 text 中找所有命中的敏感词。
    返回去重后的命中词列表；空列表表示无命中。
    """
    words = _load_words()
    if not words:
        return []
    normalized = _normalize(text).lower()
    hits: list[str] = []
    seen: set[str] = set()
    for w in words:
        if w.lower() in seen:
            continue
        wn = _normalize(w).lower()
        if wn and wn in normalized:
            hits.append(w)
            seen.add(w)
    return hits
