package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;

import java.util.List;
import java.util.Optional;

/**
 * 拒答判定。
 *
 * <p>这是"严格依据代码"的第一道闸门，也是延迟与成本优化的关键：
 * 无依据时直接返回，连大模型都不调用，既不会编造，也省掉一次请求。
 *
 * <p>P0 只做两条判断：没有证据，或最高分低于阈值。
 * 更精细的判定（例如证据覆盖度、语义一致性）留给 P3。
 */
public class RefusalPolicy {

    private final double minScore;

    public RefusalPolicy(double minScore) {
        this.minScore = minScore;
    }

    /**
     * @param evidence 检索结果
     * @return 需要拒答时返回原因，否则返回空
     */
    public Optional<String> refusalReason(List<ScoredChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Optional.of("未在项目代码中找到相关依据，无法回答该问题。");
        }
        double topScore = evidence.get(0).score();
        if (topScore < minScore) {
            return Optional.of(String.format(
                    "检索到的代码与问题相关性过低（最高相似度 %.3f，阈值 %.2f），"
                            + "未在项目代码中找到相关依据。",
                    topScore, minScore));
        }
        return Optional.empty();
    }
}
