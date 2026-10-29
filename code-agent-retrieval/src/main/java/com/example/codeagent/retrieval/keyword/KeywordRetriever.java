package com.example.codeagent.retrieval.keyword;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Retriever;

import java.util.List;

/**
 * 关键词检索器：BM25 召回 + 阈值过滤。
 *
 * <p>它的存在意义用一个例子就能说明：问"createOrder 是如何保证幂等的"，
 * 向量检索会把语义相近但无关的文件排在前面，而 BM25 会因为
 * {@code createorder} 这个稀有词元精确命中 OrderService。
 * 两路互补，正是混合检索的价值来源。
 */
public class KeywordRetriever implements Retriever {

    private final Bm25Index index;
    private final double minScore;

    public KeywordRetriever(Bm25Index index, double minScore) {
        this.index = index;
        this.minScore = minScore;
    }

    @Override
    public List<ScoredChunk> retrieve(String question, int topK) {
        return index.search(question, topK).stream()
                .filter(scored -> scored.score() >= minScore)
                .toList();
    }
}
