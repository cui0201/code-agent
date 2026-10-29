package com.example.codeagent.core.port;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ScoredChunk;

import java.util.List;

/**
 * 向量存储与相似度检索。
 *
 * <p>P0 实现是进程内的暴力余弦检索（InMemoryVectorStore）；
 * P2 会替换为 Qdrant 实现（HNSW 近似检索），接口不变。
 *
 * <p>线程约定：实现必须保证 add 与 search 的并发安全。
 */
public interface VectorStore {

    /**
     * 批量写入。
     *
     * @param chunks  切块列表
     * @param vectors 与 chunks 一一对应的向量
     */
    void add(List<Chunk> chunks, List<float[]> vectors);

    /**
     * 检索最相似的切块。
     *
     * @param queryVector 查询向量，需与写入向量同维度
     * @param topK        返回条数上限
     * @return 按相似度降序排列的结果
     */
    List<ScoredChunk> search(float[] queryVector, int topK);

    /** 当前存储的切块数量。 */
    int size();

    /** 清空全部数据。 */
    void clear();
}
