package com.example.codeagent.retrieval;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Reranker;
import com.example.codeagent.core.port.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 重排装饰器：先多召回，再精排截断。
 *
 * <p>用装饰器而不是把重排塞进检索器内部，是为了保持职责单一：
 * 检索器只管"找得到"，重排器只管"排得准"，
 * 两者可以独立替换、独立评测，也能自由组合（向量 + 重排、混合 + 重排……）。
 *
 * <p><b>拒答逻辑仍然留在被装饰的检索器里</b>：重排只改变顺序，
 * 不应该把一个本该拒答的问题"救活"——如果召回阶段判定无依据，
 * 这里拿到的候选就是空的，直接返回空列表。
 */
public class RerankingRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(RerankingRetriever.class);

    private final Retriever delegate;
    private final Reranker reranker;
    private final int candidateK;

    public RerankingRetriever(Retriever delegate, Reranker reranker, int candidateK) {
        this.delegate = delegate;
        this.reranker = reranker;
        this.candidateK = candidateK;
    }

    @Override
    public List<ScoredChunk> retrieve(String question, int topK) {
        List<ScoredChunk> candidates = delegate.retrieve(question, candidateK);
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> reranked = reranker.rerank(question, candidates, topK);
        if (log.isDebugEnabled()) {
            log.debug("重排完成: 候选={}, 返回={}, 模型={}",
                    candidates.size(), reranked.size(), reranker.modelName());
            reranked.forEach(hit -> log.debug("  {} score={}",
                    hit.chunk().location(), String.format("%.4f", hit.score())));
        }
        log.info("重排完成: 候选={}, 返回={}, 模型={}",
                candidates.size(), reranked.size(), reranker.modelName());
        return reranked;
    }
}
