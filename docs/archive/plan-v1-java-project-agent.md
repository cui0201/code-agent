# Java 项目智能体落地方案（v1 归档）

> **归档说明**：本文件为 v1（2026-09-21），其中的编排层推荐为 Python 主线，已被
> [CodeAgent 项目方案（定稿）](../code-agent-plan.md) v2.0 取代。
> 保留原因：附录 A 的各层框架备选对比（LangChain / LlamaIndex / Haystack / Milvus / pgvector /
> 模型服务 / 文档工具链）仍具参考价值，v2 中的"附录 A 备选方案"指向本文件。

> 目标：在本地部署一套「项目智能体」，管理 Java 工程源码与其生成的文档（Javadoc / OpenAPI / Markdown / ADR 等），
> 支持基于自然语言提问并回答项目相关问题。答案必须严格来自项目代码与已生成文档，且响应尽可能快。
> 除最终调用的大模型 API 外，所有组件（解析、嵌入、重排、索引、检索、服务、缓存）均在本地运行。

---

## 1. 需求拆解与设计约束

| 维度 | 要求 | 对应的设计决策 |
| --- | --- | --- |
| 数据源 | Java 源码 + 生成文档 | 离线双通道索引：代码通道（AST + 符号图）+ 文档通道（章节锚点） |
| 准确性 | 严格依据代码与文档 | 强制引用（文件 + 行号 / 文档锚点）；无依据则拒答；文档与代码冲突时以代码为准并标记 stale |
| 延迟 | 尽量低 | 语义缓存 → 混合检索 → 精排 → 上下文裁剪，全链路本地、单次 LLM 调用为主 |
| 部署 | 除 LLM 外全本地 | Docker Compose 单机编排；外网仅出向 LLM API 一个出口 |
| 可维护 | 代码持续变更 | 增量索引（git diff / 文件哈希），秒级更新 |

### 三条不可妥协的原则

1. **代码是第一事实来源**。文档只作为补充与解释；两者冲突时以代码为准，并在答案中提示"文档可能过期"。
2. **无引用不成答案**。任何一个结论都必须能回指到 `文件:行号` 或文档锚点，且引用必须来自本次检索结果集合。
3. **检索质量优先于模型能力**。低延迟和低幻觉的关键在检索层，不在提示词。

---

## 2. 总体架构

### 2.1 分层视图

```
┌──────────────────────────────────────────────────────────────────────┐
│  接入层   CLI / REST(SSE) / IDE 插件(可选) / MCP Server(可选)          │
├──────────────────────────────────────────────────────────────────────┤
│  编排层   查询理解 → 检索 → 重排 → 上下文组装 → 引用校验 → 拒答判定      │
├──────────────────────────────────────────────────────────────────────┤
│  检索层   Qdrant(稠密+稀疏, RRF 融合) + 符号图(Neo4j/SQLite) + 语义缓存  │
├──────────────────────────────────────────────────────────────────────┤
│  模型层   bge-m3(嵌入) + bge-reranker-v2-m3(重排)  ← 全部本地            │
├──────────────────────────────────────────────────────────────────────┤
│  数据层   代码图谱库(SQLite/Postgres) + 文档库 + 索引元数据(含 commit)   │
├──────────────────────────────────────────────────────────────────────┤
│  出口层   LiteLLM 本地网关 ──► 外部 LLM API（唯一外呼，按 token 计费）    │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 两条链路

**离线索引链路（构建/增量）**

```
git repo ─┬─► Tree-sitter 切块 ──► 符号抽取 ──► Spoon 静态分析 ──► 符号图
          │                                        │
          └─► 文档生成(mvn javadoc / springdoc / REST Docs) ──► Tika 解析 ──► 章节切块
                                                   │
                          所有 chunk ──► 嵌入(bge-m3) ──► Qdrant 写入 + 元数据落库
```

**在线问答链路（目标 P50 < 1.2s）**

```
问题 ──► 语义缓存查询 ──命中──► 直接返回(含旧引用)
          │未命中
          ▼
     查询改写(抽取类名/接口/字段候选) ──► 稠密+稀疏检索 ──► RRF 融合
          │
          ▼
     符号图邻域扩展(父类/子类/调用方/被调用方)
          │
          ▼
     bge-reranker 精排 Top50 → Top8
          │
          ▼
     上下文组装(代码优先 + 文档补充, 6~10k tokens) ──► LLM(流式) ──► 引用校验 ──► SSE 返回
