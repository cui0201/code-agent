# CodeAgent 开发设计文档

> **版本**：v1.0 ｜ **日期**：2026-09-21 ｜ **状态**：P0 实施依据
>
> **上游文档**：[CodeAgent 项目方案（定稿）](../code-agent-plan.md)
>
> 本文档描述"怎么做"：模块划分、类的职责、接口签名、数据模型、配置项、流程、测试与规范。
> 方案文档描述"做什么、为什么这么做"，两者互为补充。

---

## 1. 文档说明

| 项 | 说明 |
| --- | --- |
| 读者 | 项目作者本人（Java 背景，AI 方向学习者） |
| 范围 | 覆盖 P0~P8 全周期的架构约定；P0 部分给出可直接落地的类与接口设计 |
| 不包含 | 产品需求、UI 设计、运维部署手册 |
| 维护约定 | 任何架构级改动必须同步更新本文档与方案文档的附录 B（关键约定） |

**设计原则**

1. **先跑通再优化**：P0 允许"笨"的实现（定长切块），但不允许跳过引用。
2. **接口先行**：核心抽象（切块、嵌入、存储、检索）先定接口，实现可替换。
3. **不提前抽象**：只在 P0 需要时引入依赖，Qdrant / Spoon / 重排模型一律后置。
4. **可测量**：每个阶段都有可运行的验证命令，拒绝"感觉变好了"。

---

## 2. 系统上下文与边界

```
                ┌──────────────────────────────┐
                │        使用者（你）           │
                └───────┬──────────────┬───────┘
                    CLI │              │ REST/SSE
                        ▼              ▼
              ┌─────────────────────────────────┐
              │        code-agent（本项目）      │
              │  索引 · 检索 · 问答 · 引用校验    │
              └───┬──────────┬──────────┬───────┘
                  │          │          │
        读取源码   │    本地推理│    调用 LLM│（唯一外呼）
                  ▼          ▼          ▼
          ┌──────────┐ ┌──────────┐ ┌──────────────┐
          │ Java 工程 │ │ ONNX 模型 │ │  LLM API     │
          │ (Maven)  │ │ (JVM 内) │ │ (OpenAI 兼容) │
          └──────────┘ └──────────┘ └──────────────┘
                  ▲
                  │ 生成文档（javadoc / openapi）
```

**边界说明**

- 本项目**只读**目标工程，不修改被索引代码。
- 唯一的外呼是 LLM API；发送内容仅限检索命中的代码片段与文档片段。
- 嵌入与重排模型在 JVM 进程内运行，无独立服务、无 Python。

---

## 3. 总体架构

### 3.1 组件视图

| 组件 | 模块 | 职责 | 引入阶段 |
| --- | --- | --- | --- |
| 源码扫描器 | indexer | 按 include/exclude 规则遍历目标工程 | P0 |
| 切块器 | indexer | 把源文件切成带行号的 chunk | P0（定长）/ P1（AST） |
| 嵌入客户端 | indexer | 文本 → 向量（本地 ONNX） | P0 |
| 向量存储 | retrieval | 向量与元数据的存储与相似度检索 | P0（内存）/ P2（Qdrant） |
| 检索器 | retrieval | 召回 TopK，后续扩展为混合召回 | P0 / P2 |
| 提示构造器 | agent | 按 token 预算组装上下文与引用清单 | P0 |
| 问答服务 | agent | 编排"检索 → 生成 → 校验" | P0 |
| 引用校验器 | agent | 校验答案引用是否来自本次检索结果 | P0（基础）/ P3（完整） |
| CLI | web | 命令行入口 | P0 |
| REST/SSE | web | HTTP 接口 | P0（基础）/ P6（SSE） |

### 3.2 Maven 模块与依赖方向

```
code-agent-web ──► code-agent-agent ──► code-agent-retrieval ──► code-agent-indexer ──► code-agent-core
       │                  │                    │                        │                    ▲
       └──────────────────┴────────────────────┴────────────────────────┴────────────────────┘
                                     （所有模块都依赖 core）
```

依赖规则（**强制**，用 Maven Enforcer 或代码评审保证）：

1. `core` 是纯领域层，**禁止**依赖 Spring、LangChain4j、JavaParser 等任何框架。
2. 依赖只能单向：`web → agent → retrieval → indexer → core`，禁止反向依赖与循环。
3. 第三方 SDK（Spring AI、LangChain4j、Qdrant）只允许出现在 `indexer` / `agent` / `web`，不得渗入 `core`。
4. 每个模块对外只暴露必要的 `api` 包，实现类放 `internal` 包并由包级可见性约束。

---

## 4. 模块设计

### 4.1 code-agent-core

**职责**：定义全项目通用的领域模型与配置契约，保持零框架依赖。

```
com.example.codeagent.core
├─ model/
│  ├─ Chunk.java              record：切块单元（含行号与元数据）
│  ├─ ChunkKind.java          enum：FILE / CLASS / METHOD / BLOCK / DOC_SECTION
│  ├─ ScoredChunk.java        record：chunk + 相似度分数
│  ├─ Citation.java           record：引用（path / startLine / endLine / anchor / chunkId）
│  ├─ AskRequest.java         record：问题与检索参数
│  ├─ AskResponse.java        record：答案 + 引用 + 诊断信息
│  ├─ IndexStats.java         record：索引统计（文件数 / 块数 / 耗时）
│  └─ IndexReport.java        record：索引结果与失败文件清单
├─ port/
│  ├─ Chunker.java            接口：源文件 → List<Chunk>
│  ├─ EmbeddingClient.java    接口：文本 → 向量
│  ├─ VectorStore.java        接口：写入 / 检索
│  ├─ Retriever.java          接口：问题 → List<ScoredChunk>
│  └─ AnswerGenerator.java    接口：提示 → 答案文本
└─ exception/
   └─ CodeAgentException.java  受检异常基类 + 若干子类
```

**关键类职责**

| 类 | 职责 | 备注 |
| --- | --- | --- |
| `Chunk` | 承载代码/文档片段与其元数据 | 不可变 record；`id` 稳定（不含行号） |
| `ScoredChunk` | 检索结果载体 | 保留原始分数与来源（dense/sparse/rerank）便于诊断 |
| `Citation` | 引用定位信息 | 与 `Chunk` 的定位字段一致，保证"引用可回填" |
| `AskResponse` | 对外返回结构 | 含 `refused` 标志与 `diagnostics`（耗时、token、命中块数） |
| `VectorStore` | 存储抽象 | P0 内存实现，P2 换 Qdrant 实现，接口不变 |

### 4.2 code-agent-indexer

**职责**：把工程源码变成可检索的向量索引。

```
com.example.codeagent.indexer
├─ scan/
│  ├─ SourceScanner.java            扫描目标目录，按 glob 过滤
│  └─ SourceFile.java               record：path / relativePath / content / hash
├─ chunk/
│  ├─ LineWindowChunker.java        P0：定长行窗口 + overlap
│  └─ JavaAstChunker.java           P1：JavaParser AST 切块
├─ embed/
│  ├─ LocalOnnxEmbeddingClient.java P0：LangChain4j ONNX 本地模型
│  └─ EmbeddingCache.java           P0：文本 hash → 向量，避免重复计算
└─ IndexingService.java             编排：扫描 → 切块 → 嵌入 → 写入存储
```

**设计要点**

1. `LineWindowChunker` 只是 P0 的临时实现，**存在的意义是给 P1 提供对照基线**（P1 要用同一评测集对比 AST 切块的召回提升）。
2. 切块器必须输出**原始行号**，且 `content` 与文件内容逐字一致（禁止 trim、禁止拼接注释），否则引用会错位。
3. `EmbeddingCache` 以 `sha256(text)` 为 key，索引重复文本时零成本；增量索引依赖它。
4. 嵌入是纯计算，不涉及 IO，允许并行（P0 先串行，P6 再并行化）。

### 4.3 code-agent-retrieval

**职责**：把问题变成最相关的若干 chunk。P0 只有向量一路召回。

```
com.example.codeagent.retrieval
├─ store/
│  ├─ InMemoryVectorStore.java      P0：进程内 + 暴力余弦
│  └─ QdrantVectorStore.java        P2：REST 接入，持久化 + HNSW
├─ keyword/
│  ├─ Tokenizer.java                P2：标识符与中文二元组分词
│  ├─ Bm25Index.java                P2：手写 BM25 倒排索引
│  └─ KeywordRetriever.java         P2：关键词路召回（对照实验用）
├─ query/IdentifierExtractor.java   P2：识别"点名了符号"的查询
├─ fusion/RrfFuser.java             P2：手写 RRF 融合
├─ VectorRetriever.java             P0：仅向量召回
└─ HybridRetriever.java             P2：双路召回 + 混合拒答判据
```

**P2 的一处计划调整**：原计划的 `QueryRewriter`（查询改写）没有单独实现。
原因是分词器已经承担了它的职责——标识符整体形式、驼峰拆分、中文二元组都在分词阶段完成，
而 BM25 的 IDF 会自动给稀有标识符高权重。评测数据显示这个组合已经把召回做到 100%，
再加一层改写器属于没有收益的复杂度，因此不做。这是"用数据决定要不要加东西"的一个例子。

