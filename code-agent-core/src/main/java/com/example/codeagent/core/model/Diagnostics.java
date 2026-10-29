package com.example.codeagent.core.model;

/**
 * 一次问答的运行诊断信息。
 *
 * <p>这些字段会写入结构化日志，是后续做延迟与成本优化的唯一数据来源，
 * 因此即使 P0 也要采集。
 *
 * @param retrievalMs    检索阶段耗时
 * @param generationMs   生成阶段耗时
 * @param retrievedChunks 召回块数（过滤前）
 * @param usedChunks     实际注入上下文的块数
 * @param promptChars    提示字符数（近似 token 估算依据）
 * @param model          使用的模型名
 * @param cached         本次回答是否来自缓存（来自缓存时检索与生成耗时都为 0）
 */
public record Diagnostics(
        long retrievalMs,
        long generationMs,
        int retrievedChunks,
        int usedChunks,
        int promptChars,
        String model,
        boolean cached
) {

    public static Diagnostics empty() {
        return new Diagnostics(0, 0, 0, 0, 0, "none", false);
    }

    /** 缓存命中时的诊断信息：既然没走检索与生成，耗时自然为 0。 */
    public static Diagnostics cached(String model, int citations) {
        return new Diagnostics(0, 0, 0, citations, 0, model, true);
    }

    public long totalMs() {
        return retrievalMs + generationMs;
    }
}
