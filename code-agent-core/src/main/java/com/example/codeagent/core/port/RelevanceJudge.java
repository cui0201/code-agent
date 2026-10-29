package com.example.codeagent.core.port;

import com.example.codeagent.core.model.ScoredChunk;

import java.util.List;

/**
 * 相关性判官：判断"这些证据能不能回答这个问题"。
 *
 * <h2>为什么需要它</h2>
 *
 * 拒答最初用的是"最高相似度低于阈值就拒答"。这个做法在纯英文代码语料上还能工作，
 * 但加入中文文档后失效了：中文问句对任意中文技术文本的余弦基线就有 0.6 左右，
 * 而真正的相关证据也才 0.65~0.7。实测中，问"生产环境 QPS 峰值是多少"
 * 命中了一段讲延迟的文档，得分 0.8963——比很多真答案还高。
 *
 * <p>换句话说：<b>相似度衡量的是"像不像"，不是"答不答得上"</b>。
 * 后者需要另一层判断，这就是本接口存在的原因。
 *
 * <h2>两种实现，一种兜底</h2>
 *
 * <ul>
 *   <li>{@code LlmRelevanceJudge}：让模型看一眼证据，直接回答"能否回答"。
 *       判断最准，但多一次模型调用；</li>
 *   <li>{@code LexicalRelevanceJudge}：不需要模型，检查证据里是否出现了
 *       问题中的"稀有词"。速度极快，但对换说法的问句偏严。</li>
 * </ul>
 *
 * <p>生产配置建议：优先用 LLM 判官，判官调用失败时<b>回退</b>到词面判官
 * （而不是直接放行或直接拒答）——判官本身不应该成为新的单点故障。
 */
public interface RelevanceJudge {

    /**
     * @param question 用户问题
     * @param evidence 已召回的候选证据（按相关性降序）
     * @return 判断结果，见 {@link Judgement}
     */
    Judgement judge(String question, List<ScoredChunk> evidence);

    /** 判官标识，写入日志与诊断信息。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 判断结果。
     *
     * @param canAnswer 这些证据是否足以回答问题
     * @param reason    判断依据，会写进日志；拒答时会作为拒答原因返回给用户
     */
    record Judgement(boolean canAnswer, String reason) {

        public static Judgement yes(String reason) {
            return new Judgement(true, reason);
        }

        public static Judgement no(String reason) {
            return new Judgement(false, reason);
        }
    }
}
