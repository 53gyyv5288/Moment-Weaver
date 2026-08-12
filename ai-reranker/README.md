# ai-reranker · 独立 Rerank 服务

为 Moment Weaver 主 AI 服务提供 bge-reranker-v2-m3 推理，独立部署、独立重启、不影响采访流。

## 端口

`9001`（与主 AI 服务 `8000` 隔离）

## 启动

### 本地（开发）

```bash
cd ai-reranker
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 9001 --reload
```

首次启动会从 HuggingFace 下载 `BAAI/bge-reranker-v2-m3` 模型（≈ 568 MB），10-30s 冷启动。

### Docker

`docker-compose.yml` 已包含 `ai-reranker` 服务。手动构建：

```bash
docker build -t momentweaver/ai-reranker:latest ai-reranker/
docker run -p 9001:9001 momentweaver/ai-reranker:latest
```

## API

### POST `/rerank`

请求：

```json
{
  "query": "1978 年那张老照片",
  "documents": ["受访者：1978 年安徽插队", "受访者：1980 年春节家庭合影", "..."],
  "top_k": 5,
  "normalize": true
}
```

响应：

```json
{
  "results": [
    {"index": 1, "score": 0.93},
    {"index": 0, "score": 0.71}
  ],
  "cost_s": 0.234
}
```

### GET `/healthz`

```json
{"status": "ok", "model": "BAAI/bge-reranker-v2-m3"}
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `RERANKER_MODEL` | `BAAI/bge-reranker-v2-m3` | HuggingFace model id |
| `RERANKER_PORT` | `9001` | 监听端口 |
| `OMP_NUM_THREADS` | `4` | CPU 线程数 |
| `MKL_NUM_THREADS` | `4` | Intel MKL 线程数 |
| `LOG_LEVEL` | `INFO` | 日志级别 |
| `HF_ENDPOINT` | (unset) | 设为 `https://hf-mirror.com` 走国内镜像 |

## 性能

- 20 对打分：约 200-500ms（CPU + ONNX）
- 50 对打分：约 500-1200ms
- 长文档（>2000 字）：单对 100-200ms