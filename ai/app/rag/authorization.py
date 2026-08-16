"""Authorization 校验 + revoked_at 写回。

合规硬要求（plan §2.4）：
  - Milvus filter 必带 `subject_id == "..." AND revoked_at IS NULL`
  - FastAPI 层额外校验：
    1) caller userId 必须是 subject_id 所在家族/工作区的 member（防御性，本服务在
       Spring 后端之后，原则上 Spring 已校验过；这里做兜底防止直连 RAG 服务）
    2) subject_id 对应的 Authorization.status == 'granted' 且未过期

实现：直接调 Spring 后端的 /api/v1/memory/authorizations/check?subjectId=...
失败抛 RagAuthError；Milvus filter 是性能过滤，这层是真正的安全边界。

V15：响应里带回 familyId / familyMemberId，供 RAG 用作 Milvus filter
（跨 family 隔离 + 同 familyMember 共享）。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime

import httpx

from app.config import get_settings

log = logging.getLogger(__name__)


class RagAuthError(PermissionError):
    pass


@dataclass
class AuthContext:
    """V15：authz check 通过后带回的上下文。"""
    family_id: int = 0
    family_member_id: int = 0


def _now_ms() -> int:
    return int(datetime.utcnow().timestamp() * 1000)


async def check_subject_authorization(
    *, subject_id: str, user_id: int | None
) -> AuthContext:
    """校验用户有权读取该 subject 的数据。

    跳过策略（**fail-closed** by default）：
      - user_id is None（Spring 内调用，但显式不传）→ 跳过，依赖 Milvus filter
      - moment_backend_url 未配置 → 跳过（开发环境兜底）
      - 404 端点未实现 → 跳过（**仅开发期**；生产必须返回 200/403）

    **严格 raise** 的情况（不放过）：
      - **401**：内部调用未认证 → 不信任（修过的关键 bug：之前 fallback to allow）
      - 403：明确拒绝
      - **5xx**：Spring 故障 → fail-closed（修过：之前 fallback to allow）
      - **网络错误**：Spring 不可达 → fail-closed（修过：之前 fallback to allow）
      - 200 但 status != "granted"：明确未授权
      - **其他 4xx**（400/405/...）：当作 deny（防未预期状态被吞）

    调用方约定：RagAuthError → pipeline_retrieve 捕获后返回空 SearchResponse，
    debug.auth_rejected 字段带原因。**不会**抛到客户端。
    """
    s = get_settings()
    if not user_id:
        # Spring 端做最终拦截；这里放过
        return AuthContext()
    if not s.moment_backend_url:
        log.warning("MOMENT_BACKEND_URL 未配置，跳过 RAG authz 校验（开发模式）")
        return AuthContext()
    # 调 Spring 端兜底接口（GET /api/v1/memory/subjects/{id}/authorizations）
    # 取该 subject 的授权状态；user 必须对应 subject 所在 family/workspace 的 member
    url = (s.moment_backend_url.rstrip("/")
           + f"/api/v1/memory/subjects/{subject_id}/authorizations/check"
           + f"?userId={user_id}")
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            resp = await client.get(
                url,
                headers={
                    "X-Internal-Call": "rag",
                    # 共享密钥：Spring 端 InternalAuthzController 校验
                    # 不匹配 → 401 → fail-closed
                    "X-Internal-Secret": s.rag_internal_secret,
                },
            )
    except httpx.HTTPError as e:
        # 网络错误：Spring 不可达 → fail-closed（修过：之前是 fallback to allow）
        log.error("subject authz check network error, fail-closed: %s", e)
        raise RagAuthError(f"authz check unreachable: {e}") from e

    if resp.status_code == 200:
        data = resp.json()
        inner = data.get("data") or {}
        status = inner.get("status")
        if status != "granted":
            raise RagAuthError(f"subject {subject_id} 授权状态 {status}")
        # V15：带回 familyId / familyMemberId（可能为 None / 0 → 退回原 subject 粒度）
        return AuthContext(
            family_id=int(inner.get("familyId") or 0),
            family_member_id=int(inner.get("familyMemberId") or 0),
        )
    if resp.status_code == 403:
        raise RagAuthError(f"user {user_id} 非 subject {subject_id} 家族/工作区成员")
    if resp.status_code == 404:
        # 接口可能还没实现（plan 阶段 B），降级放过；Milvus filter 兜底
        log.debug("subject authz endpoint 404, skip")
        return AuthContext()
    if resp.status_code == 401:
        # 内部调用未认证 → 不信任，必须 deny（修过的关键 bug：之前是 fallback to allow）
        log.error("authz check 401: AI 服务未通过 Spring 内部认证，fail-closed")
        raise RagAuthError("authz check 401: 内部调用未认证")
    if resp.status_code >= 500:
        # Spring 故障 → fail-closed（修过：之前是 fallback to allow）
        log.error("authz check 5xx, fail-closed: %s", resp.status_code)
        raise RagAuthError(f"authz check {resp.status_code}: Spring 故障")
    # 其他意外状态码（400/405/...）→ 当作 deny，不允许 fallback
    log.error("authz check 意外状态 %s, fail-closed", resp.status_code)
    raise RagAuthError(f"authz check 意外状态 {resp.status_code}")