```

---

## 3. 技术选型（全部可本地部署）

| 能力 | 首选 | 备选 | 说明 |
| --- | --- | --- | --- |
| Java 切块 | Tree-sitter-java | JavaParser | 快、容错，不要求能编译 |
| 类型/调用图 | Spoon | Eclipse JDT | 有类型解析、调用图、字段引用，需能编译 |
| 文档生成 | Maven 插件链（javadoc / springdoc-openapi / Spring REST Docs） | 手写 Markdown | 统一输出到 `build/docs/` |
| 文档解析 | Apache Tika + flexmark | asciidoctorj | 保留标题层级，生成稳定锚点 |
| 嵌入模型 | `BAAI/bge-m3`（中英混合，8192 上下文） | `jina-embeddings-v2-base-code`（代码更强）、`Qwen3-Embedding-0.6B` | 起步用 bge-m3 单模型最省资源 |
| 重排模型 | `BAAI/bge-reranker-v2-m3` | `Qwen3-Reranker-0.6B` | 精排是精度提升最明显的一环 |
| 向量库 | Qdrant | Milvus / pgvector | 单二进制、支持稠密+稀疏+RRF 融合、payload 过滤 |
| 模型服务 | Infinity | TEI（HF Text Embeddings Inference） | Infinity 同时支持 dense+sparse 与 rerank；TEI 更轻但主要输出 dense |
| 符号图 | SQLite（起步）→ Neo4j（图查询复杂时） | Postgres 递归 CTE | 起步不必上 Neo4j |
| 缓存 | 内存 LRU + Redis（语义缓存） | — | 语义缓存是降低延迟最有效的手段 |
| 编排/服务 | FastAPI（Python）自研固定管线 | LangGraph / LangChain4j | 固定管线比自由 ReAct 更可控、更低延迟 |
| LLM 出口 | LiteLLM 本地代理 | 直连 SDK | 统一路由、重试、限流、计费统计、多模型切换 |

> 若团队要求"全 Java 栈"，可把编排层换成 Spring Boot + LangChain4j；但嵌入/重排服务仍建议独立容器（HTTP 调用），
> 因为 Python 侧的模型服务化方案成熟度更高。

---

## 4. 代码侧：解析、切块与符号图

### 4.1 切块粒度（关键）

不要按固定字符数切 Java 代码。按语义单元切，并保留父子关系：

| 层级 | 单元 | 典型 token | 用途 |
| --- | --- | --- | --- |
| L0 | 文件级摘要（类名 + 职责 + 依赖列表） | 150~300 | 粗排、导航 |
| L1 | 类 / 接口 / 枚举 | 300~1200 | 主体检索单元 |
| L2 | 方法 / 构造函数 | 100~600 | 精确检索，回答"某方法怎么实现的" |
| L3 | 代码块 / 关键语句段（仅在方法过长时） | 80~200 | 局部细节 |

每个 chunk 的元数据字段（务必齐全，决定了过滤和引用能力）：

```json
{
  "chunk_id": "com.acme.order.OrderService#createOrder(OrderReq):L120-L168",
  "project": "acme-order",
  "ref": "main@8f3a9c1",
  "commit_sha": "8f3a9c1f...",
  "file_hash": "blake3:2f9c...",
  "path": "src/main/java/com/acme/order/OrderService.java",
  "language": "java",
  "kind": "method",
  "fqn": "com.acme.order.OrderService#createOrder",
  "package": "com.acme.order",
  "modifiers": ["public"],
  "annotations": ["@Transactional", "@PostMapping(\"/orders\")"],
  "start_line": 120,
  "end_line": 168,
  "parent_fqn": "com.acme.order.OrderService",
  "calls": ["com.acme.order.OrderRepository#save", "com.acme.event.OrderEventPublisher#publish"],
  "called_by": ["com.acme.order.OrderController#post"],
  "doc_refs": ["docs/api/order-create.md#请求字段"]
}
```

### 4.2 符号图（Spoon）

用 Spoon 一次性构建，落库为边表，供在线阶段做邻域扩展：

```sql
CREATE TABLE symbol (
  fqn TEXT PRIMARY KEY, kind TEXT, path TEXT, start_line INT, end_line INT, commit_sha TEXT
);
CREATE TABLE edge (
  src TEXT, dst TEXT, type TEXT, -- EXTENDS | IMPLEMENTS | CALLS | INJECTS | ANNOTATES | REFERENCES
  file TEXT, line INT, commit_sha TEXT
);
CREATE INDEX idx_edge_src ON edge(src, type);
CREATE INDEX idx_edge_dst ON edge(dst, type);
```

在线检索命中某个方法后，用一跳邻域扩展就能回答"谁调用了它"、"它依赖了哪些 Spring Bean"这类问题，
且能显著提升"影响面分析"类问题的准确率。

### 4.3 增量索引

触发方式（三选一或叠加）：

- 本地开发：文件监听（`watchman` / Java `WatchService`）
- 提交时：`.git/hooks/post-commit` 调用 `reindex --changed`
- CI：流水线在构建成功后调用索引服务

增量算法：

1. `git diff --name-status <last_indexed_sha>..HEAD` 得到变更文件集合。
2. 对每个变更文件重新解析，计算新 chunk 的稳定 ID（`fqn + 签名`，不要在 ID 里放行号）。
3. 与新结果做集合差：新增写入、消失删除、内容变化仅更新 payload 与向量。
4. 反向依赖扩展：`SELECT src FROM edge WHERE dst IN (变更符号)`，把这些符号标记为待刷新（其摘要可能失效）。
5. 记录 `last_indexed_sha`。整个流程按文件粒度并行。

> 稳定 ID 的价值：方法行号变了不会导致引用错乱，历史对话中的引用仍可解析（用 commit_sha 定位旧版本）。

---

## 5. 文档侧：生成、解析与漂移检测

### 5.1 统一生成流水线

```bash
mvn -q javadoc:javadoc                    # → target/site/apidocs + javadoc XML
mvn -q -DskipTests package spring-boot:run -Dspring-boot.run.arguments=--springdoc.api-docs.path=/tmp/openapi.json
mvn -q test                               # Spring REST Docs → target/generated-snippets
```

把所有产物收集到统一目录，例如 `build/docs/{javadoc,openapi,adoc,md}`，再统一入库。

### 5.2 文档切块与锚点

- 按标题层级切（H1/H2/H3），生成锚点：`docs/api/order-create.md#响应字段`。
- HTML（Javadoc）优先解析其 `javadoc.xml`，比解析 HTML 稳定得多。
- OpenAPI 按 `path + method` 切一个 chunk，把 request/response schema 展平成文本。
- 每个文档 chunk 记录 `doc_refs`，与代码 chunk 做双向关联：
  - 从 Javadoc `@see` / 全限定名 / 路径中抽取关联；
  - 从 Markdown 中的 `` `com.acme.OrderService#createOrder` `` 反查符号。

### 5.3 文档漂移检测（对应"严格按代码"）

文档 chunk 存储生成它的源码 `file_hash`。当源码 hash 变化而文档未重新生成时：

1. 把该文档 chunk 标记 `stale=true`；
2. 检索时可召回但降权；
3. 若最终答案引用了 stale 文档，在答案末尾追加提示：
   `注意：docs/api/order-create.md 对应的 OrderController 已在 8f3a9c1 之后变更，请以代码为准。`

---

## 6. 检索策略（精度与延迟的核心）

### 6.1 混合检索

三路召回 + 融合：

1. **稠密向量**：语义相似，处理"这段逻辑是干什么的"。
2. **稀疏/BM25**：精确匹配类名、方法名、配置键、错误码。
3. **符号图扩展**：结构化关系召回，处理"谁调用了它"。

融合用 RRF（Reciprocal Rank Fusion），比加权求和更稳、几乎不需要调参：

```
score(d) = Σ_r 1 / (k + rank_r(d))      # k = 60
```

### 6.2 查询理解（低成本、高收益）

在检索前做一次轻量改写（规则 + 一个小模型或纯正则即可，不必调用大模型）：

- 抽取可能的标识符：驼峰词、`xxx.yyy.Zzz`、`#method`、`/api/path`、错误码；
- 中文别名映射：`订单服务 → OrderService`、`下单接口 → createOrder / POST /orders`；
- 生成 2~3 个查询变体（原句、关键词串、标识符串），分别检索后并集去重。

这一步对中文提问检索 Java 代码的提升非常明显，成本却接近零。

### 6.3 上下文预算分配

最终送给 LLM 的上下文遵循 **代码优先、文档补充、宁少勿滥**：

| 内容 | 预算占比 | 数量 |
| --- | --- | --- |
| 命中方法/类源码原文 | 60% | 4~6 个 |
| 符号关系摘要（调用方/被调用方） | 20% | 1~2 段 |
| 文档章节 | 15% | 2~3 个 |
| 项目概览（模块/技术栈） | 5% | 1 段 |

总 token 控制在 6k~10k。经验上，检索质量到位后，堆更多上下文只会增加延迟和幻觉。

### 6.4 分层检索（长类场景的加速技巧）

离线为每个类/方法生成一段"符号摘要"（签名 + 注释 + 调用关系，约 100 token）。
检索阶段先用**摘要向量**做粗排（快、覆盖广），命中后再按 ID 拉取原文代码。

好处：索引体积小、嵌入快、粗排便宜；且摘要天然适合"这个类是干什么的"这类问题。

---

## 7. 防幻觉：把"严格"做成机制而不是提示词

四道闸门，任何一道不过就不给答案：

1. **强制引用格式**（写进 system prompt，并在代码里校验）

   ```
   [1] src/main/java/com/acme/order/OrderService.java:120-168
   [2] docs/api/order-create.md#响应字段
   ```

2. **引用白名单校验**：解析回答中的所有 `[n]`，逐一比对本次检索结果集合。
   出现集合外引用 → 丢弃重试一次（把违规引用作为反馈），再失败则降级为"检索结果列表"。

3. **拒答阈值**：精排后 top1 分数低于阈值（如 bge-reranker 归一化分 < 0.35）→
   直接返回"在项目代码与文档中未找到相关依据"，不调用大模型（同时省掉一次延迟）。

4. **行号回填校验**：引用中的行号区间必须与 chunk 元数据一致；代码原文在拼装时由程序从文件读取，
   不让模型复述代码，避免"记忆型"篡改。

