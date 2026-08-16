"""RAG 公共数据结构。

跨模块复用，避免循环 import。Pydantic 模型做输入校验 + OpenAPI 文档。
"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

# 检索场景（plan §4.3 三处集成）
Scenario = Literal["interview", "timeline", "narrative_facts"]


class ChunkUpsert(BaseModel):
    """单条 chunk 写入请求。"""
    chunk_id: str = Field(..., description="幂等键，如 'interview:{sessionId}:turn_{i}'")
    parent_id: str | None = Field(default=None, description="big chunk 组 id，用于 small-to-big 回溯")
    chunk_text: str = Field(..., max_length=2048)
    parent_text: str = Field(..., max_length=16384)
    subject_id: str
    # 各 collection 特有字段放 metadata
    metadata: dict[str, Any] = Field(default_factory=dict)


class IngestRequest(BaseModel):
    """ingest 端点入参。collection 决定走哪条 chunker + 哪些 metadata 必填。"""
    collection: Literal["interview_chunks", "asset_chunks"]
    chunks: list[ChunkUpsert]
    # 是否事实维度（narrative facts 走此标记）
    is_curated_for_facts: bool = False


class IngestResponse(BaseModel):
    inserted: int
    collection: str


class SearchRequest(BaseModel):
    scenario: Scenario
    query: str = Field(..., min_length=1)
    subject_id: str = Field(..., description="必带；用于 filter")
    user_id: int | None = Field(default=None, description="防御性鉴权")
    top_k: int | None = None  # None 时使用 settings.rag_top_k
    extra_filter: str | None = None  # 额外 Milvus filter（已 union subject_id/revoked）
    # V15：跨 family 隔离 + 同 familyMember 共享授权。
    # 由 Spring /authorizations/check 响应里带回；缺省 0 = 退回原 subject 粒度。
    family_id: int = Field(default=0, description="subject 所在 family；0=个人项目/未回填")
    family_member_id: int = Field(default=0, description="subject 对应 familyMember；0=匿名 subject")


class EvidenceChunk(BaseModel):
    """检索结果。返回给 Spring 端。"""
    chunk_id: str
    parent_text: str  # small-to-big 的父片（喂 LLM 用）
    chunk_text: str | None = None  # 调试用
    score: float
    metadata: dict[str, Any] = Field(default_factory=dict)


class SearchResponse(BaseModel):
    chunks: list[EvidenceChunk]
    rewritten_query: str | None = None
    debug: dict[str, Any] = Field(default_factory=dict)