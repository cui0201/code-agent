# CodeAgent 项目方案（定稿）

> **版本**：v2.0 ｜ **定稿日期**：2026-09-21 ｜ **技术栈状态**：已确认（全 Java 主线）
>
> **关联文档**
> - [附录 D：Agent 框架生态与选型建议](appendix-d-agent-frameworks.md)
> - [旧版归档 v1](archive/plan-v1-java-project-agent.md)（含各层框架备选对比，作为参考资料保留）

---

## 0. 本版变更说明

相对 v1 的三处关键调整：

| 项 | v1（旧） | v2（本版，已定稿） |
| --- | --- | --- |
| 编排层 | 主推 Python（FastAPI / LangGraph） | **全 Java**：Spring Boot 3 + Spring AI（对照 LangChain4j） |
| 本地模型 | 主推 Infinity / TEI 容器 | **JVM 内 ONNX**（LangChain4j），需要更强效果时才起容器 |
| 路线 | 按能力分层（P0~P3） | 按**可交付里程碑**分层（P0~P8，每阶段含验收标准与学习点） |

Python 相关方案不再作为主线，仅作为备选记录保留在归档文档中。

---

## 1. 项目定位

**code-agent**：一个管理 Java 工程源码与其生成文档的智能体，支持围绕项目提问，并给出严格依据代码的答案。

**三个成功标准（可量化，同时是简历数据来源）**

1. **准**：引用准确率 > 95%，无依据问题的拒答准确率 > 95%。
2. **快**：单问 P95 延迟 < 2.5 秒（含首个 token）。
3. **省**：单问成本 < 0.01 美元，重复提问命中语义缓存。

**约束**：除 LLM API 外全链路本地部署；业务代码 100% Java；Python 一行不写（基础设施只用 Docker 跑现成镜像）。

**明确的非目标（本版不做，避免范围失控）**

| 不做的事 | 原因 |
| --- | --- |
| 多智能体协作 | 本场景是单 Agent + 固定管线，角色分工属于过度设计 |
| 模型微调（LoRA / SFT） | 成本高、迭代慢，本场景 RAG 收益更直接 |
| 前端框架 | REST + SSE 加一个极简页面足够 |
| 多租户与权限体系 | 单人 / 小团队本地使用 |
| 支持所有语言 | 先把 Java 做到位，再谈扩展 |

---

## 2. 技术栈定稿

| 层 | 选型 | 形态 | 为什么是它 |
| --- | --- | --- | --- |
| 语言 | Java 21 | LTS | 虚拟线程利于并发检索 |
| 构建 | Maven | 多模块 | 与现有项目一致 |
| 应用框架 | Spring Boot 3.3+ | Web 或 WebFlux | 生态完整，简历辨识度高 |
| AI 编排（主） | Spring AI | 1.x GA | 官方维护；Advisor / VectorStore / ETL / MCP 齐全 |
| AI 编排（补） | LangChain4j | 1.x | **提供 JVM 内 ONNX 嵌入与重排模型**（Spring AI 缺这块） |
| LLM 接入 | Spring AI OpenAI 兼容客户端 | `base-url` 可配 | DeepSeek / Qwen / GLM / Kimi / OpenAI 随时切换 |
| 嵌入模型 | LangChain4j ONNX 本地模型 | JVM 内，CPU 可跑 | 零容器、零 GPU、零额外 HTTP 跳 |
| 重排模型 | LangChain4j `OnnxScoringModel` | JVM 内 cross-encoder | 精度提升最明显的一环，无需额外服务 |
| 向量库 | Qdrant | Docker 单容器 | 原生 dense + sparse + RRF，行业标准 |
| 代码切块 | JavaParser | 纯 Java 库 | API 友好，AST 级可拿精确行号 |
| 类型解析 / 调用图 | Spoon | 纯 Java 库 | 真正的类型求解与调用图 |
| 符号图存储 | JGraphT + SQLite | 内存图 + 落盘 | 纯 Java、零运维 |
| 文档解析 | flexmark-java + Apache Tika | 纯 Java | Markdown 与通用格式兜底 |
| 缓存 | Caffeine | 纯 Java | 本地 LRU + 语义缓存 |
| 接口 | REST + SSE，附带 CLI | Spring Web + Shell | CLI 起步最快，SSE 提供流式体验 |
| 文档生成 | javadoc + springdoc 的 Maven 插件 | Maven 生命周期内 | 无额外语言依赖 |
| 评测 | JUnit 5 + YAML golden set | 测试驱动 | 用测试框架做 RAG 回归 |
| 可观测 | Micrometer + Prometheus（后期） | Spring 原生 | 指标先行 |
| 容器 | Docker Compose | 只跑 Qdrant | 基础设施最小化 |

### 2.1 明确排除的技术

