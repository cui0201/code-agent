# code-agent

一个管理 Java 工程源码与其生成文档的**项目智能体**：对 Maven 工程做结构化索引，
支持用自然语言提问，并给出**严格依据代码**、带精确行号引用的答案。

除大模型 API 外，全链路本地运行——嵌入与重排在 JVM 进程内完成，不需要 Python、不需要额外推理服务。

```
问题 ──┬─► 本地 ONNX 嵌入 ──► 向量检索 ─┐
       └─► 分词 + BM25 ────► 关键词检索 ─┴─► RRF 融合 ──► 拒答判定 ──► 提示组装
                                                             │
                                                     无依据则直接拒答
                                                     （不调用 LLM，不编造）
                                                             ▼
                                              LLM ──► 引用校验 ──► 答案 + 行号引用
```

## 架构

```
                          ┌──────────────────────────────────────────┐
   接入层                  │ CLI(--index/--ask) │ REST+SSE │ MCP Server │
                          └───────────┬──────────┬───────────┬───────┘
                                      ▼          ▼           ▼
                          ┌──────────────────────────────────────────┐
   编排层                  │  QaService：缓存 → 检索 → 拒答 → 提示组装   │
                          │            → 生成 → 引用校验 →（失败则重试） │
                          └───────────┬──────────────────────────────┘
                                      ▼
        ┌─────────────────────────────────────────────────────────────┐
   索引层│  向量索引(Qdrant/内存)   关键词索引(BM25)   符号图(JGraphT)  │
        └───────┬──────────────────────┬────────────────────┬────────┘
                │                      │                    │
   切块层  ┌────┴─────┐          ┌─────┴─────┐        ┌─────┴─────┐
          │JavaParser│          │ 分词器     │        │  Spoon    │
          │  AST切块 │          │标识符+中文 │        │ 类型解析   │
          └────┬─────┘          └─────┬─────┘        └─────┬─────┘
               │                      │                    │
   数据层  ┌───┴──────────────────────┴────────────────────┴────┐
          │ 源码(.java) + 文档(.md) + 漂移检测 + 增量复用缓存      │
          └────────────────────────┬─────────────────────────────┘
                                   ▼
   模型层            bge-small-zh（JVM 内 ONNX，512 维）
```

依赖方向严格单向：`web → agent → retrieval → indexer → core`。
`core` 零框架依赖，所有框架（Spring AI、LangChain4j、Spoon、JGraphT）都在外层。

## Benchmark 汇总

以下数字全部来自本项目的实测，可用 `mvn verify` 复现（每份报告带语料指纹）。

**切块策略**（样例工程，召回策略统一为向量）

| 策略 | 切块数 | 证据召回率 | 命中块平均多余行数 |
| --- | --- | --- | --- |
| AST + Markdown 章节 | 43 | **75.0%** | **0.0** |
| 定长窗口 60 行 | 13 | 41.7% | 9.5 |

**召回策略**（混合 = 向量 + BM25 + RRF）

| 语料 | 仅向量 | 仅关键词 | **混合** |
| --- | --- | --- | --- |
| 样例工程（43 切块） | 75.0% | 100.0% | **91.7%** |
| code-agent 自身（1003 切块） | 66.7% | 96.3% | **88.9%** |

> 上表是加入生成文档通道之前的数字。当前（1362 切块，含 Javadoc/OpenAPI）：
> 严格闸门 85.9%、无闸门 **96.9%**（code-agent 自身）。

仅关键词路召回最高，但拒答准确率为 **0%**——它总能找到"某个词出现过"的片段。
只有混合检索能同时兼顾召回与拒答。

**索引与缓存**（样例工程 11 文件 / 43 切块）

| 操作 | 耗时 |
| --- | --- |
| 冷启动索引（43 块全部嵌入） | 872 ms |
| 无变更重新索引（全部复用） | **4 ms** |
| 单块嵌入（512 维，CPU） | 约 45 ms |
| 语义缓存命中 | 跳过检索与生成，仅一次问题嵌入 |

**索引持久化**（code-agent 自身 169 文件 / 1274 块，两个独立进程）

| 场景 | 复用文件 | 实际嵌入块 | 索引耗时 |
| --- | --- | --- | --- |
| 冷启动 | 0 / 169 | 1274 | 14 014 ms |
| 冷启动（嵌入批处理后） | 0 / 169 | 1280 | **10 820 ms** |
| 重启后加载快照 | **169 / 169** | **0** | **228 ms** |

