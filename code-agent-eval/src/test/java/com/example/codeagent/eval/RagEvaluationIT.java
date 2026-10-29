package com.example.codeagent.eval;

import com.example.codeagent.core.port.Chunker;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.IndexingResult;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import com.example.codeagent.indexer.chunk.CompositeChunker;
import com.example.codeagent.indexer.chunk.LineWindowChunker;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import com.example.codeagent.indexer.embed.LocalOnnxEmbeddingClient;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.retrieval.HybridRetriever;
import com.example.codeagent.retrieval.RerankingRetriever;
import com.example.codeagent.retrieval.VectorRetriever;
import com.example.codeagent.retrieval.fusion.RrfFuser;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import com.example.codeagent.retrieval.keyword.KeywordRetriever;
import com.example.codeagent.retrieval.rerank.OnnxCrossEncoderReranker;
import com.example.codeagent.retrieval.store.InMemoryVectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索链路评测：P1 的切块策略对比与 P2 的召回策略对比。
 *
 * <p>两部分都只跑检索，不调用大模型，因此可以零成本、稳定地重复执行，
 * 也能在 CI 里作为回归门禁。
 */
class RagEvaluationIT {

    private static final Path DEMO_PROJECT = Path.of("..", "examples", "demo-project");

    private static final Path GOLDEN_SET =
            Path.of("src", "test", "resources", "golden", "demo-project.yml");

    private static final Path REPORT_FILE = Path.of("target", "rag-report.md");

    private static final int TOP_K = 8;

    private static final int CANDIDATE_K = 30;

    /** 重排模型路径；文件不存在时跳过重排对比，不让构建失败。 */
    private static final Path RERANK_MODEL = Path.of(
            System.getProperty("codeagent.rerank.model",
                    System.getenv().getOrDefault("HOME", ".")
                            + "/.codeagent/models/bge-reranker-base/model_quantized.onnx"));

    private static final Path RERANK_TOKENIZER = Path.of(
            System.getProperty("codeagent.rerank.tokenizer",
                    System.getenv().getOrDefault("HOME", ".")
                            + "/.codeagent/models/bge-reranker-base/tokenizer.json"));

    /**
     * 向量路的拒答阈值。
     *
     * <p>P1 在"中文提问 + 英文代码"的语料上校准为 0.60；P4 引入中文文档后重新校准为 0.62：
     * 拒答类最高分从 0.584 升到 0.603，而必须通过的证据类最低分是 0.626——
     * <b>两者只差 0.023</b>。原因是中文问句对任意中文技术文档的余弦基线就有 0.6 左右，
     * 这让"绝对阈值"这种拒答方式在中文语料上已经接近失效。
     * 详细分析与后续方案见设计文档 14.8 节。
     */
    private static final double MIN_SCORE = 0.62;

    /** 关键词路的放行阈值，与线上配置保持一致。 */
    private static final double MIN_KEYWORD_SCORE = 1.0;

    /**
     * 关键词路的拒答阈值，仅用于"纯关键词检索"的对照实验。
     *
     * <p>混合检索不使用这个阈值：它改用"问题是否点名了索引中真实存在的符号"作为判据，
     * 原因见 {@code HybridRetriever} 的类注释——调分数会被注释里的常见词带偏。
     */
    private static final double KEYWORD_ONLY_MIN_SCORE = 0.0;

    /** 嵌入模型加载较慢，所有评测共用同一个实例与缓存。 */
    private final LocalOnnxEmbeddingClient embeddingClient = new LocalOnnxEmbeddingClient();

    private final VectorStore vectorStore = new InMemoryVectorStore();

    private final SourceScanner scanner = new SourceScanner(
            // 与线上默认配置保持一致：源码 + 文档 + 配置文件。
            // 早期这里漏了 yml/pom，导致"样例工程用什么构建工具"这类问题必然无命中
            List.of("**/*.java", "**/*.md", "**/*.yml", "**/*.yaml",
                    "**/*.properties", "**/pom.xml",
                    "**/javadoc/**/*.html", "**/*openapi*.json"),
            List.of("**/target/**", "**/build/**"));