| 排除项 | 原因 |
| --- | --- |
| Python 编排（FastAPI / LangGraph） | 技术栈不匹配，Java 侧能力已足够 |
| Infinity / TEI 容器 | JVM 内 ONNX 已够用；需要 bge-m3 级效果时再临时引入 |
| Milvus | 起步阶段部署过重（依赖 etcd + MinIO） |
| Chroma | 生产特性弱，不适合长期项目 |
| CrewAI / AutoGen | 多智能体对本场景是过度设计 |
| Dify / RAGFlow / Flowise | 行级引用与 Java 结构感知弱；Flowise 已归档 |

---

## 3. 系统架构

### 3.1 离线索引链路

```
Maven 工程 ─┬─► JavaParser（AST 切块）──► 类/方法 Chunk（含精确行号）
            ├─► Spoon（类型解析）──► 符号图 ──► JGraphT ──► SQLite 落盘
            └─► mvn javadoc / springdoc ──► flexmark + Tika ──► 文档 Chunk（含锚点）
                                                    │
                    所有 Chunk ──► ONNX 嵌入（JVM 内）──► Qdrant
                                                    │
                    元数据（commit_sha / 行号 / 关系）──► SQLite
```

### 3.2 在线问答链路

```
问题 ──► 语义缓存（Caffeine）──命中──► 直接返回（带旧引用）
           │未命中
           ▼
     查询理解：中文术语 → 标识符映射 + 关键词抽取
           ▼
     混合检索：稠密向量 ∪ BM25/稀疏 ──► 手写 RRF 融合
           ▼
     符号图邻域扩展（调用方 / 被调用方 / 实现类）
           ▼
     ONNX cross-encoder 重排 Top50 → Top8
           ▼
     上下文组装（代码优先 + 文档补充，6~10k token）
           ▼
     LLM 流式生成 ──► 引用白名单校验 ──► SSE 返回
           └─ 精排分数低于阈值 → 直接拒答（不调 LLM）
```

---

## 4. 工程结构

```
code-agent/
├─ pom.xml                        # packaging=pom，统一版本与 BOM
├─ code-agent-core/               # 领域模型：Chunk / Citation / AskRequest / 配置
├─ code-agent-indexer/            # JavaParser 切块、Spoon 建图、文档解析、嵌入、写库
├─ code-agent-retrieval/          # 混合检索、RRF、重排、图扩展、语义缓存
├─ code-agent-agent/              # 问答管线、Prompt、引用校验、拒答、工具循环
├─ code-agent-web/                # Spring Boot 启动模块：REST + SSE + CLI
└─ code-agent-eval/               # golden set + JUnit 5 评测 + 指标报告
```

**父 POM 要点**

```xml
<properties>
  <java.version>21</java.version>
  <spring-ai.version>1.x.x</spring-ai.version>    <!-- 以 Maven Central 最新 GA 为准 -->
  <langchain4j.version>1.x.x</langchain4j.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-bom</artifactId>
      <version>${langchain4j.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

**关键依赖（坐标以 Maven Central 实际版本为准）**

```xml
<!-- 代码分析 -->
com.github.javaparser:javaparser-symbol-solver-core
fr.inria.gforge.spoon:spoon-core
org.jgrapht:jgrapht-core

<!-- 文档解析 -->
com.vladsch.flexmark:flexmark-all
org.apache.tika:tika-core

<!-- 本地模型（JVM 内，无 Python） -->
dev.langchain4j:langchain4j-embeddings-*      <!-- 选具体 ONNX 模型模块 -->

<!-- 向量库 / 缓存 -->
org.springframework.ai:spring-ai-starter-vector-store-qdrant
com.github.ben-manes.caffeine:caffeine

<!-- 服务与接口文档 -->
org.springframework.boot:spring-boot-starter-web
org.springdoc:springdoc-openapi-starter-webmvc-ui

<!-- 测试 -->
org.testcontainers:testcontainers
```

**Spring AI 与 LangChain4j 的桥接**：写一个约 30 行的适配器，把 LangChain4j 的 ONNX 模型实现桥接到
Spring AI 的 `EmbeddingModel` 接口。这个适配层本身就是简历素材——它证明你能读懂两套框架的抽象。

---

## 5. 数据模型

### 5.1 Chunk 元数据（决定过滤与引用能力，必须齐全）

```json
{
  "chunkId": "com.acme.order.OrderService#createOrder(OrderReq):L120-L168",
  "project": "acme-order",
  "ref": "main@8f3a9c1",
  "commitSha": "8f3a9c1f...",
  "fileHash": "blake3:2f9c...",
  "path": "src/main/java/com/acme/order/OrderService.java",
  "language": "java",
  "kind": "method",
  "fqn": "com.acme.order.OrderService#createOrder",
  "packageName": "com.acme.order",
  "modifiers": ["public"],
  "annotations": ["@Transactional", "@PostMapping(\"/orders\")"],
  "startLine": 120,
  "endLine": 168,
  "parentFqn": "com.acme.order.OrderService",
  "calls": ["com.acme.order.OrderRepository#save"],
  "calledBy": ["com.acme.order.OrderController#post"],
  "docRefs": ["docs/api/order-create.md#请求字段"],
  "stale": false
}
```

> `chunkId` 用 `fqn + 签名`，**不要放行号**：行号变化不会导致引用错乱，历史对话仍可解析。

### 5.2 符号图表结构

```sql
CREATE TABLE symbol (
  fqn TEXT PRIMARY KEY, kind TEXT, path TEXT,
  start_line INT, end_line INT, commit_sha TEXT
);