补充：`temperature=0`，并在 prompt 中明确"若代码与文档冲突，以代码为准，并指出文档已过期"。

---

## 8. 低延迟设计

### 8.1 延迟预算（单机 GPU：RTX 4090 / Apple M2 Max 及以上）

| 阶段 | 目标耗时 | 说明 |
| --- | --- | --- |
| 语义缓存命中 | < 50 ms | 端到端直接返回 |
| 查询改写 + 嵌入 | 15~40 ms (GPU) / 80~200 ms (CPU) | bge-m3，可批量 |
| 稠密 + 稀疏检索 + RRF | 10~30 ms | Qdrant 本地内存索引 |
| 符号图扩展 | 5~20 ms | SQLite 索引查询 |
| 精排 50 → 8 | 40~120 ms | bge-reranker-v2-m3 |
| 上下文组装 | < 10 ms | 本地文件读取 + 模板 |
| LLM 首字 (TTFT) | 300~1500 ms | 外部 API，主要变量 |
| **总计** | **P50 ≈ 0.6~1.2 s，P95 < 2.5 s** | 流式输出后用户感知的首字更低 |

### 8.2 具体手段清单

1. **语义缓存（收益最大）**：用问题向量在 Redis（或 Qdrant 独立 collection）里找相似历史问题，
   余弦相似度 > 0.95 且 `commit_sha` 未变 → 直接返回缓存答案。重复提问占比通常 20%~40%。
2. **流式返回（SSE）**：首字延迟从"整段生成完"降到 TTFT。
3. **并行化**：三路召回、符号扩展、文档检索并发执行；嵌入请求做批量。
4. **模型常驻 + 预热**：嵌入/重排模型进程常驻，启动时跑一次热身推理，避免首请求冷启动（可省 1~3s）。
5. **上下文裁剪**：宁可 6 个高相关 chunk，也不要 20 个一般相关 chunk。
6. **单次调用优先**：默认固定管线只调用 1 次大模型；只有"需要多步推理"的问题（如影响面分析）
   才进入最多 3 轮的受限工具循环，且每轮都设 token 与时间上限。
7. **模型分级**：简单问题（查签名、查配置）走小模型/直出，复杂问题才上大模型。
8. **索引常驻内存**：Qdrant 与图数据库数据量在千万级 chunk 以下时可全内存，注意 `mmap` 配置。

---

## 9. 智能体能力设计

### 9.1 工具集（Function Calling）

保持工具少而正交，避免模型乱调：

| 工具 | 作用 | 说明 |
| --- | --- | --- |
| `search_code(query, filters)` | 混合检索代码 | 主入口 |
| `get_symbol(fqn)` | 按全限定名取原文 | 精确、零检索成本 |
| `get_callers(fqn)` / `get_callees(fqn)` | 调用关系 | 影响面分析 |
| `search_docs(query)` | 文档检索 | 与代码结果合并排序 |
| `list_project_structure(path?)` | 目录/模块概览 | 回答"项目怎么组织的" |
| `read_file(path, start, end)` | 读原始文件片段 | 兜底与校验 |

### 9.2 编排模式：固定管线 + 受限循环（推荐）

```
Router（规则/小分类器）
 ├─ 事实型问题  → 固定管线：一次检索 → 一次 LLM 调用 → 引用校验   （占 80%+，最快）
 ├─ 关系型问题  → 检索 + 图扩展 → 一次 LLM 调用
 └─ 分析型问题  → 最多 3 轮工具循环（带 token / 时间预算）→ 汇总
```

相比自由 ReAct，这种设计延迟可控、成本可预测、失败可复现。

### 9.3 典型问答与响应策略

| 用户问题示例 | 走的路径 |
| --- | --- |
| "OrderService.createOrder 做了什么？" | 标识符精确命中 → 取方法原文 + Javadoc → 1 次 LLM |
| "下单接口的请求参数有哪些校验？" | 中文别名映射 → 检索 Controller + DTO + 校验注解 → 1 次 LLM |
| "改了 OrderService 会影响哪些接口？" | 图扩展（callers 闭包）→ 代码 + OpenAPI 文档 → 1 次 LLM |
| "项目用的是哪个版本的 Spring Boot？" | 直接解析 `pom.xml`（结构化，无需 RAG）→ 近乎零延迟 |
| "帮我总结订单模块" | 类级摘要向量检索 → 多 chunk 汇总，可走较大模型 |

> "能用结构化查询解决的，不要用 RAG"：版本号、依赖清单、配置项、接口清单这类问题，
> 直接查 `pom.xml` / `application.yml` / OpenAPI JSON，准确率 100% 且延迟最低。

---

## 10. 部署形态

### 10.1 目录结构

```
project-agent/
├─ docker-compose.yml
├─ services/
│  ├─ api/                 # FastAPI：编排 + 检索 + 引用校验（Python）
│  ├─ indexer/             # 索引流水线：tree-sitter / Spoon / Tika
│  └─ embed/               # Infinity：bge-m3 + bge-reranker
├─ data/
│  ├─ qdrant/              # 向量数据卷
│  ├─ sqlite/agent.db      # 符号图 + 元数据 + 缓存
│  └─ docs/                # 生成的文档产物（挂载只读）
├─ config/
│  ├─ settings.yaml        # 项目、路径、阈值、预算
│  └─ prompts/             # system / answer / verify 模板
└─ scripts/
   ├─ reindex.sh           # 全量 / 增量索引
   └─ gen-docs.sh          # 文档生成
```

### 10.2 docker-compose 骨架

```yaml
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports: ["6333:6333"]
    volumes: ["./data/qdrant:/qdrant/storage"]

  embed:                       # 同时提供 /embeddings 与 /rerank
    image: michaelfeil/infinity:latest
    command: >
      v2 --model-id BAAI/bge-m3 --port 7997
         --reranker-model-id BAAI/bge-reranker-v2-m3
    ports: ["7997:7997"]
    deploy:
      resources:
        reservations:
          devices: [{ driver: nvidia, count: 1, capabilities: [gpu] }]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  litellm:                     # 唯一对外出口，统一计费/重试/模型切换
    image: ghcr.io/berriai/litellm:main-latest
    command: ["--config", "/app/config.yaml", "--port", "4000"]
    environment:
      - LLM_API_KEY=${LLM_API_KEY}
    volumes: ["./config/litellm.yaml:/app/config.yaml"]
    ports: ["4000:4000"]

  api:
    build: ./services/api
    environment:
      - QDRANT_URL=http://qdrant:6333
      - EMBED_URL=http://embed:7997
      - REDIS_URL=redis://redis:6379
      - LLM_BASE_URL=http://litellm:4000
    volumes:
      - ./data:/data
      - ${JAVA_REPO}:/repo:ro
    ports: ["8080:8080"]
```

> 仅在 macOS（无 NVIDIA GPU）时：去掉 `deploy.resources`，改用 Infinity / ONNX Runtime 的 CPU 或 CoreML 后端；
> 单次查询嵌入约 80~200 ms，rerank 约 150~400 ms，仍可维持在 2s 内。

### 10.3 硬件建议

| 场景 | 配置 | 说明 |
| --- | --- | --- |
| 单人开发 | Mac M2/M3 Pro 18~36GB，全 CPU/ANE | 够用，嵌入与重排可接受 |
| 小团队 | 1× RTX 4090 / A10 24GB | bge-m3 + reranker 常驻显存约 8~12GB，余量充足 |
| 多项目/多分支 | 1× L40S 48GB 或 2× 4090 | 索引与检索并发更高 |

