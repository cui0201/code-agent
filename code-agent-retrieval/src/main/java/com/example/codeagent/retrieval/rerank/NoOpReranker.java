package com.example.codeagent.retrieval.rerank;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Reranker;

import java.util.List;

/**
 * 空重排器：只做截断，不改变顺序。
 *
 * <p>存在的意义是让"是否启用重排"成为一个可切换的配置，
 * 而不是在代码里到处写 if。这也是评测能对比"有重排/无重排"的前提。
 */
public class NoOpReranker implements Reranker {

    public static final NoOpReranker INSTANCE = new NoOpReranker();

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        return List.copyOf(candidates.subList(0, Math.min(topK, candidates.size())));
    }

    @Override
    public String modelName() {
        return "none";
    }
}