CREATE TABLE edge (
  src TEXT, dst TEXT,
  type TEXT,            -- EXTENDS | IMPLEMENTS | CALLS | INJECTS | ANNOTATES | REFERENCES
  file TEXT, line INT, commit_sha TEXT
);

CREATE INDEX idx_edge_src ON edge(src, type);
CREATE INDEX idx_edge_dst ON edge(dst, type);
```

### 5.3 引用格式（全局统一，写入 system prompt 并在代码中校验）

```
[1] src/main/java/com/acme/order/OrderService.java:120-168
[2] docs/api/order-create.md#响应字段
```

---

## 6. 核心机制

### 6.1 切块策略

不按字符数切 Java 代码，按语义单元切，并保留父子关系：

| 层级 | 单元 | 典型 token | 用途 |
| --- | --- | --- | --- |
| L0 | 文件级摘要（类名 + 职责 + 依赖列表） | 150~300 | 粗排、导航 |
| L1 | 类 / 接口 / 枚举 | 300~1200 | 主体检索单元 |
| L2 | 方法 / 构造函数 | 100~600 | 精确检索 |
| L3 | 方法内的关键语句段（仅超长方法） | 80~200 | 局部细节 |

实现要点：

- 用 JavaParser 遍历 `CompilationUnit` → `ClassOrInterfaceDeclaration` → `MethodDeclaration`，
  用节点的 `Range` 取**原始行号**，切块后不再二次加工文本（否则行号会错位）。
- 每个方法块以「签名 + Javadoc 注释 + 方法体」为一个整体，签名必须在块内（检索时靠它命中）。
- 类块只保留「字段声明 + 方法签名」，方法体由 L2 承担，避免同一个方法体被索引两遍。
- 超长方法按空行与语句块拆 L3，并保留与 L2 的父子指针。

### 6.2 混合检索与 RRF 融合

三路召回：

1. **稠密向量**：语义相似，回答"这段逻辑做什么"。
2. **BM25 / 稀疏向量**：精确匹配类名、方法名、配置键、错误码、接口路径。
3. **符号图扩展**：结构化关系召回，回答"谁调用了它"。

融合用 RRF（Reciprocal Rank Fusion），比加权求和更稳、几乎不需要调参：

```
score(d) = Σ_r 1 / (k + rank_r(d))        # k = 60
```

**查询理解**（低成本高收益，用规则 + 别名表即可，不必调 LLM）：

- 抽取标识符候选：驼峰词、`a.b.C`、`#method`、`/api/path`、错误码；
- 中文别名映射：`订单服务 → OrderService`、`下单接口 → createOrder / POST /orders`；
- 生成 2~3 个查询变体（原句 / 关键词串 / 标识符串），分别检索后并集去重。

> 中文向英文代码提问时，这一步对召回率的提升最明显。

### 6.3 重排与上下文预算

`Top50 → Top8` 由 JVM 内 ONNX cross-encoder 完成。最终上下文遵循**代码优先、文档补充、宁少勿滥**：

| 内容 | 预算 | 数量 |
| --- | --- | --- |
| 命中方法 / 类源码原文 | 60% | 4~6 个 |
| 符号关系摘要（调用方 / 被调用方） | 20% | 1~2 段 |
| 文档章节 | 15% | 2~3 个 |
| 项目概览（模块 / 技术栈） | 5% | 1 段 |

总量控制在 6k~10k token。检索质量到位后，堆更多上下文只会增加延迟和幻觉。

### 6.4 引用校验与拒答（把"严格"做成机制，而不是提示词）

四道闸门，任何一道不过就不给答案：

1. **强制引用格式**：`[n] path:startLine-endLine` 或 `[n] doc#anchor`，写入 system prompt。
2. **引用白名单校验**：解析回答里所有 `[n]`，逐一比对本次检索结果集合；出现集合外引用则
   带反馈重试一次，再失败就降级为"检索结果列表"。
3. **拒答阈值**：精排后 top1 归一化分数低于阈值（建议初值 0.35）→ 直接返回
   "在项目代码与文档中未找到相关依据"，**不调用 LLM**（同时省掉一次延迟与成本）。
4. **行号回填**：代码原文由程序从文件读取后拼装，不让模型复述代码，杜绝"记忆型"篡改。

补充：`temperature=0`；prompt 中明确"代码与文档冲突时以代码为准，并指出文档可能已过期"。

### 6.5 缓存与失效

| 层 | 内容 | 失效条件 |
| --- | --- | --- |
| L1 Caffeine | 完全相同问题的答案 | TTL + 条目上限 |
| L2 语义缓存 | 问题向量相似度 > 0.95 的历史问答 | `commit_sha` 变化即整体失效 |

