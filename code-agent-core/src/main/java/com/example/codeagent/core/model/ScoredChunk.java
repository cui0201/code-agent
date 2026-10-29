package com.example.codeagent.core.model;

/**
 * 带分数的检索结果。
 *
 * <p>{@link #compareTo} 按 {@code score} <b>降序</b>排列，便于直接排序取 TopK。
 *
 * @param chunk  命中的切块
 * @param score  相似度分数（余弦相似度，取值区间通常为 [-1, 1]）
 * @param source 召回来源：{@code dense} / {@code sparse} / {@code graph} / {@code rerank}
 */
public record ScoredChunk(Chunk chunk, double score, String source) implements Comparable<ScoredChunk> {

    public ScoredChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk 不能为 null");
        }
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
    }

    @Override
    public int compareTo(ScoredChunk other) {
        return Double.compare(other.score, this.score);
    }
}
