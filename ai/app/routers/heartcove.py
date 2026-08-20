"""AI 心声信箱对话路由（M13+）。

端点：
  POST /heartcove/stream   流式生成 AI 回应（SSE）
  POST /heartcove/summary  生成对话滚动摘要（短期记忆 → 中期记忆）

输入由 Spring Boot module-heartcove 提供：
  - subject_id, session_id
  - persona_summary (AI 抽取自采访素材)
  - related_quotes (从 interview_message 检索得到)
  - recent_dialog (会话内最近 8 条消息)
  - user_msg (用户当前输入)
  - style_tone (温和长辈 / 平实日常 / 简洁克制)

设计原则：
  1. 不进入 milvus——本路由不调用任何向量库；素材由 Spring Boot 端注入。
  2. 不知道话术池 + 危机干预全部在 Python 侧预生成。
  3. SSE 输出 token / done / error 三个事件，与 interview.py 一致。
"""

import logging
from typing import AsyncIterator

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.services.heartcove_phrases import (
    detect_unknown_type,
    get_crisis_interrupt,
    pick_phrase,
)
from app.services.heartcove_prompts import (
    HEARTCOVE_PROMPT_VERSION,
    build_system_prompt,
)
from app.services.llm import LlmError, chat, stream_chat_with_retry, stream_chat_with_think

log = logging.getLogger(__name__)
router = APIRouter()


# ---- 请求 / 响应 schema ----

class QuoteItem(BaseModel):
    # ⛔ 溯源修复:MongoDB interview_session.messages 是嵌入式数组,单条 message 没有 _id,
    # 只能用 InterviewSession._id + InterviewMessage.turnId 联合定位原话来源。
    # turn_id 是同一对 user/assistant 共享的 UUID(采访里 role=user 的发言一定有 turn_id)。
    # 供 source_quote_ids 回写到 heartcove_message.source_message_ids,
    # 实现"查看原话"跳转与合规追溯。缺失时 meta 仍可输出,只是溯源 ID 为空。
    turn_id: str = ""
    interview_session_id: str = ""
    content: str
    source: str = ""


class DialogItem(BaseModel):
    role: str       # user | assistant
    content: str


class HeartcoveStreamRequest(BaseModel):
    session_id: str = Field(default="")
    subject_id: str = Field(default="")
    display_name: str = Field(default="先辈")
    # M14+: 删 age_hint (硬模板会限制先辈类型, 改为 persona_summary 决定人设)
    style_tone: str = Field(default="温和长辈")    # 温和长辈 / 平实日常 / 简洁克制
    persona_summary: str = Field(default="")
    relation: str = Field(default="先辈")          # 用户对先辈的称呼, 如 "孙子" "女儿" "我自己"
    recent_dialog: list[DialogItem] = Field(default_factory=list)
    related_quotes: list[QuoteItem] = Field(default_factory=list)
    user_msg: str


class HeartcoveSummaryRequest(BaseModel):
    session_id: str = Field(default="")
    subject_id: str = Field(default="")
    recent_dialog: list[DialogItem] = Field(default_factory=list)
    last_summary: str = Field(default="")


class HeartcoveSummaryResponse(BaseModel):
    summary: str
    version: str = HEARTCOVE_PROMPT_VERSION


def _safe(text: str) -> str:
    return text.replace("\r", "").replace("\n", " ")


# ---- 不知道话术池注入（每类预生成 5 句） ----

def _build_unknown_phrases() -> dict:
    return {
        "modern_topic": [pick_phrase("modern_topic") for _ in range(5)],
        "no_material":  [pick_phrase("no_material")  for _ in range(5)],
        "emotion_support": [pick_phrase("emotion_support") for _ in range(5)],
        "boundary":     [pick_phrase("boundary")     for _ in range(5)],
        "crisis_interrupt": get_crisis_interrupt(),
    }


# ---- 流式对话 ----

