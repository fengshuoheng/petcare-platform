"""宠物养护 AI 服务（FastAPI + LangChain Agent）。

职责：多轮对话、工具编排、RAG 生成。
边界：不连数据库、不写业务数据，所有数据通过 Java /internal/** 获取。

路由：
  GET  /health      探活，顺带暴露模型名、是否已配置 Key、Java 基地址，供部署检查
  POST /agent/chat  对话主入口，由 Java 侧 /api/ai/chat 转发过来，返回一次性 JSON
处理链路：医疗敏感词入口拒答 → java_client 检索知识库 → 配了 Key 就交给 LangChain Agent 生成 →
          无 Key / 模型异常时降级为模板答案，保证 Java 侧不会因为 AI 服务失败而报错。
注意：当前是普通 JSON 响应，不是 SSE/流式输出；若要流式需改成 StreamingResponse(text/event-stream)。
"""
from typing import Any, Dict, List, Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

from . import java_client
from .agent import run_agent
from .config import settings

app = FastAPI(title="petcare-ai-service", version="1.0.0")

# 疾病/用药类敏感词：命中即在入口拒答，不检索也不调模型（合规边界）
REFUSE_WORDS = [
    "药", "用药", "剂量", "生病", "呕吐", "腹泻", "拉稀", "发烧", "抽搐",
    "中毒", "手术", "骨折", "便血", "怀孕", "难产", "诊断",
]

REFUSE_ANSWER = (
    "这个问题涉及疾病、用药或剂量，我不能给出建议。"
    "请尽快带宠物去正规宠物医院就医，并如实告诉医生症状、持续时间和近期饮食。"
)


# 请求体：userId/sessionId 用于多轮上下文，petId 可选，message 限长 500
class ChatRequest(BaseModel):
    userId: int
    sessionId: str = ""
    message: str = Field(..., max_length=500)
    petId: Optional[int] = None


# 只要问题里出现任一敏感词就判定为医疗类，走拒答分支
def _contains_medical(text: str) -> bool:
    return any(word in text for word in REFUSE_WORDS)


# 把知识片段压成前端展示用的引用：标题、来源、正文前 120 字摘要
def _citations(chunks: List[Dict[str, Any]]) -> List[Dict[str, str]]:
    result = []
    for chunk in chunks:
        result.append({
            "title": chunk.get("title", ""),
            "source": chunk.get("source", ""),
            "snippet": (chunk.get("content") or "")[:120],
        })
    return result


# 把检索片段拼成带 [1][2] 编号的【参考资料】注入 prompt，约束模型只据此作答并标注引用
def _context(chunks: List[Dict[str, Any]]) -> str:
    if not chunks:
        return "（本次没有检索到相关资料）"
    lines = []
    for index, chunk in enumerate(chunks, start=1):
        lines.append(
            f"[{index}]《{chunk.get('title', '')}》（{chunk.get('source', '')}）：{chunk.get('content', '')}"
        )
    return "\n".join(lines)


# 无 Key / 模型异常时的降级答案：直接引用片段原文，不经过大模型，保证始终有据可依
def _template_answer(chunks: List[Dict[str, Any]], tool_note: str = "") -> str:
    if not chunks:
        return (tool_note + "我暂时没有在知识库里找到可靠依据，建议咨询兽医或平台客服。").strip()
    answer = chunks[0].get("content", "")
    if len(chunks) > 1:
        answer += "\n\n补充：" + chunks[1].get("content", "")
    sources = "、".join(f"《{c.get('title')}》" for c in chunks)
    return (tool_note + answer + f"\n\n来源：{sources}\n"
            "如果宠物出现精神差、呕吐、腹泻等异常，请及时就医。").strip()


# 探活接口：返回模型名、是否已配置 DeepSeek Key、Java 基地址，不依赖任何业务数据
@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "model": settings.model,
        "llmConfigured": bool(settings.deepseek_api_key),
        "javaBaseUrl": settings.java_base_url,
    }


# 对话入口：Java 的 AiService 通过 HTTP 调这里；返回 answer/citations/refused/llmUsed/tools/sessionId
@app.post("/agent/chat")
def chat(request: ChatRequest) -> Dict[str, Any]:
    question = request.message.strip()
    if not question:
        return {"answer": "问题不能为空。", "citations": [], "refused": False,
                "llmUsed": False, "tools": [], "sessionId": request.sessionId}

    # 医学类问题在入口直接拒答，不调模型
    if _contains_medical(question):
        return {
            "answer": REFUSE_ANSWER,
            "citations": [],
            "refused": True,
            "llmUsed": False,
            "tools": ["medical_refuse"],
            "sessionId": request.sessionId,
        }

    # 先调 Java 的 /internal/knowledge/search 取知识片段，引用与 prompt 资料都来自这里
    chunks = java_client.search_knowledge(question, 3)
    citations = _citations(chunks)

    # 只有配置了 Key 才启用 LangChain Agent；否则直接走下面的模板答案，离线也能回答
    if settings.deepseek_api_key:
        try:
            answer, tools_used = run_agent(request.userId, request.petId, question, _context(chunks))
            if answer:
                return {
                    "answer": answer,
                    "citations": citations,
                    "refused": False,
                    "llmUsed": True,
                    "tools": tools_used,
                    "sessionId": request.sessionId,
                }
        except Exception as exc:  # 模型异常时降级为模板，不让 Java 侧失败
            print(f"[agent] fallback: {exc}")

    return {
        "answer": _template_answer(chunks),
        "citations": citations,
        "refused": False,
        "llmUsed": False,
        "tools": [],
        "sessionId": request.sessionId,
    }
