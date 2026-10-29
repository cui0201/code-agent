# 附录 D：Agent 框架生态与选型建议

> 数据采集时间：**2026-09-21**（GitHub API 实时查询）。
> star 数只反映关注度，不代表工程质量或长期存续——本页结论正依赖这一点。

---

## D.1 当前生态全貌（按类别）

### ① 通用编排框架（Python / TypeScript 为主）

| 框架 | Stars | 最近提交 | 定位 |
| --- | --- | --- | --- |
| langchain-ai/langchain | 146.8k | 活跃 | 事实标准，生态最大，但抽象层厚 |
| langchain-ai/langgraph | 42.1k | 活跃 | 状态机式编排，复杂流程与可回放的首选 |
| crewAIInc/crewAI | 58.8k | 活跃 | 角色扮演式多智能体协作，上手快 |
| microsoft/agent-framework | 13.7k | 活跃 | **微软新统一框架**（AutoGen 与 Semantic Kernel 合流） |
| microsoft/autogen | 61.1k | **2026-04 起停更** | 已被 Agent Framework 取代 |
| microsoft/semantic-kernel | 28.6k | 活跃 | C#/.NET 生态主力，仍在维护 |
| openai/openai-agents-python | 29.6k | 活跃 | OpenAI 官方 Agents SDK，轻量、贴近官方 API |
| google/adk-python | 21.6k | 活跃 | Google 官方 Agent Development Kit |
| agno-agi/agno | 42.3k | 活跃 | 轻量高性能多智能体框架 |
| pydantic/pydantic-ai | 20.1k | 活跃 | 类型安全的 Agent 框架 |
| huggingface/smolagents | 29.4k | 活跃 | 极简"代码即 Agent"，适合学原理 |
| stanfordnlp/dspy | 38.2k | 活跃 | 提示与流程的编译式优化，偏学术 |
| letta-ai/letta | 24.8k | 活跃 | 长记忆 Agent（原 MemGPT） |
| camel-ai/camel | 17.7k | 活跃 | 多智能体研究导向 |
| deepset-ai/haystack | 26.6k | 活跃 | 显式 Pipeline，生产取向 |
| run-llama/llama_index | 52.3k | 活跃 | RAG 抽象最完整 |
| mastra-ai/mastra | 28.2k | 活跃 | TypeScript 生态首选 |
| langchain-ai/langchainjs | 18.2k | 活跃 | LangChain 的 TS 版本 |
| openai/openai-agents-js | 3.8k | 活跃 | Agents SDK 的 TS 版本 |
| FoundationAgents/MetaGPT | 70.5k | **2026-01 起停更** | 多智能体软件公司隐喻，热度已过 |

### ② 低代码 / 可视化平台

| 平台 | Stars | 状态 | 说明 |
| --- | --- | --- | --- |
| n8n-io/n8n | 205.5k | 活跃 | 工作流自动化为主，AI 节点只是其中一块 |
| langgenius/dify | 156.7k | 活跃 | 最流行的开源 LLM 应用平台，自带知识库与工作流 |
| browser-use/browser-use | 115.6k | 活跃 | 浏览器自动化 Agent |
| FlowiseAI/Flowise | 55.5k | **已归档（Archived）** | 停止维护，不要再选 |

### ③ 编程智能体（垂直形态）

| 项目 | Stars | 状态 | 说明 |
| --- | --- | --- | --- |
| OpenHands/OpenHands | 88.7k | 活跃 | 原 All-Hands-AI/OpenHands，最强开源编码 Agent 之一 |

### ④ Java 生态（与你直接相关）

| 框架 | Stars | 最近提交 | 说明 |
| --- | --- | --- | --- |
| langchain4j/langchain4j | 13.1k | 活跃 | Java 侧 RAG 抽象最成熟；ONNX 本地模型模块完善 |
| spring-projects/spring-ai | 9.5k | 活跃 | Spring 官方；Advisor / VectorStore / ETL / MCP 齐全 |
| google/adk-java | 1.7k | 活跃 | Google ADK 的 Java 版，2026 年新项目，值得关注 |
| modelcontextprotocol/java-sdk | 3.7k | 活跃 | MCP 协议的 Java 实现，做工具生态的基石 |

### ⑤ 协议层（不是框架）

MCP（Model Context Protocol）已成为连接模型与外部工具、数据的通用协议，主流框架、IDE、
编码 Agent 几乎都在接入。**它是本附录里唯一"框架无关"的资产**——学它不会被框架洗牌清算。

---

## D.2 三个值得注意的信号

