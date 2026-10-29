package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.core.port.RelevanceJudge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 判官的测试。
 *
 * <p>判官的价值全在"边界行为"上：模型说 YES / NO / 说了一堆废话 / 调用直接失败。
 * 这四种情况都要有确定的结果，否则拒答逻辑会变得不可预测。
 * 用假生成器覆盖，不需要 API Key。
 */
class LlmRelevanceJudgeTest {

    private static class FixedAnswerGenerator implements AnswerGenerator {
        private final String answer;
        private final RuntimeException failure;

        FixedAnswerGenerator(String answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public String modelName() {
            return "fake-judge";
        }
    }

    private final List<ScoredChunk> evidence =
            List.of(TestChunks.scored("src/OrderService.java", 10, 40, 0.8));

    @Test
    @DisplayName("模型回答 YES 时判为可以回答")
    void should_accept_yes() {
        LlmRelevanceJudge judge = new LlmRelevanceJudge(new FixedAnswerGenerator("YES", null), null);

        assertThat(judge.judge("金额怎么校验？", evidence).canAnswer()).isTrue();
    }

    @Test
    @DisplayName("模型回答 NO 时判为无法回答，并给出可展示的理由")
    void should_reject_no() {
        LlmRelevanceJudge judge = new LlmRelevanceJudge(new FixedAnswerGenerator("NO", null), null);

        RelevanceJudge.Judgement judgement = judge.judge("QPS 峰值是多少？", evidence);

        assertThat(judgement.canAnswer()).isFalse();
        assertThat(judgement.reason()).contains("不足以回答");
    }

    @Test
    @DisplayName("带标点或前缀的输出也能解析")
    void should_parse_noisy_output() {
        assertThat(LlmRelevanceJudge.parse("YES.")).isTrue();
        assertThat(LlmRelevanceJudge.parse("答案：NO")).isFalse();
        assertThat(LlmRelevanceJudge.parse("  no  ")).isFalse();
        assertThat(LlmRelevanceJudge.parse("不确定")).isNull();
        assertThat(LlmRelevanceJudge.parse(null)).isNull();
    }

    @Test
    @DisplayName("输出无法解析时回退到备用判据")
    void should_fall_back_on_unparseable_output() {
        RelevanceJudge fallback = (question, hits) -> RelevanceJudge.Judgement.no("备用判据认为不行");
        LlmRelevanceJudge judge = new LlmRelevanceJudge(
                new FixedAnswerGenerator("我觉得大概可以吧", null), fallback);

        RelevanceJudge.Judgement judgement = judge.judge("问题", evidence);

        assertThat(judgement.canAnswer()).isFalse();
        assertThat(judgement.reason()).contains("无法解析").contains("备用判据");
    }

    @Test
    @DisplayName("判官调用失败时不能成为新的单点故障")
    void should_not_block_when_judge_fails_without_fallback() {
        LlmRelevanceJudge judge = new LlmRelevanceJudge(
                new FixedAnswerGenerator(null, new IllegalStateException("超时")), null);

        RelevanceJudge.Judgement judgement = judge.judge("问题", evidence);

        // 没有备用判据时选择放行：生成阶段还有引用校验兜底，
        // 而"判官挂了就全拒答"会让系统在网络抖动时彻底不可用
        assertThat(judgement.canAnswer()).isTrue();
        assertThat(judgement.reason()).contains("调用失败").contains("放行");
    }

    @Test
    @DisplayName("空证据直接拒答，不必调用模型")
    void should_reject_empty_evidence_without_calling_model() {
        LlmRelevanceJudge judge = new LlmRelevanceJudge(
                new FixedAnswerGenerator("YES", null), null);

        assertThat(judge.judge("问题", List.of()).canAnswer()).isFalse();
    }
}
