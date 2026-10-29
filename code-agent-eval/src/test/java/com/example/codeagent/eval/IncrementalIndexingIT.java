package com.example.codeagent.eval;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.indexer.IndexingResult;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.indexer.chunk.CompositeChunker;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import com.example.codeagent.indexer.chunk.LineWindowChunker;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.retrieval.store.InMemoryVectorStore;
import com.example.codeagent.indexer.snapshot.IndexSnapshotStore;
import com.example.codeagent.indexer.doc.DocumentDriftDetector;
import com.example.codeagent.indexer.graph.SpoonGraphBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量索引的验收测试。
 *
 * <p>用计数的假嵌入模型而不是真模型：这里要验证的是<b>复用逻辑</b>
 * （哪些文件被复用、哪些块被重新嵌入），而不是嵌入质量。
 * 假模型让断言可以精确到"调用了几次"，同时把测试时间从数秒压到毫秒级。
 */
class IncrementalIndexingIT {

    @TempDir
    Path workspace;

    /** 记录调用次数、返回确定性向量的假嵌入模型。 */
    private static final class CountingEmbeddingClient implements EmbeddingClient {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            // 用文本长度与首字符构造一个确定性向量，够用且不依赖任何模型
            float a = text.length();
            float b = text.isEmpty() ? 0 : text.charAt(0);
            double norm = Math.sqrt(a * a + b * b);
            return new float[]{(float) (a / norm), (float) (b / norm)};
        }

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public String modelName() {
            return "counting-stub";
        }
    }

    private final CountingEmbeddingClient embeddingClient = new CountingEmbeddingClient();

    private IndexingService indexingService;

    @BeforeEach
    void setUp() throws IOException {
        // 把样例工程复制到临时目录：增量测试要真的改文件，不能动被测数据
        Path source = Path.of("..", "examples", "demo-project");
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String relative = source.relativize(path).toString();
                if (!relative.endsWith(".java") && !relative.endsWith(".md")) {
                    continue;
                }
                Path target = workspace.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.writeString(target, Files.readString(path, StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8);
            }
        }
        SourceScanner scanner = new SourceScanner(
                List.of("**/*.java", "**/*.md"), List.of("**/target/**"));
        indexingService = new IndexingService(
                scanner,
                new CompositeChunker(JavaAstChunker.withLineWindowFallback(60, 10),
                        new MarkdownDocChunker(), new LineWindowChunker(60, 10)),
                embeddingClient,
                new InMemoryVectorStore());
    }

    @Test
    @DisplayName("首次索引全量嵌入，第二次索引全部复用")
    void should_reuse_everything_when_nothing_changed() {
        IndexReport first = indexingService.index(workspace).report();
        int firstCalls = embeddingClient.calls.get();

        IndexReport second = indexingService.index(workspace).report();

        assertThat(first.reusedFiles()).isZero();
        assertThat(first.embeddedChunks()).isEqualTo(first.chunkCount());
        assertThat(firstCalls).isEqualTo(first.chunkCount());

        // 第二次：一个文件都不用重新切块，一次嵌入调用都不该发生
        assertThat(second.reusedFiles()).isEqualTo(second.fileCount());
        assertThat(second.embeddedChunks()).isZero();
        assertThat(embeddingClient.calls.get()).isEqualTo(firstCalls);
        assertThat(second.chunkCount()).isEqualTo(first.chunkCount());
    }

    @Test
    @DisplayName("只改一个文件时，只重新嵌入这个文件的切块")
    void should_reembed_only_changed_file() throws IOException {
        IndexReport first = indexingService.index(workspace).report();
        int callsAfterFirst = embeddingClient.calls.get();

        // 真的改内容（不是 touch）：内容哈希变了才会触发重新切块
        Path target = workspace.resolve("src/main/java/com/acme/order/OrderController.java");
        Files.writeString(target,
                Files.readString(target, StandardCharsets.UTF_8)
                        + "\n// 新增一行注释，用于触发增量索引\n",
                StandardCharsets.UTF_8);

        IndexReport second = indexingService.index(workspace).report();

        assertThat(second.reusedFiles()).isEqualTo(first.fileCount() - 1);
        assertThat(second.embeddedChunks()).isPositive();
        // 只重新嵌入了变更文件的块，远小于全量
        assertThat(second.embeddedChunks()).isLessThan(first.chunkCount());
        assertThat(embeddingClient.calls.get()).isEqualTo(callsAfterFirst + second.embeddedChunks());
    }

    @Test
    @DisplayName("只改 Markdown 时复用符号图，不重复做 Spoon 解析")
    void should_reuse_symbol_graph_when_only_docs_changed() throws IOException {
        IndexingResult first = indexingService.index(workspace);
        assertThat(first.symbolGraph().symbolCount()).isPositive();

        Path doc = workspace.resolve("docs/api/order-api.md");
        Files.writeString(doc, Files.readString(doc, StandardCharsets.UTF_8) + "\n补充说明。\n",
                StandardCharsets.UTF_8);
        IndexingResult second = indexingService.index(workspace);

        // 图对象是同一个实例，说明没有重建
        assertThat(second.symbolGraph()).isSameAs(first.symbolGraph());
    }

    @Test
    @DisplayName("删除文件后，它的切块不再出现在索引里")
    void should_drop_chunks_of_deleted_file() throws IOException {
        IndexReport first = indexingService.index(workspace).report();
        Path victim = workspace.resolve("docs/guide/getting-started.md");
        Files.delete(victim);

        IndexReport second = indexingService.index(workspace).report();
        IndexingResult result = indexingService.index(workspace);

        assertThat(second.fileCount()).isEqualTo(first.fileCount() - 1);
        assertThat(result.chunks())
                .noneMatch(chunk -> chunk.path().endsWith("getting-started.md"));
    }

    @Test
    @DisplayName("复用后切块内容与全量索引一致")
    void should_produce_same_chunks_after_reuse() {
        List<String> firstIds = indexingService.index(workspace).chunks().stream()
                .map(Chunk::id).sorted().toList();

        List<String> secondIds = indexingService.index(workspace).chunks().stream()
                .map(Chunk::id).sorted().toList();

        assertThat(secondIds).isEqualTo(firstIds);
    }

    @Test
    @DisplayName("语料指纹：内容不变则稳定，任一文件变化则改变")
    void should_track_corpus_fingerprint() throws IOException {
        String first = indexingService.index(workspace).report().corpusFingerprint();
        // 只 touch 不改内容：指纹必须保持稳定（指纹看内容，不看修改时间）
        String second = indexingService.index(workspace).report().corpusFingerprint();
        assertThat(second).isEqualTo(first);

        Path target = workspace.resolve("docs/api/order-api.md");
        Files.writeString(target, Files.readString(target, StandardCharsets.UTF_8) + "\n追加。\n",
                StandardCharsets.UTF_8);
        String third = indexingService.index(workspace).report().corpusFingerprint();

        assertThat(third).isNotEqualTo(first);
    }

    @Test
    @DisplayName("模拟重启：从快照恢复后不再重新嵌入")
    void should_reuse_snapshot_after_restart() {
        Path snapshotDir = workspace.resolve(".codeagent/index");
        IndexSnapshotStore snapshotStore = new IndexSnapshotStore(snapshotDir);

        // 第一次：正常索引并保存快照
        IndexingService first = new IndexingService(
                newScanner(), structuredChunker(), embeddingClient, new InMemoryVectorStore(),
                new DocumentDriftDetector(), new SpoonGraphBuilder(), snapshotStore);
        IndexReport firstReport = first.index(workspace).report();
        int callsAfterFirst = embeddingClient.calls.get();
        assertThat(snapshotStore.exists()).isTrue();

        // 模拟进程重启：全新的服务实例、全新的向量存储，只共享磁盘上的快照
        IndexingService restarted = new IndexingService(
                newScanner(), structuredChunker(), embeddingClient, new InMemoryVectorStore(),
                new DocumentDriftDetector(), new SpoonGraphBuilder(), snapshotStore);
        IndexReport restartedReport = restarted.index(workspace).report();

        // 关键断言：全部文件复用，一次嵌入都没有发生
        assertThat(restartedReport.reusedFiles()).isEqualTo(firstReport.fileCount());
        assertThat(restartedReport.embeddedChunks()).isZero();
        assertThat(embeddingClient.calls.get()).isEqualTo(callsAfterFirst);
        assertThat(restartedReport.chunkCount()).isEqualTo(firstReport.chunkCount());
    }

    private SourceScanner newScanner() {
        return new SourceScanner(
                List.of("**/*.java", "**/*.md"), List.of("**/target/**", "**/build/**"));
    }

    private CompositeChunker structuredChunker() {
        return new CompositeChunker(JavaAstChunker.withLineWindowFallback(60, 10),
                new MarkdownDocChunker(), new LineWindowChunker(60, 10));
    }
}
