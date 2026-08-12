"""Chunking for RAG ingest.

策略：small-to-big
  - small chunk：检索单元（单轮 user+assistant 对话 / asset caption + 关联采访片段）
  - big  chunk：返回给 LLM 的「完整上下文」（相邻 3 轮 / Asset 完整元数据）

边界规则（确保 chunk_id 稳定，方便增量重写）：
  - interview：每对 user+assistant 一片 → chunk_id = "interview:{session_id}:turn_{i}"
  - parent_id = "interview:{session_id}:win_{j}"（窗口号 j = i // 3，每 3 片共用一个父）

注意：assistant 的思考链（thinking）**不**进 chunk_text，避免索引污染（plan §7）。
"""
from __future__ import annotations

import logging
import re
from typing import Any, Iterable

log = logging.getLogger(__name__)


# assistant 的 content 里可能内嵌 <think>...</think> 推理块（MiniMax-M3 等推理模型）。
# 实测 Mongo 里的历史数据确实带着它，所以不能假设上游已经剥干净。
_THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
_THINK_OPEN = re.compile(r"<think>.*$", re.DOTALL | re.IGNORECASE)


def strip_thinking(text: str) -> str:
    """剥离 assistant content 里的思考链，避免污染索引（plan §7）。

    思考链进索引有三重危害：
      1. 召回变差 —— 嵌入的是推理过程（常为英文）而非实际回答，语义偏离
      2. prompt 污染 —— 命中后 parent_text 会把思考链一起喂回 LLM
      3. 浪费 embedding 额度 —— 思考链往往比正文更长

    未闭合的 <think>（内容被截断）视为整段都是思考，全部丢弃。
    """
    if not text:
        return ""
    cleaned = _THINK_BLOCK.sub("", text)
    cleaned = _THINK_OPEN.sub("", cleaned)
    return cleaned.strip()


# ============ Interview ============

def interview_chunks(messages: list[dict], *, session_id: str,
                     window_size: int = 3) -> list[dict]:
    """把一整段 InterviewSession.messages 切成 small chunks + 共享的 parent chunks。

    输入 messages 元素形如 {"role": "user|assistant|system",
                             "content": "...", "thinking": "..."(可选)}
    返回 list[dict]，每个元素含 chunk_id / parent_id / chunk_text / parent_text /
                  subject_id(占位，外部补) / metadata。
    """
    # 1) 只取 user/assistant，跳过 system（system 是后端注入的 prompt，不进索引）
    pairs: list[tuple[str, str]] = []  # (user_text, assistant_text)
    last_user: str | None = None
    for m in messages:
        role = m.get("role")
        content = (m.get("content") or "").strip()
        if role == "user" and content:
            # 出现连续 user 时，前一条未闭合，丢弃前一条 user 单独成对
            if last_user is not None:
                pairs.append((last_user, ""))
            last_user = content
        elif role == "assistant" and content:
            # 剥离思考链。注意不能假设上游已经处理过 —— 历史数据里 content
            # 本身就内嵌着 <think>...</think>。
            content = strip_thinking(content)
            if not content:
                # 整条都是思考链，没有实际回答 —— 当作没回答
                continue
            if last_user is None:
                # 没有 user 的开场白 assistant（极少见）—— 跳过
                continue
            pairs.append((last_user, content))
            last_user = None
    # 收尾
    if last_user is not None:
        pairs.append((last_user, ""))

    if not pairs:
        return []

    # 2) 切 small chunk + 算 parent
    out: list[dict] = []
    for i, (u, a) in enumerate(pairs):
        win_idx = i // window_size
        # small chunk：单轮 user+assistant
        small_lines = [f"受访者：{u}"]
        if a:
            small_lines.append(f"AI 采访官：{a}")
        chunk_text = "\n".join(small_lines).strip()
        parent_text = _build_interview_parent(pairs, win_idx, window_size)

        out.append({
            "chunk_id": f"interview:{session_id}:turn_{i}",
            "parent_id": f"interview:{session_id}:win_{win_idx}",
            "chunk_text": chunk_text[:2048],  # VARCHAR(2048) 上限
            "parent_text": parent_text[:16384],
            "metadata": {
                "session_id": session_id,
                "role": "user+assistant",
                "turn_index": i,
                "window_index": win_idx,
                "has_assistant": bool(a),
            },
        })
    log.debug("interview_chunks: session=%s pairs=%d windows=%d",
              session_id, len(pairs), (len(pairs) + window_size - 1) // window_size)
    return out


def _build_interview_parent(pairs: list[tuple[str, str]],
                            win_idx: int, window_size: int) -> str:
    """把一个窗口（window_size 个 pairs）拼成 parent_text。"""
    start = win_idx * window_size
    end = min(start + window_size, len(pairs))
    lines: list[str] = []
    for u, a in pairs[start:end]:
        if u:
            lines.append(f"受访者：{u}")
        if a:
            lines.append(f"AI 采访官：{a}")
        lines.append("")  # 空行分隔
    return "\n".join(lines).strip()


# ============ Asset ============

def asset_chunks(asset: dict, *, linked_messages: Iterable[dict] | None = None) -> list[dict]:
    """Asset → 1 个 chunk（caption + 关联采访片段）。Asset 数据量小，先不强切。

    asset 必填字段（外部 mapper 应保证）：
      - id, kind, caption(可空), takenAt, subjectId
    linked_messages：可选，从关联 InterviewSession 截 2-3 条相关 user 原话。
    """
    asset_id = str(asset.get("id"))
    caption = (asset.get("caption") or "").strip()
    kind = asset.get("kind", "image")
    taken_at = asset.get("takenAt")  # epoch ms 或 ISO 字符串
    original_name = asset.get("originalName") or ""

    # small chunk：caption + 关联采访片段（如有）
    small_lines: list[str] = []
    if caption:
        small_lines.append(f"素材描述：{caption}")
    if original_name:
        small_lines.append(f"文件名：{original_name}")
    if linked_messages:
        for m in list(linked_messages)[:3]:
            content = (m.get("content") or "").strip()
            if content and m.get("role") == "user":
                small_lines.append(f"受访者相关回忆：{content}")

    # parent chunk：Asset 完整元数据（喂给 LLM 兜底理解）
    parent_lines: list[str] = [f"素材类型：{kind}"]
    if taken_at:
        parent_lines.append(f"拍摄/上传时间：{taken_at}")
    if original_name:
        parent_lines.append(f"文件名：{original_name}")
    if caption:
        parent_lines.append(f"描述：{caption}")
    if asset.get("ossKey"):
        parent_lines.append(f"存储路径：{asset['ossKey']}")
    if linked_messages:
        for m in list(linked_messages)[:5]:
            content = (m.get("content") or "").strip()
            if content:
                who = "受访者" if m.get("role") == "user" else "AI 采访官"
                parent_lines.append(f"关联采访（{who}）：{content}")

    chunk_text = "\n".join(small_lines).strip()[:2048] or "(无描述)"
    parent_text = "\n".join(parent_lines).strip()[:16384] or chunk_text
    return [{
        "chunk_id": f"asset:{asset_id}:v1",
        "parent_id": f"asset:{asset_id}",
        "chunk_text": chunk_text,
        "parent_text": parent_text,
        "metadata": {
            "asset_id": asset_id,
            "kind": kind,
            "taken_at": taken_at,
            "file_url": asset.get("fileUrl") or asset.get("ossKey") or "",
        },
    }]