缓存 key 必须包含 `project + ref + commit_sha`，否则代码改动后会出现脏读。
语义缓存是延迟优化的最大收益点（重复提问通常占 20%~40%）。

### 6.6 增量索引

触发方式（可叠加）：`git hooks/post-commit`、Maven 插件在 `package` 阶段触发、本地文件监听。

算法：

1. `git diff --name-status <last_indexed_sha>..HEAD` 得到变更文件集合；
2. 对每个变更文件重新解析，计算**稳定 chunkId**（`fqn + 签名`）；
3. 做集合差：新增写入、消失删除、变化仅更新 payload 与向量；
4. 反向依赖扩展：`SELECT src FROM edge WHERE dst IN (变更符号)`，把上游标记为待刷新；
5. 记录 `last_indexed_sha`；按文件粒度并行处理。

---

## 7. 文档通道与漂移检测

### 7.1 统一生成流水线（全部在 Maven 生命周期内）

```bash
mvn -q javadoc:javadoc                          # → target/site/apidocs（优先解析 javadoc.xml）
mvn -q -DskipTests package springdoc:generate   # → openapi.json
mvn -q verify                                   # Spring REST Docs → target/generated-snippets
```

产物统一收集到 `build/docs/{javadoc,openapi,adoc,md}` 后入库。

### 7.2 文档切块与锚点

- 按标题层级（H1/H2/H3）切块，生成稳定锚点：`docs/api/order-create.md#响应字段`。
- Javadoc 优先解析 XML（比 HTML 稳定得多）；OpenAPI 按 `path + method` 切一个 chunk。
- 每个文档 chunk 记录 `docRefs`，与代码 chunk 双向关联：正向从 `@see`、全限定名、路径抽取；
  反向从 Markdown 中的 `` `com.acme.OrderService#createOrder` `` 反查符号。

### 7.3 漂移检测

文档 chunk 记录生成它时的源码 `fileHash`。当源码 hash 变化而文档未重新生成：

1. 标记 `stale=true`；
2. 检索时仍可召回但降权；
3. 若答案引用了 stale 文档，在末尾追加提示：

```
注意：docs/api/order-create.md 对应的 OrderService 已在 8f3a9c1 之后变更，请以代码为准。
```

---

## 8. 性能预算

目标环境：单机 CPU（Apple Silicon 或 x86 均可），无独立 GPU。

| 阶段 | 目标耗时 | 说明 |
| --- | --- | --- |
| 语义缓存命中 | < 50 ms | 端到端直接返回 |
| 查询理解 + 本地 ONNX 嵌入 | 30~150 ms | 模型常驻 + 启动预热 |
| 稠密 + 稀疏检索 + RRF | 10~30 ms | Qdrant 本地索引 |
| 符号图扩展 | 5~20 ms | SQLite 索引查询 |
| ONNX 精排 50 → 8 | 80~300 ms | cross-encoder，CPU |
| 上下文组装 | < 10 ms | 本地文件读取 + 模板 |
| LLM 首字（TTFT） | 300~1500 ms | 外部 API，主要变量 |
| **合计** | **P50 ≈ 0.8~1.5 s；P95 < 2.5 s** | 流式输出降低用户感知延迟 |

优化手段（按收益排序）：

1. **语义缓存**——收益最大；
2. **模型常驻 + 启动预热**——避免首请求冷启动（可省 1~3 秒）；
3. **SSE 流式**——首字延迟从"整段生成完"降到 TTFT；
4. **并行检索**——三路召回与图扩展并发执行；
5. **上下文裁剪**——宁可 6 个高相关块，也不要 20 个一般相关块；
6. **单次调用优先**——默认固定管线只调 1 次 LLM；
7. **结构化直答**——版本号、依赖清单、配置项直接查 `pom.xml` / `application.yml` / OpenAPI JSON，
   准确率 100% 且延迟最低，**能用结构化查询解决的不要走 RAG**。

---

## 9. 接口设计

```http
POST /v1/ask
{
  "question": "OrderService.createOrder 做了哪些校验？",
  "project": "acme-order",
  "ref": "main",
  "topK": 8,
  "stream": true,
  "mode": "fast"                 // fast(单次调用) | deep(受限工具循环)
}

→ SSE 事件流：
  data: {"type":"context","chunks":[...]}
  data: {"type":"delta","text":"createOrder 首先校验..."}
  data: {"type":"citations","items":[{"path":"src/.../OrderService.java","start":120,"end":168}]}
  data: {"type":"done","latencyMs":842,"cached":false,"tokens":1234}

GET  /v1/symbol/{fqn}?ref=main          # 按全限定名取原文
GET  /v1/impact/{fqn}?depth=2           # 影响面分析
POST /v1/index/reindex                  # 触发全量/增量索引
GET  /v1/health                         # 各组件状态 + 索引版本
```

**CLI**（起步阶段的主要入口，开发体验最好）：

