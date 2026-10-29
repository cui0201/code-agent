package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.core.port.RelevanceJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 让模型自己判断"这些证据能不能回答问题"。
 *
 * <h2>为什么这一步值得多花一次调用</h2>
 *
 * 生成阶段的任务是"根据证据组织语言"，它天然倾向于把手里有什么就用什么——
 * 哪怕证据完全不相关，它也会努力拼出一个听起来合理的答案。
 * 把"这件事该不该答"单独交给一次判断，比在生成提示里写十遍"没有依据就拒答"更可靠。
 *
 * <p>成本上也可以接受：判官只需要看问题与前三条证据（每条约 400 字），
 * 输出只有两个词（YES/NO），比完整生成便宜一个数量级。
 *
 * <h2>失败时怎么办</h2>
 *
 * 判官本身可能超时、限流、返回无法解析的内容。<b>它不能成为新的单点故障</b>：
 * 出现这些问题时回退到备用判官（通常是词面判官），而不是直接放行或直接拒答。
 * 这条策略写在这里，是因为"判官挂了就全部拒答"看起来安全，实际会让系统在
 * 网络抖动时彻底不可用。
 */
public class LlmRelevanceJudge implements RelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmRelevanceJudge.class);

    /**
     * 判官看前几条证据。
     *
     * <p>最初取 3 条，实测会把"答案排在第 4~5 位"的问题误判成无法回答
     * （例如"余弦相似度怎么算"命中了 VectorMath，但它不在前三条）。
     * 放到 5 条后误拒明显减少，而输入长度仍然远小于完整生成。
     */
    private static final int MAX_EVIDENCE = 5;

    /** 每条证据参与判断的最大字符数。 */
    private static final int MAX_CHARS_PER_EVIDENCE = 400;

    private static final String SYSTEM_PROMPT = """
            你是一个严格的相关性判官。你的唯一任务是判断：
            下面给出的证据片段，是否足以回答用户的问题。

            判断标准：
            - 证据既可以是文档说明，也可以是**源码片段**。
              源码里体现了回答问题所需的事实（例如方法体里就是那段计算逻辑），也算 YES；
            - 只有证据中明确包含回答问题所需的事实时，才回答 YES；
            - 仅仅主题相近、提到相同词汇、但不包含答案时，回答 NO；
            - 不要使用你自己的知识补全证据里没有的内容。
            - 如果证据只讲了相关问题的一部分，但已经能给出有依据的部分答案，回答 YES。

            输出格式：只输出一个词，YES 或 NO，不要有任何其他内容。
            """;

    private final AnswerGenerator generator;
    private final RelevanceJudge fallback;

    /**
     * @param generator 判官使用的模型客户端（可与生成共用同一个）
     * @param fallback  判官失败时的备用判据；可为 null，为 null 时失败即放行
     */
    public LlmRelevanceJudge(AnswerGenerator generator, RelevanceJudge fallback) {
        this.generator = generator;
        this.fallback = fallback;
    }

    @Override
    public Judgement judge(String question, List<ScoredChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Judgement.no("没有召回任何证据");
        }
        try {
            String answer = generator.generate(SYSTEM_PROMPT, buildPrompt(question, evidence));
            Boolean verdict = parse(answer);
            if (verdict == null) {
                log.warn("判官输出无法解析，回退: {}", answer);
                return fallbackOrPass(question, evidence, "判官输出无法解析");
            }
            return verdict
                    ? Judgement.yes("判官确认证据足以回答问题")
                    : Judgement.no("判官判断现有证据不足以回答该问题（主题相近但没有答案）");
        } catch (RuntimeException e) {
            log.warn("判官调用失败，回退: {}", e.getMessage());
            return fallbackOrPass(question, evidence, "判官调用失败: " + e.getMessage());
        }
    }

    private Judgement fallbackOrPass(String question, List<ScoredChunk> evidence, String reason) {
        if (fallback != null) {
            Judgement judgement = fallback.judge(question, evidence);
            return new Judgement(judgement.canAnswer(), reason + "；回退判据: " + judgement.reason());
        }
        // 没有备用判据时选择放行：生成阶段仍有引用校验兜底，
        // 而"判官挂了就全拒答"会让系统在网络抖动时彻底不可用
        return Judgement.yes(reason + "；无备用判据，放行给生成阶段");
    }

    private String buildPrompt(String question, List<ScoredChunk> evidence) {
        StringBuilder builder = new StringBuilder();
        builder.append("【问题】\n").append(question).append("\n\n【证据】\n");
        int index = 0;
        for (ScoredChunk hit : evidence.subList(0, Math.min(MAX_EVIDENCE, evidence.size()))) {
            builder.append('[').append(++index).append("] ")
                    .append(hit.chunk().path()).append('\n')
                    .append(truncate(hit.chunk().content())).append("\n\n");
        }
        builder.append("这些证据是否足以回答上述问题？只回答 YES 或 NO。");
        return builder.toString();
    }

    /**
     * 解析判官输出。
     *
     * <p>模型偶尔会输出 "YES。" 或 "答案：NO"，因此先归一化再判断；
     * 先查 NO 再查 YES，避免 "NOT ENOUGH" 这类文本被误判成 YES。
     */
    static Boolean parse(String answer) {
        if (answer == null) {
            return null;
        }
        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        // 用词边界匹配而不是 startsWith：模型可能输出"答案：NO"这类带前缀的形式。
        // 先判 NO 再判 YES，"NOT ENOUGH" 这类文本才不会被误判成 YES
        if (java.util.regex.Pattern.compile("\\bNO\\b").matcher(normalized).find()
                || normalized.contains("不足以")) {
            return Boolean.FALSE;
        }
        if (java.util.regex.Pattern.compile("\\bYES\\b").matcher(normalized).find()) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CHARS_PER_EVIDENCE
                ? text : text.substring(0, MAX_CHARS_PER_EVIDENCE) + "...";
    }
}
