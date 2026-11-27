"""Agent 工具集。

工具只做「取数」（查 Java /internal/** 接口），不做任何业务写入；
user_id / pet_id 由请求上下文绑定，避免让模型自己编造用户或宠物 id。
用 langchain_core.tools 的 @tool 装饰器注册，build_tools 按请求闭包绑定后返回列表，
交给 AgentExecutor 使用。
"""
import json
from typing import List, Optional

from langchain_core.tools import tool

from . import java_client


# 每次请求构造一组新工具：闭包绑定当前 userId/petId，模型无法指定别的用户或宠物
def build_tools(user_id: int, pet_id: Optional[int]) -> List:
    # 工具：查当前宠物档案 → GET /internal/pets/{id}；本次未选宠物时直接返回错误提示
    @tool
    def get_pet_profile() -> str:
        """查询当前用户的宠物档案：种类、品种、体重、过敏史、疫苗时间。"""
        if not pet_id:
            return json.dumps({"ok": False, "error": "用户本次未选择宠物"}, ensure_ascii=False)
        pet = java_client.get_pet(int(pet_id), int(user_id))
        if not pet:
            return json.dumps({"ok": False, "error": "未查询到宠物档案"}, ensure_ascii=False)
        return json.dumps({"ok": True, "pet": pet}, ensure_ascii=False, default=str)

    # 工具：查当前用户最近一笔订单 → GET /internal/orders/latest
    @tool
    def get_latest_order() -> str:
        """查询当前用户最近一笔订单的状态、服务项目、预约时间和金额。"""
        order = java_client.get_latest_order(int(user_id))
        if not order:
            return json.dumps({"ok": True, "order": None, "message": "当前没有订单"}, ensure_ascii=False)
        return json.dumps({"ok": True, "order": order}, ensure_ascii=False, default=str)

    # 工具：模型追问时再检索一次知识库 → GET /internal/knowledge/search
    @tool
    def search_pet_knowledge(query: str) -> str:
        """检索宠物饮食、护理、疫苗、行为知识库，返回带标题和来源的片段。"""
        chunks = java_client.search_knowledge(query, 3)
        return json.dumps({"ok": True, "chunks": chunks}, ensure_ascii=False)

    # 这个列表就是 Agent 本次可见/可调用的全部工具集合
    return [get_pet_profile, get_latest_order, search_pet_knowledge]