大模型不占本地显存（走外部 API），这是本方案能用较小硬件跑起来的关键。

---

## 11. 对外接口设计

```http
POST /v1/ask
{
  "question": "OrderService.createOrder 有哪些校验？",
  "project": "acme-order",
  "ref": "main",                 // 分支/commit，默认跟随工作区
  "top_k": 8,
  "stream": true,
  "mode": "fast"                 // fast(单次调用) | deep(受限工具循环)
}
→ SSE: data: {"type":"context","chunks":[...]}
       data: {"type":"delta","text":"createOrder 首先校验..."}
       data: {"type":"citations","items":[{"path":"src/.../OrderService.java","start":120,"end":168}]}
       data: {"type":"done","latency_ms":842,"cached":false}

GET  /v1/symbol/{fqn}?ref=main
GET  /v1/impact/{fqn}?depth=2          # 影响面分析
POST /v1/index/reindex { "project":"acme-order", "mode":"incremental" }
GET  /v1/health                        # 各组件状态 + 索引版本
```

附加能力（强烈建议）：把 `search_code` / `get_symbol` / `get_callers` 暴露为 **MCP Server**，
这样 IDE、Codex 等支持 MCP 的客户端可以直接调用同一套检索能力，无需重复建设。

---

## 12. 关键配置示例

```yaml
# config/settings.yaml
project: acme-order
repo: /repo
ref: main

index:
  include: ["src/main/java/**", "build/docs/**"]
  exclude: ["**/target/**", "**/generated-sources/**", "**/*Test.java"]
  chunk:
    class_max_tokens: 1200
    method_max_tokens: 600
    overlap_lines: 3

retrieval:
  dense_top_k: 50
  sparse_top_k: 50
  rrf_k: 60
  rerank_top_n: 8

guard:
  min_rerank_score: 0.35      # 低于此值直接拒答
  require_citation: true
  citation_retry: 1

cache:
  semantic_threshold: 0.95
  ttl_seconds: 86400
  invalidate_on_new_commit: true

llm:
  base_url: http://localhost:4000
  model: gpt-4o-mini           # 或 deepseek-chat / qwen-plus 等
  temperature: 0
  max_tokens: 1200
```

```
# config/prompts/answer.md（要点）
你是 acme-order 项目的代码助手。只依据下面提供的检索片段回答。

规则：
1. 每一句结论后面必须带引用，格式 [1]、[2]，引用只能来自提供的片段。
2. 片段中没有依据时，直接回答"在项目代码与文档中未找到相关依据"，不要推测。
3. 代码与文档冲突时以代码为准，并明确说明文档已过期。
4. 不要复述大段代码，只解释关键逻辑；引用代码请给出 路径:起始行-结束行。
5. 涉及方法签名、字段名、配置键时，必须与片段逐字一致。

【代码片段】
{code_chunks}

【文档片段】
{doc_chunks}

【问题】{question}
```

---

## 13. 实施路线图

| 阶段 | 周期 | 交付物 | 验收标准 |
| --- | --- | --- | --- |
| P0 打通闭环 | 1 周 | 切块 + bge-m3 + Qdrant + 单次 LLM 问答 + 引用 | 能回答"某方法做了什么"，引用行号正确率 > 90% |
| P1 精度与体验 | 1~2 周 | 混合检索 + 重排 + 文档通道 + 语义缓存 + SSE | 中文提问 Recall@10 > 85%，P95 < 2.5s |
| P2 严格性与维护 | 2~3 周 | 引用校验 + 拒答 + 漂移检测 + 增量索引 + 符号图 | 无依据问题拒答准确率 > 95%，增量索引 < 10s |
| P3 智能体化 | 2~4 周 | 影响面分析 + 文档生成/校验 + MCP/IDE 集成 + 多项目多分支 | 影响面分析人工核对通过率 > 90% |

每阶段都必须先建评测集再优化模型与参数，否则容易陷入调参玄学。

---

## 14. 评估与度量

建一个 100~200 条的 golden 问答集（覆盖：事实查询、实现解释、调用关系、配置、影响面、超出范围应拒答）。

| 指标 | 计算方式 | 目标 |
| --- | --- | --- |
| Recall@10 | 正确证据 chunk 是否在召回集内 | > 90% |
| 引用准确率 | 答案引用是否真实存在且支撑结论 | > 95% |
| 拒答准确率 | 超范围问题的拒答比例 / 未误拒比例 | > 95% / > 98% |
| P50 / P95 延迟 | 端到端首字与完成 | < 1.2s / < 2.5s |
| 缓存命中率 | 语义缓存命中占比 | > 25% |
| 单问成本 | 输入+输出 token × 单价 | 尽量 < 0.01 美元/问 |

每次调整（切块大小、模型、阈值）都跑一遍回归，并保留 `commit_sha` 作为评测基线版本。

---

## 15. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 中文提问检索不到英文代码 | 召回低 | 查询改写 + 术语别名表 + 中英双语嵌入（bge-m3） |
| 文档过期导致错误答案 | 误导 | 漂移检测 + stale 降权 + 代码优先规则 |
| 代码无法编译，静态分析失败 | 符号图缺失 | 降级到 tree-sitter 的语法级依赖，不阻塞主流程 |
| 大项目索引慢/占用大 | 体验差 | 分层摘要 + 只索引 main 分支 + 排除 test/generated |
| 外部 LLM 超时或限流 | 服务不可用 | LiteLLM 重试 + 多模型降级；超时直接返回检索原文摘要 |
| 代码外泄顾虑 | 合规 | 全部本地；可在出口做敏感信息脱敏与白名单字段过滤 |
| 单次调用无法回答复杂问题 | 准确率 | `mode=deep` 受限工具循环，最多 3 轮并设预算上限 |

---

## 16. 一句话总结

用 **tree-sitter 切块 + Spoon 建图 + bge-m3 嵌入 + Qdrant 混合检索 + bge-reranker 精排 + 语义缓存**
把"找证据"做到又快又准，再把**代码原文与文档章节**作为唯一上下文交给外部大模型做总结，
最后用**引用白名单校验 + 拒答阈值**保证严格性。整个检索链路本地化，
外部只保留一次按 token 计费的 LLM 调用，从而同时满足"严格、快速、可控、低成本"四个目标。

---

## 附录 A：框架选型对比（备选与取舍）

选型的关键不是"哪个框架最强"，而是**哪个框架能保住本方案的两条命脉**：
① 能对 Java 代码做语义级切块并拿到准确行号；② 能在答案里强制带可校验的引用。
凡是会在这两点上打折的框架，无论多流行都要降级使用。

### A.1 智能体 / 编排框架

