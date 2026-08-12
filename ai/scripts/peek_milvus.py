"""诊断：Milvus 里的数据到底能不能查出来。

Attu「数据」页为空时跑这个 —— 直接用 SDK query，绕开 Attu 的查询语句生成逻辑。
"""
from pymilvus import MilvusClient

COLL = "interview_chunks"

c = MilvusClient(uri="http://localhost:19530")

print("SERVER:", c.get_server_version())

# 1) 加载状态：没 load 的 collection 查不出数据
try:
    print("load_state:", c.get_load_state(collection_name=COLL))
except Exception as e:
    print("load_state 查询失败:", e)

# 2) 统计口径的行数（可能包含未 flush 的数据）
try:
    print("stats:", c.get_collection_stats(collection_name=COLL))
except Exception as e:
    print("stats 查询失败:", e)

# 3) 真正 query 一把。chunk_id 是 VARCHAR 主键，用 != "" 兜住全部
print("\n--- query(filter='chunk_id != \"\"', limit=5) ---")
try:
    rows = c.query(
        collection_name=COLL,
        filter='chunk_id != ""',
        output_fields=["chunk_id", "subject_id", "session_id", "chunk_text"],
        limit=5,
    )
    print("返回条数:", len(rows))
    for r in rows:
        text = (r.get("chunk_text") or "")[:60].replace("\n", " ")
        print(f"  {r.get('chunk_id')} | subject={r.get('subject_id')} | {text}...")
except Exception as e:
    print("query 失败:", e)

# 4) 对照组：Attu 对整数主键常用的默认表达式，在 VARCHAR 主键上会出错
print("\n--- 对照：query(filter='chunk_id > 0') ---")
try:
    rows2 = c.query(collection_name=COLL, filter="chunk_id > 0", limit=5)
    print("返回条数:", len(rows2))
except Exception as e:
    print("预期内的失败:", type(e).__name__, str(e)[:160])
