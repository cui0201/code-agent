package com.example.codeagent.retrieval.store;

import com.example.codeagent.core.math.VectorMath;
import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.VectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内的向量存储，使用暴力余弦检索。
 *
 * <p>复杂度为 O(N)，1 万条切块约 5~15 ms，对 P0 完全够用。
 * P2 会替换为 Qdrant（HNSW 近似检索），但 {@link VectorStore} 接口不变，
 * 上层的检索器与问答服务无需修改。
 *
 * <p>所有公有方法都是同步的：P0 阶段索引与问答在同一进程内并发访问，
 * 用最简单的互斥保证一致性，代价在可接受范围内。
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<Chunk> chunks = new ArrayList<>();
    private final List<float[]> vectors = new ArrayList<>();

    @Override
    public synchronized void add(List<Chunk> newChunks, List<float[]> newVectors) {
        if (newChunks.size() != newVectors.size()) {
            throw new IllegalArgumentException(
                    "切块数与向量数不一致: " + newChunks.size() + " vs " + newVectors.size());
        }
        for (int i = 0; i < newChunks.size(); i++) {
            float[] vector = newVectors.get(i);
            if (vector == null) {
                throw new IllegalArgumentException("第 " + i + " 个向量为 null");
            }
            if (!vectors.isEmpty() && vectors.get(0).length != vector.length) {
                throw new IllegalArgumentException(
                        "向量维度不一致: 期望 " + vectors.get(0).length + "，实际 " + vector.length);
            }
            chunks.add(newChunks.get(i));
            vectors.add(vector);
        }
    }

    @Override
    public synchronized List<ScoredChunk> search(float[] queryVector, int topK) {
        if (topK <= 0 || chunks.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            double score = VectorMath.cosine(queryVector, vectors.get(i));
            scored.add(new ScoredChunk(chunks.get(i), score, "dense"));
        }
        // ScoredChunk 的自然序即分数降序
        scored.sort(null);
        return List.copyOf(scored.subList(0, Math.min(topK, scored.size())));
    }

    @Override
    public synchronized int size() {
        return chunks.size();
    }

    @Override
    public synchronized void clear() {
        chunks.clear();
        vectors.clear();
    }
}
