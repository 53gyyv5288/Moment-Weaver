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


@lru_cache
def get_settings() -> Settings:
    return Settings()