**为什么 P0 就手写余弦相似度**：方案文档把"手写检索"列为 P1 任务，但 P0 若不写就无法完成最小闭环。
因此把"手写余弦相似度"提前到 P0（教学目的完全达成），P1 专注 AST 切块与评测集建设。

### 4.4 code-agent-agent

**职责**：编排问答流程，并保证答案有依据。

```
com.example.codeagent.agent
├─ PromptBuilder.java       组装 system prompt + 上下文 + 问题，控制 token 预算
├─ QaService.java           主编排：检索 → 生成 → 校验 → 组装响应
├─ CitationValidator.java   解析答案中的 [n]，校验是否来自本次检索结果
└─ RefusalPolicy.java       判定是否应当拒答（P0：无检索结果即拒答）
```

### 4.5 code-agent-web

**职责**：进程入口与对外接口。

```
com.example.codeagent.web
├─ CodeAgentApplication.java    Spring Boot 启动类
├─ cli/CliRunner.java           ApplicationRunner：--index / --ask
├─ rest/AskController.java      POST /v1/ask
└─ config/AgentConfiguration.java  装配各 port 的实现（P0 手工 new，P1 起转 @Bean）
```

### 4.6 code-agent-eval（P1 引入）

**职责**：golden set 驱动的回归评测，输出指标报告。

```
com.example.codeagent.eval
├─ GoldenCase.java          record：题目 / 期望证据 / 是否应拒答
├─ GoldenSetLoader.java     读取 YAML
├─ MetricsCalculator.java   Recall@k / 引用准确率 / 拒答准确率
└─ RagEvaluationTest.java   JUnit 5 测试入口
```

---

## 5. 关键接口设计

接口全部定义在 `core` 的 `port` 包中，实现分散在各模块。**接口一旦发布，改签名必须同步改文档。**

### 5.1 领域模型

```java
public record SourceFile(
        String path,          // 相对项目根的路径，统一用 / 分隔
        String content,       // 原始全文，禁止加工
        String hash           // sha256(content)
) {}

public enum ChunkKind { FILE, CLASS, METHOD, BLOCK, DOC_SECTION }

public record Chunk(
        String id,                       // 稳定 ID：方法级为 fqn+签名，不含行号
        String path,
        ChunkKind kind,
        String content,                  // 与源文件逐字一致
        int startLine,                   // 1-based 闭区间
        int endLine,
        Map<String, Object> metadata
) {
    public Chunk {
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("非法行号区间: " + startLine + "-" + endLine);
        }
        metadata = Map.copyOf(metadata);
    }

    /** 人类可读定位，直接用于引用展示 */
    public String location() {
        return path + ":" + startLine + "-" + endLine;
    }
}

public record ScoredChunk(Chunk chunk, double score, String source) {}

public record Citation(String path, int startLine, int endLine, String anchor, String chunkId) {
    public String format() {
        return anchor == null || anchor.isBlank()
                ? path + ":" + startLine + "-" + endLine
                : path + "#" + anchor;
    }
}
```

### 5.2 问答模型

```java
public record AskRequest(String question, String project, String ref, int topK, boolean stream) {
    public static AskRequest of(String question) {
        return new AskRequest(question, "default", "working-tree", 8, false);
    }
}

public record Diagnostics(
        long retrievalMs,
        long generationMs,
        int retrievedChunks,
        int usedChunks,
        int promptChars,
        String model
) {}

public record AskResponse(
        String answer,
        List<Citation> citations,
        boolean refused,
        List<ScoredChunk> evidence,
        Diagnostics diagnostics
) {
    public static AskResponse refused(String reason) {
        return new AskResponse(reason, List.of(), true, List.of(), null);
    }
}

public record IndexReport(
        int fileCount, int chunkCount, int skippedFiles,
        long elapsedMs, List<String> failures
) {}
```

### 5.3 端口（Port）

```java
public interface Chunker {
    List<Chunk> chunk(SourceFile file);
}

public interface EmbeddingClient {
    float[] embed(String text);
    int dimension();
    String modelName();
}

public interface VectorStore {
    void add(List<Chunk> chunks, List<float[]> vectors);
    List<ScoredChunk> search(float[] queryVector, int topK);
    int size();
    void clear();
}

public interface Retriever {
    List<ScoredChunk> retrieve(String question, int topK);
}

public interface AnswerGenerator {
    /** 返回模型原始文本；引用校验由上层负责 */
    String generate(String systemPrompt, String userPrompt);
}
```

**为什么要有 `AnswerGenerator`**：把"调 LLM"隔离在接口后面，`QaService` 的单元测试可以用假实现跑通，
不依赖网络与 API Key。这是 P7 评测能自动化运行的前提。

### 5.4 异常约定

```java
public class CodeAgentException extends RuntimeException {
    public CodeAgentException(String message, Throwable cause) { super(message, cause); }
}

public class IndexingException  extends CodeAgentException { ... }  // 扫描/切块/嵌入失败
public class LlmInvocationException extends CodeAgentException { ... }  // LLM 调用失败
public class ConfigurationException extends CodeAgentException { ... }  // 配置缺失或非法
```

原则：基础设施异常（IO、网络）一律包装为上述异常向上抛，**不吞异常**；
已知可降级的场景（单个文件切块失败、LLM 超时）由调用方捕获并记录，继续处理其余部分。

---

## 6. 数据模型

### 6.1 Chunk 元数据字段（P0 必需子集）

| 字段 | 类型 | P0 | 说明 |
| --- | --- | --- | --- |
| `id` | String | ✅ | 稳定 ID；P0 用 `path + "#L" + startLine + "-" + endLine`，P1 起改为 `fqn + 签名` |
| `path` | String | ✅ | 相对路径 |
| `kind` | ChunkKind | ✅ | P0 全部为 `BLOCK`，P1 起区分 CLASS / METHOD |
| `content` | String | ✅ | 原文，逐字一致 |
| `startLine` / `endLine` | int | ✅ | 1-based 闭区间 |
| `metadata["language"]` | String | ✅ | 固定 `java` |
| `metadata["fileHash"]` | String | ✅ | 用于增量索引与陈旧检测 |
| `metadata["commitSha"]` | String | P2 | 缓存失效与版本绑定 |
| `metadata["fqn"]` | String | P1 | AST 切块产出 |
| `metadata["annotations"]` | List | P1 | 注解（Spring 相关问答用得上） |
| `metadata["docRefs"]` | List | P4 | 关联文档锚点 |

### 6.2 内存索引结构（P0）

```
InMemoryVectorStore
├─ List<Chunk> chunks                     顺序表，下标即向量下标
├─ float[][] vectors                      与 chunks 一一对应
└─ volatile boolean normalized            向量是否已 L2 归一化

检索：cosine(q, v) = dot(q, v) / (|q| * |v|)
      若两侧都已归一化，则退化为 dot(q, v)，可省一次除法
```

设计约束：

- 写入后**不可变**（P0 采用写时复制语义：`clear()` 后再批量写入，避免读写竞态）。
- 内存占用估算：向量维度 512、4 字节/维 → 每块约 2 KB；1 万个块约 20 MB，可接受。
- 检索为 O(N) 暴力扫描：1 万块约 5~15 ms，P0 足够；P2 换 Qdrant 后由 HNSW 承担。

### 6.3 后续映射到 Qdrant（P2 预留）

| 本模型 | Qdrant 对应 |
| --- | --- |
| `vectors` | named vector `dense`（cosine 距离） |
| `Chunk.id` | point id（UUID 派生或直接字符串） |
| `Chunk.content` | payload `content` |
| `path` / `kind` / `startLine` / `endLine` | payload 字段，用于过滤与引用回填 |
| `metadata.*` | payload 展开字段 |

---

## 7. 配置项清单

配置文件：`code-agent-web/src/main/resources/application.yml`，可用环境变量覆盖。

| Key | 默认值 | 说明 | 阶段 |
| --- | --- | --- | --- |
| `code-agent.project.name` | `default` | 项目标识 | P0 |
| `code-agent.index.root` | `.` | 被索引工程的根目录 | P0 |
| `code-agent.index.include` | `**/*.java` | 包含模式（glob） | P0 |
| `code-agent.index.exclude` | `**/target/**,**/build/**` | 排除模式 | P0 |
| `code-agent.chunk.strategy` | `ast` | 切块策略：`ast` 或 `line-window` | P1 |
| `code-agent.chunk.window-lines` | `60` | P0 定长窗口行数 | P0 |
| `code-agent.chunk.overlap-lines` | `10` | 窗口重叠行数 | P0 |
| `code-agent.retrieval.top-k` | `8` | 最终注入上下文的块数 | P0 |
| `code-agent.retrieval.min-score` | `0.60` | 低于此分数视为无依据（拒答）；P1 评测集校准值，见 14.4 | P0 |
| `code-agent.retrieval.strategy` | `hybrid` | 召回策略：`hybrid` / `vector` / `keyword` | P2 |
| `code-agent.retrieval.candidate-k` | `30` | 每路召回的候选数（融合前） | P2 |
| `code-agent.store.type` | `memory` | 向量存储：`memory` / `qdrant` | P2 |
| `code-agent.store.qdrant-url` | `http://localhost:6333` | Qdrant 地址 | P2 |
| `code-agent.store.collection` | `code-agent` | Qdrant 集合名 | P2 |
| `code-agent.rerank.enabled` | `false` | 重排开关（P3 实测无收益，默认关闭，见 14.7） | P3 |
| `code-agent.rerank.model-path` | `~/.codeagent/models/.../model_quantized.onnx` | ONNX 重排模型路径 | P3 |
| `code-agent.rerank.tokenizer-path` | `~/.codeagent/models/.../tokenizer.json` | 重排模型的分词器路径 | P3 |
| `code-agent.rerank.candidate-k` | `30` | 送入重排的候选数 | P3 |
| `code-agent.guard.citation-retry` | `1` | 引用校验失败后的重试次数 | P3 |
| `code-agent.retrieval.candidate-k` | `30` | 召回候选数（重排前） | P3 |
| `code-agent.llm.base-url` | `https://api.deepseek.com` | OpenAI 兼容端点 | P0 |
| `code-agent.llm.api-key` | `${LLM_API_KEY:not-configured}` | API Key，**只从环境变量注入** | P0 |
| `code-agent.llm.model` | `deepseek-chat` | 模型名 | P0 |
| `code-agent.llm.temperature` | `0.0` | 必为 0 | P0 |
| `code-agent.llm.max-tokens` | `1200` | 输出上限 | P0 |
| `code-agent.embedding.model` | `bge-small-zh-v15-q` | 本地 ONNX 模型标识 | P0 |
| `code-agent.guard.require-citation` | `true` | 无引用时是否降级 | P0 |
| `code-agent.guard.citation-retry` | `1` | 引用校验失败后的重试次数 | P3 |
| `code-agent.cache.semantic-threshold` | `0.95` | 语义缓存阈值 | P6 |

