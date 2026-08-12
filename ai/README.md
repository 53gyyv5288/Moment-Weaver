# Moment Weaver · AI Service

FastAPI 单应用，承担 AI 采访对话、叙事生成、图像基础分析三类能力。

## 本地开发

```powershell
# 安装依赖（推荐用 uv；没有 uv 可用 pip）
pip install -e .

# 启动
uvicorn app.main:app --reload --port 8000
```

## 路由

- `GET  /healthz`                  基础健康检查
- `GET  /readyz`                   依赖连通性自检（DeepSeek / Mongo / Redis）
- `POST /api/v1/interview`         AI 采访对话（M2 落地）
- `POST /api/v1/narrative`         叙事生成（M4 落地）
- `POST /api/v1/asset/ocr`         图像 OCR / 敏感信息（M3 占位）
- **`POST /api/v1/rag/search`**    RAG 检索增强（plan §4.3 三处集成）
- **`POST /api/v1/rag/ingest`**    RAG 批量入库（backfill 用）

## 环境变量

复制 `.env.example` 为 `.env` 并填写：

```
DEEPSEEK_API_KEY=sk-xxx
DEEPSEEK_BASE_URL=https://api.deepseek.com
QWEN_API_KEY=sk-xxx            # 备用
MOMENT_BACKEND_URL=http://localhost:8080

# ============ M6+ RAG ============
DASHSCOPE_API_KEY=sk-xxx                       # text-embedding-v3
MILVUS_URI=http://localhost:19530              # Docker Standalone
RERANKER_URL=http://localhost:9001             # ai-reranker 独立服务
```

## RAG（M6+ Phase 0-3）

### 启动顺序

```bash
# 1. 起 Milvus（deploy/docker-compose.yml 已包含）
docker compose -f deploy/docker-compose.yml up -d milvus

# 2. 起 ai-reranker
cd ai-reranker && docker build -t ai-reranker . && docker run -p 9001:9001 ai-reranker

# 3. 启动主 AI 服务（会自动 ensure_collections + warmup + healthcheck）
uvicorn app.main:app --reload --port 8000

# 4. 一次性 backfill 历史数据
python ai/scripts/backfill_rag.py

# 5. 生成 + 跑评估
python ai/scripts/gen_test_queries.py    # 生成 recallset.jsonl
python ai/scripts/eval_retrieval.py      # Recall@5 ≥ 60% 门槛
```

### 三个集成点

1. **跨 session 回忆** — Spring `InterviewService.streamMessage` 异步拉 evidence（600ms 软超时），注入 system 消息
2. **时间线素材检索** — `GET /api/v1/projects/{pid}/timeline/search?q=...&subjectId=...`
3. **成稿 grounding** — `DraftService.generate` merge RAG facts（top-10，`is_curated_for_facts==true`）到 factsSnapshot

详见 `../docs/rag-plan.md`（如果存在）或 git commit message。
