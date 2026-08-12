"""从历史 InterviewMessage 自动生成测试 query + ground truth。

跑一次生成 ai/tests/fixtures/recallset.jsonl。
评估脚本：python ai/scripts/eval_retrieval.py

设计要点（plan §6）：
- 按 (subject_id, session_id) 分组，收集 user 消息
- 每个 subject 抽 2 个 session，每个 session 抽 1-2 个有 ground-truth 的 query
- expected_chunk_ids = 同 session 的所有 chunk_id（'interview:{sid}:turn_{i}'）
- 只保留 user 消息数 ≥ 2 的 session（保证能查到）

环境变量：
  MONGO_URI  默认 mongodb://localhost:27017
  MONGO_DB   默认 moment_weaver
"""
from __future__ import annotations

import json
import os
import random
from pathlib import Path

from pymongo import MongoClient


def gen_queries() -> list[dict]:
    random.seed(42)
    mongo_uri = os.getenv("MONGO_URI", "mongodb://localhost:27017")
    mongo_db = os.getenv("MONGO_DB", "moment_weaver")
    db = MongoClient(mongo_uri)[mongo_db]

    by_session: dict[str, dict] = {}
    for doc in db.interview_session.find({}, {
        "messages": 1, "subjectId": 1, "projectId": 1, "_id": 1,
    }):
        msgs = doc.get("messages") or []
        user_msgs = [m for m in msgs if m.get("role") == "user"
                     and (m.get("content") or "").strip()]
        if len(user_msgs) < 2:
            continue
        sid = str(doc.get("_id"))
        by_session[sid] = {
            "subject_id": str(doc.get("subjectId") or ""),
            "session_id": sid,
            "project_id": str(doc.get("projectId") or ""),
            "user_messages": user_msgs,
        }

    # 按 subject 分组
    by_subject: dict[str, list[dict]] = {}
    for s in by_session.values():
        by_subject.setdefault(s["subject_id"], []).append(s)

    queries: list[dict] = []
    for subject_id, sessions in by_subject.items():
        if not subject_id:
            continue
        for sess in random.sample(sessions, min(2, len(sessions))):
            # 每个 session 抽 1-2 个 user 消息作 query
            user_msgs = sess["user_messages"]
            n_pick = min(2, len(user_msgs))
            for um in random.sample(user_msgs, n_pick):
                content = (um.get("content") or "").strip()
                if len(content) < 4:
                    continue
                # ground-truth：本 session 全部 turn 的 chunk_id
                expected = [f"interview:{sess['session_id']}:turn_{i}"
                            for i in range(len(sess["user_messages"]))]
                queries.append({
                    "subject_id": subject_id,
                    "session_id": sess["session_id"],
                    "project_id": sess["project_id"],
                    "query": content,
                    "expected_chunk_ids": expected,
                    "scenario": "interview",
                })
                if len(queries) >= 20:
                    return queries

    return queries[:20]


def main() -> None:
    out_path = Path(__file__).parent.parent / "tests" / "fixtures" / "recallset.jsonl"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    queries = gen_queries()
    if not queries:
        print("⚠️ 没有可生成的 query（Mongo 可能为空，或 user 消息 < 2）")
        print("  请先用采访 UI 跑几个真实 session，再跑这个脚本")
        return
    with open(out_path, "w", encoding="utf-8") as f:
        for q in queries:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")
    print(f"✅ 生成 {len(queries)} 条测试 query → {out_path}")
    print(f"   覆盖 subject 数：{len(set(q['subject_id'] for q in queries))}")
    print(f"   覆盖 session 数：{len(set(q['session_id'] for q in queries))}")


if __name__ == "__main__":
    main()