| 框架 | 语言 | 强项 | 短板 | 在本方案中的定位 |
| --- | --- | --- | --- | --- |
| **自研固定管线（FastAPI）** | Python | 延迟最低、逻辑完全可控、依赖最少、易调试 | 需要自己写编排代码（约 500~800 行） | **主推荐**。80% 的问题是"检索→一次调用"，用不上框架 |
| **LangGraph** | Python/JS | 显式状态机、支持循环与分支、可回放、可中断续跑 | 抽象学习成本；过度使用会让简单问题变慢 | **推荐**作为 `mode=deep` 的受限工具循环实现 |
| **LlamaIndex** | Python/TS | RAG 抽象最完整（NodeParser / Retriever / PostProcessor / Reranker 全都有），检索调优 API 齐全 | 默认切块不认 Java 语法，需自定义 NodeParser；对符号图支持弱 | **推荐**用于检索层的快速搭建，尤其是 rerank、hybrid retriever |
| **LangChain** | Python/JS | 生态最大、示例最多 | 抽象层厚、版本破坏性变更频繁、链路难调试、隐式 prompt 注入多 | **不建议**做核心；只取其 `text-splitters`、少数 loader |
| **Haystack 2.x** | Python | Pipeline 显式、组件可替换、生产取向、文档质量高 | 中文社区小；Agent 能力弱于 LangGraph | 备选，若团队偏好显式 DAG |
| **Spring AI** | Java | 与 Spring Boot 原生集成、`Advisor` 机制适合放 RAG 与引用校验、工具调用完善 | RAG 抽象仍在演进，向量库适配层偶有版本坑 | **Java 栈首选之一** |
| **LangChain4j** | Java | RAG 抽象比 Spring AI 更成熟、`AiServices` 声明式接口、`EmbeddingStore`/`ContentRetriever` 开箱即用、支持 `TokenStream` 流式 | 与 Spring 生态整合不如 Spring AI 顺滑 | **Java 栈首选之一**，与 Spring AI 二选一 |
| **Dify** | 低代码平台 | 本地 Docker 一键部署、有 UI、知识库与工作流可视化、内置编排与日志 | 代码切块/符号图/行级引用校验都要改源码；深度定制成本高 | **仅用于 P0 价值验证或内部演示** |
| **RAGFlow** | 低代码平台 | 文档解析（含表格/OCR）最强、切片可视化、支持引用溯源 | 面向文档而非代码；Java 结构感知弱；资源占用大（需 ES + 模型） | **仅用于文档侧评估**，不适合做主链路 |
| **FastGPT / AnythingLLM / Open WebUI** | 低代码 | 上手极快、中文友好、适合个人 | 同上，且行级引用与代码图谱能力弱 | 原型/个人试用 |

**结论**：主链路用 FastAPI 自研固定管线；复杂问题用 LangGraph 兜住受限工具循环；
Java 团队若坚持同栈，用 Spring Boot + LangChain4j（或 Spring AI）替换 Python 编排层，
但嵌入/重排服务仍建议保持独立容器。

### A.2 本地模型服务（嵌入 / 重排）

| 框架 | 强项 | 短板 | 定位 |
| --- | --- | --- | --- |
| **Infinity** | 一个容器同时托管 embedding + reranker；支持 bge-m3 的 **dense + sparse**；OpenAI 兼容接口 | 生态与文档不如 TEI 成熟 | **主推荐**（正好满足 hybrid 检索需求） |
| **TEI（HF Text Embeddings Inference）** | Rust 实现，吞吐与延迟最好，HF 官方维护，生产验证充分 | 主要输出 dense；sparse 需自行处理 | 若放弃稀疏向量、只用 BM25 做关键词侧，则改推 TEI |
| **Xinference** | 国产、一个服务托管 LLM/embedding/rerank、中文文档友好、支持多硬件后端 | 性能调优不及 TEI/Infinity | 备选，中文团队友好 |
| **Ollama** | 部署最简单、Mac 友好、也提供 embedding 接口 | 只适合轻量；无 rerank；并发能力弱 | 单机 P0 验证；或本地 LLM 试水 |
| **ONNX Runtime / OpenVINO / CoreML(MLX)** | 模型进程内运行，去掉 HTTP 一跳；Mac 上可用 ANE | 需自己写服务化与批处理、显存管理 | 延迟极致优化阶段再考虑 |
| **vLLM / SGLang** | 本地 LLM 推理的工业标准（PagedAttention 等） | 本方案 LLM 走外部 API，用不上 | 若未来要本地化 LLM，直接选这两个 |
| **Triton Inference Server** | 企业级多模型编排、动态批处理 | 部署与运维重，单人项目过杀 | 大团队/多模型场景 |

### A.3 向量库 / 检索存储

| 方案 | 强项 | 短板 | 定位 |
| --- | --- | --- | --- |
| **Qdrant** | Rust 单二进制、dense + sparse 原生、Query API 内置 RRF/DBSF 融合、payload 过滤强、mmap 友好 | 图关系查询弱（需外部图库） | **主推荐** |
| **Milvus / Milvus Lite** | 功能最全、十亿级规模、多索引类型 | 全量部署依赖 etcd + MinIO，重；Lite 版能力受限 | 超大规模或已有 Milvus 时选 |
| **pgvector / pgvectorscale** | 与业务数据同库、事务一致、运维最省 | hybrid 检索能力弱于 Qdrant；索引构建慢 | 已有 Postgres 时优先考虑 |
| **Weaviate** | 内置 hybrid 检索与模块化、GraphQL 友好 | 部署比 Qdrant 重、内存占用高 | 备选 |
| **Elasticsearch / OpenSearch** | BM25 最强、向量+关键词同引擎、生态成熟 | 资源占用大（JVM + 内存）、调优复杂 | 已有 ES 集群的团队首选 |
| **LanceDB / Chroma** | 嵌入式零运维、单文件、适合本地 | 并发与生产特性弱（Chroma 尤甚）、缺原生 hybrid | 原型/单机小规模；Chroma 不建议上生产 |
| **Tantivy / Lucene** | 自建 BM25 的底层引擎、可控性最高 | 要自己搭索引服务 | 追求极致轻量时的自研选项 |

### A.4 Java 代码分析框架

| 框架 | 能力层级 | 强项 | 短板 | 定位 |
| --- | --- | --- | --- | --- |
| **Tree-sitter-java** | 语法级 | 极快、支持增量、容错（编译不过也能解析）、多语言统一 | 无类型解析、无跨文件绑定 | **切块主选** |
| **Spoon** | 语义级 | 完整类型解析、调用图、字段/注解引用、API 对 Java 模型友好、纯 Java 库 | 需要可用 classpath（依赖需下载）、大项目内存高 | **符号图主选** |
| **Eclipse JDT (AST/Core)** | 语义级 | 工业级编译器前端、最准确、IDE 同源 | API 繁琐、绑定的容错性差、需精细配置 classpath | 需要极高准确性时替换 Spoon |
| **JavaParser + Symbol Solver** | 语义级（按需） | API 简洁、上手快、容错好 | 类型求解能力弱于 Spoon/JDT | 中小项目或快速原型 |
| **SCIP + scip-java** | 索引级 | 生成与 IDE 导航同源的代码智能索引（定义/引用/实现全都有） | 需要额外构建步骤与存储，生态偏工具链 | **强烈建议评估**：可极大简化"跳转/引用"类问题 |
| **SootUp / ASM** | 字节码级 | 能分析编译后的 jar 与第三方依赖、跨过程数据流 | 与源码行号映射需额外工作 | 依赖分析、跨过程分析场景 |
| **ArchUnit** | 规则级 | 把架构约束写成可执行测试（分层、依赖方向） | 只做校验不做检索 | 让智能体引用"项目规范"作为依据 |
| **semgrep / CodeQL** | 模式级 | 可回答"哪里用了 X 模式"这类结构化问题，规则可共享 | 需维护规则；CodeQL 学习曲线陡 | 作为 `search_code` 的结构化补充工具 |

