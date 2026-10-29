package com.example.codeagent.core.port;

import com.example.codeagent.core.model.ScoredChunk;

import java.util.List;

/**
 * 重排器：对召回结果重新排序并截断。
 *
 * <p>为什么需要它：召回阶段（向量 + BM25）用的是双编码器或纯词法匹配，
 * 查询与文档<b>各自独立</b>编码，因此判断粒度粗、排序靠后。
 * 重排用的是交叉编码器（cross-encoder），把"查询 + 候选"拼成一条输入一起过模型，
 * 能捕捉两者的交互信息，精度明显更高——代价是计算量大，
 * 所以只能用在少量候选上，这也决定了它必须放在召回之后。
 *
 * <p>实现约定：
 * <ul>
 *   <li>输入必须已按相关性降序；</li>
 *   <li>输出条数不超过 {@code topK}；</li>
 *   <li>输出结果的 {@code score} 必须来自重排模型，不能被改写为其他含义。</li>
 * </ul>
 */
public interface Reranker {

    /**
     * @param query      用户问题
     * @param candidates 召回结果（已按相关性降序）
     * @param topK       截断条数
     * @return 重排后的结果；候选为空时返回空列表
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);

    /** 模型标识，写入日志与诊断信息。 */
    String modelName();
}
