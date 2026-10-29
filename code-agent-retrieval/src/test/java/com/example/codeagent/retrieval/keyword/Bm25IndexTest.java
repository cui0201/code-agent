package com.example.codeagent.retrieval.keyword;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25IndexTest {

    private static Chunk chunk(String path, int startLine, int endLine, String content) {
        return new Chunk(path + "#L" + startLine, path, ChunkKind.METHOD, content,
                startLine, endLine, Map.of("language", "java"));
    }

    private final Bm25Index index = Bm25Index.build(List.of(
            chunk("src/OrderService.java", 54, 78,
                    "public Order createOrder(OrderRequest request) { validateRequest(request); }"),
            chunk("src/OrderRepository.java", 10, 20,
                    "Optional<Order> findByRequestId(String requestId); Order save(Order order);"),
            chunk("src/PaymentService.java", 1, 30,
                    "public void pay(BigDecimal amount) { gateway.charge(amount); }")));

    @Test
    @DisplayName("稀有标识符能精确定位到对应文件")
    void should_locate_by_rare_identifier() {
        List<ScoredChunk> hits = index.search("createOrder", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk().path()).isEqualTo("src/OrderService.java");
        assertThat(hits.get(0).source()).isEqualTo("sparse");
    }

    @Test
    @DisplayName("方法名查询命中包含该方法的切块")
    void should_match_method_name() {
        List<ScoredChunk> hits = index.search("findByRequestId", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).chunk().path()).isEqualTo("src/OrderRepository.java");
    }

    @Test
    @DisplayName("完全没有交集的查询返回空列表")
    void should_return_empty_when_no_term_matches() {
        assertThat(index.search("kubernetes deployment yaml", 3)).isEmpty();
    }

    @Test
    @DisplayName("BM25 分数为正且随匹配强度单调")
    void should_produce_positive_scores() {
        List<ScoredChunk> hits = index.search("createOrder", 3);

        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isGreaterThan(0.0));
        // 命中稀有词元的切块分数应明显高于仅命中常见词的切块
        double top = hits.get(0).score();
        assertThat(top).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("空索引与空查询都被安全处理")
    void should_handle_empty_inputs() {
        Bm25Index empty = Bm25Index.build(List.of());

        assertThat(empty.search("anything", 5)).isEmpty();
        assertThat(index.search("", 5)).isEmpty();
        assertThat(index.search("createOrder", 0)).isEmpty();
    }

    @Test
    @DisplayName("索引规模与词表规模可观测")
    void should_expose_stats() {
        assertThat(index.size()).isEqualTo(3);
        assertThat(index.termCount()).isGreaterThan(5);
    }
}
