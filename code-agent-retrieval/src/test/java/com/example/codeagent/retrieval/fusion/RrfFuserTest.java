package com.example.codeagent.retrieval.fusion;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RrfFuserTest {

    private static ScoredChunk hit(String id, double score, String source) {
        Chunk chunk = new Chunk(id, "src/" + id + ".java", ChunkKind.METHOD,
                "content", 1, 10, Map.of());
        return new ScoredChunk(chunk, score, source);
    }

    private final RrfFuser fuser = RrfFuser.withEqualWeights(2);

    @Test
    @DisplayName("两路都排第一的结果融合后得分最高且归一化为 1")
    void should_rank_common_top_hit_first() {
        List<ScoredChunk> dense = List.of(hit("A", 0.9, "dense"), hit("B", 0.8, "dense"));
        List<ScoredChunk> sparse = List.of(hit("A", 8.0, "sparse"), hit("C", 5.0, "sparse"));

        List<ScoredChunk> fused = fuser.fuse(3, List.of(dense, sparse));

        assertThat(fused.get(0).chunk().id()).isEqualTo("A");
        assertThat(fused.get(0).score()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fused.get(0).source()).isEqualTo("hybrid");
    }

    @Test
    @DisplayName("只被一路召回的结果也能进入融合结果")
    void should_include_single_path_hits() {
        List<ScoredChunk> dense = List.of(hit("A", 0.9, "dense"));
        List<ScoredChunk> sparse = List.of(hit("C", 5.0, "sparse"));

        List<ScoredChunk> fused = fuser.fuse(5, List.of(dense, sparse));

        assertThat(fused).extracting(result -> result.chunk().id())
                .containsExactlyInAnyOrder("A", "C");
    }

    @Test
    @DisplayName("同一 id 出现在两路时只保留一条，分数相加")
    void should_deduplicate_by_chunk_id() {
        List<ScoredChunk> dense = List.of(hit("A", 0.9, "dense"));
        List<ScoredChunk> sparse = List.of(hit("A", 5.0, "sparse"));

        List<ScoredChunk> fused = fuser.fuse(5, List.of(dense, sparse));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("RRF 只依赖排名，与原始分数大小无关")
    void should_depend_only_on_rank() {
        List<ScoredChunk> lowScores = fuser.fuse(2, List.of(
                List.of(hit("A", 0.01, "dense"), hit("B", 0.009, "dense")),
                List.of(hit("A", 0.5, "sparse"))));
        List<ScoredChunk> highScores = fuser.fuse(2, List.of(
                List.of(hit("A", 0.99, "dense"), hit("B", 0.98, "dense")),
                List.of(hit("A", 90.0, "sparse"))));

        assertThat(lowScores.get(0).score()).isCloseTo(highScores.get(0).score(),
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("空输入与非法参数")
    void should_handle_edge_cases() {
        assertThat(fuser.fuse(5, List.of(List.of(), List.of()))).isEmpty();
        assertThatThrownBy(() -> new RrfFuser(0, List.of(1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
