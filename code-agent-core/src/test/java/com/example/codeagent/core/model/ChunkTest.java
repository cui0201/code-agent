package com.example.codeagent.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkTest {

    @Test
    @DisplayName("location 输出 path:startLine-endLine")
    void should_format_location() {
        Chunk chunk = new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 10, 20, Map.of());

        assertThat(chunk.location()).isEqualTo("src/A.java:10-20");
        assertThat(chunk.lineCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("非法行号区间被拒绝")
    void should_reject_invalid_line_range() {
        assertThatThrownBy(() ->
                new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 5, 4, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法行号区间");
    }

    @Test
    @DisplayName("起始行必须从 1 开始")
    void should_reject_zero_start_line() {
        assertThatThrownBy(() ->
                new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 0, 4, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("元数据是不可变副本，外部修改不影响已建对象")
    void should_defensively_copy_metadata() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("language", "java");

        Chunk chunk = new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 1, 2, mutable);
        mutable.put("language", "kotlin");

        assertThat(chunk.metadata()).containsEntry("language", "java");
    }

    @Test
    @DisplayName("metadata 为 null 时退化为空 Map")
    void should_default_null_metadata_to_empty_map() {
        Chunk chunk = new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 1, 2, null);

        assertThat(chunk.metadata()).isEmpty();
    }

    @Test
    @DisplayName("ScoredChunk 按分数降序排列")
    void should_sort_scored_chunks_descending() {
        Chunk chunk = new Chunk("id-1", "src/A.java", ChunkKind.BLOCK, "code", 1, 2, Map.of());
        ScoredChunk low = new ScoredChunk(chunk, 0.2, "dense");
        ScoredChunk high = new ScoredChunk(chunk, 0.9, "dense");

        // ScoredChunk 的自然序为分数降序，因此高分的比较结果为负数
        assertThat(high.compareTo(low)).isNegative();
    }
}