**组合建议**：`Tree-sitter（切块，快）+ Spoon（建图，准）+ ArchUnit/semgrep（规则，可选）`，
并用 `SCIP` 作为精确导航的增强项。三者职责不重叠，可并行建设。

### A.5 文档生成与解析

| 环节 | 选型 | 说明 |
| --- | --- | --- |
| API 文档（代码优先） | **springdoc-openapi**（取代已停更的 springfox） | 直接产出 `openapi.json`，结构化程度最高，最适合入库 |
| 契约测试文档 | **Spring REST Docs** | 文档由测试保证，天然抗漂移 |
| Javadoc | **maven-javadoc-plugin** / Gradle `javadoc` | 优先解析 `javadoc.xml`（比 HTML 稳定）；`mvn javadoc:javadoc -Ddoclint=none` |
| AsciiDoc | **AsciidoctorJ** | 技术文档主流格式 |
| Markdown | **flexmark-java** / CommonMark | 保留标题层级用于生成锚点 |
| 通用格式兜底 | **Apache Tika** | PDF/Word/PPT 统一抽取，含元数据 |
| PDF 精读 | **PDFBox** | 需要坐标/表格时使用 |
| 架构图 | **Structurizr (C4)** / PlantUML / Mermaid | 文本可版本控制，天然适合入库 |
| 文档站 | Docusaurus / MkDocs Material / Antora | 只影响发布形态，不影响索引 |

### A.6 服务、网关与可观测

| 能力 | 推荐 | 备选 |
| --- | --- | --- |
| API 框架 | **FastAPI + uvicorn**（SSE 简单、异步好） | Litestar；Java 侧用 **Spring Boot 3 + WebFlux** |
| LLM 网关 | **LiteLLM**（重试、限流、多模型降级、token 计费统计） | 直连 SDK；通用网关 Kong/APISIX |
| 缓存 | **Redis**（语义缓存 + 结果缓存）+ 进程内 LRU | cachetools / Caffeine(Java) |
| 编排/调度 | 简单队列（RQ / Celery / Spring Batch） | 规模大时上 Temporal |
| 链路追踪 | **OpenTelemetry** | — |
| RAG 观测 | **Langfuse**（开源可自托管） | Arize Phoenix |
| 指标监控 | Prometheus + Grafana | — |
| MCP 接入 | 官方 **MCP Python SDK** 暴露检索工具 | Java 侧 Spring AI MCP |

### A.7 三套推荐组合

**方案 A：性能与可控优先（推荐）**

```
编排   FastAPI 自研固定管线（复杂问题降级到 LangGraph）
嵌入   Infinity + bge-m3（dense + sparse）
重排   Infinity + bge-reranker-v2-m3
向量   Qdrant（hybrid + RRF）
图     Spoon 构建 → SQLite（起步）/ Neo4j（进阶）
切块   Tree-sitter-java
文档   springdoc-openapi + javadoc XML + Tika/flexmark
缓存   Redis 语义缓存
出口   LiteLLM → 外部 LLM API
观测   Langfuse + OpenTelemetry
```

**方案 B：全 Java 栈（团队技术栈统一）**

```
编排   Spring Boot 3 + LangChain4j（或 Spring AI）
检索   LangChain4j ContentRetriever + Qdrant Java Client
嵌入/重排  独立 Infinity 或 TEI 容器（HTTP 调用，不引入 Java 侧模型依赖）
分析   Spoon + JavaParser + ArchUnit
流式   WebFlux SSE
出口   Spring AI 的 OpenAI 兼容客户端，或统一走 LiteLLM
```

**方案 C：半天出 Demo（价值验证）**

```
RAGFlow 或 Dify 本地部署 + 接入项目 Git 仓库
→ 验证"提问能否找到对的代码"这个核心假设
→ 验证通过后再迁移到方案 A / B（不要在此平台上做长期迭代）
```

### A.8 选型时的硬性红线

无论选哪套框架，下面五条必须满足，否则后期必然返工：

1. **引用能落到行号**：切块必须保留 `path + start_line + end_line`，且能原样回传。
2. **切块可控**：必须能按 Java 的类/方法边界切（自定义 Splitter / NodeParser），不能只按字符数切。
3. **支持元数据过滤**：检索时可按 `project / ref / kind / package / language` 过滤。
4. **可替换**：嵌入模型、重排模型、LLM 都要能换（统一走 OpenAI 兼容接口）。
5. **可离线**：全部组件有本地部署形态，且无外部遥测强依赖。

**不建议的选择**：把 LangChain 全量抽象当作核心架构；一上来就上 Milvus；
用 Chroma 承载生产流量；把 Dify/RAGFlow 当作长期核心并指望在其上实现行级引用与符号图。

---

## 附录 B：Java 优先落地方案（Maven + 全 Java 栈）

> 前提：项目用 Maven；你精通 Java、Python 只懂基础语法；目标同时是"能用的工具"和"可写进简历的学习项目"。
>
> **结论：业务代码 100% 用 Java，Python 一行都不写。** 需要 Python 生态的地方（模型服务）全部用现成
> Docker 镜像跑起来即可，只当基础设施用，不碰源码。更进一步，嵌入与重排也能完全跑在 JVM 里。

### B.1 三条 Python 消除路径

原方案里只有两个地方会用到 Python：本地模型服务（Infinity/TEI）和 LLM 网关（LiteLLM）。三条消除路径：

| 路径 | 做法 | 是否写 Python | 适用阶段 |
| --- | --- | --- | --- |
| **① 全 Java 原生** | LangChain4j 的 ONNX 模块在 JVM 内直接跑嵌入与重排模型（ONNX Runtime 作为依赖） | ❌ | **起步首选**，零容器、零 GPU 也能跑 |
| **② 只跑容器** | `docker run` 启动 Infinity / TEI 镜像，Java 侧 HTTP 调用 | ❌（只是运行别人写好的镜像） | 需要 bge-m3 级别效果时 |
| **③ DJL 自建服务** | 用 Deep Java Library 加载 HuggingFace 上的 ONNX 模型，自己用 Spring Boot 包一层 | ❌ | 进阶，训练/推理工程师方向加分 |

LLM 网关（LiteLLM）同理：起步阶段直接用 Spring AI 的 OpenAI 兼容客户端 + 自己写的重试/降级逻辑（Java），
完全不需要 LiteLLM；等要做多厂商路由和统一计费时再考虑加容器。