**密钥纪律**：`api-key` 只允许通过环境变量注入，**禁止**写入 `application.yml`、测试资源或提交到 Git。

---

## 8. 关键流程

### 8.1 索引流程（P0）

```
CliRunner --index
   │
   ▼
IndexingService.index()
   ├─1 SourceScanner.scan(root, include, exclude) ──► List<SourceFile>
   ├─2 for each file: chunker.chunk(file) ─────────► List<Chunk>
   │     └─ 单文件异常 → 记录到 failures，继续下一个文件（不中断整体）
   ├─3 embeddingClient.embed(chunk.content) × N ───► List<float[]>
   ├─4 vectorStore.clear(); vectorStore.add(chunks, vectors)
   └─5 输出 IndexReport（文件数 / 块数 / 跳过数 / 耗时 / 失败清单）
```

### 8.2 问答流程（P0）

```
CliRunner --ask "问题"
   │
   ▼
QaService.ask(AskRequest)
   ├─1 retriever.retrieve(question, topK) ─────────► List<ScoredChunk>   [计时 retrievalMs]
   ├─2 过滤 score < minScore
   ├─3 若结果为空或全部低于阈值 ──► 直接返回 refused（不调用 LLM）
   ├─4 promptBuilder.build(evidence, question) ───► systemPrompt + userPrompt
   ├─5 answerGenerator.generate(...) ─────────────► 原始答案文本      [计时 generationMs]
   ├─6 citationValidator.validate(answer, evidence)
   │     ├─ 全部引用合法 → 组装 citations
   │     └─ 存在非法引用 → 记录告警；重试 0 次（P3 才启用重试）
   └─7 组装 AskResponse（answer / citations / evidence / diagnostics）
```

### 8.3 引用校验流程

```
输入：答案文本、本次证据列表 chunks[1..n]
   ├─ 正则提取答案中所有 [数字]
   ├─ 检查每个编号 n 满足 1 <= n <= chunks.size()
   ├─ 统计被引用的编号集合 referenced
   └─ 输出：
        valid        = 所有编号都在范围内
        citations    = 按 referenced 顺序生成 Citation 列表
        unauthorized = 越界编号列表（用于日志与后续重试）
```

> P0 只做"编号越界"与"无引用"两类校验；编号内容是否**真的支撑**结论由 P3 的
> LLM 复核与人工评测集覆盖。

---

## 9. 错误处理与降级矩阵

| 场景 | 处理策略 | 用户可见结果 |
| --- | --- | --- |
| 目标目录不存在 | 启动时校验，抛 `ConfigurationException` | 明确报错并给出配置项名 |
| 单个文件读取/切块失败 | 记录 `failures`，继续 | 索引报告里列出失败文件 |
| 嵌入模型加载失败 | 抛 `IndexingException` | 提示检查依赖与模型包 |
| 检索结果为空或低于阈值 | 不调用 LLM，直接拒答 | "未在项目代码中找到相关依据" |
| LLM 未配置 API Key | 调用前检查，抛 `ConfigurationException` | 提示设置 `LLM_API_KEY` |
| LLM 超时/限流 | 捕获后重试 1 次；仍失败则降级 | 返回检索到的代码片段摘要（非生成式答案） |
| 答案引用越界 | 记录告警，保留答案但标注异常 | 答案附带"引用校验未通过"提示 |
| 索引为空时提问 | 检测 `vectorStore.size() == 0` | 提示先执行 `--index` |

**降级优先级**：能返回"带证据的片段列表" > 返回"无依据" > 报错。任何情况下都不编造。

---

## 10. 日志与可观测

### 10.1 日志规范

| 级别 | 使用场景 | 示例 |
| --- | --- | --- |
| `ERROR` | 需要人工介入的失败 | 索引整体失败、LLM 持续不可用 |
| `WARN` | 可降级但需关注 | 单文件切块失败、引用校验未通过、LLM 重试 |
| `INFO` | 关键节点 | 索引开始/结束与统计、每次问答的耗时与命中块数 |
| `DEBUG` | 排查用 | 检索分数明细、组装后的 prompt 长度、每个 chunk 的 id |

每次问答结束必须输出一行结构化 INFO 日志，包含：`question长度 / 命中块数 / retrievalMs /
generationMs / promptChars / 是否拒答 / 引用校验结果`。这行日志就是日后做指标与调优的数据源。

**禁止**：日志中输出 API Key、完整 prompt（可能含大段代码）、完整模型回复（仅在 `DEBUG` 且截断）。

### 10.2 指标（P6 接入 Micrometer）

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `codeagent.index.duration` | Timer | 索引耗时 |
| `codeagent.index.chunks` | Gauge | 当前索引块数 |
| `codeagent.ask.duration` | Timer | 端到端问答耗时 |
| `codeagent.ask.retrieval.duration` | Timer | 检索段耗时 |
| `codeagent.ask.generation.duration` | Timer | 生成段耗时 |
| `codeagent.ask.refusal.count` | Counter | 拒答次数 |
| `codeagent.ask.citation.invalid.count` | Counter | 引用校验失败次数 |
| `codeagent.cache.hit.ratio` | Gauge | 缓存命中率（P6） |

---

## 11. 测试策略

### 11.1 测试金字塔

| 层级 | 范围 | 是否依赖网络/模型 | 阶段 |
| --- | --- | --- | --- |
| 单元测试 | 切块器、余弦相似度、引用校验、提示构造 | ❌ 全部用假实现 | P0 |
| 集成测试 | 索引流水线（真实 ONNX 嵌入，不调 LLM） | 仅本地模型 | P0 |
| 端到端测试 | 索引 + 检索 + 假 LLM 生成 | ❌ | P0 |
| 评测回归 | golden set + 真实 LLM | ✅ | P1 起 |

### 11.2 必测用例（P0 验收）

| 用例 | 断言 |
| --- | --- |
| `LineWindowChunkerTest` | 行号连续、无越界、overlap 正确、content 与原文一致 |
| `CosineSimilarityTest` | 正交向量得 0、相同向量得 1、未归一化时结果仍正确 |
| `InMemoryVectorStoreTest` | add 后 size 正确、search 返回按分数降序、topK 截断正确 |
| `CitationValidatorTest` | 越界编号被识别、无引用被识别、合法引用能生成 Citation 列表 |
| `QaServiceTest`（假 LLM） | 无检索结果时拒答且**不调用** LLM；有结果时返回引用 |
| `IndexingServiceIT` | 对 `examples/demo-project` 索引，块数 > 0，且能检索到预期文件 |

### 11.3 测试命名与结构

- 单元测试类名以 `Test` 结尾，集成测试以 `IT` 结尾。
- 用 `@DisplayName` 写中文描述，方法名用英文描述行为：`should_reject_when_no_evidence()`。
- 测试数据放在 `src/test/resources` 或 `examples/`，禁止硬编码绝对路径。

---

## 12. 编码规范与约定

| 项 | 约定 |
| --- | --- |
| 包名 | `com.example.codeagent.<module>.<layer>`（正式开源前替换为真实组织域名） |
| 不可变 | 领域模型一律用 `record`；集合字段在紧凑构造器中 `List.copyOf` |
| 空值 | 对外 API 不接受 `null`；用 `Optional` 仅作返回值，禁止作字段与参数 |
| 异常 | 不吞异常；不用异常控制流程；包装底层异常时保留 cause |
| 依赖注入 | 优先构造器注入；P0 用 `@Configuration` 手工装配，避免 `@Component` 满天飞 |
| 日志 | 用 SLF4J 参数化占位符（`log.info("x={}", x)`），禁止字符串拼接 |
| 命名 | 领域概念用方案文档中的统一译名（chunk / citation / evidence / refusal） |
| 注释 | 公共类与公共方法必须有 Javadoc；解释"为什么"，不复述"做了什么" |
| 禁止事项 | 在 `core` 中引入框架依赖；在业务代码中 `System.out.println`；提交 API Key |

