package com.example.codeagent.eval;

/**
 * 单条用例的运行结果。
 *
 * @param goldenCase 用例定义
 * @param passed     是否通过
 * @param hitCount   检索返回的块数
 * @param topScore   最高相似度；无命中时为 0
 * @param rank       期望证据在结果中的排名（1 起）；未命中为 -1
 * @param extraLines 命中块的引用范围比期望范围多出的行数；-1 表示不适用
 * @param detail     判定依据，写入报告便于人工复核
 */
public record CaseResult(
        GoldenCase goldenCase,
        boolean passed,
        int hitCount,
        double topScore,
        int rank,
        int extraLines,
        String detail
) {
}
