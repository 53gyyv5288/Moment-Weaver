# ai-reranker Dockerfile
# 镜像基于 python:3.11-slim，小但有 ONNX runtime 所需系统库
FROM python:3.11-slim

# ONNX runtime / transformers / FlagEmbedding 需要的系统库
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 先 copy requirements 单独装，加速层
COPY requirements.txt ./
# 先单独装 torch CPU 版（约 200MB；GPU 版会拖到 ~2.5GB + NVIDIA 一堆库）
# 必须独立 RUN，因为如果跟其他包一起装，pip 会从阿里云拉 GPU 版 torch（包含全部 nvidia-*）
RUN pip install --no-cache-dir \
    --index-url https://download.pytorch.org/whl/cpu \
    "torch>=2.0"
# 再装其他通用包（阿里云镜像，避开 PyPI 官方源在中国大陆的 hash 校验偶发问题）
RUN pip install --no-cache-dir \
    -i https://mirrors.aliyun.com/pypi/simple/ \
    --trusted-host mirrors.aliyun.com \
    -r requirements.txt

COPY main.py ./

# FlagEmbedding / sentence-transformers 默认从 HuggingFace 下载模型。
# 国内必须走镜像源：huggingface.co 在大陆直连经常超时，模型 568MB 会下到一半断。
# hf-mirror.com 是 HuggingFace 官方维护的国内镜像，文件与上游一致。
ENV HF_ENDPOINT=https://hf-mirror.com

# 模型预拉（构建期下载，运行时免下载）—— 注释掉可让容器首次启动时再拉
# RUN python -c "from FlagEmbedding import FlagReranker; FlagReranker('BAAI/bge-reranker-v2-m3', use_fp16=False)"

ENV LOG_LEVEL=INFO \
    RERANKER_MODEL=BAAI/bge-reranker-v2-m3 \
    RERANKER_PORT=9001 \
    OMP_NUM_THREADS=4 \
    MKL_NUM_THREADS=4

EXPOSE 9001

# 启动：uvicorn 单 worker（FlagReranker 进程级共享，无需多 worker）
# --workers 1 是显式约束；多 worker 会让多个进程各自加载 568MB 模型，浪费内存
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9001", "--workers", "1", "--access-log"]