### B.2 技术栈定版

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 语言/构建 | **Java 21 + Maven 多模块** | 虚拟线程对并发检索有帮助；Maven 与你现有项目一致 |
| 应用框架 | **Spring Boot 3.3+** | 简历金字招牌，且生态完整 |
| AI 编排 | **Spring AI**（主）+ **LangChain4j**（补） | 见 B.3 的对比与定位 |
| LLM 接入 | Spring AI 的 OpenAI 兼容客户端 | `base-url` 指向任意厂商（DeepSeek / Qwen / GLM / Kimi / OpenAI），随时切换 |
| 嵌入模型 | **LangChain4j ONNX 本地模型**（JVM 内） | 起步零容器；进阶换 bge-m3（Infinity 容器或 DJL） |
| 重排模型 | **LangChain4j `OnnxScoringModel`**（JVM 内） | cross-encoder 重排，CPU 可跑 |
| 向量库 | **Qdrant**（Docker 一条命令） | 支持 dense + sparse + RRF；备选 pgvector / 全内存 |
| 关键词检索 | Qdrant sparse 或 Lucene BM25 | 走 Qdrant 时天然 hybrid |
| 代码切块 | **JavaParser** | API 友好、上手快、纯 Java，最适合你 |
| 类型/调用图 | **Spoon** | 类型解析、调用图、字段引用；能编译的模块用，不能的降级 |
| 符号图存储 | **JGraphT**（内存）+ JSON/SQLite 落盘 | 纯 Java；关系复杂了再上 Neo4j 容器 |
| 文档解析 | **flexmark-java**（Markdown）+ **Apache Tika**（兜底） | 纯 Java |
| 缓存 | **Caffeine**（本地 LRU）+ 可选 Redis | Caffeine 纯 Java、零运维 |
| 接口 | **REST + SSE**（WebFlux 或 MVC SseEmitter）+ **Spring Shell/CLI** | CLI 起步最快，便于你随时验证 |
| 文档生成 | maven-javadoc-plugin + springdoc-openapi-maven-plugin | 全部 Maven 插件，无额外语言 |
| 评测 | **JUnit 5 + golden set（YAML/JSON）** | 用测试框架做 RAG 回归，简历上是亮点 |
| 可观测 | Micrometer + Prometheus + Grafana；自研 RAG trace 表 | 中后期 |
| 容器 | Docker Compose（只跑 Qdrant 等基础设施） | 你不用写 Dockerfile 也能起步 |

### B.3 Spring AI vs LangChain4j：怎么选（面试也常问）

| 维度 | Spring AI | LangChain4j |
| --- | --- | --- |
| 出身 | Spring 官方团队 | 社区（活跃度很高） |
| Spring 集成 | 原生、开箱即用 | 良好，但需自己装配 |
| RAG 抽象 | `ETL Pipeline`（Reader/Transformer/Writer）+ `VectorStore` + `Advisor` | `EmbeddingStore` / `ContentRetriever` / `RetrievalAugmentor` / `AiServices`，概念更贴近 RAG 论文 |
| 本地 ONNX 嵌入/重排 | 需自己写适配器 | **内置**（`langchain4j-embeddings-*`、`OnnxScoringModel`） |
| MCP 支持 | 官方 MCP Client/Server starter | 较新，追赶中 |
| 工具调用 | `@Tool` 注解 | `@Tool` 注解（更早成熟） |

**推荐组合（不冲突）**：

```
Spring Boot 3 作为应用骨架
  ├─ 用 Spring AI 管 ChatClient / VectorStore / Advisor / SSE / MCP
  └─ 用 LangChain4j 的 ONNX 模块提供本地 EmbeddingModel 与 ScoringModel
     （写一个 ~30 行的适配器把 LangChain4j 的模型桥接到 Spring AI 的 EmbeddingModel 接口）
```

> 这个"适配层"本身就是绝佳的简历素材：它证明你能读懂两个框架的抽象并做桥接，而不是只会调 API。
> 如果嫌麻烦，也可以全程只用 LangChain4j，能力上完全够用。

### B.4 Maven 多模块结构

```
code-agent/
├─ pom.xml                       # packaging=pom，统一版本与 BOM
├─ code-agent-core/              # 领域模型：Chunk / Citation / AskRequest / Config
├─ code-agent-indexer/           # JavaParser 切块、文档解析、嵌入、写向量库、增量调度
├─ code-agent-retrieval/         # 混合检索、RRF、重排、符号图扩展、语义缓存
├─ code-agent-agent/             # 问答管线、Prompt、引用校验、拒答、工具循环
├─ code-agent-web/               # Spring Boot 启动模块：REST + SSE + CLI
└─ code-agent-eval/              # golden set + JUnit 5 评测 + 指标报告
```

父 POM 要点：

```xml
<properties>
  <java.version>21</java.version>
  <spring-ai.version>1.x.x</spring-ai.version>   <!-- 以 Maven Central 最新 GA 为准 -->
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

关键依赖（坐标以 Maven Central 实际版本为准，几处需要你确认）：

```xml
<!-- 代码分析 -->
com.github.javaparser:javaparser-symbol-solver-core
fr.inria.gforge.spoon:spoon-core
org.jgrapht:jgrapht-core

<!-- 文档解析 -->
com.vladsch.flexmark:flexmark-all
org.apache.tika:tika-core  (+ tika-parsers-standard-package)

<!-- 本地模型（JVM 内，无 Python） -->
dev.langchain4j:langchain4j-embeddings-*        <!-- 具体 ONNX 模型模块，按需选 -->

<!-- 向量库 / 缓存 -->
org.springframework.ai:spring-ai-starter-vector-store-qdrant
com.github.ben-manes.caffeine:caffeine

<!-- 服务与文档 -->
org.springframework.boot:spring-boot-starter-webflux
org.springdoc:springdoc-openapi-starter-webmvc-ui

<!-- 测试 -->
org.testcontainers:testcontainers   <!-- 在测试里拉起 Qdrant 容器 -->
```

### B.5 分阶段路线（与学习同步，约 6~10 周业余时间）

| 阶段 | 时长 | 做什么 | 你学到的 AI 知识 | 简历/产出 |
| --- | --- | --- | --- | --- |
| **P0 打通** | 3~5 天 | Spring Boot + Spring AI + **全内存向量库** + 单文件读取；问一句答一句 | LLM 调用、Prompt、token、上下文窗口 | 能跑通的最小 demo，GitHub 首次提交 |
| **P1 结构化切块** | 1 周 | JavaParser 遍历 AST，产出 类/方法 级 chunk（带行号元数据） | 切块策略、embedding 语义、余弦相似度 | 手写一遍余弦相似度检索（**不上库**），真正理解向量检索 |
| **P2 真检索** | 1 周 | 接入 Qdrant；dense + BM25/sparse 混合；手写 RRF 融合 | 混合检索、BM25、RRF、HNSW 参数 | 检索命中率对比表（切块前 vs 后） |
| **P3 精排与引用** | 1 周 | ONNX cross-encoder 重排 Top50→Top8；上下文预算组装；引用白名单校验 + 拒答阈值 | bi-encoder vs cross-encoder、上下文位置偏差、幻觉成因 | **引用准确率、拒答准确率**两个指标 |
| **P4 文档通道** | 1 周 | maven javadoc + springdoc 生成文档 → flexmark/Tika 解析入库；文档漂移检测 | 多源 RAG、检索融合权重、知识新鲜度 | 双通道召回对比 |
| **P5 符号图与影响面** | 1~2 周 | Spoon 建图 + JGraphT；`谁调用了它` / `改动影响哪些接口` | 结构化检索 + 语义检索互补 | 影响面分析 demo（**最能打动面试官的功能**） |
| **P6 性能与评测** | 1~2 周 | Caffeine 缓存 + 语义缓存；增量索引；JUnit 5 golden set 评测；LLM-as-judge | 评估指标（Recall@k / faithfulness）、成本优化 | **完整 benchmark 表**，简历上的量化数据来源 |
| **P7 包装** | 1 周 | README 架构图、SSE 前端、Docker Compose 一键启动、系列博文 | 工程化与表达 | 可展示的开源项目 |

> 关键：**P1 手写一遍余弦相似度检索**，**P2 手写一遍 RRF**，**P3 手写一遍引用校验**。
> 这三处"不用框架、自己实现"是学习效率最高的地方，也是面试能被追问出深度的点。

### B.6 评测集与指标（简历数据的来源）

用 `code-agent-eval` 模块，把问题写成 YAML：

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
  expect_refusal: true        # 超范围问题，应拒答
```

