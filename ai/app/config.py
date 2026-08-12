"""应用配置，全部从环境变量加载。"""
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # 服务
    app_name: str = "moment-weaver-ai"
    app_port: int = 8000
    log_level: str = "INFO"

    # LLM（M2 启用）— OpenAI 兼容协议，兼容 DeepSeek/通义千问/Ollama/vLLM/LM Studio/MiniMax-M3
    # 实际项目里把 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL 替换为你的 MiniMax-M3 服务对应值
    # 真实 key 放在 .env（已 gitignore），这里只是兜底默认值
    llm_base_url: str = "http://localhost:11434/v1"   # Ollama 默认
    llm_api_key: str = "ollama"                        # 本地一般随便填
    llm_model: str = "qwen2.5:7b"
    # M4 family-template-v1 家族成稿（3 subjects）+ MiniMax-M3 推理思考链，
    # 单次实测 4~6 分钟，bump 到 600s 留 1.5x 余量
    llm_timeout_s: float = 600.0
    llm_max_tokens: int = 1024
    # 摘要专用：MiniMax-M3 推理链吃 token 多，全局 1024 不够；summarize.py 单独读这个
    llm_summarize_max_tokens: int = 2048
    llm_temperature: float = 0.7

    # 历史供应商（保留兼容）
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"

    # 业务后端
    moment_backend_url: str = "http://localhost:8080"

    # 中间件
    mongo_uri: str = "mongodb://localhost:27017"
    mongo_db: str = "moment_weaver"
    redis_url: str = "redis://localhost:6379/0"

    # ============ RAG（M6+ Phase 0）============
    # DashScope Embedding（多语种，1024 维）
    dashscope_api_key: str = ""
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024
    embedding_batch_size: int = 16  # DashScope 单次最多 25 文本/请求，留 9 余量
    embedding_qps_limit: float = 30.0  # 保守值；官方限 60，留一半

    # Milvus（Standalone，Docker 本地）
    milvus_uri: str = "http://localhost:19530"
    milvus_token: str = ""  # Standalone 不需要；生产 zilliz cloud 才用
    milvus_db: str = "default"
    milvus_collection_interview: str = "interview_chunks"
    milvus_collection_asset: str = "asset_chunks"

    # Reranker 独立服务
    reranker_url: str = "http://localhost:9001"
    # 5s 兜底 Windows 抖动 / 偶发冷启动。实测 max=100 chars × 10 docs 仅需 0.5s，
    # 留 10x 余量。超时/失败 → pipeline_retrieve 降级到 Milvus 排序。
    reranker_timeout_s: float = 5.0
    reranker_max_fallback: bool = True  # reranker 挂掉时降级到 Milvus 排序

    # 检索参数
    rag_top_k: int = 10  # Milvus 召回候选数（之前 20，CPU rerank 容易超时；减半后留余量）
    rag_top_k_rerank: int = 5  # rerank 后保留
    rag_query_rewrite_timeout_s: float = 0.6  # 软超时：采访流不能被 RAG 阻塞
    rag_query_rewrite_enabled: bool = True

    # 内部服务共享密钥：调 Spring /api/v1/memory/subjects/*/authorizations/check 时
    # 通过 X-Internal-Secret 头传。生产必须用环境变量覆盖，dev 默认值与 Spring 默认一致。
    rag_internal_secret: str = "moment-internal-dev-secret-change-me"


@lru_cache
def get_settings() -> Settings:
    return Settings()
