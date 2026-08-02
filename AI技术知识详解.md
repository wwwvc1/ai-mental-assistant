# AI 心理健康助手 — AI 技术知识详解

> 目标：读完能独立开发 AI 对话功能，并为后续学习 LangChain / LangGraph / RAG 打下基础。

---

## 一、整体架构图

```
┌─────────────┐     HTTP/SSE      ┌──────────────┐     API 调用      ┌──────────────┐
│  浏览器前端   │ ────────────────→ │  Spring Boot  │ ────────────────→ │  StepPlan    │
│  (Vue 3)    │ ←──────────────── │  后端服务      │ ←──────────────── │  大模型 API   │
│             │   流式返回          │              │   流式返回          │              │
└─────────────┘                   └──────────────┘                   └──────────────┘
                                        │
                                        ▼
                                  ┌──────────────┐
                                  │     MySQL    │
                                  │  (会话/消息)  │
                                  └──────────────┘
```

**数据流一句话总结**：前端发消息 → 后端存消息 → 后端调大模型 API → 大模型流式返回 → 后端逐字转发给前端。

---

## 二、核心概念逐层拆解

### 2.1 大模型 API 调用（LLM Inference）

#### 什么是大模型 API？
大模型（如 GPT-4、Claude、StepPlan）运行在云服务商的服务器上。你通过 HTTP 请求发 prompt，它返回生成的文本。你的项目用的是**阶跃星辰（StepFun）的 StepPlan 模型**。

#### 调用方式
本项目用 **Spring AI** 框架来调用，它把 HTTP 请求封装成了 Java 代码：

```java
// 核心调用代码（PsychologicalSupportService.java）
chatClient.prompt()
    .user(userMessage)          // 用户说的话
    .stream()                   // 要求流式返回（一个字一个字吐）
    .content()                  // 拿返回的文本流
    .doOnNext(fragment -> {     // 每收到一段就转发给前端
        sink.next(fragment);
    })
    .doOnComplete(() -> {       // 全部回复完成后
        consultationMessageService.saveAimessage(dbSessionId, completeRes, "openai");
    })
    .subscribe();
```

#### 关键参数

| 参数 | 本项目值 | 说明 |
|------|---------|------|
| base_url | `https://api.stepfun.ai/step_plan/v1` | API 地址 |
| model | `step-3.5-flash` | 用的具体模型 |
| api_key | `2FCxKjha...` | 身份凭证，相当于密码 |

> 这些配置在 `ai-springboot/src/main/resources/application.yml` 里。

---

### 2.2 流式输出（SSE Streaming）

#### 为什么不一次性返回？
AI 生成一段 200 字的回复可能需要 5-10 秒。如果等全部生成完再返回，用户会觉得"卡了"。**流式输出**让用户看到逐字出现的打字效果，体验好很多。

#### SSE（Server-Sent Events）协议
SSE 是 HTML5 标准协议，服务器可以持续向客户端推送数据。本项目用它实现流式回复：

**后端（PsychologicalChat.java）：**
```java
@PostMapping(value = "/stream", produces = "text/event-stream")
public Flux<ServerSentEvent<String>> SteamChart(...) {
    return psychologicalSupportService.streamPsychologicalChat(sessionId, userMessage)
        .map(fragment -> ServerSentEvent.<String>builder()
            .event("message")                              // 事件名
            .data(JSONUtil.toJsonStr(Result.ok(Map.of(       // 数据内容
                "message", fragment, "type", "normal"
            ))))
            .build())
        .concatWith(Flux.just(ServerSentEvent.<String>builder()  // 最后发一个 done 事件
            .event("done").data("{}").build()));
}
```

**前端（consultation.vue）：**
```javascript
fetchEventSource('/api/psychological-chat/stream', {
    method: 'POST',
    headers: { 'Token': localStorage.getItem('token') },
    body: JSON.stringify({ sessionId, userMessage }),
    onmessage: (event) => {
        // 每收到一段就拼到消息气泡里
        const payload = JSON.parse(event.data);
        aiMessage.content += payload.data.content;
    }
});
```

#### SSE 数据格式
```
event: message
data: {"code":"200","data":{"message":"你","type":"normal"}}

event: message
data: {"code":"200","data":{"message":"你好","type":"normal"}}

event: message
data: {"code":"200","data":{"message":"你好！我","type":"normal"}}

event: done
data: {}
```

每收到一个 `message` 事件，前端就把 `data.message` 追加到聊天气泡里，形成打字效果。

---

### 2.3 会话管理（Session Management）

#### 概念
一次"会话" = 用户和 AI 的一段完整对话。系统需要：
1. 创建会话（用户第一次发消息时）
2. 保存消息（用户说的 + AI 回复的）
3. 查询历史会话列表
4. 点击历史会话 → 加载之前的对话记录

