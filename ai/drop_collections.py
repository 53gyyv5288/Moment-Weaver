"""清 Milvus collection（V15 重建用）。

⚠️ 危险：会删掉所有 RAG chunks。先停 AI 服务再跑。

用法：
  python drop_collections.py
  MILVUS_URI=http://localhost:19530 python drop_collections.py
"""
from pymilvus import MilvusClient
import os

uri = os.getenv("MILVUS_URI", "http://localhost:19530")
token = os.getenv("MILVUS_TOKEN", "")
db = os.getenv("MILVUS_DB", "default")

print(f"Connecting to Milvus: uri={uri} db={db}")
client = MilvusClient(uri=uri, token=token or None, db_name=db)

for name in ("interview_chunks", "asset_chunks"):
    if client.has_collection(name):
        client.drop_collection(name)
        print(f"  ✓ dropped: {name}")
    else:
        print(f"  - not exists: {name}")

print("Done. Restart AI service to recreate with V15 schema.")
