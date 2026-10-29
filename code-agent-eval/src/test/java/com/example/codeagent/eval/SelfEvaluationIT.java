package com.example.codeagent.eval;

import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.IndexingResult;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.indexer.chunk.CompositeChunker;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import com.example.codeagent.indexer.chunk.LineWindowChunker;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import com.example.codeagent.indexer.embed.LocalOnnxEmbeddingClient;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.retrieval.HybridRetriever;
import com.example.codeagent.retrieval.VectorRetriever;
import com.example.codeagent.retrieval.fusion.RrfFuser;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import com.example.codeagent.retrieval.keyword.KeywordRetriever;
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
 * 把 code-agent 自己当作评测对象。
 *
 * <h2>为什么要做"自评测"</h2>
 *
 * 样例工程只有 11 个文件、几十个切块，很多问题（跨文件综合、文档过期、
 * 检索被无关内容干扰）在那个规模上根本暴露不出来。
 * 用自己的代码库做评测，语料立刻变成 100+ 文件、数百个切块，
 * 而且问题都是真实的——"拒答阈值是多少""为什么选 Spoon"这类问题
 * 正是使用者会问的。
 *
 * <p>它同时是一个**真实的噪声环境**：语料里混着归档的旧方案文档、
 * 学习路线、README，各处对同一个概念可能有不同年代的表述。
 * 检索能不能在噪声里找到当前正确的依据，在这里才看得出来。
 */
class SelfEvaluationIT {

    private static final Path PROJECT_ROOT = Path.of("..");

    private static final Path GOLDEN_SET =
            Path.of("src", "test", "resources", "golden", "code-agent-self.yml");

    private static final Path REPORT_FILE = Path.of("target", "self-rag-report.md");

    private static final int TOP_K = 8;

    private static final int CANDIDATE_K = 30;

    /** 与线上一致：P4 用 14 条用例校准出的阈值。 */
    private static final double MIN_SCORE = 0.62;

    /** 关键词路的放行阈值，与线上配置保持一致。 */
    private static final double MIN_KEYWORD_SCORE = 1.0;

    private final LocalOnnxEmbeddingClient embeddingClient = new LocalOnnxEmbeddingClient();

    private final VectorStore vectorStore = new InMemoryVectorStore();

    private final SourceScanner scanner = new SourceScanner(
            List.of("**/*.java", "**/*.md", "**/*.yml", "**/pom.xml",
                    "**/javadoc/**/*.html", "**/*openapi*.json"),
            // 必须排除测试源码：评测问题会逐字出现在测试里（例如
            // LlmRelevanceJudgeTest 里写着"QPS 峰值是多少"），
            // 把它们纳入语料会让"无关问题"命中自己的测试用例，
            // 评测结果被人为扭曲。真实产品可以索引测试代码，评测语料不行。
            List.of("**/target/**", "**/build/**", "**/src/test/**"));