快照保存在 `.codeagent/index/`（已加入 `.gitignore`，因为里面含代码原文）。

**重排（cross-encoder，实测结论：不默认启用）**

| 指标 | 无重排 | 有重排 |
| --- | --- | --- |
| MRR | **0.9167** | 0.8750 |
| 单次查询延迟 | < 1 ms | **4145 ms** |

**影响面分析**（Spoon + JGraphT，样例工程 52 节点 / 46 边）

| 查询 | 结论 | 核对 |
| --- | --- | --- |
| `OrderService#createOrder` 的调用方 | `OrderController#create`（1 跳） | ✅ |
| `OrderService#validateAmount` 的调用方 | `validateRequest`，行号 98 | ✅ |

## 当前状态：P8 已完成

| 能力 | 状态 |
| --- | --- |
| Maven 多模块骨架 | ✅ |
| 源码扫描（glob 过滤、行尾规范化、内容指纹） | ✅ |
| 切块：AST 结构化切块（类/方法，稳定 ID）+ 定长窗口基线 | ✅ |
| 本地 ONNX 嵌入（JVM 内，512 维） | ✅ |
| 混合检索：稠密向量 + BM25 关键词 + 手写 RRF 融合 | ✅ |
| 向量库可切换：进程内（零依赖）/ Qdrant（持久化 + HNSW） | ✅ |
| 提示组装 + 拒答 + 引用白名单校验 | ✅ |
| CLI（`--index` / `--ask`）与 REST（`/v1/ask`、`/v1/impact`、`/v1/health`） | ✅ |
| Golden set 评测（YAML 驱动、可对比不同策略） | ✅ |
| cross-encoder 重排（已实现并实测，**结论是不默认启用**） | ✅ |
| 引用校验失败后的带反馈重试 | ✅ |
| 文档通道：Markdown 按标题切章节 + 符号关联 + 漂移检测 | ✅ |
| 符号图（Spoon + JGraphT）与影响面分析 `GET /v1/impact` | ✅ |
| 配置通道：YAML / properties 按配置块切分 + 配置意图加权 | ✅ |
| 生成文档通道：Javadoc HTML（按成员）+ OpenAPI JSON（按接口） | ✅ |
| 索引持久化：切块、向量与符号图落盘，重启直接复用 | ✅ |
| 嵌入批处理：16 条一批，批量失败自动逐条回退 | ✅ |
| 语义缓存（精确层 + 语义层，索引重建时失效） | ✅ |
| 增量索引（按文件复用切块与向量） | ✅ |
| SSE 流式问答 `GET /v1/ask/stream` | ✅ |
| 运行指标 `GET /v1/metrics`（缓存命中率、拒答率、P50/P95） | ✅ |
| 评测闭环：**100 条用例**（两个语料）+ 分类指标 + 语料指纹 | ✅ |
| MCP Server（stdio，4 个工具）+ 冒烟测试脚本 | ✅ |
| 架构图与 benchmark 汇总 | ✅ |
| 上游贡献准备（`JavaAstDocumentSplitter` 适配器 + 执行方案） | ✅ |
| 测试 | ✅ 110 个单元测试 + 29 个集成测试 |

**召回策略对比**（详见 `code-agent-eval/target/rag-report.md`）：

| 策略 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |
| --- | --- | --- | --- |
| 仅向量（P1） | 75.0% | 100% | 0.0 |
| 仅关键词（BM25） | 100.0% | **0.0%** | 0.0 |
| **混合（RRF 融合）** | **100.0%** | **100.0%** | **0.0** |

两条单路各有硬伤，混合才拿到 100% / 100%：向量路漏掉"问题里点名了符号"的查询，
关键词路则无法拒答（问"用的哪个消息队列"，BM25 因为注释里出现过"消息队列"而给了 14.86 的高分）。
切块策略的对比（AST vs 定长窗口：召回 75% vs 62.5%，引用多余行数 0 vs 9.5）见设计文档 14.4 节。

**P3 的结论是否定的，但结论本身来自实测**：

| 策略 | 召回率 | MRR | 正确证据排第一 | 单次查询延迟 |
| --- | --- | --- | --- | --- |
| hybrid（P2） | 100.0% | **0.9167** | 7 / 8 | < 1 ms |
| hybrid + rerank（P3） | 100.0% | 0.8750 | 6 / 8 | **4145 ms** |

