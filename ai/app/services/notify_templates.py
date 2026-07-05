"""通知文案模板（M5-A.4）。

设计：
  - 模板优先：保证零 LLM 依赖也能给出合理通知
  - 模板按 event_type 路由；变量走 .format() 注入
  - 可选 AI 改写：M5 暂不开启（开关 MOMENT_NOTIFY_AI_ENABLED=false）
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class NotifyTemplate:
    title: str
    body: str
    # body 用 {placeholders} 渲染；title 渲染后 ≤ 20 字
    # body 渲染后 ≤ 60 字


# 注意：body 中占位符需要 {actor} / {draft_title} / {share_scope} / {days} 等具体值
# 模板里的字面量必须满足"渲染后" ≤ 60 字
_REGISTRY: dict[str, NotifyTemplate] = {
    "DRAFT_PUBLISHED": NotifyTemplate(
        title="成稿已发布",
        body="《{draft_title}》已发布，可阅读 / 分享",
    ),
    "SHARE_CREATED": NotifyTemplate(
        title="新分享链接",
        body="《{draft_title}》{share_scope} · {days} 天后到期",
    ),
    "SHARE_ACCESSED": NotifyTemplate(
        title="分享被访问",
        body="链接被访问 {count} 次",
    ),
    "SHARE_REVOKED": NotifyTemplate(
        title="分享已撤销",
        body="《{draft_title}》的分享链接已撤销",
    ),
    "SHARE_EXPIRED": NotifyTemplate(
        title="分享已过期",
        body="《{draft_title}》的分享链接已过期",
    ),
    "EXPORT_READY": NotifyTemplate(
        title="导出已就绪",
        body="你的数据导出已生成，7 天内可下载",
    ),
    "DELETION_EXECUTED": NotifyTemplate(
        title="删除已执行",
        body="你请求的删除已执行，数据不可恢复",
    ),
    "AUTHORIZATION_REVOKED": NotifyTemplate(
        title="授权被撤回",
        body="{count} 个 draft 章节因授权撤回已脱敏",
    ),
    "UNKNOWN": NotifyTemplate(
        title="通知",
        body="你有新通知",
    ),
}


def render(event_type: str, vars: dict | None = None) -> tuple[str, str]:
    """
    返回 (title, body)，渲染失败时回退到 UNKNOWN 模板。
    """
    tpl = _REGISTRY.get(event_type) or _REGISTRY["UNKNOWN"]
    v = vars or {}
    try:
        title = tpl.title.format(**v)
        body = tpl.body.format(**v)
    except KeyError as e:
        log.warning("notify template missing var: type=%s var=%s", event_type, e)
        title = tpl.title
        body = tpl.body
    # 兜底截断
    if len(title) > 30:
        title = title[:30]
    if len(body) > 80:
        body = body[:80]
    return title, body
