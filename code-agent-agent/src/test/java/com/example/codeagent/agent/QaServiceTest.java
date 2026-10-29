package com.example.codeagent.agent;

import com.example.codeagent.core.model.AskRequest;
import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.core.port.Retriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QaServiceTest {

    /** 可编程的假检索器。 */
    private static class FakeRetriever implements Retriever {
        private final List<ScoredChunk> result;

        FakeRetriever(List<ScoredChunk> result) {
            this.result = result;
        }

        @Override
        public List<ScoredChunk> retrieve(String question, int topK) {
            return result;
        }
    }

    /** 记录调用次数的假生成器，用来验证"拒答时不调用大模型"。 */
    private static class FakeAnswerGenerator implements AnswerGenerator {
        private final String answer;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();

        FakeAnswerGenerator(String answer) {
            this.answer = answer;
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            prompts.add(userPrompt);
            return answer;
        }

        @Override
        public String modelName() {
            return "fake-model";
        }
    }

    private QaService service(Retriever retriever, AnswerGenerator generator) {
        return service(retriever, generator, 0);
    }

    private QaService service(Retriever retriever, AnswerGenerator generator, int citationRetry) {
        return new QaService(
                retriever,
                generator,
                new PromptBuilder(20_000),
                new CitationValidator(),
                new RefusalPolicy(0.30),
                true,
                citationRetry);
    }

    @Test
    @DisplayName("无检索结果时拒答，且不调用大模型")
    void should_refuse_without_evidence_and_skip_llm() {
        FakeAnswerGenerator generator = new FakeAnswerGenerator("不应被调用");
        QaService service = service(new FakeRetriever(List.of()), generator);

        AskResponse response = service.ask(AskRequest.of("订单模块用了什么消息队列？"));

        assertThat(response.refused()).isTrue();
        assertThat(response.answer()).contains("未在项目代码中找到相关依据");
        assertThat(generator.calls.get()).isZero();
    }

    @Test
    @DisplayName("有证据且引用合法时返回引用列表")
    void should_return_citations_when_answer_is_valid() {
        List<ScoredChunk> evidence = List.of(TestChunks.scored("src/OrderService.java", 10, 40, 0.82));
        FakeAnswerGenerator generator = new FakeAnswerGenerator("下单时会校验金额必须大于 0 [1]。");
        QaService service = service(new FakeRetriever(evidence), generator);

        AskResponse response = service.ask(AskRequest.of("下单接口对金额做了哪些校验？"));

        assertThat(response.refused()).isFalse();
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).path()).isEqualTo("src/OrderService.java");
        assertThat(response.citations().get(0).startLine()).isEqualTo(10);
        assertThat(generator.calls.get()).isEqualTo(1);
        assertThat(response.diagnostics().usedChunks()).isEqualTo(1);
    }

    @Test
    @DisplayName("引用越界时保留答案但追加告警")
    void should_append_warning_when_citation_is_invalid() {
        List<ScoredChunk> evidence = List.of(TestChunks.scored("src/OrderService.java", 10, 40, 0.82));
        FakeAnswerGenerator generator = new FakeAnswerGenerator("依据片段 [9]。");
        QaService service = service(new FakeRetriever(evidence), generator);

        AskResponse response = service.ask(AskRequest.of("金额校验？"));

        assertThat(response.answer()).contains("引用校验未通过");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    @DisplayName("最高分低于阈值时拒答")
    void should_refuse_when_score_below_threshold() {
        List<ScoredChunk> weak = List.of(TestChunks.scored("src/Other.java", 1, 5, 0.11));
        FakeAnswerGenerator generator = new FakeAnswerGenerator("不应被调用");
        QaService service = service(new FakeRetriever(weak), generator);

        AskResponse response = service.ask(AskRequest.of("订单模块用了什么消息队列？"));

        assertThat(response.refused()).isTrue();
        assertThat(response.answer()).contains("相关性过低");
        assertThat(generator.calls.get()).isZero();
    }

    @Test
    @DisplayName("提示中包含证据定位信息，且编号从 1 开始")
    void should_embed_locations_into_prompt() {
        List<ScoredChunk> evidence = List.of(
                TestChunks.scored("src/A.java", 1, 5, 0.9),
                TestChunks.scored("src/B.java", 6, 9, 0.8));
        FakeAnswerGenerator generator = new FakeAnswerGenerator("结论 [1][2]。");
        QaService service = service(new FakeRetriever(evidence), generator);

        service.ask(AskRequest.of("随便问问"));

        String userPrompt = generator.prompts.get(0);
        assertThat(userPrompt).contains("[1] src/A.java:1-5").contains("[2] src/B.java:6-9");
    }

    @Test
    @DisplayName("引用越界时带反馈重试，第二次回答正确则不再告警")
    void should_retry_once_when_citation_is_invalid() {
        List<ScoredChunk> evidence = List.of(TestChunks.scored("src/OrderService.java", 10, 40, 0.82));
        // 第一次返回越界引用，第二次返回合法引用
        QueueAnswerGenerator generator = new QueueAnswerGenerator(
                "依据片段 [9]。", "下单会校验金额 [1]。");
        QaService service = service(new FakeRetriever(evidence), generator, 1);

        AskResponse response = service.ask(AskRequest.of("金额校验？"));

        assertThat(generator.calls.get()).isEqualTo(2);
        assertThat(response.answer()).doesNotContain("引用校验未通过");
        assertThat(response.citations()).hasSize(1);
        // 重试提示必须带上"越界编号"和可用范围，让模型知道错在哪
        assertThat(generator.prompts.get(1))
                .contains("[9]")
                .contains("可用编号只有 1 到 1");
    }

    @Test
    @DisplayName("重试仍失败时保留答案并追加告警")
    void should_keep_warning_when_retry_also_fails() {
        List<ScoredChunk> evidence = List.of(TestChunks.scored("src/OrderService.java", 10, 40, 0.82));
        QueueAnswerGenerator generator = new QueueAnswerGenerator("依据片段 [9]。", "还是 [9]。");
        QaService service = service(new FakeRetriever(evidence), generator, 1);

        AskResponse response = service.ask(AskRequest.of("金额校验？"));

        assertThat(generator.calls.get()).isEqualTo(2);
        assertThat(response.answer()).contains("引用校验未通过");
    }

    /** 按顺序返回预设答案的假生成器，用于验证重试路径。 */
    private static class QueueAnswerGenerator implements AnswerGenerator {
        private final java.util.Queue<String> answers = new java.util.ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new ArrayList<>();

        QueueAnswerGenerator(String... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            prompts.add(userPrompt);
            String answer = answers.poll();
            return answer == null ? "无更多预设答案" : answer;
        }

        @Override
        public String modelName() {
            return "queue-model";
        }
    }
}