重排把 q001 从第 1 名压到第 2 名（q002 从第 3 名升到第 2 名），**质量没提升，延迟却是 P95 预算的 1.6 倍**，
因此默认关闭。原因分析与重新评估的条件见设计文档 14.7 节。

**P4 加入了文档通道**（Markdown 按标题切章节、抽取符号引用、漂移检测），评测集扩到 14 条：

| 策略 | 切块数 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |
| --- | --- | --- | --- | --- |
| ast + Markdown 章节 | 43 | 75.0% | 100.0% | **0.0** |
| 定长窗口（全部文件） | 13 | 41.7% | 100.0% | 9.5 |

结构化切块的优势从 P1 的 75% vs 62.5% 扩大到 **75% vs 41.7%**——定长窗口会把一个文档章节从中间切断。
但文档通道也带来两个新问题，见设计文档 14.8 节：**拒答阈值的分离度只剩 0.023**（中文问句对中文文档的余弦基线就有 0.6），
以及 **RRF 融合会把单路排第一的结果挤出结果集**。

**P5 加入了符号图**（Spoon 做类型解析 + JGraphT 存图），让"改动影响面"这类
向量检索根本答不了的问题有了确定答案（样例工程 7 个文件 → 52 节点 / 46 边）：

| 查询 | 结论 | 人工核对 |
| --- | --- | --- |
| `OrderService#createOrder` 的调用方 | `OrderController#create`（1 跳） | ✅ |
| 受影响文件 | `src/main/java/com/acme/order/OrderController.java` | ✅ |
| `OrderService#validateAmount` 的调用方 | `OrderService#validateRequest`，行号 98 | ✅ 与源码一致 |

**配置类问题**（配置通道接入后，全部命中 `application.yml`）

| 问题 | 结果 |
| --- | --- |
| 默认的拒答阈值是多少？ | 0.62 |
| 默认的向量库是哪一个？ | `memory`（进程内存储） |
| 语义缓存的容量默认是多少？ | 1000 |

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
export LLM_API_KEY=sk-xxx                 # 唯一必须的凭据
export JAVA_PROJECT=/path/to/your/project # 要索引的 Java 工程
docker compose up -d --build
curl localhost:8080/v1/health
```

compose 会启动两个容器：

| 服务 | 作用 |
| --- | --- |
| `code-agent` | 应用本体（已包含本地嵌入模型，无需额外模型服务） |
| `qdrant` | 向量库；应用通过 `--code-agent.store.type=qdrant` 使用它 |

被索引的工程以**只读**方式挂载到 `/workspace`，应用不会修改你的源码。
索引快照保存在 `code-agent-index` 卷里，容器重启后直接复用，不必重建。

> `LLM_API_KEY` 通过环境变量注入，不进镜像、不进仓库。
> 没有设置时 compose 会直接报错退出，避免"以为配好了其实没配"。

### 方式二：本地运行

### 环境要求

- **原生 arm64 的 JDK 21**（Apple Silicon 必须用 arm64，不能用 x86_64 的 JDK）
  - 本项目已验证：Temurin 21.0.12.1 arm64，位于 `~/Library/Java/JavaVirtualMachines/temurin-21-aarch64.jdk`
  - `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- Maven 3.9+
- 一个 OpenAI 兼容的大模型服务（DeepSeek / 通义千问 / 智谱 / Kimi / OpenAI 均可）

> ⚠️ 用 x86_64 JDK（Rosetta）会报 `Failed to load Huggingface native library`。

### 构建

```bash
mvn clean verify          # 编译 + 单元测试 + 集成测试
```

### 索引样例工程

```bash
mvn -q -pl code-agent-web spring-boot:run \
  -Dspring-boot.run.arguments="--index --code-agent.index.root=examples/demo-project"
```

输出示例（数值为示意，实际取决于模型与网络）：

```
索引完成: 文件 7 个, 切块 9 个, 平均 1.3 块/文件, 失败 0 个, 耗时 273 ms
```

### 提问

```bash
export LLM_API_KEY=sk-xxxxxx          # 密钥只从环境变量读取
mvn -q -pl code-agent-web spring-boot:run \
  -Dspring-boot.run.arguments="--ask 下单接口对订单金额做了哪些校验？"
```

输出示例：