```bash
code-agent index --project acme-order --mode incremental
code-agent ask "下单接口的请求参数有哪些校验？"
code-agent impact com.acme.order.OrderService#createOrder --depth 2
```

**MCP Server**（进阶，强烈建议）：把 `search_code` / `get_symbol` / `get_callers` 暴露为 MCP 工具，
IDE 与 Codex 等客户端即可直接复用同一套检索能力。MCP 是协议而非框架，不受框架洗牌影响。

---

## 10. 路线图（本方案的核心）

### 10.1 阶段总览

| 里程碑 | 包含阶段 | 含义 | 对外可展示的东西 |
| --- | --- | --- | --- |
| **M1 最小闭环** | P0 | 能跑通，但很笨 | 一段 CLI 录屏 |
| **M2 可用版本** | P1~P3 | 检索准、有引用、会拒答 | README 第一版 |
| **M3 完整版本** | P4~P6 | 代码 + 文档双通道、影响面分析、快 | 架构图 + 指标表 |
| **M4 可展示版本** | P7~P8 | 有评测、有 PR、有文章 | GitHub 项目 + 简历条目 |

| 阶段 | 主题 | 预计工期 | 依赖 | 完成后打 tag |
| --- | --- | --- | --- | --- |
| P0 | 环境与最小闭环 | 3~5 天 | — | `v0.0.1` |
| P1 | 结构化切块 + 手写向量检索 | 1 周 | P0 | `v0.1.0` |
| P2 | Qdrant + 混合检索 + 手写 RRF | 1 周 | P1 | `v0.2.0` |
| P3 | 重排 + 引用校验 + 拒答 | 1 周 | P2 | `v0.3.0` |
| P4 | 文档通道与漂移检测 | 1 周 | P1（可与 P3 并行） | `v0.4.0` |
| P5 | 符号图与影响面分析 | 1~2 周 | P1 | `v0.5.0` |
| P6 | 缓存、增量索引、流式、可观测 | 1~2 周 | P3 | `v0.6.0` |
| P7 | 评测闭环正式化 | 1 周 | P1~P6 | `v0.9.0` |
| P8 | 沉淀与包装（含开源贡献） | 1~2 周 | P3 之后 | `v1.0.0` |

按每周 8~10 小时业余投入估算，全程 **10~14 周**。

---

### 10.2 P0 环境与最小闭环（3~5 天）

**目标**：整条链路先跑通一遍，哪怕很笨。

- **交付物**
  - 父 POM + 先建 `code-agent-core` 与 `code-agent-web` 两个模块（其余按需再拆）；
  - LLM 客户端配置（OpenAI 兼容，`base-url` 与 API Key 走环境变量，不入库）；
  - 读取单个 `.java` 文件 → 简易切块 → 内存向量存储 → 问答返回。
- **验收标准**：`code-agent ask "OrderService 是做什么的？"` 能返回答案，且答案里带文件路径。
- **学习点**：token 与上下文窗口、temperature、一次 LLM 调用的完整生命周期。
- **注意**：先把依赖版本锁定，不要边搭边升级。

### 10.3 P1 结构化切块 + 手写向量检索（1 周）

**目标**：把"切块"和"向量检索"这两件事亲手做一遍。

- **交付物**
  - `ChunkSchema` 定稿（第 5.1 节全部字段）；
  - `JavaAstChunker`：JavaParser 遍历 AST，产出类 / 方法级 chunk，带精确行号；
  - **手写**余弦相似度 + 内存 TopK（这一阶段刻意不用框架的检索能力）；
  - `code-agent-eval` 起步：10 条基准问题。
- **验收标准**：输出切块统计表（类数 / 方法数 / 平均 token 数）+ 10 条问题的命中率。
- **学习点**：embedding 原理、余弦相似度与归一化、切块粒度对召回的直接影响。
- **关键**：手写检索是理解 RAG 的必经之路，跳过这一步后面全是调包。

### 10.4 P2 Qdrant + 混合检索 + 手写 RRF（1 周）

- **交付物**：Qdrant 接入（Docker）；稠密 + BM25/稀疏双路召回；**手写 RRF**；
  中文术语 → 标识符别名表；查询理解（多查询变体）。
- **验收标准**：Recall@10 对比表（P1 单路 vs P2 混合），混合检索应有明显提升。
- **学习点**：BM25 原理、HNSW 参数（`m` / `ef_construct` / `ef_search`）的取舍、
  RRF 为什么比加权求和稳定。

### 10.5 P3 重排 + 引用校验 + 拒答（1 周）

- **交付物**：LangChain4j ONNX `ScoringModel` 接入 + 桥接 Spring AI 的适配器；
  引用白名单校验；行号回填；拒答阈值。
- **验收标准**：引用准确率 > 90%、拒答准确率 > 90%（MVP 标准，最终目标 95%）。
- **学习点**：bi-encoder 与 cross-encoder 的区别与代价、幻觉的成因与抑制手段、上下文位置偏差。
- **里程碑**：**M2 可用版本** —— 打 tag，写 README 第一版。