自动计算并输出报告：Recall@10、引用准确率、拒答准确率、P50/P95 延迟、平均 token 成本。
**这些数字就是简历里唯一可信的量化来源**，别编。

### B.7 常见坑（Java 侧）

| 坑 | 现象 | 对策 |
| --- | --- | --- |
| ONNX 模型加载慢 | 首次请求卡 2~5 秒 | 启动时预热一次推理；模型对象做成单例 |
| 向量未归一化 | 相似度排序错乱 | ONNX 模型输出的向量手动做 L2 归一化，或确认模型已归一化 |
| 行号错位 | 引用指向错误行 | 用 JavaParser 的 `Range`/`begin.line` 取原始行号，切块后不要二次加工文本 |
| 中文提问检索不到 | 召回为空 | 查询改写：中文术语 → 类名/接口名映射表；嵌入模型选中文友好的 |
| Spoon 构建失败 | 依赖下载不全、编译不过 | 只对能编译的模块开启类型解析，其余降级到 JavaParser 语法级 |
| SSE 中文乱码 | 前端显示异常 | `produces = MediaType.TEXT_EVENT_STREAM_VALUE` + UTF-8 |
| 上下文超长 | LLM 报 token 超限 | 组装前用 `TokenCountEstimator` 估算并按预算裁剪 |
| 缓存脏读 | 代码改了还在答旧答案 | 缓存 key 带 `commit_sha`，新提交自动失效 |
| 大项目索引 OOM | 内存爆掉 | 分批处理 + 流式写入，别把整个项目的 AST 全留内存 |

---

## 附录 C：学习路径与简历包装

### C.1 必须先补的 AI 概念（按学习顺序）

| 顺序 | 概念 | 对应项目模块 | 推荐掌握程度 |
| --- | --- | --- | --- |
| 1 | Token 与上下文窗口、temperature | P0 | 能算出为什么上下文要裁剪 |
| 2 | Embedding 与向量相似度（余弦/点积/L2） | P1 | 能手写余弦相似度，解释归一化 |
| 3 | 切块策略（定长 vs 语义 vs 结构） | P1 | 能说清不同切块对召回的影响 |
| 4 | 向量索引（HNSW / IVF） | P2 | 知道 `m`、`ef_construct`、`ef_search` 的取舍 |
| 5 | BM25 与混合检索、RRF | P2 | 能手写 RRF 并解释为什么比加权求和稳 |
| 6 | bi-encoder vs cross-encoder | P3 | 能解释重排为什么提升精度、代价是什么 |
| 7 | 幻觉成因与抑制 | P3 | 引用校验 + 拒答阈值 + temperature=0 |
| 8 | 位置偏差（Lost in the Middle） | P3 | 能解释长上下文里信息位置的影响 |
| 9 | RAG 评估（Recall@k、faithfulness、answer relevance） | P6 | 能自己设计并跑评测 |
| 10 | Agent / 工具调用 / MCP | P5 | 能解释 ReAct 与固定管线的取舍 |
| 11 | （选学）Transformer 与注意力机制 | — | 面试被问到能讲清 QKV 与自注意力即可 |
| 12 | （选学）微调 vs RAG、LoRA | — | 能说清什么时候该微调、为什么不选微调 |

> 只做工程不补 11/12 也能过面试，但补上会让你从"会用框架"变成"懂原理"。
> 学习资源建议：吴恩达 DeepLearning.AI 的 RAG 短课、Hugging Face NLP Course、
> 以及三篇值得读的论文：DPR（稠密检索）、ColBERT（后期交互）、Lost in the Middle（位置偏差）。

### C.2 简历怎么写

**一句话项目描述（放在项目标题下第一行）**

> 基于 Spring Boot 3 + Spring AI 构建的 Java 项目智能体：对 Maven 工程的源码与生成文档做结构化索引，
> 通过「AST 切块 + 混合检索 + cross-encoder 重排 + 引用校验」实现严格依据代码库的问答，
> 支持调用影响面分析与增量索引；除 LLM API 外全链路本地部署。

**技术亮点（每条都能撑起一个面试问题）**

1. 用 JavaParser 做 AST 级切块（类/方法粒度），保留精确行号元数据，取代朴素定长切块。
2. 稠密向量 + BM25 混合召回，手写 RRF 融合，对比实验证明 Recall@10 从 x% 提升到 y%。
3. 引入 ONNX cross-encoder 重排（本地 JVM 内运行），Top50→Top8 精排。
4. 用 Spoon 构建符号图（继承/实现/调用/注入），支撑"改动影响哪些接口"类结构化查询。
5. 引用白名单校验 + 重排分数拒答阈值，将无依据回答率降到 x%。
6. Caffeine 语义缓存 + 基于 commit_sha 的缓存失效，P95 延迟从 x ms 降到 y ms。
7. 基于 git diff 的增量索引，单文件变更重索引耗时 < 1s。
8. JUnit 5 驱动的 RAG 回归评测集（n 条 golden Q/A），每次改动跑全量指标。

**写简历的三条纪律**

- 所有百分比和延迟必须是自己跑出来的，面试官一定会追问实验设置。
- 用 STAR：背景（代码库大、新人上手慢、文档滞后）→ 任务 → 行动（上述亮点）→ 结果（数字）。
- 别写"使用了 LangChain4j 做了个问答机器人"。**差异化在代码+文档双通道、符号图、可校验引用、评测闭环**，
  这些才是让人记住的点。

### C.3 面试高频问题（提前准备）

- Embedding 维度是多少？为什么需要归一化？余弦相似度和点积什么时候等价？
- HNSW 的 `m` 和 `ef_search` 分别影响什么？召回率和延迟怎么权衡？
- chunk size 怎么定的？太大和太小分别有什么问题？overlap 有什么用？
- 为什么要混合检索？BM25 和向量检索各自擅长什么？RRF 相比加权求和好在哪？
- cross-encoder 为什么比 bi-encoder 准？为什么不能直接用它做全量检索？
- 你怎么防止模型编造？引用校验具体怎么实现？拒答阈值怎么定的？
- 代码更新了，索引和缓存怎么办？
- 你怎么评估这套系统好不好？Recall@10 怎么测？faithfulness 怎么测？
- 一次问答大概花多少 token、多少钱？怎么降？
- 为什么选 RAG 而不是微调？什么情况下你会选微调？

### C.4 一句话总结（Java 版）

**Maven 多模块 + Spring Boot 3 + Spring AI（配 LangChain4j 的 JVM 内 ONNX 模型）+ Qdrant +
JavaParser/Spoon + Caffeine**，业务代码全 Java，基础设施只用 Docker 起一个 Qdrant；
按 P0→P7 推进，每一步都留下可量化的实验结果，最终产出一个**有 benchmark、有架构图、有评测集**
的开源项目——它既是能天天用的工具，也是简历上最容易被追问、且你答得上来的那一段经历。

> 相关阅读：[附录 D：Agent 框架生态与选型建议](appendix-d-agent-frameworks.md)
> （含 2026-09 实时 star / 活跃度数据、"该不该选框架"的结论，以及贡献开源框架的路径）