---

## 13. 安全与合规

1. **密钥**：仅通过环境变量注入；`.gitignore` 必须包含 `.env`、`*.local.yml`。
2. **数据外发范围**：发送给 LLM 的内容 = 检索命中的代码/文档片段 + 用户问题。
   全量代码库**永不**外发。
3. **可审计**：每次请求记录命中的 chunk id 列表（不含正文），便于追溯"什么内容被送出"。
4. **只读**：索引过程只读目标工程，绝不写入。
5. **脱敏预留**：P6 增加可配置的敏感信息正则（如内部域名、密钥串），命中后替换后再外发。

---

## 14. 性能设计与预算

### 14.1 P0 实测目标（单机 CPU）

| 环节 | 目标 | 说明 |
| --- | --- | --- |
| 索引 1000 个 Java 文件 | ≈ 70~75 s | 按实测 45 ms/块 × 约 1.6 块/文件 推算；嵌入是瓶颈 |
| 单次嵌入（512 维，短文本） | 实测约 45 ms | ONNX CPU，串行；P6 并行化后预计降到 10 ms 级 |
| 内存检索（1 万块） | 5~15 ms | 暴力余弦 |
| 端到端问答 | < 3 s | 主要是 LLM 首字 |

> 实测数据（2026-09-21）：索引 code-agent 自身 56 个文件 → 92 个切块，耗时 4178 ms。
> 该数字说明**串行嵌入是索引阶段的主要瓶颈**，也是 P6 引入并行嵌入与增量索引的直接动机。

### 14.2 优化路径（按阶段）

| 阶段 | 手段 | 预期收益 |
| --- | --- | --- |
| P0 | 嵌入缓存（文本 hash 去重） | 重复内容零成本，索引提速 20%~50% |
| P2 | 换 Qdrant（HNSW） | 检索从 O(N) 降到近似 O(log N) |
| P3 | 候选集裁剪 + 精排 | 精度提升，上下文更短 |
| P6 | 并行嵌入 + 语义缓存 + 增量索引 | 端到端延迟与重建成本大幅下降 |

### 14.3 P0 实测结果（2026-09-21，样例工程 demo-project）

| 项 | 实测值 |
| --- | --- |
| 索引规模 | 7 个 Java 文件 → 9 个切块 |
| 索引耗时 | 首次 273 ms（含 ONNX 模型加载），缓存预热后 8 ms |
| 嵌入维度 | 512，向量模长 1.0（已归一化） |
| 相关问题 | "下单接口对订单金额做了哪些校验？" top1 = `OrderService.java:101-132`，得分 **0.6869**（该窗口正是 `validateAmount` 方法） |
| 无关问题 | "这个项目使用了哪个版本的 Kubernetes？" 最高分 **0.4912** |
| 测试 | 48 个单元测试 + 3 个集成测试，全部通过 |

**两个必须记住的结论**

1. **默认拒答阈值 0.30 是错的**。无关问题的相似度也能到 0.49，说明该阈值完全起不到过滤作用。
   当时曾暂定为 0.55，随后在 P1 用评测集重新校准到 0.60（见 14.4 节）——
   这条记录保留下来，是为了说明"单条样本不足以定阈值"。
2. **单路向量召回的判别力有限**。8 条命中集中在 0.60~0.69 这个很窄的区间内，相关性区分不明显。
   这正是 P2 引入混合检索（用关键词路补齐标识符匹配）、P3 引入 cross-encoder 精排的直接依据——
   不是"为了用技术而用"，而是单路召回确实不够。

### 14.4 P1 实测结果：AST 切块 vs 定长窗口切块

评测条件：样例工程 7 个文件，10 条 golden set（8 条证据题 + 2 条应拒答题），topK = 8，拒答阈值 0.60。
完整报告见 `code-agent-eval/target/rag-report.md`。

| 策略 | 切块数 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |
| --- | --- | --- | --- | --- |
| **ast** | 23 | **75.0%**（6/8） | 100%（2/2） | **0.0** |
| line-window | 9 | 62.5%（5/8） | 100%（2/2） | 12.6 |

**三个结论**

1. **引用精度是 AST 切块最确定的收益**。AST 的命中块平均多余行数为 0——
   引用范围与真实的方法/类型范围完全一致；定长窗口平均多带 12.6 行无关代码
   （例如金额校验方法本身是 21 行，窗口给出的是 32 行）。
   这直接影响三件事：引用可验证性、上下文 token 预算、以及无关代码带来的幻觉风险。
2. **召回率也有提升，但幅度依赖项目规模**。小工程上差距是 75% vs 62.5%；
   工程越大、方法越长，定长窗口把方法切断的概率越高，差距会进一步拉大。
   单看这个小样例不足以证明"结构性优势"，因此 P7 会在大工程上复测。
3. **阈值必须校准，且校准依据是数据而非直觉**。取 0.55 时无关问题得分 0.584 会漏过拒答；
   取 0.60 后拒答全部正确。代价是定长窗口策略下有一条用例被判为无依据——
   这个取舍符合"宁可拒答也不编造"的设计原则。

**仍未解决、明确留给 P2 的问题**：两条失败用例（q002 幂等、q005 OrderController）
的失败原因相同——问题里出现了 `createOrder`、`OrderController` 这类**逐字存在的标识符**，
但向量检索没有匹配上，反而被语义相近的其他文件挤掉。这是纯向量召回的固有短板，
必须靠关键词/BM25 路召回解决，而不是继续调阈值。

**在真实工程上的切块对比**（用 code-agent 自身作为目标：66 个 Java 文件）：

| 策略 | 切块数 | 平均每文件 | 索引耗时 |
| --- | --- | --- | --- |
| ast | **389** | 5.9 | **3299 ms** |
| line-window | 121 | 1.8 | 4460 ms |

值得注意的两点：AST 的切块数是定长窗口的 3.2 倍（粒度更细，覆盖类、方法、嵌套类型），
但索引反而更快。原因有两个——AST 的类块不含方法体，同一个方法体不会被索引两次；
而定长窗口有 10 行重叠，重复内容也要重新嵌入。**更细的粒度不等于更高的成本**，
这一点在 P6 优化索引性能时同样重要。

### 14.5 P2 实测结果：召回策略对比

评测条件同前（10 条用例、topK=8、切块统一为 AST、向量阈值 0.60）。

| 策略 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |
| --- | --- | --- | --- |
| ast + dense（P1 基线） | 75.0% | 100.0% | 0.0 |
| ast + keyword（仅 BM25） | **100.0%** | **0.0%** | 0.0 |
| **ast + hybrid（RRF 融合）** | **100.0%** | **100.0%** | 0.0 |

**结论一：两路召回的能力互补，单路都有硬伤。**

关键词路把 P1 遗留的两条失败用例（q002 幂等、q005 OrderController）全部救回，
召回率从 75% 直接拉到 100%——因为问题里 `createOrder`、`OrderController`
这些标识符逐字存在于源码中，BM25 一打一个准。

但反过来，纯关键词路一条拒答都没做对（0%）：它总能找到"某个词出现过"的切块。

**结论二：拒答判定不能用 BM25 分数，这是 P2 最重要的教训。**

两条拒答用例的 BM25 分数高得离谱：

| 拒答用例 | BM25 最高分 | 命中的切块 | 为什么会这样 |
| --- | --- | --- | --- |
| q010 项目里使用的是哪个消息队列？ | **14.86** | OrderEventPublisher 的注释 | 注释里逐字写着"消息队列" |
| q009 Spring Boot 版本是多少？ | 2.80 | OrderRequest 的注释 | 注释里的"使用"与问题中的"使用"撞上 |

词法匹配无法区分"提到过"与"就是答案"。这类假阳性不可能靠调阈值消除——
q010 的 14.86 比真正命中的 q002（5.83）还高，任何阈值都会误伤其中一边。

**结论三：把判据从"分数"换成"符号引用"。**

最终采用的规则是：**问题里出现了标识符形态的词（驼峰、下划线、全大写），
且该符号确实存在于索引中，才算关键词路提供了强证据**。

| 拒答用例 | 是否含标识符 | 符号是否在索引中 | 判定 |
| --- | --- | --- | --- |
| q002 createOrder 是如何保证幂等的？ | `createOrder` 是驼峰 | 是 | 有依据 ✅ |
| q005 OrderController 对外暴露了哪些接口？ | `OrderController` 是驼峰 | 是 | 有依据 ✅ |
| q009 Spring Boot 版本是多少？ | `Spring`、`Boot` 是普通单词 | — | 交给向量路判定 → 拒答 ✅ |
| q010 项目里使用的是哪个消息队列？ | 无 ASCII 词 | — | 交给向量路判定 → 拒答 ✅ |

这个判据比调分数稳健得多，原因是它对应了一个真实的领域事实：
**在代码问答场景里，"用户点名了一个真实存在的符号"就是最强证据**，
而不是"某个词在某个地方出现过"。

### 14.6 Qdrant 替换验证

把向量库从进程内实现换成 Qdrant，用同一个问题检索，两边结果如下：