```
=== 回答 ===
下单金额校验包含三条规则 [1]：金额不能为空、必须不小于 0.01 元、不得超过 10 万元，
且最多保留两位小数 [1]。

=== 引用 ===
[1] src/main/java/com/acme/order/OrderService.java:101-132

耗时: 检索 12 ms, 生成 1420 ms, 使用证据 5 块, 提示 6821 字符, 模型 deepseek-chat
```

### 作为服务运行

```bash
mvn -q -pl code-agent-web spring-boot:run        # 默认端口 8080

curl -X POST localhost:8080/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"createOrder 如何保证幂等？"}'

curl localhost:8080/v1/health

# 影响面分析：改动这个方法会波及谁
curl -s "localhost:8080/v1/impact?symbol=OrderService%23createOrder&depth=2" | jq

# 流式问答（SSE）：delta → citations → done
curl -N "localhost:8080/v1/ask/stream?question=订单有哪几种状态？"

# 运行指标
curl -s localhost:8080/v1/metrics | jq
```

### 作为 MCP Server 运行

以 stdio 方式启动，供支持 MCP 的客户端（IDE、编码助手）直接调用检索与影响面分析：

```bash
java -jar code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar --mcp \
  --code-agent.index.root=/path/to/your/project
```

启动时会自动建索引，暴露 4 个工具：`search_code`、`get_symbol`、`impact_analysis`、`index_status`。
冒烟测试（用真实子进程走一遍协议）：

```bash
bash scripts/mcp-smoke-test.sh
```

#### 接入 Codex

先构建 JAR，再将稳定启动脚本注册为 Codex 的本地 MCP：

```bash
mvn -B -DskipTests package
codex mcp add code_agent -- "$PWD/scripts/run-mcp.sh" "$PWD"
codex mcp list
```

`run-mcp.sh` 不传工程目录时索引 code-agent 自身；传入其他 Maven 工程路径即可切换目标：

```bash
codex mcp add another_project -- "$PWD/scripts/run-mcp.sh" /path/to/another-project
```

注册后需要新开一个 Codex 会话，让客户端重新加载 MCP 工具。这个 MCP 只负责检索、
取符号和影响面分析，不调用大模型，因此不需要 `LLM_API_KEY`；由 Codex 根据返回的
源码与行号组织最终回答。

> 注意：stdio 模式下 **stdout 是协议通道**，日志必须走 stderr。
> 因为 Spring Boot 的启动横幅写在容器启动阶段，日志改道必须早于它——
> 这个细节由 `McpStdioChannel` 保证，冒烟测试里有对应的断言。

### 增量索引

索引以**文件**为单位复用上一次的结果（切块 + 向量），只有内容变化（按内容哈希判断，
不是修改时间）的文件才会重新切块与嵌入。实测样例工程：

| 场景 | 复用文件 | 实际嵌入块 | 耗时 |
| --- | --- | --- | --- |
| 冷启动 | 0 / 11 | 43 | 872 ms |
| 无任何变更 | 11 / 11 | 0 | **4 ms** |

注意：增量状态在进程内，因此一次性 CLI 命令（`--index` 后退出）每次都是冷启动；
常驻服务模式下通过 `POST /v1/index` 重复触发才享受增量。

### 可选：切换到 Qdrant

默认用进程内存储（零依赖）。需要**持久化 + HNSW 近似检索**时切换到 Qdrant：

```bash
# 方式一：Docker
docker run -d --name code-agent-qdrant -p 6333:6333 qdrant/qdrant

# 方式二：官方原生二进制（macOS arm64，比拉镜像快得多）
curl -L -o qdrant.tar.gz https://github.com/qdrant/qdrant/releases/download/v1.19.1/qdrant-aarch64-apple-darwin.tar.gz
tar -xzf qdrant.tar.gz && ./qdrant

# 切换存储并重建索引
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -jar code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar \
  --index --code-agent.store.type=qdrant --code-agent.index.root=examples/demo-project
```

已验证：切换后检索结果与内存实现**逐条完全一致**（同顺序、同分数）。
若配置了 Qdrant 但服务未启动，应用会直接报错并给出启动命令，而不是静默降级。

## 模块结构

```
code-agent-core        领域模型与端口接口（零框架依赖）
code-agent-indexer     扫描、切块、本地 ONNX 嵌入
code-agent-retrieval   向量存储与检索
code-agent-agent       提示组装、问答编排、引用校验、拒答
code-agent-web         Spring Boot 装配、CLI、REST
```

