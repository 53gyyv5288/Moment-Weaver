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

- `GET  /healthz`          基础健康检查
- `GET  /readyz`           依赖连通性自检（DeepSeek / Mongo / Redis）
- `POST /api/v1/interview` AI 采访对话（M2 落地）
- `POST /api/v1/narrative` 叙事生成（M4 落地）
- `POST /api/v1/asset/ocr` 图像 OCR / 敏感信息（M3 占位）

## 环境变量

复制 `.env.example` 为 `.env` 并填写：

```
DEEPSEEK_API_KEY=sk-xxx
DEEPSEEK_BASE_URL=https://api.deepseek.com
QWEN_API_KEY=sk-xxx            # 备用
MOMENT_BACKEND_URL=http://localhost:8080
```
