# 配置读取：固定加载 ai-service/.env（避免 PyCharm 工作目录不同读不到 Key），
# 所有值都取自环境变量，便于 Docker 注入；Settings 是 dataclass，模块加载时生成单例 settings。
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

# 固定加载 ai-service/.env，避免在 PyCharm 里工作目录不同导致读不到 Key
BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


# 字段默认值即 os.getenv 的兜底值：JAVA_BASE_URL 指向 Spring Boot，
# INTERNAL_TOKEN 是与 Java 约定的共享令牌，DEEPSEEK_* 控制是否启用大模型
@dataclass
class Settings:
    """运行配置，全部来自环境变量，方便 Docker 注入。"""

    java_base_url: str = os.getenv("JAVA_BASE_URL", "http://localhost:8082")
    internal_token: str = os.getenv("INTERNAL_TOKEN", "petcare-internal-token")
    deepseek_api_key: str = os.getenv("DEEPSEEK_API_KEY", "")
    deepseek_base_url: str = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    model: str = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
    max_iterations: int = int(os.getenv("AGENT_MAX_ITERATIONS", "5"))
    request_timeout: float = float(os.getenv("JAVA_TIMEOUT_SECONDS", "5"))


# 全局配置单例，其他模块统一 from .config import settings 复用
settings = Settings()