```
qdrant: 0.6855  src/main/java/com/acme/order/OrderService.java:101-121
memory: 0.6855  src/main/java/com/acme/order/OrderService.java:101-121
qdrant: 0.6678  src/main/java/com/acme/order/OrderController.java:34-41
memory: 0.6678  src/main/java/com/acme/order/OrderController.java:34-41
...
```

Top-5 的**顺序、命中的切块、相似度分数完全一致**（分数精确到小数点后四位）。
检索器、提示构造、引用校验、问答编排这些上层代码一行都没有改。
这是"接口先于实现"这一设计原则最直接的验证：换向量库没有引发任何涟漪。

实现上的两个取舍：

1. **用 REST 而不是 gRPC 客户端**。本地单机检索 QPS 很低，用 JDK 内置的
   `HttpClient` 直接调 REST，可以少引入 gRPC + protobuf 一整套依赖，
   出问题时用 curl 就能复现，排查成本最低。
2. **切块 id 映射为确定性 UUID**。Qdrant 的 point id 只接受整数或 UUID，
   而我们的 id 是 `全限定名#签名`。用 MD5 派生 UUID 保证同一切块每次写入得到相同 id，
   重复索引是覆盖而不是产生重复点。

### 14.7 P3 实测结果：重排在本场景下没有收益（否定结论）

P3 实现了完整的重排链路，但评测给出的结论是**不启用**。这一节的记录比结论本身更重要。

| 策略 | 证据召回率 | 拒答准确率 | MRR | 正确证据排第一的用例数 | 单次查询延迟 |
| --- | --- | --- | --- | --- | --- |
| hybrid（P2） | 100.0% | 100.0% | **0.9167** | **7 / 8** | < 1 ms |
| hybrid + rerank（P3） | 100.0% | 100.0% | 0.8750 | 6 / 8 | **4145 ms** |

逐题排名变化（问题的细节都在这里）：

| 用例 | hybrid 排名 | rerank 排名 | 变化 |
| --- | --- | --- | --- |
| q001 下单接口对订单金额做了哪些校验？ | 1 | 2 | **下降** |
| q002 createOrder 是如何保证幂等的？ | 3 | 2 | 提升 |
| q003 ~ q008 | 1 | 1 | 不变 |

**结论：重排既没有提升质量，又严重超预算，因此默认关闭。**

延迟这一条尤其致命：4145 ms/查询（30 条候选，CPU 推理）是整个 P95 预算（2.5 秒）的 1.6 倍。
质量与延迟两条理由中任意一条，都足以让它不进入默认链路。

为什么没效果？三个可能原因，按可能性排序：

1. **模型不是代码重排模型**。bge-reranker-base 是通用中英文重排模型，训练语料里没有代码，
   难以判断"中文问句"与"Java 代码块"之间的匹配关系；
2. **跨语言信号弱**。查询是中文、候选主要是英文标识符组成的代码，
   而 RRF 里的 BM25 一路恰好擅长这种逐字匹配，重排反而把这个信号压平了；
3. **样本量不足**。8 条证据题里只要有一条名次变化，MRR 就会动 0.042，
   这个幅度在统计上说明不了太多。

**保留了什么**：`Reranker` 端口、ONNX 交叉编码器实现、`RerankingRetriever` 装饰器，
以及"有重排 / 无重排"的对比评测能力。
**能测出"这个优化没用"，比默认打开它更有价值**——下次换模型时跑一遍评测就知道该不该启用。

**什么条件下值得重新评估**：

- 评测集扩到 100 条以上（当前样本量不足以支撑结论）；
- 换成代码感知的重排模型（例如在代码检索语料上训练过的 cross-encoder）；
- 只重排 top-10 候选，并把重排分数与 RRF 分数融合，而不是完全替换排序。

### 14.8 P4 实测结果：文档通道与其带来的两个新问题

P4 把 `docs/` 下的 Markdown 纳入索引（按标题切章节、抽取符号引用、做漂移检测），
评测集从 10 条扩到 14 条（新增 4 条以文档为证据的用例）。语料变成 11 个文件（7 Java + 4 Markdown）。

**切块策略的优势被放大**

| 策略 | 切块数 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |
| --- | --- | --- | --- | --- |
| ast + Markdown 章节 | 43 | **75.0%** | 100.0% | **0.0** |
| 定长窗口（全部文件） | 13 | 41.7% | 100.0% | 9.5 |

差距从 P1 的 75% vs 62.5% 扩大到 75% vs 41.7%。原因很直接：
定长窗口把一个章节从中间切断，文档块的"这一节讲什么"信号就丢了；
而按标题切块天然保留了章节语义，引用也能落到锚点。

**召回策略：混合检索仍是唯一同时满足两个目标的方案**

| 策略 | 证据召回率 | 拒答准确率 |
| --- | --- | --- |
| ast + dense | 75.0% | 100.0% |
| ast + keyword | **100.0%** | **0.0%** |
| ast + hybrid | 91.7% | **100.0%** |

**问题一：拒答阈值在中文文档面前接近失效（重要）**

P1 校准的 0.60 在 P4 失效了：拒答用例 q009（"Spring Boot 版本是多少"）拿到 **0.6032**，
刚好越过阈值。重新校准到 0.62 后：

| 指标 | 数值 |
| --- | --- |
| 拒答类最高分（必须低于阈值） | 0.603 |
| 必须通过的证据类最低分 | 0.626 |
| **分离度** | **0.023** |

0.023 的分离度意味着这个阈值已经不可靠了——评测集再变一点就会失效。
根因是：**中文问句对任意中文技术文档的余弦基线本来就接近 0.6**，
所以"用一个绝对相似度阈值判断有没有依据"这套做法，在中文语料上天生脆弱。
英文代码时代它还能工作（问句中文、代码英文，基线天然偏低），加入中文文档后这点优势就没了。

这不是调参能解决的问题。真正需要的是机制层面的改进，留给 P5/P7：

- 用一次轻量的相关性判定（可以让大模型判断"这些片段能否回答问题"，而不是只看分数）；
- 或改为相对判据：比较 top-1 与候选集整体的分布，而不是与固定阈值比较；
- 或利用信号更强的特征（符号命中、标题与问题关键词的重合度）作为拒答的主判据。

**问题二：RRF 融合会把单路排第一的结果挤出结果集**

q012（"为什么用 requestId 做幂等键"）在纯关键词检索里能排进结果，
但融合之后掉出了前 8——因为 RRF 奖励"两路都靠前"，
而它只被关键词路强烈命中，向量路排名靠后，求和后被多个"两路都中等靠前"的块超过。

这是 RRF 的固有特性，不是实现错误。可行的改进方向（同样留给 P5）：

- 问题里命中真实符号时提高关键词路权重；
- 或在融合时保留"任一路排第一"的保底名额。

### 14.9 P5 实测结果：符号图与影响面分析

P5 用 Spoon 做类型解析、JGraphT 存图，把"代码之间的结构关系"变成可查询的资产。

**建图结果（样例工程 7 个 Java 文件）**

| 项 | 数值 |
| --- | --- |
| 节点数 | 52 |
| 边数 | 46 |
| 索引耗时影响 | 从 670 ms 增到 1172 ms（+约 500 ms） |

Spoon 的解析成本不低（约 70 ms/文件），这是 P6 需要考虑的：增量索引时不该重建全图。

**影响面分析的正确性（人工逐条核对过）**

| 查询 | 结论 | 核对 |
| --- | --- | --- |
| `OrderService#createOrder` 的调用方 | `OrderController#create`（1 跳，CALLS） | ✅ 与源码一致 |
| 受影响文件 | `src/main/java/com/acme/order/OrderController.java` | ✅ 正是对外接口所在文件 |
| `OrderService#validateAmount` 的调用方 | `OrderService#validateRequest`，行号 **98** | ✅ 与源码第 98 行一致 |
| `OrderService` 声明的成员 | 6 个（含构造函数） | ✅ 与源码一致 |
| `OrderRepository` 的实现类 | 空 | ✅ 样例里确实没有实现类 |

**三个关键设计决策**

1. **符号键必须归一化**。切块 id 由 JavaParser 生成（参数用简单名），
   符号图由 Spoon 生成（参数用全限定名），两者字面不等。
   不归一化的话，检索命中的切块在图里查不到节点，"引用能落地"这条底线会直接失效。
   归一化规则集中在 {@code SymbolKeys} 一个类里，两个模块共用。
2. **noClasspath 模式下宁可少几条边，也不要错几条边**。真实工程的三方依赖常常不可得，
   完整解析会失败。降级时无法确定目标的调用直接跳过——
   影响面分析给出错误结论，比给不出结论更糟。
3. **输入解析要做结构化后缀匹配**。用户写 `OrderService#createOrder`，
   而图里的符号是 `com.acme.order.OrderService#createOrder(OrderRequest)`——
   直接 endsWith 匹配不上（后面还有参数列表）。
   因此把查询与候选都拆成"类型 + 方法名"两段分别比较，参数一律忽略。

### 14.10 P6 实测结果：增量索引、缓存、流式与可观测

**增量索引：以文件为复用单位，省掉的是切块与嵌入**

| 场景 | 复用文件 | 实际嵌入块 | 耗时 |
| --- | --- | --- | --- |
| 冷启动（首次） | 0 / 11 | 43 | **872 ms** |
| 无任何变更 | 11 / 11 | 0 | **4 ms** |
| 只改一个 Java 文件 | 10 / 11 | 仅该文件的块 | 与变更规模成正比 |

