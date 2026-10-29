package com.example.codeagent.retrieval.store;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryVectorStoreTest {

    private final InMemoryVectorStore store = new InMemoryVectorStore();

    private Chunk chunk(String path) {
        return new Chunk(path + "#L1-5", path, ChunkKind.BLOCK, "content", 1, 5, Map.of());
    }

    @Test
    @DisplayName("按相似度降序返回，且相似度最高的排第一")
    void should_return_most_similar_first() {
        store.add(
                List.of(chunk("src/A.java"), chunk("src/B.java"), chunk("src/C.java")),
                List.of(
                        new float[]{1.0f, 0.0f},
                        new float[]{0.0f, 1.0f},
                        new float[]{0.7f, 0.7f}));

        List<ScoredChunk> hits = store.search(new float[]{1.0f, 0.0f}, 3);

        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).chunk().path()).isEqualTo("src/A.java");
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(2).chunk().path()).isEqualTo("src/B.java");
        assertThat(hits.get(0).source()).isEqualTo("dense");
    }

    @Test
    @DisplayName("topK 截断生效")
    void should_truncate_to_top_k() {
        store.add(
                List.of(chunk("src/A.java"), chunk("src/B.java")),
                List.of(new float[]{1.0f, 0.0f}, new float[]{0.5f, 0.5f}));

        assertThat(store.search(new float[]{1.0f, 0.0f}, 1)).hasSize(1);
    }

    @Test
    @DisplayName("topK 非正或存储为空时返回空列表")
    void should_return_empty_for_invalid_input() {
        assertThat(store.search(new float[]{1.0f, 0.0f}, 5)).isEmpty();

        store.add(List.of(chunk("src/A.java")), List.of(new float[]{1.0f, 0.0f}));
        assertThat(store.search(new float[]{1.0f, 0.0f}, 0)).isEmpty();
    }

    @Test
    @DisplayName("切块数与向量数不一致时报错")
    void should_reject_size_mismatch() {
        assertThatThrownBy(() ->
                store.add(List.of(chunk("src/A.java")), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("切块数与向量数不一致");
    }

    @Test
    @DisplayName("维度不一致时报错，避免静默产生错误分数")
    void should_reject_dimension_mismatch() {
        store.add(List.of(chunk("src/A.java")), List.of(new float[]{1.0f, 0.0f}));

        assertThatThrownBy(() ->
                store.add(List.of(chunk("src/B.java")), List.of(new float[]{1.0f, 0.0f, 0.0f})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("向量维度不一致");
    }

    @Test
    @DisplayName("clear 后存储为空")
    void should_clear_all_data() {
        store.add(List.of(chunk("src/A.java")), List.of(new float[]{1.0f, 0.0f}));
        assertThat(store.size()).isEqualTo(1);

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.search(new float[]{1.0f, 0.0f}, 5)).isEmpty();
    }
}
