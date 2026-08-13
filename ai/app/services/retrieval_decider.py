"""Adaptive RAG 决策器（M9+ Phase 1）。

背景：原 InterviewService.fetchRagEvidence 是无条件同步调一次 RAG。
本模块引入「是否需要 RAG」决策器，让 LLM 自己判断：
  - 闲聊（"早安""谢谢""好的"）→ 不调 RAG，省 embedding + reranker + Milvus 调用
  - 事实性提问（"奶奶当年住哪""初恋是哪里人"）→ 走原貌

设计要点：
  1. 单条轻量 prompt（< 100 tokens in / 5 tokens out）→ 主路径同款 LLM
  2. 超时 500ms 兜底（详见 ai/app/routers/decide.py），Java 侧也会设 500ms
  3. Pydantic 强类型校验；解析失败 → 走容错回退
  4. 不引入 LangChain —— 与现有 narrative.py / summarize.py 风格保持一致
"""
from __future__ import annotations

import json
import logging
import re
from typing import Literal

from pydantic import BaseModel, Field, ValidationError

from app.config import get_settings
from app.services.llm import chat

log = logging.getLogger(__name__)


# ============ Schema ============

ToolName = Literal[
    "rag_search",         # 跨 session 检索：默认值
    "timeline_query",     # 查时间线（占位，二期实现）
    "fact_consolidate",   # 整合已有事实（占位，二期实现）
]


class RetrievalDecision(BaseModel):
    """LLM 决策输出。

    need_retrieval: 是否需要调用 RAG。
    tool: 调用哪个 retrieval backend；rag_search 已实现，其它占位。
    rewritten_query: 给 RAG 用的查询词（替代用户原话，通常更结构化）。
    filter: 元数据过滤（按需扩展，如 {"decade": "1950s"}）。
    confidence: LLM 自我评估的决策置信度（0-1）。
    rationale: 一句话理由（方便日志与可观测性）。
    """
    need_retrieval: bool
    tool: ToolName = "rag_search"
    rewritten_query: str | None = None
    filter: dict | None = None
    confidence: float = Field(ge=0.0, le=1.0, default=0.5)
    rationale: str | None = None


# ============ Prompt ============

_DECIDER_SYSTEM = """你是一个「是否需要检索历史事实」的判定助手，专门用于家族口述史采访场景。

【输入】用户上一轮原文，以及（可选的）简短历史摘要 + 最近几轮对话。

【任务】判定该消息是否需要检索历史片段（采访过的内容、素材备注、时间线事实）。

【判定原则】
- 闲聊问候（"早安""谢谢""好的""再见""嗯"等）：need_retrieval = false
- 情感反馈（"开心""难过""想念"）：need_retrieval = false
- 用户对当前采访问题的回应（"是""对""我出生在 1950 年"）：need_retrieval = false
  （这是新事实，由 user 直接给出，不需要查历史）
- 询问历史事实（"奶奶当年说过什么""那年他在哪工作""我们家有谁当过兵"）：
  need_retrieval = true；如能改写更结构化的检索词，也写在 rewritten_query
- 询问某人物已说过的话 / 已发生的事：need_retrieval = true
- 询问家族关系 / 谱系：need_retrieval = true
- 用户说"我不记得了""你帮我说"或主动要求查：need_retrieval = true

【输出】严格按 schema 输出 JSON，不要任何解释性文字、不要 markdown 代码块、不要 <think> 推理块。
【硬约束】JSON 字符串值内部禁止使用 ASCII 半角双引号 "，统一用「」/『』/（）。"""

_DECIDER_USER_TEMPLATE = """请判定下列用户消息是否需要检索历史片段。

【用户消息原文】
{user_content}

【最近对话上下文（供判定参考，可为空）】
{context_brief}

【可选的对话历史摘要（可为空）】
{rolling_summary}

【输出 schema】
{{
  "need_retrieval": true | false,
  "tool": "rag_search" | "timeline_query" | "fact_consolidate",
  "rewritten_query": "改写后的检索词（需要时可空）",
  "filter": {{"key": "value"}} 或 null,
  "confidence": 0.0 ~ 1.0,
  "rationale": "一句话判定理由"
}}"""