**218 倍**的差距来自"一次嵌入都不做"。这印证了 14.1 节的判断：
嵌入是索引阶段的主要瓶颈（约 45 ms/块）。

三个设计细节值得记住：

1. **复用单位是文件，不是代码块**。改一个方法会让它之后所有块的行号位移，
   按块复用会出现"id 还对得上、行号全错"的情况；
2. **漂移检测每次都要重跑**。它依赖文件之间的比较结果，别的文件变了
   会让本文档的过期状态变化，不能复用；但它只改元数据、不碰内容，
   因此不影响已复用的向量；
3. **符号图只在 Java 文件变化时重建**。Spoon 解析约 70 ms/文件，
   只改 Markdown 时图对象保持同一个实例（测试里用 `isSameAs` 断言）。

**语义缓存：两级结构**

| 层级 | 命中条件 | 成本 | 覆盖场景 |
| --- | --- | --- | --- |
| 精确层 | 归一化后字面相同 | 零 | 用户反复问同一句 |
| 语义层 | 向量余弦 ≥ 0.95 | 一次嵌入（约 45 ms） | 换一种说法问同一件事 |

两个刻意的决策：**拒答结果不缓存**（拒答往往因为表述不好或索引不全，
缓存它会让"换个说法再问"也拿不到答案）；**索引重建时整体清空**
（缓存答案里的引用绑定在旧版本代码上，精细失效一旦漏判就会给出错误引用）。

**流式（SSE）与它的固有代价**

事件顺序固定为 `delta` → `citations` → `done`。引用只能在全部文本产出后给出，
因为校验依赖完整答案。因此流式模式下**不做引用重试**——
重试意味着把第二段回答也推给前端，用户会看到两段互相矛盾的答案。
这个取舍写在代码注释里，避免以后被当成 bug 修。

**可观测：先能看见，再谈接入**

没有引入 Micrometer/actuator（这个构建环境下载依赖很慢，而当前只需要
四个计数器与两个分位数），改为实现了一个只依赖 JDK 的进程内收集器，
接口形态与 Micrometer 对齐（计数器 + 计时器 + 快照），迁移时改动面很小。
暴露在 `GET /v1/metrics`，包含缓存命中率、拒答率、检索与生成的 P50/P95。

**为什么必须看分位数**：一次 5 秒的慢请求在 100 次平均里只体现为多 50 ms，
但用户体验是"偶尔卡得没法用"——平均值会把这个信号完全盖住。

### 14.11 P7 实测结果：评测闭环与它暴露出的短板

P7 把评测集从 14 条扩到 **44 条**（样例工程 14 + code-agent 自身 30），
并给每条用例打上类别标签，报告按类别分组给出通过率。

**为什么要做"自评测"**：样例工程只有 11 个文件、几十个切块，
跨文件综合、文档噪声、拒答边界这些问题在那个规模上根本暴露不出来。
拿自己的代码库当评测对象，语料立刻变成 117 个文件、1003 个切块，
而且语料里混着**归档的旧方案文档**——检索能不能在噪声里找到当前正确的依据，
只有这个规模才看得出来。

**分类指标暴露的短板（self + hybrid，语料指纹绑定在报告里）**

| 类别 | 用例数 | 通过 | 通过率 |
| --- | --- | --- | --- |
| 实现解释 | 10 | 10 | **100.0%** |
| 事实查询 | 6 | 5 | 83.3% |
| 设计决策 | 5 | 4 | 80.0% |
| 学习路径 | 3 | 2 | 66.7% |
| 跨文件综合 | 3 | 1 | **33.3%** |
| 拒答 | 3 | 1 | **33.3%** |

如果只看总分，这个系统看起来"还行"；分类之后立刻看清两件事：
**单点实现问题回答得很好（100%），跨文件综合和拒答很差（33%）**。

**三路策略在真实规模上的表现**

| 策略 | 证据召回率 | 拒答准确率 | MRR |
| --- | --- | --- | --- |
| 仅向量 | 66.7% | 66.7% | 0.4765 |
| 仅关键词 | **96.3%** | **0.0%** | 0.5513 |
| 混合 | 88.9% | 33.3% | 0.5815 |

对比样例工程上的结果（91.7% / 100%），真实规模下两项指标都明显下降。
这不是代码退化，而是**语料变大、问题变难**——这正是样例工程作为评测对象不够用的证明。

**拒答为什么只做到 1/3（P7 最重要的发现）**

两条失败用例的命中分数高得反常：

| 用例 | 命中位置 | 分数 | 为什么会被误判为有依据 |
| --- | --- | --- | --- |
| 生产环境 QPS 峰值是多少？ | 设计文档里讲延迟的段落 | 0.8963 | 都在讨论"性能"，语义邻近 |
| 使用了哪个版本的 Kubernetes？ | **归档的 v1 方案文档** | 0.9122 | 中文长文档之间基线相似度本来就高 |

这不是阈值调得不够的问题。0.91 比很多真正命中的证据分数还高——
**任何阈值都会误伤其中一边**。这与 P4 的结论一致，只是现在有了更大规模的证据。

**结论与下一步**：阈值拒答已经把它的能力用尽了。下一步应该换成
机制层面的判据（让模型判断"这些片段能否回答这个问题"，
而不是只看相似度），这也是学习路线里第 10 个动手实验。
在此之前，拒答准确率 33% 这个水位被**显式写进测试断言**：
变好会通过，变差会失败，不会被悄悄忽略。

**可复现性：语料指纹而不是 commit sha**

设计文档原本要求"评测结果绑定 commit sha"，但本项目不是一个 git 仓库，
而且 commit sha 也只能覆盖被提交的内容。因此改用**语料指纹**：
把所有文件「路径 + 内容哈希」排序后拼接再取 sha256 前 16 位。

它有两个好处：不依赖 git；而且精确刻画"这份指标基于什么语料"——
指纹相同，结果必然可复现；指纹不同，两份报告不可比。
测试同时验证了"只改修改时间（touch）指纹不变、改内容指纹变化"。

### 14.12 拒答改造：两次失败尝试与最终方案

14.8 / 14.11 反复确认了同一件事：绝对相似度阈值在中文语料上已经失效。
P8 之后的第一件事就是把它换成机制判据。这一节记录**两次失败尝试**——
它们的价值不低于成功方案，因为它们把"看起来聪明但没用"的路排除掉了。

**尝试一：稀有词命中（命中任意一个即放行）**

思路：问题里的"常见词"（使用、环境、如何）命中什么都说明不了，
只在"稀有词"（QPS、峰值）出现时才认为有依据。稀有性用 BM25 的文档频率判定，
不需要维护停用词表，而且能随语料自动适应。

实测结果：**30 条用例全部放行，指标没有任何变化**。

原因出在中文二元组上。分词器把"归一化之后点积"切成
`归一/一化/化之/之后/后点/点积`，其中"后点"这种跨词边界的组合
在任何一段代码里都可能偶然出现；一条问题有十几个二元组，
"命中任意一个"等于没有判据。

**尝试二：稀有词覆盖率（至少 40% 的稀有词出现）**

思路：单个命中太弱，改成看覆盖率。

实测结果：**召回从 88.9% 掉到 63.0%，拒答准确率仍然是 33.3%**。
两边的代价都付了，收益却是零。

更关键的是失败原因：问"生产环境的 QPS 峰值是多少"，
`qps`、`峰值`、`生产` 这些"稀有词"**确实在语料里出现过**
（讲 Qdrant 的那一节写过"本地单机检索 QPS 很低"）。
词面匹配永远分不清"提到过"和"回答了问题"——这正是 P2 在
`消息队列` 那条例子上得出的同一结论，只是这次发生在了问答层。

**最终方案：让模型回答"这些证据能不能答"**

两次尝试都失败后，结论很明确：这不是调参问题，而是判据类型的问题。
`RelevanceJudge` 接口 + `LlmRelevanceJudge` 实现：

| 设计点 | 做法 | 理由 |
| --- | --- | --- |
| 判什么 | 只问"证据是否足以回答问题"，要求输出 YES/NO | 任务足够窄，模型不容易发挥 |
| 看多少 | 前 3 条证据，每条截断到 400 字 | 成本比完整生成低一个数量级 |
| 失败怎么办 | 回退到"放行"，而不是拒答 | 判官不能成为新的单点故障：网络抖动时全拒答比偶尔误答更糟 |
| 放在哪 | 第一道阈值闸门之后、生成之前 | 阈值先过滤明显无关的，判官处理"像但不对"的 |

**代价与局限**：每次问答多一次极小的模型调用。流式模式下这一步发生在
首个 token 之前，因此会增加首字延迟；如果将来测得影响明显，
可以改成"边生成边判断，判为 NO 时中断并撤回"——但那需要前端配合，
当前先选择实现简单的方案。

**实测结果**（deepseek-flash，code-agent 自身 127 文件 / 1106 切块，
经 `/v1/ask` 端到端跑，脚本 `scripts/judge-acceptance-test.sh`）：

| 指标 | 仅阈值判据 | **阈值 + LLM 判官** |
| --- | --- | --- |
| 拒答准确率 | 57.1%（4/7） | **100%（7/7）** |
| 证据类作答率 | — | **92.3%（12/13）** |
| 单次判官开销 | 0 | 约 1~2 秒（在首 token 之前） |

