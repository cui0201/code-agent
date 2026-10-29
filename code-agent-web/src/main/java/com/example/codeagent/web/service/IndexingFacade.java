package com.example.codeagent.web.service;

import com.example.codeagent.indexer.IndexingResult;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.agent.SemanticCache;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 索引入口：一次调用同时维护向量索引与关键词索引。
 *
 * <p>为什么要包一层：混合检索依赖两套索引，而它们必须基于<b>同一批切块</b>。
 * 如果分别触发（比如只重建向量索引），两路的 id 与行号就会错位，
 * 融合出的引用会指向不存在的代码。把两个索引的更新收口到一个方法里，
 * 是避免这类不一致最省事的做法。
 */
public class IndexingFacade {

    private static final Logger log = LoggerFactory.getLogger(IndexingFacade.class);

    private final IndexingService indexingService;
    private final Bm25Index keywordIndex;
    private final SymbolGraphHolder symbolGraphHolder;
    private final SemanticCache semanticCache;

    public IndexingFacade(IndexingService indexingService,
                          Bm25Index keywordIndex,
                          SymbolGraphHolder symbolGraphHolder,
                          SemanticCache semanticCache) {
        this.indexingService = indexingService;
        this.keywordIndex = keywordIndex;
        this.symbolGraphHolder = symbolGraphHolder;
        this.semanticCache = semanticCache;
    }

    /**
     * 全量重建两套索引。
     *
     * @param root 工程根目录
     */
    public IndexingResult index(Path root) {
        IndexingResult result = indexingService.index(root);
        keywordIndex.clear();
        keywordIndex.addAll(result.chunks());
        log.info("关键词索引重建完成: 切块={}, 词表={}",
                keywordIndex.size(), keywordIndex.termCount());
        // 符号图与关键词索引一样，必须和向量索引来自同一次扫描，
        // 否则三者描述的不是同一份代码快照
        symbolGraphHolder.update(result.symbolGraph());
        // 代码变了，缓存答案里的引用就可能指向错误的位置，必须整体失效。
        // 放在这里而不是"定期清理"：只有索引重建才意味着代码快照发生变化
        if (semanticCache != null) {
            semanticCache.invalidate();
        }
        return result;
    }
}
