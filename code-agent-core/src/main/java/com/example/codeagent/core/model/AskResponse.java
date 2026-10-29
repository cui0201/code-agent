package com.example.codeagent.core.model;

import java.util.List;

/**
 * 一次问答的结果。
 *
 * @param answer      答案文本；拒答时说明拒答原因
 * @param citations   引用列表（顺序与答案中 {@code [n]} 一致）
 * @param refused     是否拒答
 * @param evidence    本次作为依据的证据块（便于前端展示与人工核对）
 * @param diagnostics 运行诊断
 */
public record AskResponse(
        String answer,
        List<Citation> citations,
        boolean refused,
        List<ScoredChunk> evidence,
        Diagnostics diagnostics
) {

    public AskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 构造拒答响应：不调用大模型，直接返回。 */
    public static AskResponse refused(String reason, Diagnostics diagnostics) {
        return new AskResponse(reason, List.of(), true, List.of(), diagnostics);
    }
}
