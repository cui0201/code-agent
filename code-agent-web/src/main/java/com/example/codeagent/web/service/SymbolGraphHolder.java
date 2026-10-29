package com.example.codeagent.web.service;

import com.example.codeagent.indexer.graph.ImpactAnalyzer;
import com.example.codeagent.indexer.graph.ImpactReport;
import com.example.codeagent.indexer.graph.SymbolGraph;

/**
 * 符号图的持有者：索引时写入，查询时读取。
 *
 * <p>用 volatile 引用替换而不是给图本身加锁：
 * 图在构建完成后就不再修改，重建索引时整体替换引用即可。
 * 好处是查询路径完全无锁，且一次请求只会看到"某个完整版本的图"，
 * 不会读到构建到一半的中间状态。
 */
public class SymbolGraphHolder {

    private volatile SymbolGraph graph = new SymbolGraph();

    /** 用新构建的图整体替换旧图。 */
    public void update(SymbolGraph newGraph) {
        this.graph = newGraph == null ? new SymbolGraph() : newGraph;
    }

    public SymbolGraph get() {
        return graph;
    }

    public boolean isEmpty() {
        return graph.symbolCount() == 0;
    }

    /**
     * 执行一次影响面分析。
     *
     * @param symbol 符号名（支持简写，解析规则见 ImpactAnalyzer）
     * @param depth  传播跳数
     */
    public ImpactReport analyze(String symbol, int depth) {
        return new ImpactAnalyzer(graph).analyze(symbol, depth);
    }
}
