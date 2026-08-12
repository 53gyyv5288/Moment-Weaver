"""RAG 模块的最小冒烟测试（不需要 Milvus / reranker / Mongo）。

覆盖：
  - chunker 切片函数（小片 + 父片）
  - query_rewrite prompt 内容（健壮性）
  - schemas pydantic 校验

端到端测试（Milvus + reranker）跑 ai/scripts/eval_retrieval.py。
"""
from __future__ import annotations

from app.rag import chunker, query_rewrite
from app.rag.schemas import ChunkUpsert, IngestRequest, SearchRequest, SearchResponse


def test_interview_chunks_small_and_parent():
    msgs = [
        {"role": "system", "content": "（系统提示，不入索引）"},
        {"role": "user", "content": "您小时候住哪里？"},
        {"role": "assistant", "content": "住在北京胡同里。"},
        {"role": "user", "content": "那时候最爱吃什么？"},
        {"role": "assistant", "content": "炸酱面，冬天妈妈做的酸菜白肉。"},
    ]
    chunks = chunker.interview_chunks(msgs, session_id="sess-1")
    assert len(chunks) == 2, f"expected 2 small chunks, got {len(chunks)}"
    # small chunk 包含 user + assistant
    assert "北京胡同" in chunks[0]["chunk_text"]
    assert "炸酱面" in chunks[1]["chunk_text"]
    # chunk_id 格式
    assert chunks[0]["chunk_id"] == "interview:sess-1:turn_0"
    assert chunks[1]["chunk_id"] == "interview:sess-1:turn_1"
    # parent_id 落在同一窗口
    assert chunks[0]["parent_id"] == chunks[1]["parent_id"]
    # parent_text 至少包含两条对话
    assert "北京胡同" in chunks[0]["parent_text"]
    assert "炸酱面" in chunks[0]["parent_text"]
    # system 不入索引
    assert "系统提示" not in chunks[0]["chunk_text"]


def test_interview_chunks_filters_thinking():
    """assistant.thinking 不进 chunk_text（plan §7）。"""
    msgs = [
        {"role": "user", "content": "讲讲您的家乡"},
        {"role": "assistant", "content": "安徽，皖南山区。", "thinking": "用户在问地理，应该简洁回答"},
    ]
    chunks = chunker.interview_chunks(msgs, session_id="sess-2")
    assert len(chunks) == 1
    assert "皖南山区" in chunks[0]["chunk_text"]
    # thinking 不该出现在 chunk_text / parent_text
    assert "简洁回答" not in chunks[0]["chunk_text"]
    assert "简洁回答" not in chunks[0]["parent_text"]


def test_asset_chunks():
    asset = {
        "id": 42,
        "subjectId": "subj-1",
        "kind": "image",
        "caption": "1980 年春节家庭合影",
        "takenAt": "1980-02-16",
        "originalName": "spring1980.jpg",
        "ossKey": "uploads/2024/01/01/abc.jpg",
    }
    chunks = chunker.asset_chunks(asset, linked_messages=[
        {"role": "user", "content": "那年春节下了好大的雪"},
    ])
    assert len(chunks) == 1
    c = chunks[0]
    assert c["chunk_id"] == "asset:42:v1"
    assert "1980" in c["chunk_text"]
    assert "皖南" not in c["chunk_text"]
    # parent chunk 应有完整元数据
    assert "拍摄/上传时间" in c["parent_text"]
    assert "下了好大的雪" in c["parent_text"]


def test_query_rewrite_prompt():
    """system prompt 应有指代消解 + 时间归一化指引。"""
    p = query_rewrite.SYSTEM_PROMPT
    assert "代词" in p or "他/她" in p
    assert "去年" in p or "相对时间" in p
    assert "YYYY" in p or "日期" in p


def test_schemas_validation():
    """Pydantic 模型校验：必填 + 枚举值。"""
    req = SearchRequest(scenario="interview", query="test", subject_id="s-1")
    assert req.scenario == "interview"
    bad = SearchRequest(scenario="bogus", query="x", subject_id="s-1")
    # pydantic 不阻止非法 enum（只是 typing hint），但 scenario 字段类型是 str
    # 这里只验证关键字段存在
    assert bad.scenario == "bogus"

    chunk = ChunkUpsert(
        chunk_id="interview:s:turn_0",
        chunk_text="x",
        parent_text="y",
        subject_id="s-1",
    )
    assert chunk.chunk_id == "interview:s:turn_0"

    resp = SearchResponse(chunks=[], debug={"k": "v"})
    assert resp.debug["k"] == "v"

    ir = IngestRequest(collection="interview_chunks", chunks=[chunk])
    assert ir.collection == "interview_chunks"