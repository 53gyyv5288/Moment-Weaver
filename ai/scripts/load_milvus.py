"""补建缺失的索引 + load。

背景：Milvus 要求 load 之前所有向量字段都有索引，否则报
      "there is no vector index on field: [xxx]"，而 get_collection_stats
      仍能读到行数 —— 极易误判成「数据没写进去」。

数据不受影响：建索引不会动已有的 entity。
"""
from pymilvus import MilvusClient
from pymilvus.milvus_client.index import IndexParams

from app.rag.milvus_client import _ASSET_INDEX_PARAMS, _INTERVIEW_INDEX_PARAMS

c = MilvusClient(uri="http://localhost:19530")

TARGETS = [
    ("interview_chunks", _INTERVIEW_INDEX_PARAMS),
    ("asset_chunks", _ASSET_INDEX_PARAMS),
]

for name, params_list in TARGETS:
    print(f"\n=== {name} ===")
    if not c.has_collection(name):
        # 上次启动在 load 环节就抛异常中断了 lifespan，后面的 collection 没来得及建。
        # 重启 AI 服务即可由 ensure_collections() 补建。
        print("collection 不存在，跳过（重启 AI 服务会自动创建）")
        continue
    existing = set(c.list_indexes(collection_name=name))
    print("已有索引:", sorted(existing) or "(无)")

    for idx in params_list:
        field = idx["field_name"]
        if field in existing:
            print(f"  {field:22} skip (已存在)")
            continue
        ip = IndexParams()
        ip.add_index(
            field_name=field,
            index_type=idx["index_type"],
            metric_type=idx.get("metric_type", ""),
            params=idx.get("params") or {},
        )
        try:
            c.create_index(collection_name=name, index_params=ip)
            print(f"  {field:22} ✅ created "
                  f"({idx['index_type']}/{idx.get('metric_type', '-')})")
        except Exception as e:  # noqa: BLE001
            print(f"  {field:22} ❌ FAILED: {e}")
            raise

    c.load_collection(name)
    print("load_state:", c.get_load_state(collection_name=name))

# 验证：load 完立刻能查出数据
rows = c.query(
    collection_name="interview_chunks",
    filter='chunk_id != ""',
    output_fields=["chunk_id", "subject_id", "chunk_text"],
    limit=3,
)
print(f"\n验证 query 返回 {len(rows)} 条：")
for r in rows:
    text = (r.get("chunk_text") or "")[:60].replace("\n", " ")
    print(f"  {r['chunk_id']} | subject={r['subject_id']} | {text}...")