依赖方向严格单向：`web → agent → retrieval → indexer → core`。
替换实现时只改 `AgentConfiguration` 一个文件（例如 P2 把 `InMemoryVectorStore` 换成 Qdrant）。

## 配置

主要配置项在 `code-agent-web/src/main/resources/application.yml`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `code-agent.index.root` | `.` | 被索引工程的根目录 |
| `code-agent.chunk.window-lines` | `60` | 行窗口大小（P1 后由 AST 切块取代） |
| `code-agent.retrieval.top-k` | `8` | 注入上下文的证据块数 |
| `code-agent.retrieval.min-score` | `0.60` | 低于该相似度直接拒答（P1 评测集校准值） |
| `code-agent.retrieval.strategy` | `hybrid` | `hybrid` / `vector` / `keyword` |
| `code-agent.retrieval.candidate-k` | `30` | 每路召回的候选数（融合前） |
| `code-agent.store.type` | `memory` | `memory` 或 `qdrant` |
| `code-agent.store.qdrant-url` | `http://localhost:6333` | Qdrant 地址 |
| `code-agent.chunk.strategy` | `ast` | `ast` 按类/方法结构化切块；`line-window` 为定长窗口基线 |
| `code-agent.rerank.enabled` | `false` | 重排开关（实测无收益，默认关闭） |
| `code-agent.guard.citation-retry` | `1` | 引用校验失败后带反馈重试的次数 |
| `code-agent.cache.enabled` | `true` | 语义缓存开关 |
| `code-agent.cache.semantic-threshold` | `0.95` | 语义层判定为同一问题的相似度下限 |
| `code-agent.cache.max-entries` | `1000` | 缓存条目上限，超出后淘汰最旧的 |

## 评测

`mvn verify` 会跑两个语料的评测并输出报告：

| 报告 | 语料 | 内容 |
| --- | --- | --- |
| `code-agent-eval/target/rag-report.md` | 样例工程（11 文件 / 43 切块） | 切块策略对比 + 三路召回策略对比 |
| `code-agent-eval/target/self-rag-report.md` | **code-agent 自身**（117 文件 / 1003 切块） | 真实规模下的三路召回对比 + 分类指标 |

每份报告都带**语料指纹**（所有文件路径 + 内容哈希的组合摘要），
指纹相同则结果可复现，指纹不同则两份报告不可比。

**当前水位**（自评测，混合检索）：

| 类别 | 通过率 |
| --- | --- |
| 实现解释 | 100.0% |
| 事实查询 | 83.3% |
| 设计决策 | 80.0% |
| 学习路径 | 66.7% |
| 跨文件综合 | **33.3%** |
| 拒答 | **33.3%** |

分类指标的意义就在这里：只看总分看不出问题，分开看立刻发现
"单点实现问题答得很好，跨文件综合与拒答很差"。
拒答只做到 1/3，根因是绝对阈值在中文语料上失效（详见设计文档 14.11）。
| `code-agent.llm.base-url` | `https://api.deepseek.com` | 任意 OpenAI 兼容端点 |
| `code-agent.llm.api-key` | 环境变量 `LLM_API_KEY` | **禁止写入配置文件** |
| `code-agent.llm.model` | `deepseek-chat` | 环境变量 `LLM_MODEL` 可覆盖 |

## 已知限制（都是刻意保留的，且都有对应的后续阶段）

1. **拒答已改为 LLM 判官，实测 7/7 全对**。相似度阈值在中文语料上失效后，
   先试过"稀有词命中"（全部放行）与"稀有词覆盖率"（召回掉 26 个百分点、
   拒答毫无改善）两种启发式，都失败了（设计文档 14.12）；
   换成 LLM 判官后端到端实测：**拒答准确率 100%（7/7）、证据类作答率 92.3%（12/13）**。
   代价是每条问题多 1~2 秒判官延迟，以及一条误拒（答案不在前五条证据里）。
   复现：`scripts/judge-acceptance-test.sh`（需要 API Key）。
2. **RRF 融合会丢掉"单路第一"**。q012 被关键词路排第一，融合后掉出前 8 ——
   因为 RRF 奖励"两路都靠前"，而它只被一路强命中。
3. **重排已实现但默认关闭**。实测不提升质量，且延迟达 4145 ms/查询（设计文档 14.7 节）。
4. **符号图只覆盖源码内部关系**。`noClasspath` 模式下三方依赖解析不到，
   Spring 注入、跨模块调用都没有进图；建图成本约 70 ms/文件，全量重建代价明显。
