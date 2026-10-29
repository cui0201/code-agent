package com.example.codeagent.eval;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.RelevanceJudge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测执行器：用一套问题跑检索链路，算出可比较的指标。
 *
 * <p>它只依赖 {@link Retriever} 接口，所以换切块策略、换向量库、加重排，
 * 都能用同一个评测集横向比较——这正是 P1 需要的对照实验能力。
 *
 * <p>关于拒答判定：检索器已经把低于阈值的命中过滤掉了，
 * 因此返回空列表等价于上层会拒答。评测直接复用这个语义，
 * 不另写一套拒答逻辑，避免两处判定不一致。
 */
public class RagEvaluator {

    private final Retriever retriever;
    private final int topK;
    private final RelevanceJudge judge;

    public RagEvaluator(Retriever retriever, int topK) {
        this(retriever, topK, null);
    }

    /**
     * @param judge 可选的拒答判官。传入后，评测会模拟线上链路：
     *              检索 → 判官判断 → 判为答不上则视为拒答。
     *              这样"召回率"与"拒答准确率"才是同一条链路上的真实权衡，
     *              而不是分别测两个组件再拼在一起
     */
    public RagEvaluator(Retriever retriever, int topK, RelevanceJudge judge) {
        this.retriever = retriever;
        this.topK = topK;
        this.judge = judge;
    }

    /**
     * 运行评测。
     *
     * @param label 本次评测标识，会写进报告
     * @param set   评测集
     */
    public EvaluationReport evaluate(String label, GoldenSet set) {
        List<CaseResult> results = new ArrayList<>(set.cases().size());
        int evidenceHit = 0;
        int refusalHit = 0;
        double scoreSum = 0.0;
        int scoreCount = 0;
        double maxRefusalScore = 0.0;
        long extraLinesSum = 0;
        int extraLinesCount = 0;
        double reciprocalRankSum = 0.0;
        int topOneCount = 0;

        for (GoldenCase goldenCase : set.cases()) {
            // 用例分两类，走完全不同的判定路径：
            //   证据题 —— 期望在结果里找到指定文件的指定区间/锚点；
            //   拒答题 —— 期望结果为空（检索器已经按阈值过滤，空即代表上层会拒答）
            List<ScoredChunk> hits = retriever.retrieve(goldenCase.question(), topK);
            if (judge != null && !hits.isEmpty()
                    && !judge.judge(goldenCase.question(), hits).canAnswer()) {
                // 判官否掉：与"检索为空"等价，因为上层会直接拒答
                hits = List.of();
            }
            double topScore = hits.isEmpty() ? 0.0 : hits.get(0).score();

            if (goldenCase.expectRefusal()) {
                boolean passed = hits.isEmpty();
                if (passed) {
                    refusalHit++;
                } else {
                    // 记录"本来该拒答却给了结果"时的最高分——
                    // 这个数字是校准拒答阈值的直接依据
                    maxRefusalScore = Math.max(maxRefusalScore, topScore);
                }
                results.add(new CaseResult(goldenCase, passed, hits.size(), topScore, -1, -1,
                        describeRefusal(passed, hits)));
            } else {
                // 只认第一个命中：排名越靠前，模型看到正确证据的概率越高，
                // 因此这里同时记录 rank 并累加倒数排名（MRR）
                int matchedIndex = firstMatchIndex(hits, goldenCase.expectedEvidence());
                ScoredChunk matched = matchedIndex < 0 ? null : hits.get(matchedIndex);
                int rank = matchedIndex < 0 ? -1 : matchedIndex + 1;
                boolean passed = matched != null;
                if (passed) {
                    evidenceHit++;
                    reciprocalRankSum += 1.0 / rank;
                    if (rank == 1) {
                        topOneCount++;
                    }
                }
                if (!hits.isEmpty()) {
                    scoreSum += topScore;
                    scoreCount++;
                }
                int extraLines = extraLinesOf(hits, goldenCase.expectedEvidence());
                if (extraLines >= 0) {
                    extraLinesSum += extraLines;
                    extraLinesCount++;
                }
                results.add(new CaseResult(goldenCase, passed, hits.size(), topScore, rank,
                        extraLines, describeEvidence(matched, hits)));
            }
        }

        int evidenceTotal = set.evidenceCases().size();
        int refusalTotal = set.refusalCases().size();
        return new EvaluationReport(
                label,
                evidenceTotal,
                evidenceHit,
                ratio(evidenceHit, evidenceTotal),
                refusalTotal,
                refusalHit,
                ratio(refusalHit, refusalTotal),
                scoreCount == 0 ? 0.0 : scoreSum / scoreCount,
                maxRefusalScore,
                evidenceTotal == 0 ? 0.0 : reciprocalRankSum / evidenceTotal,
                topOneCount,
                extraLinesCount == 0 ? 0.0 : (double) extraLinesSum / extraLinesCount,
                buildCategoryMetrics(results),
                results);
    }