#### 数据库表设计

| 表名 | 作用 |
|------|------|
| `consultation_session` | 会话表：标题、开始时间、情绪分析结果 |
| `consultation_message` | 消息表：哪个会话、谁发的（用户/AI）、内容、时间 |

#### 会话 ID 格式
```
前端内部格式：sessionId_27          ← 对应数据库 id = 27
后端提取：Long.parseLong("27") → 27
```

---

### 2.4 对话记忆（Chat Memory）

#### 为什么需要记忆？
大模型本身**是无状态的**——每次调用都是独立的，它不记得你上一句说了什么。为了让 AI 能"记住"上下文，需要把历史对话一起发给它。

#### 本项目如何实现记忆
使用 Spring AI 的 `ChatMemory` + `MessageChatMemoryAdvisor`：

```java
// 1. 每次发消息前，把用户消息存入记忆
chatMemory.add(conversationId, List.of(new UserMessage(userMessage)));

// 2. 调用大模型时，传入 conversationId，框架自动把历史消息拼到 prompt 里
chatClient.prompt()
    .user(userMessage)
    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
    .stream()
```

**conversationId** 是一个字符串，本项目用 `"conversation_sessionId_27"` 作为唯一标识。同一个 conversationId 下的所有消息会被自动管理（本项目限制了最近 35 条，防止 token 超限）。

#### 不同框架的记忆对比

| 框架 | 记忆管理 | 本项目 |
|------|---------|--------|
| 原生 API | 手动拼接历史消息到 prompt | ❌ |
| Spring AI | `ChatMemory` + `Advisor` 自动管理 | ✅ 本项目用这个 |
| LangChain | `Memory` 类（如 ConversationBufferMemory） | 🔜 后续学习 |

---

### 2.5 Prompt Engineering（提示词工程）

#### System Prompt
项目里定义了一个系统提示词，决定了 AI 的角色和行为：

```java
// ChatClientConfig.java
.defaultSystem("你是一名专业心理疏导导师，性格温和、富有耐心，擅长倾听。"
    + "你能够提供专业的心理安抚与情绪疏导支持")
```

这是**系统级提示词（System Message）**，它告诉 AI："你不是通用助手，你是心理咨询师"。所有用户消息都会带上这个角色设定。

#### Prompt 结构
每次调用大模型时，实际发送的 prompt 结构是：

```
System: 你是一名专业心理疏导导师，性格温和、富有耐心...

User: 你好啊
```

加上 ChatMemory 管理的上下文，实际可能是：

```
System: 你是一名专业心理疏导导师...

User: 你好啊
Assistant: 你好！我是小暖，很高兴陪伴你...

User: 我今天心情不太好
```

AI 看到完整的对话历史，才能做出连贯的回复。

#### Prompt 模板
本项目用了 `PromptMessage` 类存放系统提示词，这是一种**可扩展的 prompt 管理方式**。后续可以：
- 对不同场景用不同 prompt（如：情绪疏导 vs 认知行为疗法）
- 加入 few-shot examples
- 动态注入用户信息

---

### 2.6 JWT 认证（Security）

#### 什么是 JWT？
JSON Web Token，一种**无状态的认证方式**。用户登录后，服务器签发一个 token 给前端，前端每次请求带上它，服务器验证 token 就知道你是谁。

```
登录：POST /api/user/login {username, password}
  ↓
服务器返回：{token: "eyJhbGciOiJIUzI1NiJ9...", userInfo: {...}}
  ↓
前端存储：localStorage.setItem('token', token)
  ↓
后续请求：Header: Token: eyJhbGciOiJIUzI1NiJ9...
```

#### Token 里存了什么？
```json
{
  "userId": 1,
  "username": "admin",
  "roleType": 2,
  "exp": 1750000000
}
```

#### 无状态 vs 有状态
- **无状态（本项目）**：token 本身包含所有信息，服务器不需要存 session
- **有状态**：服务器内存/Redis 里存 session ID，每次请求查表

本项目用无状态，因为 Spring Security + JWT 天然支持。

---

## 三、LangChain 对照映射

> 学会本项目后，迁移到 LangChain（Python）会非常顺畅，因为核心概念完全对应。