七条该拒答的问题全部拒对了，包括之前一直漏过的
"QPS 峰值是多少"（相似度 0.93）与"Kubernetes 版本"（0.90）。
代价是一条误拒：问"默认的拒答阈值是多少"被拒了——它的答案在
`application.yml` 的注释里，而判官看到的五条证据里没有它。
这说明判官的瓶颈已经转移到**检索**：证据没被召回，判官只能如实说"答不上"。

**首版判官的两次调整**（记录在此，避免后来者重走）：

1. 证据条数从 3 提到 5。取 3 条时"余弦相似度怎么算"被误拒——
   命中 `VectorMath` 的证据排在第 4~5 位，判官根本看不到；
2. 明确"源码片段也算证据"。最初的提示词只说"证据需明确包含事实"，
   判官面对一段代码会倾向于说"这没有解释"，导致大量代码类问题被误拒。

### 14.13 配置文件的召回修复

14.12 里那条误拒（"默认的拒答阈值是多少"）追查下去，发现根因不在判官，而在索引：
**配置文件根本没被索引**。默认的 include 只有 `**/*.java` 与 `**/*.md`，
`application.yml` 从未进过语料，所以"默认值是多少"这类问题只能靠
文档里的转述来回答——而文档转述不一定是当前值。

**三步修复**

1. **把配置文件纳入索引**：`**/*.yml`、`**/*.yaml`、`**/*.properties`、`**/pom.xml`。
2. **配置专用切块器**（`ConfigFileChunker`）：按二级配置块切，而不是按行窗口切。
   `code-agent.retrieval`、`code-agent.store` 各自成块，块内保留键值与上方注释，
   键路径写进元数据 `configKey`，切块 id 用 `path#key:<键路径>`
   ——键在文件里移动位置不会改变 id。
3. **配置意图加权**：问题里出现"默认/配置/设置/开启/关闭/是多少"时，
   给带 `configKey` 的块乘 1.3。因为语义检索会把"向量库"匹配到
   `EmbeddingClient`、把"缓存容量"匹配到 `SemanticCache`——那些代码确实在讲这个概念，
   却没有回答问题本身。

**效果**（自评测，46 条用例）

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 混合检索召回率 | 82.1% | **87.2%** |
| MRR | 0.5897 | **0.6282** |
| 端到端证据类作答率（13 条抽样） | 12/13 | **13/13** |
| 端到端拒答准确率 | 7/7 | **7/7** |

**过程中最有价值的一课：配置文件的注释质量直接决定可检索性。**

修复上线后 s040（"默认的向量库是哪一个"）仍然失败。加日志打出判官看到的证据后
才发现：`store:` 的注释写的是"进程内存储（默认，零依赖）"，
**通篇没有"向量"二字**；而问句里最关键的词正是"向量库"。
同理，"默认的拒答阈值是多少"失败，是因为配置里写的是 `min-score` 与"低于该相似度"，
**没有出现"阈值"**。

把两处注释改成"向量存储类型：memory（进程内存储，默认值…）"与
"拒答阈值（min-score）：低于该相似度…"之后，两条问题都变成第 1 名命中。

这不是为了通过测试而改文案——配置键 `type`、`min-score` 本身语义空洞，
注释是唯一能说明"这个键是什么"的地方。**能被检索到的前提，是它被清楚地写出来。**

同一类问题后来又出现了第三次：`embedding:` 块里只有 `model: bge-small-zh-v15-q`，
没有任何中文说明，于是"默认的嵌入模型是什么"被误拒。
补上"本地嵌入模型（JVM 内 ONNX 推理，输出 512 维向量…）"后恢复正常。

**由此得到一条可复用的规则**：配置键名往往是英文缩写或抽象名词，
注释必须用**用户提问时使用的语言**写清"这是什么、默认值是什么意思"。
这不是文档美化，而是索引质量的一部分。

**最终端到端验收**（code-agent 自身，141 文件 / 1170 切块，deepseek-flash）：

| 指标 | 结果 |
| --- | --- |
| 拒答准确率（12 条该拒答） | **12/12（100%）** |
| 证据类作答率（13 条抽样） | **13/13（100%）** |
| 检索层闸门 | `NONE`（有判官时不拒答，职责分离） |

### 14.14 生成文档通道：Javadoc 与 OpenAPI

P4 只接入了手写 Markdown，而"生成的文档"才是更可靠的第二信源——
它由源码或注解直接生成，不容易忘记更新。P9 补齐了两个通道。

**Javadoc（HTML）**