@router.post("/stream")
async def stream(req: HeartcoveStreamRequest) -> StreamingResponse:
    """心声信箱流式对话。SSE 协议：
      event: token
      data: <text token>

      event: meta          (附带 unknown_type 与 source 引用)
      data: {json}

      event: error
      data: <msg>

      event: done
      data:
    """
    unknown_phrases = _build_unknown_phrases()

    # 1. 危机检测：最高优先级
    unknown_type = detect_unknown_type(req.user_msg)
    if unknown_type == "crisis_detected":
        async def crisis_event_source() -> AsyncIterator[bytes]:
            interrupt = get_crisis_interrupt()
            yield f"event: token\ndata: {_safe(interrupt)}\n\n".encode("utf-8")
            yield b'event: meta\ndata: {"unknown_type":"crisis_detected"}\n\n'
            yield b"event: done\ndata:\n\n"
        return StreamingResponse(crisis_event_source(), media_type="text/event-stream")

    # 2. 命中不知道话术池（modern_topic / boundary）→ 短路返回一句
    #    M14+: 删 no_material 短路。情绪倾诉("我好烦") / 日常("忘写作业") 这类场景
    #    走 LLM 走完会自然回应(基于 persona_summary + recent_dialog),不再被通用话术池打回。
    #    no_material 话术池保留,作为 LLM 异常时的兜底(下面 event_source 的 except 分支)。
    if unknown_type in ("modern_topic", "boundary"):
        phrase = pick_phrase(unknown_type)
        async def short_event_source() -> AsyncIterator[bytes]:
            yield f"event: token\ndata: {_safe(phrase)}\n\n".encode("utf-8")
            yield f'event: meta\ndata: {{"unknown_type":"{unknown_type}"}}\n\n'.encode("utf-8")
            yield b"event: done\ndata:\n\n"
        return StreamingResponse(short_event_source(), media_type="text/event-stream")

    # 3. 其他所有场景（包括情绪/日常/无原话召回）→ 走 LLM 流式生成
    sys_prompt = build_system_prompt(
        display_name=req.display_name,
        relation=req.relation,
        style_tone=req.style_tone,
        persona_summary=req.persona_summary,
        recent_dialog=[m.model_dump() for m in req.recent_dialog],
        related_quotes=[q.model_dump() for q in req.related_quotes],
        unknown_phrases=unknown_phrases,
    )
    msgs = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": req.user_msg},
    ]
    log.info(
        "heartcove stream start: session=%s subject=%s quotes=%d recent=%d",
        req.session_id, req.subject_id, len(req.related_quotes), len(req.recent_dialog),
    )

    async def event_source() -> AsyncIterator[bytes]:
        try:
            # M14+: 用 stream_chat_with_think 拿 (kind, token),kind ∈ {"text","think"}
            # kind=think 的部分以 event: thinking 帧转发,前端 HeartcoveChat.vue 折叠展示。
            async for kind, token in stream_chat_with_think(msgs):
                safe_token = _safe(token)
                if kind == "think":
                    yield f"event: thinking\ndata: {safe_token}\n\n".encode("utf-8")
                else:
                    yield f"event: token\ndata: {safe_token}\n\n".encode("utf-8")
            # 附带 meta
            import json
            meta = {
                "unknown_type": unknown_type,
                # ⛔ 溯源修复:传出真实 turn_id(UUID)列表,而不是 related_quotes 的数组下标;
                # Java 侧 parseAndAccumulate 拿这个字符串数组写回 heartcove_message.source_message_ids。
                "source_quote_ids": [q.turn_id for q in req.related_quotes if q.turn_id],
            }
            yield f"event: meta\ndata: {json.dumps(meta, ensure_ascii=False)}\n\n".encode("utf-8")
        except LlmError as e:
            # LLM 异常兜底: 不让前端收到 error 后空白, 用 no_material 话术池补一句
            log.exception("heartcove LLM error, falling back to no_material phrase: %s", e)
            phrase = pick_phrase("no_material")
            yield f"event: token\ndata: {_safe(phrase)}\n\n".encode("utf-8")
        yield b"event: done\ndata:\n\n"

    return StreamingResponse(event_source(), media_type="text/event-stream")


# ---- 滚动摘要 ----

@router.post("/summary", response_model=HeartcoveSummaryResponse)
async def summarize(req: HeartcoveSummaryRequest) -> HeartcoveSummaryResponse:
    """把最近 8 条对话压缩成一段滚动摘要（中期记忆）。

    设计：固定 prompt，输出 ≤ 200 字，纯文本。
    """
    if not req.recent_dialog:
        return HeartcoveSummaryResponse(summary=req.last_summary or "")

    sys_prompt = (
        "你是对话摘要助手。请根据以下对话与既往摘要，生成一段不超过 200 字的滚动摘要，"
        "覆盖：用户的情感状态、提到的人/事/物、对话进展。不要添加原对话中没有的内容。"
        "使用第三人称「用户」描述用户，使用「先辈」描述被复刻方。"
    )
    user_payload = (
        f"【既往摘要】\n{req.last_summary or '（无）'}\n\n"
        f"【最近对话】\n" +
        "\n".join(f"{('用户' if m.role=='user' else '先辈')}: {m.content}" for m in req.recent_dialog)
    )
    msgs = [
        {"role": "system", "content": sys_prompt},
        {"role": "user", "content": user_payload},
    ]
    chunks: list[str] = []
    try:
        async for tok in stream_chat_with_retry(msgs):
            chunks.append(tok)
    except LlmError as e:
        log.exception("heartcove summary LLM error")
        return HeartcoveSummaryResponse(summary=req.last_summary or "")
    return HeartcoveSummaryResponse(summary="".join(chunks).strip())