| 本项目（Java / Spring AI） | LangChain（Python） | 说明 |
|---------------------------|---------------------|------|
| `ChatClient` | `ChatOpenAI` / `ChatAnthropic` | LLM 客户端 |
| `ChatMemory` | `ConversationBufferMemory` | 对话记忆 |
| `MessageChatMemoryAdvisor` | `MemorySaver` + `RunnableWithMessageHistory` | 自动管理上下文 |
| `SystemMessage` + `UserMessage` | `SystemMessage` + `HumanMessage` | 消息类型 |
| `Prompt` | `PromptTemplate` | 提示词模板 |
| `Flux<ServerSentEvent>` | `astream_events()` | 流式输出 |
| `advisorSpec.param(CONVERSATION_ID, id)` | `config["configurable"]["session_id"]` | 会话隔离 |

### 最小 LangChain 对照代码

```python
# 等效于本项目 PsychologicalSupportService.java 的核心逻辑
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables.history import RunnableWithMessageHistory
from langchain_community.chat_message_histories import ChatMessageHistory

llm = ChatOpenAI(
    model="step-3.5-flash",
    base_url="https://api.stepfun.ai/step_plan/v1",
    api_key="你的key"
)

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一名专业心理疏导导师..."),
    ("placeholder", "{chat_history}"),
    ("human", "{input}")
])

chain = prompt | llm

# 记忆管理（等效于 ChatMemory）
store = {}
def get_session_history(session_id):
    if session_id not in store:
        store[session_id] = ChatMessageHistory()
    return store[session_id]

chain_with_history = RunnableWithMessageHistory(
    chain,
    get_session_history,
    input_messages_key="input",
    history_messages_key="chat_history",
)

# 流式调用（等效于 .stream()）
for chunk in chain_with_history.stream(
    {"input": "你好啊"},
    config={"configurable": {"session_id": "session_27"}}
):
    print(chunk.content, end="", flush=True)
```

---

## 四、LangGraph 进阶

### 什么是 LangGraph？
LangChain 的升级版，支持**有状态的多步骤 AI 工作流**。适合复杂的对话场景（如：心理咨询的多轮评估）。

#### 本项目 vs LangGraph

| 场景 | 本项目（简单对话） | LangGraph（复杂工作流） |
|------|------------------|----------------------|
| 流程 | 用户输入 → AI 回复（一步） | 用户输入 → 情绪分析 → 判断是否需要干预 → 生成回复 → 风险评估 |
| 状态 | 仅对话历史 | 多节点状态图，每个节点可修改状态 |
| 分支 | 无 | 根据中间结果走不同分支（如：高风险用户走危机干预流程） |

#### 心理咨询的 LangGraph 设计示例

```python
from langgraph.graph import StateGraph, END
from typing import TypedDict

# 定义状态
class ChatState(TypedDict):
    messages: list
    emotion_analysis: dict
    risk_level: int
    need_intervention: bool

# 定义节点
def analyze_emotion(state):
    # 调用情绪分析模型
    return {"emotion_analysis": {...}, "risk_level": 2}

def check_risk(state):
    return {"need_intervention": state["risk_level"] >= 3}

def normal_response(state):
    # 正常心理咨询回复
    return {"messages": state["messages"] + [ai_msg]}

def crisis_intervention(state):
    # 危机干预：提供热线、建议寻求专业帮助
    return {"messages": state["messages"] + [crisis_msg]}

# 构建图
graph = StateGraph(ChatState)
graph.add_node("analyze", analyze_emotion)
graph.add_node("check_risk", check_risk)
graph.add_node("normal", normal_response)
graph.add_node("crisis", crisis_intervention)

graph.add_edge("analyze", "check_risk")
graph.add_conditional_edges("check_risk", 
    lambda s: "crisis" if s["need_intervention"] else "normal"
)
graph.add_edge("crisis", END)
graph.add_edge("normal", END)

app = graph.compile()
```

---

## 五、RAG（检索增强生成）

### 什么是 RAG？
**R**etrieval **A**ugmented **G**eneration = 检索增强生成。

大模型的知识有截止日期，且可能不准确。RAG 的做法是：
1. 用户提问
2. 从知识库检索相关内容
3. 把相关内容作为上下文拼到 prompt 里
4. 大模型基于检索到的内容回答问题

### 本项目已有基础，可以这样扩展

#### 第一步：建立知识库

```sql
-- 知识文章表（本项目已有 knowledge_article 表）
CREATE TABLE knowledge_article (
    id BIGINT PRIMARY KEY,
    title VARCHAR(200),
    content TEXT,           -- 文章全文
    embedding VECTOR(1536)  -- 向量嵌入（用于语义检索）
);
```

#### 第二步：向量化

```python
# 把文章转成向量（用 Embedding 模型）
from openai import OpenAI

client = OpenAI(
    api_key="你的key",
    base_url="https://api.stepfun.ai/step_plan/v1"
)

response = client.embeddings.create(
    model="step-embedding",
    input="文章内容..."
)
vector = response.data[0].embedding  # 1536维向量
```

