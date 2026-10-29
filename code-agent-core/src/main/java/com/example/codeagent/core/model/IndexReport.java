package com.example.codeagent.core.model;

import java.util.List;

/**
 * 一次索引的结果报告。
 *
 * @param fileCount    成功处理的文件数
 * @param chunkCount   产出的切块数
 * @param skippedFiles 因异常跳过的文件数
 * @param reusedFiles  直接复用上次索引结果的文件数（增量索引的核心指标）
 * @param embeddedChunks 本次真正做了嵌入的切块数（复用向量后其余不需要重新计算）
 * @param corpusFingerprint 语料指纹：所有文件「路径 + 内容哈希」的组合摘要。
 *                          它不依赖 git，却能精确刻画"这份索引基于什么内容"——
 *                          两次索引指纹相同，结果必然可复现；指纹不同，指标不可比
 * @param elapsedMs    总耗时
 * @param failures     失败明细（文件路径与原因）
 */
public record IndexReport(
        int fileCount,
        int chunkCount,
        int skippedFiles,
        int reusedFiles,
        int embeddedChunks,
        String corpusFingerprint,
        long elapsedMs,
        List<String> failures
) {

    public IndexReport {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** 每文件的平均块数，用于判断切块粒度是否合理。 */
    public double chunksPerFile() {
        return fileCount == 0 ? 0.0 : (double) chunkCount / fileCount;
    }
}
