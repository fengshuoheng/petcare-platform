# petcare-ai-service

宠物养护 AI 服务：**FastAPI + LangChain Agent + RAG**。
Java 主业务通过 HTTP 调用本服务；本服务不连数据库，所有数据走 Java 的 `/internal/**` 接口。

## 职责边界

```
Vue → Java /api/ai/chat
        ├─ 鉴权、限流、会话落库
        ├─ 超时 2s/15s，失败降级到本地规则 RAG
        └─ HTTP → Python /agent/chat
                    ├─ 医学类问题直接拒答
                    ├─ 预检索知识库（Java 内部接口）
                    ├─ LangChain Agent + 3 个工具
                    └─ 返回 answer + citations + tools
```

- **Java**：业务数据、权限、事务、降级
- **Python**：检索编排、生成、评测
- Python 不直连 MySQL，避免双写和权限失控

## 工具

| 工具 | 数据来源 | 作用 |
|---|---|---|
| `get_pet_profile` | `GET /internal/pets/{id}` | 种类、体重、过敏、疫苗 |
| `get_latest_order` | `GET /internal/orders/latest` | 最近订单状态、预约时间 |
| `search_pet_knowledge` | `GET /internal/knowledge/search` | 知识库检索 |

工具里的 `userId` / `petId` 由请求上下文绑定，模型不能自己编造。

## 启动

```bash
cd ai-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

set JAVA_BASE_URL=http://localhost:8082
set INTERNAL_TOKEN=petcare-internal-token
set DEEPSEEK_API_KEY=sk-xxx        # 不填则走模板回答

uvicorn app.main:app --host 0.0.0.0 --port 8000
```

验证：

```bash
curl http://localhost:8000/health
curl -X POST http://localhost:8000/agent/chat -H "Content-Type: application/json" -d "{\"userId\":5,\"sessionId\":\"s1\",\"message\":\"三个月幼猫怎么喂\",\"petId\":1}"
```

Java 侧 `application-local.yml` 默认已开启：

```yaml
petcare:
  ai:
    enabled: true
    service-url: http://localhost:8000
    internal-token: petcare-internal-token
```

## 降级策略

| 情况 | 行为 |
|---|---|
| Python 没启动 / 超时 | Java 2s 连接、15s 读超时，失败后走本地规则 RAG |
| 没配 `DEEPSEEK_API_KEY` | 本服务用检索到的片段拼答案，仍然带引用 |
| 模型调用异常 | 本服务内部降级为模板回答 |
| 命中疾病/用药关键词 | 入口直接拒答，不调模型 |

## 下一步（可选）

- SSE 流式输出（`/agent/chat/stream`）
- 知识库 embedding + 混合检索（替换关键词打分）
- 30 条评测集：召回@3、引用正确率、拒答率
