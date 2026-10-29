# 上游贡献方案：把 AST 切块器推进 LangChain4j / Spring AI

> 这份文档记录一件具体的事：怎么把本项目里已经写好、测过的组件
> 变成上游框架能接受的贡献，而不是停留在"自己项目里能用"。

---

## 1. 为什么这件事值得做

**对个人**：简历上"向 X 框架提交 N 个 PR 并被合并"的含金量，远高于"使用了 X 框架"。
它证明的是能读懂别人的代码组织、遵守别人的规范、接受别人的评审。

**对上游**：LangChain4j 自带的分割器
（`DocumentByParagraphSplitter`、`DocumentBySentenceSplitter`、`DocumentByCharacterSplitter`）
**全部是面向自然语言的**。用在源码上会把方法从中间切断，
而代码的语义单元是类与方法。上游缺的正是"按语言结构切分"这一类实现。

**对本项目**：贡献过程中会顺手把内部实现打磨得更通用
（去掉项目特有的假设、补齐边界处理），反哺自己的代码质量。

---

## 2. 已完成的技术准备

适配器已经写好并测过：

| 文件 | 作用 |
| --- | --- |
| `code-agent-indexer/.../contrib/JavaAstDocumentSplitter.java` | 实现上游 `DocumentSplitter` 接口，用 AST 结构切分 Java 源码 |
| `code-agent-indexer/.../contrib/JavaAstDocumentSplitterTest.java` | 6 个测试，围绕"上游评审会关心什么"来写 |

**它为什么是可被接受的形态**：

1. **只依赖上游自己的接口**（`DocumentSplitter` / `Document` / `TextSegment` / `Metadata`）
   与本项目一个纯 Java 工具类，不引入新依赖；
2. **单一职责**：只做"把一份 Java 源码切成语义段落"，不掺检索、不掺模型；
3. **行为可测**：行号、元数据键、边界输入都有断言覆盖；
4. **容错明确**：语法错误时降级为定长窗口，而不是抛异常——上游最反感的就是
   "一个文件解析失败让整条流水线崩掉"。

元数据键的设计也刻意对齐上游习惯：文件路径沿用 `Document.FILE_NAME`，
其余自定义键（`chunk_kind`、`fqn`、`start_line`、`end_line`）都是上游没有但源码场景必需的。

---

## 3. PR 的内容清单

一份最小、可评审的 PR 通常包含：

```
langchain4j/
└─ langchain4j/src/main/java/dev/langchain4j/data/document/splitter/
   ├─ JavaAstDocumentSplitter.java         ← 参考我们已实现的版本
   └─ （若上游要求）MarkdownDocumentSplitter.java
└─ langchain4j/src/test/java/.../splitter/
   └─ JavaAstDocumentSplitterTest.java     ← 参考我们的测试
```

如果上游不想引入 AST 解析依赖（JavaParser），退一步的方案是：
**提供一个基于正则的轻量版**——只识别类与方法声明行作为切分点，
不追求 100% 准确，但零依赖。这更可能被接受，代价是嵌套类与匿名类场景会退化。

---

## 4. 提交前必须做的事

| 项 | 说明 |
| --- | --- |
| **对齐代码风格** | 上游用 4 空格缩进、`final` 局部变量较少、注释偏英文。**注释要改成英文**——这是最容易忽略却最影响评审印象的一点 |
| **签 DCO / CLA** | LangChain4j 要求 commit 带 `Signed-off-by`；Spring 项目要求签 CLA |
| **跑通上游构建** | 不能只保证自己的模块能编译，要在上游仓库里 `mvn -pl langchain4j test` 全绿 |
| **先开 issue 再提 PR** | 直接提 PR 容易被关；先在 issue 里说明"上游缺源码结构切分，我可以实现"并拿到维护者认可 |
| **PR 描述写清动机与取舍** | 包含：为什么需要、和现有分割器的差别、已知限制（例如不支持 Kotlin）、后续可扩展方向 |

---

## 5. 选哪个上游：LangChain4j 还是 Spring AI

| 维度 | LangChain4j | Spring AI |
| --- | --- | --- |
| 接口契合度 | 高：`DocumentSplitter` 就是为"切分"设计的 | 中：对应概念在 ETL 的 `DocumentTransformer` 里 |
| 贡献门槛 | 较低：模块独立、依赖清晰 | 较高：需要遵循 Spring 的自动配置规范 |
| 目标岗位加分 | Java + AI 应用开发 | Java 后端 / Spring 生态 |
| 当前 open issue 量 | 约 900+ | 约 1500+ |

**建议**：先向 LangChain4j 提。它的接口与本项目已有实现几乎一一对应，
改动面最小；拿到一次成功合并的经验后，再考虑 Spring AI。

---

## 6. 实操步骤（按顺序）

1. **确认上游现状**：在 LangChain4j 仓库里搜索是否已有人提过类似分割器，避免重复劳动；
2. **开 issue**：标题类似 "Add AST-based Java source code splitter"，
   正文里说明现有分割器在源码场景的问题，并附上对比（例如同一段代码被切成什么样）；
3. **等维护者回应**：如果他们说"不需要"，就把精力放在别处（例如文档改进、测试补齐），
   不要硬推；
4. **Fork 并实现**：把 `JavaAstDocumentSplitter` 改写成上游风格（英文注释、包路径、命名），
   并把 `JavaAstChunker` 的核心逻辑内联进去（上游不会接受跨模块依赖）；
5. **本地全绿**：在上游仓库里跑该模块的全部测试；
6. **提 PR**：关联 issue，描述里写清动机、设计、取舍、测试覆盖；
7. **响应评审**：维护者通常会提命名、异常处理、边界条件的意见，认真改；
8. **合并后**：在 README 里加一行"已向上游贡献"，并把 PR 链接写进简历。

---

## 7. 如果 PR 没被接受怎么办

这不是失败。可以退到这些同样有价值的产出：

- **改进上游文档**：在 LangChain4j 的文档里补充"源码场景该怎么切分"的最佳实践，
  这类 PR 的接受率很高；
- **补测试**：给现有分割器补边界用例（空文档、超长段落、全角标点）；
- **写成技术文章**：把"为什么自然语言分割器不适合代码"写成一篇有实测数据的文章，
  这本身就是能力证明；
- **保留在本项目里**：作为项目的一个模块对外提供服务（本项目就是这么做的）。

---

## 8. 与本项目其他部分的关系

这次贡献不是"额外做的一件事"，而是项目自然延伸出来的一环：

| 项目阶段 | 产出的可贡献资产 |
| --- | --- |
| P1 | `JavaAstChunker`：AST 结构化切块（核心资产） |
| P4 | `MarkdownDocChunker`：按标题切分文档，含锚点去重 |
| P4 | `DocSymbolExtractor`：从文档抽取代码符号引用 |
| P5 | `SpoonGraphBuilder`：用 Spoon 构建调用关系图 |
| P8 | `JavaAstDocumentSplitter`：面向上游接口的适配器 |

其中 `JavaAstChunker` 与 `MarkdownDocChunker` 是最成熟的两个——
它们都有完整测试、明确的边界处理、以及真实数据支撑的效果（见设计文档 14.4：
结构化切块把召回从 41.7% 提到 75%，引用多余行数从 9.5 降到 0）。