    /**
     * 按类别汇总通过率。
     *
     * <p>用 LinkedHashMap 保持用例在评测集里的出现顺序，
     * 这样报告里类别的排列是稳定的，便于逐次回归对比。
     */
    private static List<EvaluationReport.CategoryMetrics> buildCategoryMetrics(
            List<CaseResult> results) {
        Map<String, int[]> counters = new LinkedHashMap<>();
        for (CaseResult result : results) {
            String category = result.goldenCase().category();
            int[] counter = counters.computeIfAbsent(category, key -> new int[2]);
            counter[0]++;
            if (result.passed()) {
                counter[1]++;
            }
        }
        List<EvaluationReport.CategoryMetrics> metrics = new ArrayList<>(counters.size());
        counters.forEach((category, counter) -> metrics.add(
                new EvaluationReport.CategoryMetrics(category, counter[0], counter[1],
                        (double) counter[1] / counter[0])));
        return metrics;
    }

    /** 返回第一个命中期望证据的候选下标；未命中返回 -1。 */
    private static int firstMatchIndex(List<ScoredChunk> hits,
                                       List<ExpectedEvidence> expectations) {
        for (int i = 0; i < hits.size(); i++) {
            if (matches(hits.get(i).chunk(), expectations)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 计算命中块比期望范围多出的行数。
     *
     * <p>取所有命中块中的最小值：系统只要能给出一个足够精准的引用就算达标，
     * 而不是惩罚它同时召回了其他块。
     *
     * @return 多出的行数；没有可计算的对象时返回 -1
     */
    private static int extraLinesOf(List<ScoredChunk> hits,
                                    List<ExpectedEvidence> expectations) {
        int best = -1;
        for (ExpectedEvidence expectation : expectations) {
            if (expectation.startLine() == null || expectation.endLine() == null) {
                continue;
            }
            int expectedLines = expectation.endLine() - expectation.startLine() + 1;
            for (ScoredChunk hit : hits) {
                Chunk chunk = hit.chunk();
                if (!pathMatches(chunk.path(), expectation.path())) {
                    continue;
                }
                boolean overlaps = chunk.startLine() <= expectation.endLine()
                        && expectation.startLine() <= chunk.endLine();
                if (!overlaps) {
                    continue;
                }
                int extra = Math.max(0, chunk.lineCount() - expectedLines);
                best = (best < 0) ? extra : Math.min(best, extra);
            }
        }
        return best;
    }

    private static String describeRefusal(boolean passed, List<ScoredChunk> hits) {
        if (passed) {
            return "已拒答（检索结果为空）";
        }
        ScoredChunk top = hits.get(0);
        return String.format("未拒答，最高分 %.4f 来自 %s", top.score(), top.chunk().location());
    }

    private static String describeEvidence(ScoredChunk matched, List<ScoredChunk> hits) {
        if (matched != null) {
            return "命中 " + describeHit(matched);
        }
        if (hits.isEmpty()) {
            return "无命中（全部低于阈值）";
        }
        String topThree = hits.stream()
                .limit(3)
                .map(RagEvaluator::describeHit)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "未命中期望文件，实际命中: " + topThree;
    }

    /** 展示命中位置：文档块显示锚点，代码块显示行号区间——光看路径分不清命中的是哪一节。 */
    private static String describeHit(ScoredChunk hit) {
        Object anchor = hit.chunk().metadata().get("anchor");
        if (anchor != null) {
            return hit.chunk().path() + "#" + anchor;
        }
        return hit.chunk().location();
    }

    /** 匹配规则：路径按后缀匹配；给了行号时要求区间重叠。 */
    private static boolean matches(Chunk chunk, List<ExpectedEvidence> expectations) {
        for (ExpectedEvidence expectation : expectations) {
            if (!pathMatches(chunk.path(), expectation.path())) {
                continue;
            }
            if (expectation.anchor() != null) {
                Object anchor = chunk.metadata().get("anchor");
                if (anchor == null || !expectation.anchor().equals(String.valueOf(anchor))) {
                    continue;
                }
            }
            if (expectation.startLine() == null || expectation.endLine() == null) {
                return true;
            }
            boolean overlaps = chunk.startLine() <= expectation.endLine()
                    && expectation.startLine() <= chunk.endLine();
            if (overlaps) {
                return true;
            }
        }
        return false;
    }

    private static boolean pathMatches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String normalizedActual = actual.replace('\\', '/');
        String normalizedExpected = expected.replace('\\', '/');
        return normalizedActual.equals(normalizedExpected)
                || normalizedActual.endsWith(normalizedExpected);
    }

    private static double ratio(int hit, int total) {
        return total == 0 ? 1.0 : (double) hit / total;
    }
}