5. **评测集 100 条**（样例工程 24 + 自身 76），覆盖配置、文档、跨文件、拒答等类别；
   自评测语料排除了 `src/test/**`，否则测试里逐字出现的评测问题会污染结果。
6. **类块是合成摘要**（类声明 + 字段 + 方法签名），元数据里标记 `synthesized=true`。
7. **生成文档只覆盖 Javadoc 与 OpenAPI**。AsciiDoc、Confluence 导出等尚未接入；
   Javadoc 的导航页与 `class-use` 交叉引用页被主动跳过（它们是噪声）。
8. **增量状态在进程内**。重启后第一次索引仍是冷启动；一次性 CLI 命令享受不到增量。
9. **嵌入仍是串行的**（约 45 ms/块）。增量索引绕开了大部分重复计算，
   但首次索引大工程仍然慢。
10. **BM25 与符号图都在进程内**，切换到 Qdrant 时不会一起持久化，重启后需要重新索引。
11. **流式模式下不做引用重试**——重试会把第二段回答也推给前端，
   用户会看到两段互相矛盾的答案（见设计文档 14.10）。

## 路线图

| 阶段 | 主题 | 状态 |
| --- | --- | --- |
| P0 | 环境与最小闭环 | ✅ 已完成 |
| P1 | AST 结构化切块 + 评测集 | ✅ 已完成 |
| P2 | Qdrant + 混合检索 + 手写 RRF | ✅ 已完成 |
| P3 | cross-encoder 重排 + 引用重试 | ✅ 已完成（重排不默认启用） |
| P4 | 文档通道（Markdown）与漂移检测 | ✅ 已完成 |
| P5 | Spoon 符号图与影响面分析 | ✅ 已完成 |
| P6 | 语义缓存、增量索引、SSE、可观测 | ✅ 已完成 |
| P7 | 评测闭环正式化 | ✅ 已完成 |
| P8 | MCP Server、README/benchmark、上游贡献准备 | ✅ 已完成 |

## 文档

| 文档 | 内容 |
| --- | --- |
| [学习路线](docs/learning-roadmap.md) | **跟着本项目学 AI 应用工程**：知识地图、每个知识点的代码位置与实验、面试问答 |
| [项目定位与价值](docs/project-value.md) | **它和"把仓库直接交给 Codex/Claude"的区别**：成本、数据边界、引用可审计、可评测 |
| [项目方案](docs/code-agent-plan.md) | 目标、架构、技术栈定稿、路线图、评测体系、简历映射 |
| [开发设计文档](docs/design/dev-design.md) | 模块设计、接口签名、数据模型、配置、流程、测试，以及 P0~P5 的全部实测数据 |
| [Agent 框架生态调研](docs/appendix-d-agent-frameworks.md) | 2026-09 主流框架活跃度数据与选型建议 |
| [上游贡献方案](docs/upstream-contribution.md) | 把 AST 切块器推进 LangChain4j 的具体步骤与取舍 |
| [部署指南](docs/deployment.md) | Docker Compose / 本地部署、配置项、验证清单、运维与故障排查 |

## 尚未完成

| 项 | 说明 |
| --- | --- |
| Docker 镜像构建验证 | `docker-compose.yml` 已通过 `docker compose config` 语法校验；完整镜像构建需要在本机拉取 Maven 依赖并打包，尚未执行 |
| AsciiDoc / Confluence 文档 | 目前生成文档只覆盖 Javadoc 与 OpenAPI |
| 跨文件综合 | 自评测里这项仍是最弱的类别之一，需要查询分解或多轮检索 |

## 许可证

[MIT](LICENSE)。版权持有人为 **Yuhua Cui**。

## 设计上刻意的三个取舍

1. **检索链路手写，编排交给框架**。余弦相似度、RRF、引用校验都由本项目管理——
   这些是理解 RAG 的核心，也是面试能被追问出深度的地方；而模型调用、流式、追踪交给 Spring AI。
2. **接口先于实现**。所有能力都定义在 `core` 的端口接口上，实现可替换，
   因此从内存向量库换到 Qdrant、从定长切块换到 AST 切块，都不会波及问答链路。
3. **先有对照，再谈优化**。定长切块是已知的"坏"实现，但它是 P1 的对照基线——
   没有基线，就无法回答"AST 切块到底带来了多少提升"。
