package com.example.codeagent.retrieval.fusion;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ScoredChunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手写的 RRF（Reciprocal Rank Fusion）融合器。
 *
 * <p>公式：
 * <pre>
 * score(d) = Σ_r  weight_r / (k + rank_r(d))
 * </pre>
 *
 * <p>为什么用 RRF 而不是把两路分数加权求和：两路的分数根本不可比——
 * 余弦相似度落在 0~1 区间且分布集中，BM25 是无上界的对数型分数。
 * 归一化后再加权需要先假设两者的分布，而 RRF 只用<b>排名</b>，
 * 天然免疫量纲差异，这正是它在工业界被广泛采用的原因。
 *
 * <p>k 的作用是压低头部差异：k 越大，排名第一与第二的差距越小。
 * 常用的 60 是经验值，含义是"排名前 60 位之内都算不错的证据"。
 */
public class RrfFuser {

    public static final int DEFAULT_K = 60;

    private final int k;
    private final List<Double> weights;

    public RrfFuser(int k, List<Double> weights) {
        if (k < 1) {
            throw new IllegalArgumentException("k 必须大于等于 1，当前: " + k);
        }
        this.k = k;
        this.weights = List.copyOf(weights);
    }

    public static RrfFuser withEqualWeights(int listCount) {
        return new RrfFuser(DEFAULT_K,
                java.util.Collections.nCopies(Math.max(1, listCount), 1.0));
    }

    /**
     * 融合多路召回结果。
     *
     * @param topK        返回条数上限
     * @param rankedLists 各路召回结果，必须已按相关性降序；下标对应权重
     * @return 融合后的结果，score 已归一化到 0~1（排名最高者可得 1.0）
     */
    public List<ScoredChunk> fuse(int topK, List<List<ScoredChunk>> rankedLists) {
        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Chunk> chunksById = new LinkedHashMap<>();

        for (int listIndex = 0; listIndex < rankedLists.size(); listIndex++) {
            double weight = listIndex < weights.size() ? weights.get(listIndex) : 1.0;
            List<ScoredChunk> ranked = rankedLists.get(listIndex);
            for (int rank = 0; rank < ranked.size(); rank++) {
                ScoredChunk scored = ranked.get(rank);
                String id = scored.chunk().id();
                chunksById.putIfAbsent(id, scored.chunk());
                fusedScores.merge(id, weight / (k + rank + 1), Double::sum);
            }
        }
        if (fusedScores.isEmpty()) {
            return List.of();
        }

        double bestPossible = weights.stream().mapToDouble(Double::doubleValue).sum() / (k + 1);
        double normalizer = bestPossible <= 0 ? 1.0 : bestPossible;

        return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> new ScoredChunk(
                        chunksById.get(entry.getKey()),
                        entry.getValue() / normalizer,
                        "hybrid"))
                .toList();
    }

    public int k() {
        return k;
    }
}