1. **洗牌很快**：AutoGen 曾有 61k star，2026-04 起停止更新；Flowise 55k star 已归档；
   MetaGPT 70k star 停更 8 个月。**star 数不是护城河，流行度也不是**。
2. **大厂在收拢阵地**：微软把 AutoGen 与 Semantic Kernel 合流为 Microsoft Agent Framework；
   OpenAI、Google 都推出了官方 SDK（Google 甚至给了 Java 版 ADK，MCP 也有官方 Java SDK）。
   官方 SDK 的崛起正在挤压第三方框架的生存空间。
3. **平台层流量远大于框架层**：n8n（205k）、Dify（156k）的 star 数远超所有代码级框架，
   说明多数使用者的真实诉求是"开箱即用的应用"，而不是"可编程的框架"。
   如果你要做的是工具而非产品，框架层才是你的战场。

---

## D.3 结论：要不要选框架？

**要选，但只选一个，并且要清楚框架真正给你的是什么。**

框架实际提供的价值只有四样：

1. 工具调用循环（tool calling loop）
2. 状态与记忆管理（state / memory）
3. 流式输出与人工中断（streaming / human-in-the-loop）
4. 可观测与追踪（tracing）

需求里没有这几项，用框架就是纯负担。

具体到本项目（代码 + 文档问答）：

| 需求 | 是否需要 | 结论 |
| --- | --- | --- |
| RAG 检索链路 | ✅ 核心 | **建议手写**：余弦相似度、RRF、引用校验，写了才是自己的 |
| 工具调用（查符号 / 调用图 / 影响面） | ✅ 需要 | 用框架的 `@Tool` 机制 |
| 多智能体协作（CrewAI / AutoGen 式） | ❌ 不需要 | 单 Agent + 固定管线足够，套上就是过度设计 |
| 长时记忆 / 人工中断 | ⚠️ 可选 | P5 之后按需引入（借鉴 LangGraph 思路） |
| 可观测追踪 | ✅ 需要 | 用框架自带 tracing，或自研 trace 表 |

**推荐做法：主框架选 Spring AI，概念上对照 LangChain4j，绝不超过两个。**

- 检索链路（切块、向量检索、RRF、重排、引用校验）→ **自己手写**，这是你的技术壁垒与面试谈资。
- 编排层（工具注册、会话管理、流式、追踪）→ **交给框架**，这部分重造轮子没有收益。

---

## D.4 关于"要不要学 Python 框架"

判断标准只有一个：**你的目标岗位是什么。**

| 目标岗位 | 是否投入 Python 框架 | 理由 |
| --- | --- | --- |
| Java 后端 / 中间件 | ❌ 不必 | Spring AI + MCP + Java 版 RAG 全套足够，且更对味 |
| Java + AI 应用开发 | ⚠️ 选学 1 个 | 推荐 LangGraph 或 OpenAI Agents SDK，理解编排范式即可 |
| AI 应用工程师（纯） | ✅ 必须学 | 该岗位默认 Python 生态，LangGraph / LlamaIndex 是标配 |

即便学，也**优先学概念而非 API**：状态机编排、工具调用循环、human-in-the-loop、多智能体拓扑。
这些概念跨语言通用，学会之后迁移到 Java 只是换写法。

---

## D.5 一条更高性价比的"加入框架"路径：贡献开源

比"使用框架"更有价值的是**给框架提交 PR**，而 Java 侧的缺口正好匹配你的技能：

- `spring-ai` 有 1500+ open issues，`langchain4j` 有 900+，`google/adk-java` 刚起步（1.7k star）。
- 起步方式：文档改进 → 补测试 → 修 bug → 补一个 `EmbeddingStore` 或 `DocumentParser` 实现。
- 收益：读懂工业级框架的代码组织与评审标准；简历上"向 X 框架提交 N 个 PR 并被合并"的含金量，
  远高于"使用了 X 框架"。
- 与本项目的结合点：你为大项目写的 **Java AST 切块器**、**Maven 文档解析器**，
  本身就是通用的 `DocumentParser` / `DocumentTransformer` 实现，容易抽象成可复用的上游贡献。
  这是"做一个项目"与"参与一个生态"之间的最短路径。

---

## D.6 最终取舍原则

**押注概念，不要押注框架。**

这个领域 12 个月内已经洗掉了 AutoGen、Flowise 等多个高星项目。
真正会随时间增值的是：检索与重排、上下文工程、评估方法、工具调用与协议（MCP），
以及把工程做扎实的能力（缓存、增量索引、可观测、评测闭环）。

框架只是这些概念的载体，随时可以换——把原理学扎实，换框架的边际成本几乎为零。
反过来，只会某一个框架的 API，会随框架一起被清算。
