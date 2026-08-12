"""RAG backfill：一次性从 Mongo + MySQL 把历史 InterviewMessage / Asset 全量入库。

跑一次：python ai/scripts/backfill_rag.py

数据源：
  - Mongo interview_session.messages → interview_chunks
  - MySQL asset (caption 非空)      → asset_chunks

目标：POST /api/v1/rag/ingest（chunk 级）

环境变量：
  RAG_AI_BASE_URL  默认 http://localhost:8000
  MONGO_URI        默认 mongodb://localhost:27017
  MONGO_DB         默认 moment_weaver
  MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx

# 复用在线 chunker 的思考链剥离规则，避免离线/在线两套逻辑漂移
from app.rag.chunker import strip_thinking

# Mongo + MySQL 都是可选依赖：开发机不一定都装。
try:
    from pymongo import MongoClient
except ImportError:
    MongoClient = None  # type: ignore[assignment]

try:
    import pymysql
except ImportError:
    pymysql = None  # type: ignore[assignment]


def _ms(dt: Any) -> int:
    if dt is None:
        return int(datetime.utcnow().timestamp() * 1000)
    if isinstance(dt, datetime):
        return int(dt.timestamp() * 1000)
    try:
        return int(dt)
    except (ValueError, TypeError):
        return int(datetime.utcnow().timestamp() * 1000)


async def _post_ingest(client: httpx.AsyncClient, body: dict) -> int:
    try:
        resp = await client.post("/api/v1/rag/ingest", json=body, timeout=60.0)
        if resp.status_code != 200:
            print(f"  ⚠️  ingest returned {resp.status_code}: {resp.text[:200]}")
            return 0
        data = resp.json()
        return int(data.get("inserted", 0))
    except httpx.HTTPError as e:
        print(f"  ⚠️  ingest error: {e}")
        return 0


def _msgs_to_chunks(messages: list[dict], session_id: str, subject_id: str) -> list[dict]:
    """与 AI 端 chunker.interview_chunks 规则一致：每对 user+assistant 一片。"""
    out: list[dict] = []
    i = 0
    j = 0
    while i < len(messages):
        m = messages[i]
        if m.get("role") == "user" and (m.get("content") or "").strip():
            user = (m.get("content") or "").strip()
            asst = ""
            if i + 1 < len(messages) and messages[i + 1].get("role") == "assistant":
                asst = strip_thinking((messages[i + 1].get("content") or "").strip())
            chunk_text = f"受访者：{user}"
            if asst:
                chunk_text += f"\nAI 采访官：{asst}"
            parent_text = chunk_text
            if len(chunk_text) > 2048:
                chunk_text = chunk_text[:2048]
            if len(parent_text) > 16384:
                parent_text = parent_text[:16384]
            out.append({
                "chunk_id": f"interview:{session_id}:turn_{j}",
                "parent_id": f"interview:{session_id}:win_{j // 3}",
                "chunk_text": chunk_text,
                "parent_text": parent_text,
                "subject_id": subject_id,
                "metadata": {
                    "session_id": session_id,
                    "role": "user+assistant",
                    "turn_index": j,
                    "created_at_ms": _ms(m.get("createdAt")),
                },
            })
            j += 1
            i += 2 if asst else 1
        else:
            i += 1
    return out


def fetch_interview_chunks() -> list[dict]:
    if MongoClient is None:
        print("⚠️  pymongo 未装，跳过 interview backfill")
        return []
    mongo_uri = os.getenv("MONGO_URI", "mongodb://localhost:27017")
    mongo_db = os.getenv("MONGO_DB", "moment_weaver")
    db = MongoClient(mongo_uri)[mongo_db]
    all_chunks: list[dict] = []
    for doc in db.interview_session.find({}):
        sid = str(doc.get("_id"))
        subject_id = str(doc.get("subjectId") or "")
        if not subject_id:
            continue
        msgs = doc.get("messages") or []
        chunks = _msgs_to_chunks(msgs, sid, subject_id)
        all_chunks.extend(chunks)
    print(f"  interview: {len(all_chunks)} chunks from {db.interview_session.count_documents({})} sessions")
    return all_chunks


def fetch_asset_chunks() -> list[dict]:
    if pymysql is None:
        print("⚠️  pymysql 未装，跳过 asset backfill")
        return []
    host = os.getenv("MYSQL_HOST", "localhost")
    port = int(os.getenv("MYSQL_PORT", "3306"))
    user = os.getenv("MYSQL_USER", "root")
    pwd = os.getenv("MYSQL_PASSWORD", "")
    db_name = os.getenv("MYSQL_DB", "moment_weaver")
    conn = pymysql.connect(host=host, port=port, user=user, password=pwd,
                            database=db_name, charset="utf8mb4")
    out: list[dict] = []
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, subject_id, kind, caption, taken_at, oss_key, original_name "
                "FROM asset WHERE caption IS NOT NULL AND caption != '' AND deleted = 0"
            )
            for row in cur.fetchall():
                asset_id, subject_id, kind, caption, taken_at, oss_key, original_name = row
                if subject_id is None:
                    continue
                caption = caption or ""
                kind = kind or "image"
                chunk_text = f"素材描述：{caption}"
                if original_name:
                    chunk_text += f"\n文件名：{original_name}"
                parent_lines = [f"素材类型：{kind}"]
                if taken_at: parent_lines.append(f"拍摄时间：{taken_at}")
                if original_name: parent_lines.append(f"文件名：{original_name}")
                if caption: parent_lines.append(f"描述：{caption}")
                if oss_key: parent_lines.append(f"存储路径：{oss_key}")
                parent_text = "\n".join(parent_lines)
                out.append({
                    "chunk_id": f"asset:{asset_id}:v1",
                    "parent_id": f"asset:{asset_id}",
                    "chunk_text": chunk_text[:2048] or "(无描述)",
                    "parent_text": parent_text[:16384] or chunk_text,
                    "subject_id": str(subject_id),
                    "metadata": {
                        "asset_id": asset_id,
                        "kind": kind,
                        "taken_at": _ms(taken_at),
                        "file_url": oss_key or "",
                    },
                })
    finally:
        conn.close()
    print(f"  asset: {len(out)} chunks")
    return out


async def main() -> int:
    base = os.getenv("RAG_AI_BASE_URL", "http://localhost:8000").rstrip("/")
    print(f"Backfilling RAG → {base}/api/v1/rag/ingest")

    interview_chunks = fetch_interview_chunks()
    asset_chunks = fetch_asset_chunks()

    if not interview_chunks and not asset_chunks:
        print("❌ 没拉到任何 chunk，请检查 Mongo / MySQL 配置")
        return 1

    # 批量发送（每批 200）
    BATCH = 200
    async with httpx.AsyncClient(base_url=base) as client:
        total = 0
        if interview_chunks:
            print(f"\n→ ingest interview_chunks ({len(interview_chunks)})")
            for i in range(0, len(interview_chunks), BATCH):
                batch = interview_chunks[i:i + BATCH]
                n = await _post_ingest(client, {
                    "collection": "interview_chunks",
                    "is_curated_for_facts": False,
                    "chunks": batch,
                })
                total += n
                print(f"  [{i + len(batch)}/{len(interview_chunks)}] inserted={n}")
        if asset_chunks:
            print(f"\n→ ingest asset_chunks ({len(asset_chunks)})")
            for i in range(0, len(asset_chunks), BATCH):
                batch = asset_chunks[i:i + BATCH]
                n = await _post_ingest(client, {
                    "collection": "asset_chunks",
                    "is_curated_for_facts": False,
                    "chunks": batch,
                })
                total += n
                print(f"  [{i + len(batch)}/{len(asset_chunks)}] inserted={n}")
    print(f"\n✅ backfill done, total inserted: {total}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))