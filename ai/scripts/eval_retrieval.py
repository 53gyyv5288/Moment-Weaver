"""RAG retrieval 评估脚本。

读 tests/fixtures/recallset.jsonl，调 /api/v1/rag/search，
输出 Recall@1 / Recall@5。Recall@5 < 0.6 时报错（plan §6 质量门槛）。

环境变量：
  RAG_AI_BASE_URL  默认 http://localhost:8000
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

import httpx


async def eval_one(client: httpx.AsyncClient, query: dict) -> tuple[bool, bool]:
    body = {
        "scenario": query["scenario"],
        "query": query["query"],
        "subject_id": query["subject_id"],
    }
    try:
        resp = await client.post(
            "/api/v1/rag/search", json=body,
            timeout=httpx.Timeout(10.0),
        )
        if resp.status_code != 200:
            return False, False
        data = resp.json()
        hits = data.get("chunks", []) or []
        ids = [h.get("chunkId") for h in hits[:5]]
        expected = set(query["expected_chunk_ids"])
        hit_top1 = bool(ids) and ids[0] in expected
        hit_top5 = any(i in expected for i in ids)
        return hit_top1, hit_top5
    except (httpx.HTTPError, json.JSONDecodeError):
        return False, False


async def main() -> int:
    fixture = Path(__file__).parent.parent / "tests" / "fixtures" / "recallset.jsonl"
    if not fixture.exists():
        print(f"❌ fixtures 不存在：{fixture}")
        print("   先跑：python ai/scripts/gen_test_queries.py")
        return 1
    queries = [json.loads(l) for l in fixture.read_text(encoding="utf-8").splitlines() if l.strip()]
    if not queries:
        print("❌ recallset 为空")
        return 1

    base = os.getenv("RAG_AI_BASE_URL", "http://localhost:8000").rstrip("/")
    print(f"Evaluating {len(queries)} queries against {base} ...")

    hits1, hits5 = 0, 0
    async with httpx.AsyncClient(base_url=base) as client:
        for q in queries:
            h1, h5 = await eval_one(client, q)
            hits1 += int(h1)
            hits5 += int(h5)
            mark1 = "✓" if h1 else "✗"
            mark5 = "✓" if h5 else "✗"
            print(f"  {mark1}{mark5}  query={q['query'][:30]!r} expected={len(q['expected_chunk_ids'])}")

    n = len(queries)
    r1 = hits1 / n
    r5 = hits5 / n
    print(f"\n📊 Recall@1 = {r1:.0%} ({hits1}/{n})")
    print(f"📊 Recall@5 = {r5:.0%} ({hits5}/{n})")
    if r5 < 0.6:
        print(f"❌ Recall@5 = {r5:.0%} < 60% 门槛，质量回归！")
        return 1
    print(f"✅ Recall@5 = {r5:.0%} ≥ 60%，通过")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))