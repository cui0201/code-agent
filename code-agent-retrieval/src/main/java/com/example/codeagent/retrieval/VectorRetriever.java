package com.example.codeagent.retrieval;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于向量相似度的检索器（P0 版本）。
 *
 * <p>流程：问题转向量，取 TopK，再按最低分阈值过滤。
 * 低于阈值的命中会被丢弃——这是"无依据就拒答"机制的第一道闸门，
 * 目的是宁可拒答，也不把不相关代码塞给大模型。
 */
public class VectorRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(VectorRetriever.class);

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final double minScore;

    public VectorRetriever(EmbeddingClient embeddingClient, VectorStore vectorStore, double minScore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.minScore = minScore;
    }

    @Override
    public List<ScoredChunk> retrieve(String question, int topK) {
        if (vectorStore.size() == 0) {
            log.warn("向量存储为空，请先执行索引");
            return List.of();
        }
        float[] queryVector = embeddingClient.embed(question);
        List<ScoredChunk> candidates = vectorStore.search(queryVector, topK);
        List<ScoredChunk> accepted = candidates.stream()
                .filter(scored -> scored.score() >= minScore)
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("检索完成: 候选={}, 通过阈值={}, 阈值={}",
                    candidates.size(), accepted.size(), minScore);
            accepted.forEach(scored -> log.debug("  {} score={}",
                    scored.chunk().location(),
                    String.format("%.4f", scored.score())));
        }
        return accepted;
    }
}