# ---- enable 时一次性生成 persona_summary（M14+ 体验修复） ----

class PersonaSummaryRequest(BaseModel):
    subject_id: str = Field(default="")
    display_name: str = Field(default="先辈")
    age_hint: str = Field(default="长辈")           # 如 "80 岁" / "出生于 1942 年"
    relation: str = Field(default="先辈")           # subject.relation
    # Java 端采样的被访者本人（role=user）原话片段, 每条已 trim
    quote_chunks: list[QuoteItem] = Field(default_factory=list)
    # 已存在的人格摘要（disable 重开时复用）
    previous_summary: str = Field(default="")


class PersonaSummaryResponse(BaseModel):
    persona_summary: str
    version: str = HEARTCOVE_PROMPT_VERSION
    fallback: bool = False   # True=LLM 失败或输入为空,返回了默认模板


# 默认模板: AI 失败时给 subject 一个"温和长辈但不知细节"的兜底,避免 LLM 拿空 prompt 编故事
_DEFAULT_PERSONA_TEMPLATE = (
    "暂无既往采访内容可用;按温和长辈的基本形象应对,被问到具体经历时坦诚说自己记不清、请对方讲讲。"
)


@router.post("/persona-summary", response_model=PersonaSummaryResponse)
async def build_persona_summary(req: PersonaSummaryRequest) -> PersonaSummaryResponse:
    """心声邮箱 enable 时一次性生成 subject 的人格摘要, 写回 Subject.heartcove_persona_summary。

    输入是 Spring Boot 端按 subject_id 从 MongoDB 采样的被访者本人原话(已 trim, ≤5KB),
    这里只负责组装 prompt + 调 LLM。LLM 失败时返回默认模板(fallback=True),
    由 Spring 端决定是否写库。
    """
    if not req.quote_chunks:
        log.warning("persona-summary request has no quote_chunks (subject=%s)", req.subject_id)
        return PersonaSummaryResponse(persona_summary=_DEFAULT_PERSONA_TEMPLATE, fallback=True)

    # 拼素材: 每条带 [i] 前缀, LLM 引用时好对照
    quote_lines = []
    for i, q in enumerate(req.quote_chunks[:30], 1):   # 防御性截断
        quote_lines.append(f"[{i}] {q.content}")
    quotes_str = "\n".join(quote_lines)

    previous_block = (
        f"\n【既有摘要(可参考可改写)】\n{req.previous_summary}\n"
        if req.previous_summary else ""
    )

    sys_prompt = (
        "你是人物档案撰写助手。基于以下【被访者本人发言】为「"
        + req.display_name + "」(一位 " + req.age_hint + " 的 " + req.relation + ")"
        " 写一段 300~500 字的人格摘要。\n"
        "要求:\n"
        "  1. 必须基于原话,不可虚构未提及的家人/年代/事件;信息不足时写「暂无」\n"
        "  2. 覆盖 5 个维度: 时代背景、关键经历、家庭关系、性格特征、说话风格/口头禅\n"
        "  3. 用第三人称「他/她」描述,不要用「爷爷/奶奶」等称呼\n"
        "  4. 输出纯文本,不要分点列表,不要 markdown 标题\n"
        + previous_block
        + "\n【被访者本人发言】(\n" + quotes_str + "\n)\n"
    )

    user_payload = "请生成摘要。"

    try:
        text = await chat([
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": user_payload},
        ])
        text = (text or "").strip()
        if not text:
            log.warning("persona-summary LLM returned empty (subject=%s), using fallback", req.subject_id)
            return PersonaSummaryResponse(persona_summary=_DEFAULT_PERSONA_TEMPLATE, fallback=True)
        return PersonaSummaryResponse(persona_summary=text, fallback=False)
    except LlmError as e:
        log.exception("persona-summary LLM error (subject=%s)", req.subject_id)
        return PersonaSummaryResponse(persona_summary=_DEFAULT_PERSONA_TEMPLATE, fallback=True)