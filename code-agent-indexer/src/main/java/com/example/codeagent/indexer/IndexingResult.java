package com.example.codeagent.indexer;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.indexer.graph.SymbolGraph;

import java.util.List;

/**
 * 一次索引的完整产物。
 *
 * <p>除统计报告外还带出切块列表：关键词索引（BM25）需要与向量索引使用
 * <b>同一批切块对象</b>，否则两路的 id 与行号可能对不上，融合时会错乱。
 *
 * @param report 索引统计报告
 * @param chunks 实际入库的切块（已成功生成向量的那些）
 * @param symbolGraph 符号图；构建失败时是空图（影响面分析不可用，但检索照常）
 */
public record IndexingResult(IndexReport report, List<Chunk> chunks, SymbolGraph symbolGraph) {

    public IndexingResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
