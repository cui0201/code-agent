package com.example.codeagent.eval;

import java.util.List;

/**
 * 一次评测的汇总结果。
 *
 * @param label                  本次评测标识，例如 ast 或 line-window
 * @param evidenceTotal          需要检索到证据的用例数
 * @param evidenceHit            成功命中的用例数
 * @param evidenceRecall         证据召回率
 * @param refusalTotal           期望拒答的用例数
 * @param refusalHit             正确拒答的用例数
 * @param refusalAccuracy        拒答准确率
 * @param meanTopScoreOnEvidence 证据类用例的平均最高相似度
 * @param maxTopScoreOnRefusal   拒答类用例中的最高相似度，越小越好，用于校准阈值
 * @param meanReciprocalRank     正确证据的平均倒数排名，衡量排序质量（1 为最好）
 * @param topOneCount            正确证据排在第一位的用例数
 * @param meanExtraLines         命中块的平均多余行数，衡量引用范围是否精准
 * @param categories             按类别分组的指标。
 *                               只有一个总分时，"某类问题全错"可能被整体成绩掩盖；
 *                               分类指标才能回答"下一步该优化哪类问题"
 * @param results                逐条明细
 */
public record EvaluationReport(
        String label,
        int evidenceTotal,
        int evidenceHit,
        double evidenceRecall,
        int refusalTotal,
        int refusalHit,
        double refusalAccuracy,
        double meanTopScoreOnEvidence,
        double maxTopScoreOnRefusal,
        double meanReciprocalRank,
        int topOneCount,
        double meanExtraLines,
        List<CategoryMetrics> categories,
        List<CaseResult> results
) {

    public EvaluationReport {
        categories = categories == null ? List.of() : List.copyOf(categories);
        results = results == null ? List.of() : List.copyOf(results);
    }

    /**
     * 单个类别的指标。
     *
     * @param category   类别名
     * @param total      该类别的用例数
     * @param passed     通过数
     * @param passRate   通过率
     */
    public record CategoryMetrics(String category, int total, int passed, double passRate) {
    }

    public boolean allPassed() {
        return evidenceHit == evidenceTotal && refusalHit == refusalTotal;
    }

    /** 渲染为 Markdown 片段，直接写入报告文件。 */
    public String toMarkdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("## 评测结果：").append(label).append("\n\n");
        builder.append("| 指标 | 值 |\n| --- | --- |\n");
        builder.append(String.format("| 证据召回率 | %.1f%% (%d/%d) |%n",
                evidenceRecall * 100, evidenceHit, evidenceTotal));
        builder.append(String.format("| 拒答准确率 | %.1f%% (%d/%d) |%n",
                refusalAccuracy * 100, refusalHit, refusalTotal));
        builder.append(String.format("| 证据类平均最高相似度 | %.4f |%n", meanTopScoreOnEvidence));
        builder.append(String.format("| 拒答类最高相似度 | %.4f |%n", maxTopScoreOnRefusal));
        builder.append(String.format("| MRR（倒数排名均值） | %.4f |%n", meanReciprocalRank));
        builder.append(String.format("| 正确证据排第一的用例数 | %d |%n", topOneCount));
        builder.append(String.format("| 命中块平均多余行数 | %.1f |%n", meanExtraLines));
        if (!categories.isEmpty()) {
            builder.append("\n| 类别 | 用例数 | 通过 | 通过率 |\n");
            builder.append("| --- | --- | --- | --- |\n");
            for (CategoryMetrics metrics : categories) {
                builder.append(String.format("| %s | %d | %d | %.1f%% |%n",
                        metrics.category(), metrics.total(), metrics.passed(),
                        metrics.passRate() * 100));
            }
        }
        builder.append("\n| 用例 | 问题 | 结果 | 排名 | 最高分 | 多余行 | 说明 |\n");
        builder.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult result : results) {
            String extra = result.extraLines() < 0 ? "-" : String.valueOf(result.extraLines());
            String rank = result.rank() < 0 ? "-" : String.valueOf(result.rank());
            builder.append(String.format("| %s | %s | %s | %s | %.4f | %s | %s |%n",
                    result.goldenCase().id(),
                    result.goldenCase().question(),
                    result.passed() ? "通过" : "**失败**",
                    rank,
                    result.topScore(),
                    extra,
                    result.detail()));
        }
        return builder.toString();
    }
}
