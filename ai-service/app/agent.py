"""LangChain Agent 编排（create_tool_calling_agent + AgentExecutor，不是 LangGraph）。

- 只使用支持 tool calling 的模型（DeepSeek 兼容 OpenAI 协议）
- 预检索的知识片段作为 {context} 注入 prompt，保证回答一定有引用
- 工具用于补充宠物档案、订单状态和追问检索；由 tools.build_tools 用 @tool 注册，
  并按请求绑定 userId/petId，避免模型自己编造 id
"""
from typing import List, Optional, Tuple

from langchain.agents import AgentExecutor, create_tool_calling_agent
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_openai import ChatOpenAI

from .config import settings
from .tools import build_tools

# 系统提示词：限定只依据参考资料/工具、不诊断疾病不给用药剂量、用 [1][2] 标注引用、纯文本输出
SYSTEM_PROMPT = """你是宠物上门服务平台「毛孩到家」的养护助手。

规则：
1. 只能依据【参考资料】和工具返回的数据回答，不要编造事实、数字或订单信息。
2. 不诊断疾病、不给用药建议、不给剂量；涉及疾病、用药、手术、中毒时，直接建议立即就医。
3. 回答用中文，先给结论，再给 2-3 条具体做法，控制在 200 字以内。
4. 使用了参考资料时，在句末用 [1][2] 标注，编号对应参考资料顺序。
5. 资料不足时直接说“暂无可靠依据”，不要猜。
6. 不要回答与宠物养护、平台服务无关的问题。
7. 不要使用 Markdown 标记（不要出现 **、#、- 等符号），用纯文本和换行。
"""


# 用 DeepSeek 的 OpenAI 兼容协议初始化 ChatOpenAI：base_url 补 /v1，temperature 压低减少自由发挥
def _llm() -> ChatOpenAI:
    base_url = settings.deepseek_base_url.rstrip("/")
    if not base_url.endswith("/v1"):
        base_url += "/v1"
    return ChatOpenAI(
        model=settings.model,
        api_key=settings.deepseek_api_key,
        base_url=base_url,
        temperature=0.2,
        timeout=30,
        max_retries=1,
    )


# 组装 tool-calling Agent 并执行，返回 (答案文本, 本次用到的工具名列表)；
# max_iterations 限制「模型↔工具」循环次数，return_intermediate_steps 用于回溯调用了哪些工具
def run_agent(user_id: int, pet_id: Optional[int], question: str,
              context: str) -> Tuple[str, List[str]]:
    # 按当前请求上下文构造工具，userId/petId 由外部传入而非模型生成
    tools = build_tools(user_id, pet_id)
    prompt = ChatPromptTemplate.from_messages([
        ("system", SYSTEM_PROMPT),
        ("human", "【参考资料】\n{context}\n\n【用户问题】\n{input}"),
        MessagesPlaceholder("agent_scratchpad"),
    ])
    agent = create_tool_calling_agent(_llm(), tools, prompt)
    executor = AgentExecutor(
        agent=agent,
        tools=tools,
        max_iterations=settings.max_iterations,
        handle_parsing_errors=True,
        return_intermediate_steps=True,
        verbose=False,
    )
    result = executor.invoke({"input": question, "context": context})
    # 从中间步骤里取出实际调用过的工具名，回传给 Java 侧用于展示与排查
    tools_used = [str(step[0].tool) for step in result.get("intermediate_steps", [])]
    return str(result.get("output", "")).strip(), tools_used