- 用 jsoup 按 {@code section.detail[id]} 切块，一个方法/字段一段，锚点直接取 Javadoc 的 id；
- 类名与包名从页面标题推断，拼成 {@code 全限定名#成员} 写进元数据，与代码切块的 fqn 对齐；
- **必须过滤导航页与交叉引用页**。26 个生成文件里有一半是
  {@code index.html}、{@code allclasses-index.html}、{@code class-use/*} 这类页面，
  它们反复出现类名却不含解释，实测把召回从 85.7% 拉到 74.1%；
  过滤后只保留 21 个有内容的页面。

**OpenAPI（JSON）**

- 按 {@code paths.<路径>.<方法>} 切块，一个接口一段；
- 把 operation 渲染成可读文本：方法 + 路径 + summary + 参数 + 请求体字段 + 响应，
  其中 4xx/5xx 显式标注为"错误码"——用户提问时通常就这么说，而 OpenAPI 原文只有数字；
- operationId 写进元数据，便于和代码里的方法名对应。

**顺带修掉的两个排序问题**

1. **RRF 会埋没"单路第一"**。`RiskClient 的 allowOrder 方法有哪些参数` 在关键词路排第 1
   （BM25 17.49），但等权融合后被挤出前 8。先尝试把关键词权重提到 2 倍，仍然不够——
   最后改成**硬保证**：问题点名了索引中真实存在的符号时，关键词路的第一名强制进入结果集。
   这是 14.8 记录的同一问题的第二次出现，说明"加权重"这类软化手段不够，需要确定性规则。
2. **Javadoc 的 `class-use` 页面是噪声源**，见上。

**结果**（100 条用例，其中新增 6 条生成文档用例）

| 语料 | 严格闸门召回 | 无闸门召回（生产） |
| --- | --- | --- |
| 样例工程（30 用例） | 92.6% | **100%** |
| code-agent 自身（76 用例） | 85.9% | **96.9%** |

### 14.15 索引持久化：重启从 14 秒降到 228 毫秒

在此之前，BM25、符号图与增量缓存全部在内存里，进程一停就全部丢失：
下次启动必须重新嵌入 1274 个块（约 45 ms/块）并重新跑 Spoon 解析。

**存什么、不存什么**

| 内容 | 是否落盘 | 理由 |
| --- | --- | --- |
| 切块（含元数据） | ✅ | 重新切块要重跑解析 |
| 向量 | ✅ | 最贵的部分，45 ms/块 |
| 符号图（节点/边/文件映射） | ✅ | Spoon 约 70 ms/文件 |
| 每个文件的内容哈希 | ✅ | 判断能否复用 |
| BM25 倒排索引 | ❌ | 从切块重建只要一两秒，序列化它会让快照大好几倍 |

**格式与安全**

- 单个 `index-snapshot.json`，向量按小端字节序转 base64（比 JSON 数组小得多，且跨平台稳定）；
- **原子写入**：先写 `.tmp` 再整体替换，崩溃时旧快照仍可用；
- **损坏即降级**：解析失败只记一条警告并走全量索引，绝不让缓存坏了导致服务起不来；
- 快照里含**代码原文**，因此 `.codeagent/` 已加入 `.gitignore`，不能提交。

**实测**（code-agent 自身，169 文件 / 1274 块，两个独立进程）

| 场景 | 复用文件 | 实际嵌入块 | 索引耗时 |
| --- | --- | --- | --- |
| 冷启动 | 0 / 169 | 1274 | 14 014 ms |
| 重启后（加载快照） | **169 / 169** | **0** | **228 ms** |

**61 倍**的差距全部来自"一次嵌入都不做、一次 Spoon 解析都不跑"。
进程总启动时间也从 18 秒降到 7.7 秒，剩下的主要是 JVM 与 ONNX 模型加载。

**边界与后续**：快照按内容哈希判断复用，所以即使只 `touch` 文件（改修改时间不改内容）
也会复用；真正改了内容的文件只会重新切块与嵌入它自己。
快照不做压缩——6.3 MB 的体量换来的是可以直接读的排障体验；
如果将来工程规模上到几十万块，再考虑分片或压缩。

### 14.16 嵌入批处理

索引阶段最后一个串行瓶颈是嵌入：1274 个切块逐条调用模型，
每次都要付出 tokenizer 初始化、张量分配与会话调度的固定开销。

**做法**

- `EmbeddingClient` 增加 `embedAll`，默认实现退化为逐条调用，不破坏已有实现；
- `LocalOnnxEmbeddingClient` 覆写它：先查缓存，未命中的一次性交给模型批量推理；
- `IndexingService` 按 16 条一批送模型；**一批失败时回退为逐条重试**，
  避免"某一块输入异常"连累整批 16 块都进不了索引。

**实测**（code-agent 自身，1280 块冷启动）

| 方式 | 耗时 | 说明 |
| --- | --- | --- |
| 逐条嵌入 | 14 014 ms | 改造前 |
| 批量 16 | **10 820 ms** | 约快 23% |
| 批量 32 | 10 687 ms | 与 16 基本持平，不值得翻倍内存 |

批大小取 16：收益已经拿到，而更大的批只会增大单批内存与失败半径。

**为什么没有做多线程并行**：ONNX Runtime 的会话本身已经是多线程的（intra-op），
再在外层并发调用容易过度订阅 CPU，收益不稳定；
真正的收益来自"减少调用次数"，而不是"同时开更多调用"。

---

## 15. P0 任务分解（WBS）

| # | 任务 | 产出物 | 验收 |
| --- | --- | --- | --- |
| 1 | 建立 Maven 多模块骨架 | 父 POM + 5 个子模块 | `mvn -q compile` 通过 |
| 2 | 领域模型与端口 | core 模块的 record / interface | 单元测试通过 |
| 3 | 源码扫描器 | `SourceScanner` + glob 过滤 | 能列出 demo-project 的 .java 文件 |
| 4 | 定长切块器 | `LineWindowChunker` | `LineWindowChunkerTest` 通过 |
| 5 | 本地 ONNX 嵌入 | `LocalOnnxEmbeddingClient` | 输出 512 维向量，重复文本命中缓存 |
| 6 | 内存向量存储 + 检索 | `InMemoryVectorStore` + `VectorRetriever` | 相似度排序正确 |
| 7 | 提示构造与问答编排 | `PromptBuilder` + `QaService` | 假 LLM 下测试通过 |
| 8 | 引用校验 | `CitationValidator` + `RefusalPolicy` | 越界引用与无依据均被识别 |
| 9 | Spring Boot 装配 + CLI | `CodeAgentApplication` + `CliRunner` | `--index` 与 `--ask` 可用 |
| 10 | REST 接口 | `POST /v1/ask` | 返回 JSON 含 citations |
| 11 | 示例工程与说明 | `examples/demo-project` + README | 新人 10 分钟能跑通 |

**P0 完成定义（DoD）**：`mvn clean verify` 全绿；对 `examples/demo-project` 执行
`--index` 后，`--ask "下单接口对订单金额做了哪些校验？"` 能返回带 `path:startLine-endLine`
引用的答案；未配置 API Key 时给出明确错误提示而不是堆栈。

---

## 16. 后续阶段在 P0 上的预留点

| 阶段 | 需要改动的地方 | 不需要改动的地方 |
| --- | --- | --- |
| P1 | 新增 `JavaAstChunker`，替换 `Chunker` 实现 | `VectorStore` / `Retriever` / `QaService` |
| P2 | 新增 `QdrantVectorStore`、`HybridRetriever`、`RrfFuser` | `Chunker` / `PromptBuilder` |
| P3 | 在 `QaService` 中加入重排段与引用重试 | 领域模型（仅新增字段） |
| P4 | 新增 `DocumentChunker` 与文档扫描分支 | 检索与问答链路 |
| P5 | 新增 `code-agent-graph` 模块与 `ImpactService` | 检索链路 |
| P6 | 在 `IndexingService` 上加缓存与增量逻辑 | 领域模型 |

**结论**：P0 的接口划分必须足够稳，后续阶段以"新增实现 + 少量编排改动"为主。

---

## 17. 依赖清单与版本（2026-09-21 实测确认）

| 依赖 | 版本 | 用途 | 引入阶段 |
| --- | --- | --- | --- |
| `org.springframework.boot:spring-boot-starter-parent` | **3.5.3** | 父 POM | P0 |
| `org.springframework.ai:spring-ai-bom` | **1.1.8** | Spring AI 版本管理 | P0 |
| `org.springframework.ai:spring-ai-starter-model-openai` | 1.1.8 | LLM 调用（OpenAI 兼容） | P0 |
| `dev.langchain4j:langchain4j-core` | **1.20.0** | 嵌入模型接口 | P0 |
| `dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15-q` | **1.20.0-beta30** | 本地 ONNX 嵌入模型（模型 24 MB 打包在 jar 内，无需运行时下载） | P0 |
| `com.github.javaparser:javaparser-symbol-solver-core` | **3.28.2** | AST 切块 | P1 |
| `com.fasterxml.jackson.core:jackson-databind` | 由 Spring Boot 管理 | Qdrant REST 交互的 JSON 处理 | P2 |
| `com.vladsch.flexmark:flexmark-all` | **0.64.8** | Markdown 解析 | P4 |
| `org.jgrapht:jgrapht-core` | **1.5.3** | 符号图 | P5 |
| `dev.langchain4j:langchain4j-onnx-scoring` | 1.20.0-beta30 | cross-encoder 重排 | P3 |
| `org.springframework.boot:spring-boot-starter-test` | 3.5.3 | JUnit 5 / AssertJ | P0 |

**环境基线（本机实测）**

| 项 | 版本 | 备注 |
| --- | --- | --- |
| 机器架构 | arm64（Apple Silicon） | `uname -m` = arm64 |
| Maven | 3.9.16 | Homebrew |
| **项目 JDK（推荐）** | **Temurin 21.0.12.1（arm64）** | 安装于 `~/Library/Java/JavaVirtualMachines/temurin-21-aarch64.jdk`；`JAVA_HOME=$(/usr/libexec/java_home -v 21)` 即可命中 |
| 备用 JDK | 26.0.1（Homebrew，arm64） | 已验证可跑通全部测试，但不在 Spring Boot 3.5 官方支持范围内 |
| 不可用 JDK | 21.0.11（Microsoft，**x86_64**） | ❌ 见下方警告 |

> ✅ P1 期间已按上述方案安装 Temurin 21 arm64，并验证全量构建与测试通过。

> ⚠️ **必须使用原生 arm64 的 JDK，不要用 x86_64 的 JDK 21**
>
> 本机的 JDK 21 是 x86_64 版本（通过 Rosetta 运行）。实测在它上面执行索引会直接失败：
>
> ```
> ai.djl.engine.EngineException: Failed to load Huggingface native library
> Caused by: BgeSmallZhV15QuantizedEmbeddingModel 初始化失败
> ```
>
> 原因是 ONNX Runtime 与 HuggingFace tokenizers 的原生库按 CPU 架构分发，
> 在 Rosetta 环境下无法正确加载。改用原生 arm64 JDK 后一切正常。
>
> **建议**：安装原生 arm64 的 LTS 版本并固定为项目 JDK——
> `brew install openjdk@21`，然后 `export JAVA_HOME=$(brew --prefix openjdk@21)`。
> 当前用 JDK 26 也能跑通，但 JDK 26 不在 Spring Boot 3.5 的官方支持范围内，
> 长期项目建议切到 arm64 的 JDK 21。

---

## 18. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Spring AI 1.1.x 与 Spring Boot 3.5.3 版本不匹配 | 编译或启动失败 | 已在 POM 中统一用两个 BOM 管版本；升级前先跑 `mvn dependency:tree` |
| LangChain4j 嵌入模块版本号带 `-beta` 后缀 | 未来升级可能破坏 API | 只在 `LocalOnnxEmbeddingClient` 一个类里使用其 API，隔离变更面 |
| bge-small-zh 对英文标识符效果一般 | 中文提问召回率不达预期 | P2 引入查询改写与关键词路召回；必要时换多语言模型或起容器跑 bge-m3 |
| 定长切块切断方法 | P0 答案质量差 | 已在方案中标注为已知基线；P1 用 AST 切块并给出对比数据 |
| LLM 供应商接口差异 | 切换模型时失败 | 统一走 OpenAI 兼容模式，`base-url` 可配 |
| 模型首次加载耗时 | 首次问答慢 | P0 记录耗时；P6 加启动预热 |

---

## 19. 附录

### 19.1 术语表

| 术语 | 含义 |
| --- | --- |
| Chunk | 切块，索引的最小单元 |
| Evidence | 本次检索命中的、被注入上下文的块集合 |
| Citation | 引用，指向代码行区间或文档锚点 |
| Refusal | 拒答，无证据时不调用 LLM 直接返回 |
| Golden Set | 评测集，人工标注的标准问答 |
| Hybrid Retrieval | 稠密向量与关键词/稀疏向量的混合召回 |
| RRF | Reciprocal Rank Fusion，多路召回结果的融合算法 |

### 19.2 目标目录结构（P0 完成后）

```
code-agent/
├─ pom.xml
├─ README.md
├─ docs/
│  ├─ code-agent-plan.md
│  ├─ design/dev-design.md
│  └─ appendix-d-agent-frameworks.md
├─ examples/demo-project/            # 索引与评测用的样例工程
├─ code-agent-core/
├─ code-agent-indexer/
├─ code-agent-retrieval/
├─ code-agent-agent/
└─ code-agent-web/
```

### 19.3 P0 验收命令

```bash
# 1. 构建与测试
export JAVA_HOME=/Users/farben/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
mvn -q clean verify

# 2. 索引示例工程
mvn -q -pl code-agent-web spring-boot:run \
  -Dspring-boot.run.arguments="--index --code-agent.index.root=examples/demo-project"

# 3. 提问（需要先配置 LLM_API_KEY）
export LLM_API_KEY=sk-xxx
mvn -q -pl code-agent-web spring-boot:run \
  -Dspring-boot.run.arguments="--ask 下单接口对订单金额做了哪些校验？"
```