# ============ Public API ============

# 简单的「闲聊兜底」白名单：主路径同款模型情况下，明显的闲聊直接跳过 LLM 调用。
# 启发式黑名单 ~70% 准确率，省下 1 次 LLM 调用；模型判定兜底剩下的 30%。
_CHITCHAT_PATTERN = re.compile(
    r"^("
    r"(早|中|晚)安(?!.*[?？])"                    # 早安/午安/晚安（不能跟问号）
    r"|谢谢?|好的?|好的了|再见?|拜拜?"
    r"|嗯(?!.*[?？])|哦(?!.*[?？])|好(?!.*[?？])"
    r"|那就这样?|行(?!.*[?？])|明白(?!.*[?？])"
    r"|好啦?|对(?!.*[?？])"
    r"|哈哈(?!.*[?？])|呵呵(?!.*[?？])"
    r")"
    r"[\s\u3002\uff01\uff0c\uff1f\uff01\.\!\,\?]*$"
)

# 「含问号」基本一定是要检索 —— 这是强信号
_HAS_QUESTION_MARK = re.compile(r"[?？]")


def is_obvious_chitchat(user_content: str) -> bool:
    """无 LLM 调用的本地启发式判定。命中则跳 decider，直接 need_retrieval=false。

    返回 True 一定不需要检索；返回 False 不代表需要，需要再交给 LLM。
    """
    s = (user_content or "").strip()
    if not s:
        return True
    if len(s) <= 4:                 # 4 字以内几乎都是 "嗯""好的""谢谢" 等
        return True
    if _CHITCHAT_PATTERN.match(s):
        return True
    return False


async def decide_retrieval(
    user_content: str,
    rolling_summary: str = "",
    recent_context: str = "",
) -> RetrievalDecision | None:
    """主函数：判断 user_content 是否需要 RAG。失败返回 None（让上游兜底走原貌）。"""
    text = (user_content or "").strip()

    # Layer 0：本地启发式黑名单（无 LLM）
    if is_obvious_chitchat(text):
        log.debug("retrieval_decider: chitchat shortcut sid=%s", text[:30])
        return RetrievalDecision(
            need_retrieval=False,
            tool="rag_search",
            confidence=0.85,
            rationale="chitchat detected by heuristic",
        )

    # Layer 1：调用 LLM 判定
    try:
        user_prompt = _DECIDER_USER_TEMPLATE.format(
            user_content=text[:300],  # 截断防 token 爆掉
            context_brief=(recent_context or "（无）")[:300],
            rolling_summary=(rolling_summary or "（无）")[:200],
        )
        msgs = [
            {"role": "system", "content": _DECIDER_SYSTEM},
            {"role": "user", "content": user_prompt},
        ]
        raw = await chat(messages=msgs, temperature=0.0, max_tokens=80)
        decision = _parse_decision(raw)
        if decision is not None:
            log.info(
                "retrieval_decider sid=%s → need=%s conf=%.2f rationale=%s",
                text[:30], decision.need_retrieval, decision.confidence,
                decision.rationale or "",
            )
        return decision
    except Exception as e:  # noqa: BLE001
        # 容错回退：返回 None，让上游降级到「默认走原貌」
        log.warning("retrieval_decider failed (will fall back): %s", e)
        return None


def _parse_decision(raw: str) -> RetrievalDecision | None:
    """从 LLM 输出解析决策。鲁棒处理：可能不带 JSON、可能在前缀、可能有 think 块。"""
    if not raw:
        return None
    # 去掉 <think> 块（推理模型可能带头）
    cleaned = re.sub(r"<think>.*?</think>", "", raw, flags=re.DOTALL).strip()
    # 提取首个 {...} 段
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start == -1 or end <= start:
        return None
    blob = cleaned[start:end + 1]
    try:
        obj = json.loads(blob)
    except json.JSONDecodeError:
        # 二次尝试：尝试替换中文引号（防御）
        try:
            obj = json.loads(blob.replace("「", '"').replace("」", '"'))
        except json.JSONDecodeError:
            return None
    try:
        return RetrievalDecision(**obj)
    except ValidationError:
        return None