### 10.6 P4 文档通道与漂移检测（1 周）

- **交付物**：`mvn javadoc:javadoc` + springdoc 生成文档；flexmark / Tika 解析入库；
  标题锚点；代码与文档双向关联；`stale` 漂移检测。
- **验收标准**：文档类问题的召回率对比；人为改代码后 stale 标记正确生效。
- **学习点**：多源 RAG、多通道结果融合与权重。

### 10.7 P5 符号图与影响面分析（1~2 周）

- **交付物**：Spoon 建图（继承 / 实现 / 调用 / 注入）；JGraphT 存储 + SQLite 落盘；
  `getCallers` / `getCallees`；`GET /v1/impact/{fqn}?depth=n`。
- **验收标准**：影响面分析结果人工核对通过率 > 90%。
- **学习点**：结构化检索与语义检索的互补关系、图遍历与深度控制。
- **里程碑**：**v0.5.0** —— 这是最能打动面试官的功能。

### 10.8 P6 缓存、增量索引、流式与可观测（1~2 周）

- **交付物**：Caffeine L1 + 语义缓存（`commit_sha` 失效）；基于 `git diff` 的增量索引；
  SSE 流式接口；Micrometer 指标；Docker Compose 一键启动。
- **验收标准**：P95 延迟 < 2.5 秒；缓存命中率 > 25%；单文件增量索引 < 1 秒。
- **学习点**：缓存一致性、增量更新策略、可观测性。

### 10.9 P7 评测闭环正式化（1 周）

- **交付物**：`code-agent-eval` 完整化；50~200 条 YAML golden set；
  JUnit 5 自动计算并输出指标报告；LLM-as-judge 打分脚本。
- **验收标准**：`mvn verify` 一键跑出全部指标，且结果可复现（绑定 `commit_sha`）。
- **学习点**：RAG 评估指标体系、评测集设计、用评测驱动优化而不是靠感觉。
- **里程碑**：**M4 可展示版本**。

### 10.10 P8 沉淀与包装（1~2 周）

- **交付物**：MCP Server；README（架构图 + benchmark 表 + 10 分钟上手指南）；
  2~3 篇技术博文；**向上游框架提交至少 1 个 PR**。
- **验收标准**：他人按 README 能在 10 分钟内跑起来；至少 1 个 PR 被合并。
- **上游贡献的切入点**：你为大项目写的 AST 切块器与 Maven 文档解析器，
  本身就是通用的 `DocumentParser` / `DocumentTransformer` 实现，稍作抽象即可提交给
  LangChain4j 或 Spring AI（两者分别有 900+ 和 1500+ 个 open issue，Java 侧缺口明显）。

---

### 10.11 依赖关系与并行建议

```
P0 ──► P1 ──► P2 ──► P3 ──┬──► P6 ──┐
                   │      │        ├──► P7 ──► P8
                   ├──► P4 ┘        │
                   └──► P5 ─────────┘
```

- **P0 → P1 → P2 → P3 严格串行**，这是主干，不能跳。
- **P4 与 P3 可并行**（文档通道独立于重排）。
- **P5 只依赖 P1**（复用 AST 与行号），可以在 P2/P3 之前先做原型。
- **P6 依赖 P3**（要先有稳定的问答链路才有优化的对象）。
- **评测从 P1 起持续积累**（每阶段补充 case），P7 只做正式化与报告化。

### 10.12 每周固定节奏（建议）

1. **周初**：把本周目标拆成 2~3 个"能用命令验证"的小目标。
2. **周中**：只写代码，不做重构，不引入新依赖。
3. **周末**：跑一次评测集，记录指标变化，提交并打 tag。

> 时间紧张时的最小路径：**P0 → P1 → P2 → P3** 就是可用 MVP（约 3~4 周），
> P5（影响面分析）与 P7（评测闭环）是性价比最高的两个加分项，优先补这两个。

---

## 11. 评测体系

### 11.1 Golden Set 格式

```yaml
- id: q001
  question: "OrderService.createOrder 做了哪些校验？"
  expected_evidence:
    - path: src/main/java/com/acme/order/OrderService.java
      start_line: 120
      end_line: 168
  must_cite: true

- id: q002
  question: "项目使用的 Spring Boot 版本是多少？"
  expected_answer_contains: ["3.3"]

- id: q003
  question: "这个项目有没有用过 Kubernetes？"
  expect_refusal: true          # 超范围问题，应拒答

- id: q004
  question: "改动 OrderService.createOrder 会影响哪些对外接口？"
  expected_evidence:
    - path: src/main/java/com/acme/order/OrderController.java
  must_cite: true
```

### 11.2 指标与目标

| 指标 | 计算方式 | 目标 |
| --- | --- | --- |
| Recall@10 | 正确证据 chunk 是否在召回集内 | > 90% |
| 引用准确率 | 答案中的引用是否真实存在且支撑结论 | > 95% |
| 拒答准确率 | 超范围问题的拒答比例 | > 95% |
| 误拒率 | 可回答却被拒答的比例 | < 2% |
| P50 / P95 延迟 | 端到端（含首 token） | < 1.5 s / < 2.5 s |
| 缓存命中率 | 语义缓存命中占比 | > 25% |
| 单问成本 | (输入 + 输出 token) × 单价 | < 0.01 美元 |