#### 第三步：检索 + 生成

```python
# 用户提问时
def rag_chat(user_question):
    # 1. 向量化用户问题
    question_vector = embed(user_question)

    # 2. 在向量数据库中检索最相关的文章（Top-K）
    relevant_docs = vector_db.search(question_vector, top_k=3)

    # 3. 把检索结果拼到 prompt 里
    prompt = f"""
    请根据以下参考资料回答用户问题：

    参考资料：
    {format_docs(relevant_docs)}

    用户问题：{user_question}
    """

    # 4. 调用大模型
    response = llm.invoke(prompt)
    return response
```

---

## 六、本项目关键文件速查

### 后端（AI 相关）

| 文件 | 作用 |
|------|------|
| `PsychologicalChat.java` | AI 对话控制器（创建会话、流式对话、获取历史） |
| `PsychologicalSupportService.java` | AI 对话核心逻辑（调用大模型、管理记忆） |
| `ChatClientConfig.java` | ChatClient 配置（模型、提示词、记忆） |
| `application.yml` | AI 模型配置（base_url、api_key、model） |
| `PromptMessage.java` | 系统提示词存放 |
| `ConsultationSessionService.java` | 会话 CRUD |
| `ConsultationMessageService.java` | 消息 CRUD |
| `JwtTokenUtil.java` | JWT 生成和验证 |
| `JwtAuthticationFilter.java` | JWT 认证过滤器 |
| `SecurityConfig.java` | Spring Security 配置 |

### 前端（AI 相关）

| 文件 | 作用 |
|------|------|
| `consultation.vue` | AI 对话页面（发送消息、流式接收、会话管理） |
| `consultations.vue` | 咨询记录列表页 |
| `frontend.js` | 前端 API 封装（startSession、getSessionList 等） |
| `request.js` | Axios 拦截器（token 注入、错误处理） |
| `router/index.js` | 路由守卫（登录态检查） |
| `login.vue` | 登录页 |

---

## 七、学习路线建议

```
当前阶段（本项目）：
  ✅ Spring AI 基础调用
  ✅ SSE 流式输出
  ✅ ChatMemory 对话记忆
  ✅ JWT 认证

  ↓ 下一步（建议按顺序）

1. Prompt Engineering 进阶
   - 学习 few-shot、chain-of-thought、role prompting
   - 尝试不同的 system prompt 看效果差异
   - 参考：OpenAI Cookbook

2. LangChain（Python）
   - 用本项目同样的逻辑用 Python + LangChain 重写
   - 学习 LangChain 的 Chain、Agent、Memory 概念
   - 参考：LangChain 官方文档 / LangChain Cookbook

3. LangGraph
   - 学习 StateGraph、节点、边、条件路由
   - 用 LangGraph 重构心理咨询流程（情绪分析 → 风险判断 → 回复生成）
   - 参考：LangGraph 官方文档

4. RAG
   - 学习 Embedding、向量数据库（ChromaDB / Milvus / Pinecone）
   - 把本项目心理知识文章接入 RAG
   - 参考：LangChain RAG 教程

5. 进阶
   - Function Calling（让 AI 调用工具）
   - 多模态（图片情绪分析）
   - Agent（自主决策 + 工具调用）
```

---

## 八、常见问题

### Q1：为什么用 `fetchEventSource` 而不是 Axios？
Axios 不支持 SSE（Server-Sent Events），`fetchEventSource`（来自 `@microsoft/fetch-event-source`）是微软开发的 SSE 客户端，专门处理流式数据。

### Q2：`advisors` 是什么？
Spring AI 的 Advisor 机制类似于 AOP 切面，可以在调用大模型前后插入逻辑。`MessageChatMemoryAdvisor` 自动处理对话记忆的读写，你不需要手动管理。

### Q3：ChatMemory 存在哪里？
默认存在内存中（`ConcurrentHashMap`），应用重启就丢失。生产环境可以换成 Redis：
```yaml
spring:
  ai:
    chat:
      memory:
        redis:
          # 用 Redis 存储对话记忆
```

### Q4：流式输出和普通请求有什么区别？
```
普通请求：等待 AI 全部生成完毕 → 一次性返回 → 用户等待 5-10 秒
流式输出：AI 每生成一个字就推送一次 → 用户实时看到 → 体验好很多
```

### Q5：token 和 API Key 有什么区别？
- **Token**：用户登录后拿到的身份凭证，证明"我是谁"
- **API Key**：后端调用大模型时的密钥，证明"我有权限用这个模型"

---

*如有疑问，随时问。建议边读代码边对照本文档，每个概念在项目里找到对应的代码行，理解会更深刻。*
