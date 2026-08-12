"""诊断：Milvus 服务端版本 + collection 是否注册了 BM25 Function。"""
from pymilvus import MilvusClient

c = MilvusClient(uri="http://localhost:19530")
print("SERVER:", c.get_server_version())
for name in c.list_collections():
    d = c.describe_collection(name)
    print(f"\n=== {name} ===")
    print("functions:", d.get("functions"))
    for f in d["fields"]:
        flags = []
        if f.get("is_primary"):
            flags.append("PK")
        if f.get("is_function_output"):
            flags.append("FN_OUT")
        print(f"  {f['name']:22} {f['type']} {' '.join(flags)}")