> 这些数字是简历里**唯一可信**的量化来源，必须自己跑出来，不要编。

### 11.3 执行方式

```bash
mvn -pl code-agent-eval verify     # 跑全量评测并输出报告
```

报告输出到 `code-agent-eval/target/rag-report.md`，包含：逐题命中情况、聚合指标、
本次运行的 `commit_sha` 与模型版本。每次调整（切块大小、模型、阈值）都必须跑一遍回归。

---

## 12. 已知坑（Java 侧）

| 坑 | 现象 | 对策 |
| --- | --- | --- |
| ONNX 模型加载慢 | 首次请求卡 2~5 秒 | 启动时预热一次推理；模型对象做成单例 |
| 向量未归一化 | 相似度排序错乱 | 手动做 L2 归一化，或确认模型输出已归一化 |
| 行号错位 | 引用指向错误行 | 用 JavaParser 的 `Range` 取原始行号，切块后不再加工文本 |
| 中文提问检索不到 | 召回为空 | 查询改写 + 中文术语 → 标识符别名表 |
| Spoon 构建失败 | 依赖下载不全或编译不过 | 只对能编译的模块开启类型解析，其余降级到 JavaParser 语法级 |
| SSE 中文乱码 | 前端显示异常 | `produces = TEXT_EVENT_STREAM_VALUE` + UTF-8 |
| 上下文超长 | LLM 报 token 超限 | 组装前估算 token 并按预算裁剪 |
| 缓存脏读 | 代码改了还在答旧答案 | 缓存 key 带 `commit_sha`，新提交自动失效 |
| 大项目索引 OOM | 内存爆掉 | 分批处理 + 流式写入，不要把整个项目的 AST 留在内存 |
| 依赖版本冲突 | Spring AI 与 LangChain4j 的传递依赖打架 | 统一用 BOM 管版本；冲突时 `mvn dependency:tree` 定位并排除 |
| LLM 侧限流/超时 | 偶发失败 | 客户端加重试与超时；超时降级为返回检索原文摘要 |

---

## 13. 学习与简历映射

### 13.1 需要在每个阶段同步补的 AI 概念

| 阶段 | 概念 | 掌握程度要求 |
| --- | --- | --- |
| P0 | Token 与上下文窗口、temperature | 能解释为什么上下文必须裁剪 |
| P1 | Embedding、余弦相似度、归一化 | 能手写余弦相似度并解释归一化的作用 |
| P1 | 切块策略（定长 vs 语义 vs 结构） | 能说清不同切块对召回的影响 |
| P2 | BM25、混合检索、RRF | 能手写 RRF，解释为什么比加权求和稳 |
| P2 | 向量索引 HNSW | 知道 `m`、`ef_construct`、`ef_search` 的取舍 |
| P3 | bi-encoder vs cross-encoder | 能解释重排为什么有效、代价是什么 |
| P3 | 幻觉成因与抑制、位置偏差 | 能讲清引用校验与拒答阈值的设计动机 |
| P5 | 结构化检索与语义检索互补 | 能说明符号图解决了哪类语义检索解决不了的问题 |
| P6 | 缓存一致性、增量更新 | 能解释 `commit_sha` 失效策略 |
| P7 | RAG 评估指标体系 | 能自己设计评测集并跑出指标 |
| P8 | Agent、工具调用、MCP | 能解释 ReAct 与固定管线的取舍 |
| 选学 | Transformer 与注意力机制 | 面试被问到时能讲清 QKV 与自注意力 |
| 选学 | 微调 vs RAG、LoRA | 能说清什么场景该微调、本场景为什么不选 |

推荐资料：吴恩达 DeepLearning.AI 的 RAG 短课、Hugging Face NLP Course，
以及三篇值得精读的论文——DPR（稠密检索）、ColBERT（后期交互）、Lost in the Middle（位置偏差）。

### 13.2 简历怎么写

**一句话项目描述（放在项目标题下第一行）**

> 基于 Spring Boot 3 + Spring AI 构建的 Java 项目智能体：对 Maven 工程的源码与生成文档做结构化索引，
> 通过「AST 切块 + 混合检索 + cross-encoder 重排 + 引用校验」实现严格依据代码库的问答，
> 支持调用影响面分析与增量索引；除 LLM API 外全链路本地部署。

**技术亮点（每条都能撑起一个面试问题）**