    /** 结构化切块：Java 走 AST，Markdown 按标题分章节。 */
    private static CompositeChunker structuredChunker() {
        return new CompositeChunker(
                JavaAstChunker.withLineWindowFallback(60, 10),
                new MarkdownDocChunker(),
                new LineWindowChunker(60, 10));
    }

    @Test
    @DisplayName("对比切块策略与召回策略，输出评测报告")
    void should_evaluate_all_strategies() throws IOException {
        GoldenSet goldenSet = GoldenSetLoader.load(GOLDEN_SET);
        assertThat(goldenSet.cases()).hasSize(30);

        // 逐题明细统一收集后一起写入报告，便于横向对比每道题在不同策略下的排名
        List<EvaluationReport> detailed = new java.util.ArrayList<>();

        // ---------- P2：同一套 AST 切块下，对比三种召回策略 ----------
        IndexingResult astIndex = indexWith(structuredChunker());
        Bm25Index bm25 = Bm25Index.build(astIndex.chunks());

        // 报告头部要写入语料指纹，因此必须等索引完成后才能拼——
        // 指纹是"这份报告基于什么语料"的唯一凭据，两次结果能不能比较全靠它
        StringBuilder report = new StringBuilder();
        report.append("# 检索评测报告\n\n")
                .append("评测集: ").append(goldenSet.name())
                .append("，用例数: ").append(goldenSet.cases().size())
                .append("，语料指纹: `").append(astIndex.report().corpusFingerprint()).append('`')
                .append("，topK: ").append(TOP_K)
                .append("，向量阈值: ").append(MIN_SCORE)
                .append("，关键词阈值: ").append(MIN_KEYWORD_SCORE)
                .append("，仅关键词对照阈值: ").append(KEYWORD_ONLY_MIN_SCORE)
                .append("\n\n")
                .append("## 召回策略对比（切块策略统一为 ast）\n\n")
                .append("| 策略 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |\n")
                .append("| --- | --- | --- | --- |\n");

        EvaluationReport dense = evaluate("ast + dense",
                new VectorRetriever(embeddingClient, vectorStore, MIN_SCORE), goldenSet);
        EvaluationReport keyword = evaluate("ast + keyword",
                new KeywordRetriever(bm25, KEYWORD_ONLY_MIN_SCORE), goldenSet);
        EvaluationReport hybrid = evaluate("ast + hybrid",
                hybridRetriever(bm25), goldenSet);
        // 生产模式：有判官时检索层不拒答（GateMode.NONE）
        EvaluationReport hybridNoGate = evaluate("ast + hybrid (no gate)",
                new HybridRetriever(embeddingClient, vectorStore, bm25,
                        RrfFuser.withEqualWeights(2), MIN_SCORE, MIN_KEYWORD_SCORE,
                        CANDIDATE_K, HybridRetriever.GateMode.NONE), goldenSet);
        for (EvaluationReport each : List.of(dense, keyword, hybrid, hybridNoGate)) {
            report.append(String.format("| %s | %.1f%% | %.1f%% | %.1f |%n",
                    each.label(),
                    each.evidenceRecall() * 100,
                    each.refusalAccuracy() * 100,
                    each.meanExtraLines()));
        }
        detailed.addAll(List.of(dense, keyword, hybrid, hybridNoGate));
        report.append("\n---\n\n");

        // ---------- P3：重排对比（模型文件存在时才跑） ----------
        report.append("## 重排对比（召回策略统一为 hybrid）\n\n");
        // 重排要跑真实的 ONNX 推理，单轮评测需要数分钟。
        // 它是实验性对比而不是回归门禁，因此默认跳过，用 -Dcodeagent.rerank.eval=true 显式开启。
        boolean runRerankComparison = Boolean.getBoolean("codeagent.rerank.eval");
        if (runRerankComparison && Files.isReadable(RERANK_MODEL)
                && Files.isReadable(RERANK_TOKENIZER)) {
            try (OnnxCrossEncoderReranker reranker = new OnnxCrossEncoderReranker(
                    RERANK_MODEL.toString(), RERANK_TOKENIZER.toString(), "bge-reranker-base-q")) {
                // 先量一轮纯混合检索的耗时基线，再比重排的开销
                long baselineStart = System.nanoTime();
                hybridRetriever(bm25).retrieve("下单接口对订单金额做了哪些校验？", TOP_K);
                long baselineMs = (System.nanoTime() - baselineStart) / 1_000_000;

                long rerankStart = System.nanoTime();
                EvaluationReport reranked = evaluateWithTopK("hybrid + rerank",
                        new RerankingRetriever(hybridRetriever(bm25), reranker, CANDIDATE_K),
                        goldenSet, TOP_K);
                long rerankMs = (System.nanoTime() - rerankStart) / 1_000_000;
                double rerankMsPerQuery = (double) rerankMs / goldenSet.cases().size();
                report.append(String.format(
                        "%n检索基线（单次混合检索，含首轮模型加载）: %d ms；"
                                + "重排 %d 条查询共 %d ms，平均 %.0f ms/查询，"
                                + "候选 %d 条，CPU 推理。%n%n",
                        baselineMs, goldenSet.cases().size(), rerankMs,
                        rerankMsPerQuery, CANDIDATE_K));
                // 重排的另一个价值：用更少的上下文块拿到同样的召回，直接省 token
                EvaluationReport rerankedTight = evaluateWithTopK("hybrid + rerank (topK=3)",
                        new RerankingRetriever(hybridRetriever(bm25), reranker, CANDIDATE_K),
                        goldenSet, 3);
                EvaluationReport hybridTight = evaluateWithTopK("hybrid (topK=3)",
                        hybridRetriever(bm25), goldenSet, 3);
                report.append("| 策略 | 召回率 | 拒答准确率 | MRR | Top1 命中 |\n")
                        .append("| --- | --- | --- | --- | --- |\n");
                for (EvaluationReport each : List.of(hybrid, hybridTight, reranked, rerankedTight)) {
                    report.append(String.format("| %s | %.1f%% | %.1f%% | %.4f | %d |%n",
                            each.label(),
                            each.evidenceRecall() * 100,
                            each.refusalAccuracy() * 100,
                            each.meanReciprocalRank(),
                            each.topOneCount()));
                }
                detailed.addAll(List.of(reranked, rerankedTight, hybridTight));
                // 逐题对比重排前后的排名变化，这是判断重排是否真的有效的直接依据
                report.append("\n### 逐题排名变化（hybrid → hybrid + rerank）\n\n")
                        .append("| 用例 | hybrid 排名 | rerank 排名 | 变化 |\n")
                        .append("| --- | --- | --- | --- |\n");
                for (int i = 0; i < hybrid.results().size(); i++) {
                    int before = hybrid.results().get(i).rank();
                    int after = reranked.results().get(i).rank();
                    String delta = before == after ? "不变"
                            : (after > 0 && (before < 0 || after < before) ? "**提升**" : "**下降**");
                    report.append(String.format("| %s | %s | %s | %s |%n",
                            hybrid.results().get(i).goldenCase().id(),
                            before < 0 ? "-" : before,
                            after < 0 ? "-" : after,
                            delta));
                }
                report.append("\n");
                // 重排臂是实验性的：断言它"一定更好"或"一定更差"都会把不稳定的期望固化进测试。
                // 这里只保证它不影响拒答（拒答判定在被装饰的检索器内部，与重排无关），
                // 其余数值写进报告供人判断，见设计文档 14.7 节。
                assertThat(reranked.refusalAccuracy()).isEqualTo(1.0);
            }
        } else if (!runRerankComparison) {
            report.append("> 重排对比已跳过（默认不跑，耗时约数分钟）。\n")
                    .append("> 开启方式：`mvn verify -Dit.test=RagEvaluationIT "
                            + "-Dfailsafe.failIfNoSpecifiedTests=false "
                            + "-Dcodeagent.rerank.eval=true`\n\n");
        } else {
            report.append("> 未找到重排模型，跳过重排对比。\n")
                    .append("> 先运行 `scripts/download-reranker-model.sh` 再重试。\n\n");
        }
        report.append("---\n\n");

        // ---------- P1：对比切块策略（召回统一为 dense，保持可比） ----------
        IndexingResult windowIndex = indexWith(new LineWindowChunker(60, 10));
        EvaluationReport windowDense = evaluate("line-window + dense",
                new VectorRetriever(embeddingClient, vectorStore, MIN_SCORE), goldenSet);
        report.append("## 切块策略对比（召回策略统一为 dense）\n\n")
                .append("| 策略 | 切块数 | 证据召回率 | 拒答准确率 | 命中块平均多余行数 |\n")
                .append("| --- | --- | --- | --- | --- |\n")
                .append(String.format("| ast | %d | %.1f%% | %.1f%% | %.1f |%n",
                        astIndex.chunks().size(),
                        dense.evidenceRecall() * 100,
                        dense.refusalAccuracy() * 100,
                        dense.meanExtraLines()))
                .append(String.format("| line-window | %d | %.1f%% | %.1f%% | %.1f |%n",
                        windowIndex.chunks().size(),
                        windowDense.evidenceRecall() * 100,
                        windowDense.refusalAccuracy() * 100,
                        windowDense.meanExtraLines()))
                .append("\n---\n\n");

        detailed.add(windowDense);
        for (EvaluationReport each : detailed) {
            report.append(each.toMarkdown()).append("\n---\n\n");
        }
        Files.writeString(REPORT_FILE, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // P1 结论：AST 的引用范围比定长窗口精确
        assertThat(dense.meanExtraLines()).isLessThan(windowDense.meanExtraLines());
        // 两套切块都不应放过无关问题
        assertThat(dense.refusalAccuracy()).isEqualTo(1.0);
        assertThat(windowDense.refusalAccuracy()).isEqualTo(1.0);

        // P2 核心结论一：单路召回都有硬伤——向量漏掉标识符查询，关键词无法拒答
        assertThat(dense.evidenceRecall()).isLessThan(1.0);
        assertThat(keyword.refusalAccuracy()).isLessThan(1.0);

        // P2 核心结论二：带严格闸门的混合检索，拒答可靠（95%+ 召回 + 100% 拒答）
        assertThat(hybrid.evidenceRecall()).isGreaterThanOrEqualTo(0.85);
        assertThat(hybrid.refusalAccuracy()).isEqualTo(1.0);
        assertThat(hybrid.meanExtraLines()).isEqualTo(0.0);

        // 生产模式：有 LLM 判官时检索层不再拒答，召回显著更高（拒答交给判官）。
        // 这个数字才是线上实际拿到的召回，严格闸门的数字是它的下界。
        assertThat(hybridNoGate.evidenceRecall()).isGreaterThanOrEqualTo(0.9);
    }

    private HybridRetriever hybridRetriever(Bm25Index bm25) {
        return new HybridRetriever(
                embeddingClient,
                vectorStore,
                bm25,
                RrfFuser.withEqualWeights(2),
                MIN_SCORE,
                MIN_KEYWORD_SCORE,
                CANDIDATE_K);
    }

    private EvaluationReport evaluate(String label, Retriever retriever, GoldenSet goldenSet) {
        return evaluateWithTopK(label, retriever, goldenSet, TOP_K);
    }

    private EvaluationReport evaluateWithTopK(String label,
                                              Retriever retriever,
                                              GoldenSet goldenSet,
                                              int topK) {
        EvaluationReport report = new RagEvaluator(retriever, topK).evaluate(label, goldenSet);
        System.out.printf("[%s] 召回=%.1f%% 拒答=%.1f%% 多余行=%.1f%n",
                label,
                report.evidenceRecall() * 100,
                report.refusalAccuracy() * 100,
                report.meanExtraLines());
        return report;
    }

    private IndexingResult indexWith(Chunker chunker) {
        IndexingService indexingService =
                new IndexingService(scanner, chunker, embeddingClient, vectorStore);
        IndexingResult result = indexingService.index(DEMO_PROJECT);
        System.out.printf("[index] 切块数=%d%n", result.chunks().size());
        return result;
    }
}
