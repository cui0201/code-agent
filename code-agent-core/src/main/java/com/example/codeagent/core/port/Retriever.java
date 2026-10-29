package com.example.codeagent.core.port;

import com.example.codeagent.core.model.ScoredChunk;

import java.util.List;

/**
 * 检索器：自然语言问题转相关切块。
 *
 * <p>P0 只有向量一路召回；P2 起扩展为稠密加稀疏的混合召回。
 * 无论内部如何实现，返回结果必须已按相关性降序排列并按阈值过滤。
 */
public interface Retriever {

    /**
     * @param question 用户问题
     * @param topK     返回条数上限
     * @return 命中的切块；无命中时返回空列表，上层据此拒答
     */
    List<ScoredChunk> retrieve(String question, int topK);
}