1. JavaParser 做 AST 级切块（类 / 方法粒度），保留精确行号元数据，取代朴素定长切块。
2. 稠密向量 + BM25 混合召回，手写 RRF 融合，Recall@10 从 x% 提升到 y%。
3. 引入 JVM 内 ONNX cross-encoder 重排（Top50 → Top8），无需额外推理服务。
4. 用 Spoon 构建符号图（继承 / 实现 / 调用 / 注入），支撑"改动影响哪些接口"类结构化查询。
5. 引用白名单校验 + 重排分数拒答阈值，将无依据回答率降到 x%。
6. Caffeine 语义缓存 + `commit_sha` 失效策略，P95 延迟从 x ms 降到 y ms。
7. 基于 git diff 的增量索引，单文件变更重索引 < 1 秒。
8. JUnit 5 驱动的 RAG 回归评测集（n 条 golden Q/A），每次改动跑全量指标。

**三条纪律**

- 所有百分比与延迟必须是自己跑出来的，面试官一定会追问实验设置。
- 用 STAR 讲：背景（代码库大、新人上手慢、文档滞后）→ 任务 → 行动 → 结果（数字）。
- 不要写成"用框架做了个问答机器人"。**差异化在代码+文档双通道、符号图、可校验引用、评测闭环**。

### 13.3 面试高频问题（提前准备）

- Embedding 维度是多少？为什么需要归一化？余弦相似度和点积何时等价？
- HNSW 的 `m` 和 `ef_search` 分别影响什么？召回率与延迟怎么权衡？
- chunk size 怎么定的？太大和太小分别有什么问题？overlap 有什么用？
- 为什么要混合检索？BM25 和向量检索各自擅长什么？RRF 比加权求和好在哪？
- cross-encoder 为什么比 bi-encoder 准？为什么不能直接用它做全量检索？
- 你怎么防止模型编造？引用校验具体怎么实现？拒答阈值怎么定的？
- 代码更新了，索引和缓存怎么办？
- 你怎么评估这套系统好不好？Recall@10 怎么测？faithfulness 怎么测？
- 一次问答大概花多少 token、多少钱？怎么降？
- 为什么选 RAG 而不是微调？什么情况下你会选微调？

---

## 附录 A：选型红线与备选方案

### A.1 五条硬性红线（任何改动都不能破坏）

1. **引用能落到行号**：切块必须保留 `path + startLine + endLine`，且能原样回传。
2. **切块可控**：必须能按 Java 的类 / 方法边界切，不能只按字符数切。
3. **支持元数据过滤**：检索时可按 `project / ref / kind / package / language` 过滤。
4. **模型可替换**：嵌入、重排、LLM 都要能换（统一走 OpenAI 兼容接口或统一接口抽象）。
5. **可离线**：所有组件有本地部署形态，且无外部遥测强依赖。

### A.2 备选方案（出现阻塞时的退路）

| 场景 | 退路 | 代价 |
| --- | --- | --- |
| 不想装 Docker | 换全内存向量存储或 Lacuna/Lucene 系本地索引 | 规模化后需要重构存储层 |
| JVM 内 ONNX 效果不达预期 | 起 Infinity / TEI 容器提供 bge-m3 | 多一个容器，多一跳 HTTP |
| 单机资源紧张 | 先用云端 embedding API（成本极低） | 违背"本地化"约束，需评估合规 |
| 图查询需求变复杂 | 用 Neo4j 容器替换 JGraphT + SQLite | 多一个容器，多一套查询语言 |
| 想做多步推理问答 | 引入 LangGraph 风格的受限工具循环 | 延迟与成本上升，需设预算上限 |

各层框架的完整备选对比（LangChain / LlamaIndex / Haystack / Milvus / pgvector 等）
见归档文档 [docs/archive/plan-v1-java-project-agent.md](archive/plan-v1-java-project-agent.md) 的附录 A。

---

## 附录 B：关键约定

以下约定一旦确定，全项目必须一致，改动需同步更新本文件：

| 约定 | 内容 |
| --- | --- |
| Chunk ID | `fqn + 签名`，**不含行号**，保证跨版本稳定 |
| 引用格式 | `[n] path:startLine-endLine`（代码）/ `[n] doc#anchor`（文档） |
| 版本绑定 | 所有 chunk、缓存、评测报告都绑定 `commit_sha` |
| 上下文预算 | 6k~10k token，代码 60% / 关系 20% / 文档 15% / 概览 5% |
| LLM 参数 | `temperature=0`，输出上限 1200 token |
| 拒答阈值 | 精排 top1 归一化分 < 0.35 直接拒答（上线后用评测集校准） |
| 缓存 key | `project + ref + commit_sha + 问题归一化文本` |
| 命名 | 模块前缀统一 `code-agent-`，包名 `com.<org>.codeagent.*` |

---

## 附录 C：一句话总结

**Maven 多模块 + Spring Boot 3 + Spring AI + LangChain4j 的 JVM 内 ONNX 模型 + Qdrant +
JavaParser / Spoon / JGraphT + Caffeine**，业务代码全 Java，基础设施只用一个 Docker 容器；
按 P0 → P8 推进，主干（P0~P3）先做出可用 MVP，再用 P5 的影响面分析和 P7 的评测闭环拉开差异，
最后用 P8 的 MCP 与上游 PR 完成"从做一个项目到进入一个生态"的跨越。