    @Test
    @DisplayName("在 code-agent 自身语料上评测三路召回策略")
    void should_evaluate_on_itself() throws IOException {
        GoldenSet goldenSet = GoldenSetLoader.load(GOLDEN_SET);
        assertThat(goldenSet.cases()).hasSize(76);

        IndexingResult indexResult = indexSelf();
        Bm25Index bm25 = Bm25Index.build(indexResult.chunks());

        EvaluationReport dense = evaluate("self + dense",
                new VectorRetriever(embeddingClient, vectorStore, MIN_SCORE), goldenSet);
        EvaluationReport keyword = evaluate("self + keyword",
                new KeywordRetriever(bm25, 0.0), goldenSet);
        EvaluationReport hybrid = evaluate("self + hybrid",
                new HybridRetriever(embeddingClient, vectorStore, bm25,
                        RrfFuser.withEqualWeights(2), MIN_SCORE, MIN_KEYWORD_SCORE,
                        CANDIDATE_K), goldenSet);
        // 生产模式：有 LLM 判官时检索层不再拒答（GateMode.NONE）。
        // 这里单独测一次，量化"把拒答交给判官"能换回多少召回
        EvaluationReport hybridNoGate = evaluate("self + hybrid (no gate)",
                new HybridRetriever(embeddingClient, vectorStore, bm25,
                        RrfFuser.withEqualWeights(2), MIN_SCORE, MIN_KEYWORD_SCORE,
                        CANDIDATE_K, HybridRetriever.GateMode.NONE), goldenSet);

        StringBuilder report = new StringBuilder();
        report.append("# 自评测报告（语料：code-agent 自身）\n\n")
                .append("用例数: ").append(goldenSet.cases().size())
                .append("，切块数: ").append(indexResult.chunks().size())
                .append("，语料指纹: `").append(indexResult.report().corpusFingerprint()).append('`')
                .append("，topK: ").append(TOP_K)
                .append("，拒答阈值: ").append(MIN_SCORE)
                .append("\n\n## 召回策略对比\n\n")
                .append("| 策略 | 证据召回率 | 拒答准确率 | MRR | 命中块平均多余行数 |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        for (EvaluationReport each : List.of(dense, keyword, hybrid, hybridNoGate)) {
            report.append(String.format("| %s | %.1f%% | %.1f%% | %.4f | %.1f |%n",
                    each.label(), each.evidenceRecall() * 100, each.refusalAccuracy() * 100,
                    each.meanReciprocalRank(), each.meanExtraLines()));
        }
        report.append("\n---\n\n");
        for (EvaluationReport each : List.of(dense, keyword, hybrid, hybridNoGate)) {
            report.append(each.toMarkdown()).append("\n---\n\n");
        }
        Files.writeString(REPORT_FILE, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        // 这批断言是"回归门禁"：阈值取自首次运行的实测结果，留一点余量。
        // 后续任何改动导致明显下降都会在这里被拦住。
        assertThat(hybrid.evidenceRecall()).isGreaterThan(0.7);
        // 混合检索至少要强于纯向量——否则说明融合逻辑出了问题
        assertThat(hybrid.evidenceRecall()).isGreaterThanOrEqualTo(dense.evidenceRecall());
        // 去掉检索层闸门后召回应该明显更高——多出来的部分正是"交给判官判断"的收益
        assertThat(hybridNoGate.evidenceRecall())
                .isGreaterThanOrEqualTo(hybrid.evidenceRecall());

        // 拒答在真实规模上只做到约 4/7。已知原因是"绝对阈值"在中文语料上失效，
        // 而 LLM 判官需要 API Key 才能在这里量化（见设计文档 14.12）。
        // 这里不断言一个漂亮数字，而是把当前水位锁住：变好会通过，变差会失败。
        assertThat(hybrid.refusalAccuracy()).isGreaterThanOrEqualTo(0.5);
    }

    private IndexingResult indexSelf() {
        IndexingService indexingService = new IndexingService(
                scanner,
                new CompositeChunker(JavaAstChunker.withLineWindowFallback(60, 10),
                        new MarkdownDocChunker(), new LineWindowChunker(60, 10)),
                embeddingClient,
                vectorStore);
        IndexingResult result = indexingService.index(PROJECT_ROOT);
        System.out.printf("[self] 索引: 文件=%d, 切块=%d, 嵌入块=%d, 耗时=%d ms%n",
                result.report().fileCount(), result.report().chunkCount(),
                result.report().embeddedChunks(), result.report().elapsedMs());
        return result;
    }

    private EvaluationReport evaluate(String label, Retriever retriever, GoldenSet goldenSet) {
        EvaluationReport report = new RagEvaluator(retriever, TOP_K).evaluate(label, goldenSet);
        System.out.printf("[%s] 召回=%.1f%% 拒答=%.1f%% MRR=%.4f%n",
                label, report.evidenceRecall() * 100, report.refusalAccuracy() * 100,
                report.meanReciprocalRank());
        return report;
    }

}
