"""访问 Java 内部接口的客户端。

数据所有权在 Java：Python 不连数据库，只通过 /internal/** 取数，
并带上与 Java 约定的共享令牌。
"""
# 访问 Java 服务的唯一出口：Python 不连数据库，所有数据走 Java 的 /internal/** 接口，
# 请求头带 X-Internal-Token（与 Java InternalController 约定的共享令牌，值见 config.settings.internal_token）。
# 对应 Java 端点：/internal/knowledge/search、/internal/pets/{id}、/internal/orders/latest。
from typing import Any, Dict, List, Optional

import httpx

from .config import settings


# 共享令牌放在 X-Internal-Token 头里，Java 侧校验通过才放行（不是用户登录 token）
def _headers() -> Dict[str, str]:
    return {"X-Internal-Token": settings.internal_token}


# 统一 GET：拼 Java 基地址、带共享令牌与超时；非 200 或 success=false 一律返回 None，
# 让上层走降级/提示，而不是把 Java 的异常抛到 AI 服务里
def _get(path: str, params: Optional[Dict[str, Any]] = None) -> Any:
    url = settings.java_base_url.rstrip("/") + path
    try:
        resp = httpx.get(url, params=params, headers=_headers(), timeout=settings.request_timeout)
        payload = resp.json()
        if resp.status_code != 200 or not payload.get("success"):
            return None
        return payload.get("data")
    except Exception:
        return None


# 知识库检索 → GET /internal/knowledge/search（query 关键词、limit 条数）
def search_knowledge(query: str, limit: int = 3) -> List[Dict[str, Any]]:
    data = _get("/internal/knowledge/search", {"query": query, "limit": limit})
    return data or []


# 宠物档案 → GET /internal/pets/{petId}，带 userId 由 Java 做归属校验
def get_pet(pet_id: int, user_id: int) -> Optional[Dict[str, Any]]:
    return _get(f"/internal/pets/{pet_id}", {"userId": user_id})


# 最近一笔订单 → GET /internal/orders/latest，用于回答「我的订单到哪一步了」
def get_latest_order(user_id: int) -> Optional[Dict[str, Any]]:
    return _get("/internal/orders/latest", {"userId": user_id})
