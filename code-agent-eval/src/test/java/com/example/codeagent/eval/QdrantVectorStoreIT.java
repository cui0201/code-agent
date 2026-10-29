package com.example.codeagent.eval;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import com.example.codeagent.indexer.chunk.CompositeChunker;
import com.example.codeagent.indexer.embed.LocalOnnxEmbeddingClient;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import com.example.codeagent.indexer.chunk.LineWindowChunker;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.retrieval.store.InMemoryVectorStore;
import com.example.codeagent.retrieval.store.QdrantVectorStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qdrant 向量存储的验收测试。
 *
 * <p>最有价值的断言不是"能查到"，而是<b>与内存实现结果一致</b>：
 * 只有当"替换向量库"不改变检索结果时，才算真正兑现了
 * "接口不变、实现可替换"这个架构承诺。
 *
 * <p>Qdrant 未启动时测试会自动跳过（用 {@link Assumptions}），
 * 这样在没有 Docker 的环境里不会阻塞构建。
 */
class QdrantVectorStoreIT {

    private static final Path DEMO_PROJECT = Path.of("..", "examples", "demo-project");

    private static final String QDRANT_URL =
            System.getProperty("codeagent.qdrant.url", "http://localhost:6333");

    private static final String COLLECTION = "code-agent-it";

    private static final String QUESTION = "下单接口对订单金额做了哪些校验？";

    private final LocalOnnxEmbeddingClient embeddingClient = new LocalOnnxEmbeddingClient();

    @Test
    @DisplayName("Qdrant 上的索引与检索结果应与内存实现一致")
    void should_match_in_memory_results() {
        QdrantVectorStore qdrant = new QdrantVectorStore(
                QDRANT_URL, COLLECTION, embeddingClient.dimension());
        Assumptions.assumeTrue(qdrant.isReachable(),
                "Qdrant 未运行（" + QDRANT_URL + "），跳过该测试");

        qdrant.clear();
        IndexStore qdrantResult = indexInto(qdrant);
        List<ScoredChunk> qdrantHits = qdrant.search(embeddingClient.embed(QUESTION), 5);

        InMemoryVectorStore memory = new InMemoryVectorStore();
        IndexStore memoryResult = indexInto(memory);
        List<ScoredChunk> memoryHits = memory.search(embeddingClient.embed(QUESTION), 5);

        System.out.printf("Qdrant 切块数=%d，内存切块数=%d%n",
                qdrantResult.chunkCount(), memoryResult.chunkCount());
        qdrantHits.forEach(hit -> System.out.printf("  qdrant: %.4f %s%n",
                hit.score(), hit.chunk().location()));
        memoryHits.forEach(hit -> System.out.printf("  memory: %.4f %s%n",
                hit.score(), hit.chunk().location()));

        assertThat(qdrant.size()).isEqualTo(qdrantResult.chunkCount());
        assertThat(qdrantHits).isNotEmpty();

        // 两套实现的 top-1 必须是同一个切块
        assertThat(qdrantHits.get(0).chunk().id())
                .isEqualTo(memoryHits.get(0).chunk().id());

        // 余弦相似度是由同一份向量算出的，两边分数应当非常接近
        assertThat(qdrantHits.get(0).score())
                .isCloseTo(memoryHits.get(0).score(),
                        org.assertj.core.data.Offset.offset(0.01));

        // 引用定位信息必须完整回填，否则引用会失效
        ScoredChunk top = qdrantHits.get(0);
        assertThat(top.chunk().path()).isNotBlank();
        assertThat(top.chunk().startLine()).isPositive();
        assertThat(top.chunk().endLine()).isGreaterThanOrEqualTo(top.chunk().startLine());
        assertThat(top.chunk().content()).isNotBlank();
    }

    private IndexStore indexInto(VectorStore store) {
        SourceScanner scanner = new SourceScanner(
                List.of("**/*.java", "**/*.md"), List.of("**/target/**", "**/build/**"));
        IndexingService indexingService = new IndexingService(
                scanner,
                new CompositeChunker(JavaAstChunker.withLineWindowFallback(60, 10),
                        new MarkdownDocChunker(), new LineWindowChunker(60, 10)),
                embeddingClient, store);
        int chunkCount = indexingService.index(DEMO_PROJECT).chunks().size();
        return new IndexStore(chunkCount);
    }

    private record IndexStore(int chunkCount) {
    }
}
