package com.example.codeagent.web;

import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.port.AnswerGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.codeagent.web.service.AgentMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 流式问答与语义缓存的端到端验收。
 *
 * <p>用假的 {@link AnswerGenerator} 替换真实大模型：这里要验证的是
 * <b>流式事件是否按约定发出</b>与<b>缓存是否真的省掉了生成调用</b>，
 * 而不是模型答得好不好。真模型既慢又需要 API Key，不适合放进回归测试。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "code-agent.index.root=../examples/demo-project",
                "code-agent.retrieval.min-score=0.2",
                "code-agent.cache.semantic-threshold=0.99"
        })
class StreamingAndCacheIT {

    /** 假生成器：支持流式，并记录被调用了多少次。 */
    static class FakeAnswerGenerator implements AnswerGenerator {

        /** 生成阶段被调用的次数（只统计真正的答案生成）。 */
        final AtomicInteger generationCalls = new AtomicInteger();
        /** 相关性判官被调用的次数。 */
        final AtomicInteger judgeCalls = new AtomicInteger();

        private boolean isJudge(String systemPrompt) {
            return systemPrompt != null && systemPrompt.contains("相关性判官");
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            if (isJudge(systemPrompt)) {
                judgeCalls.incrementAndGet();
                return "YES";
            }
            generationCalls.incrementAndGet();
            return "答案正文 [1]";
        }

        @Override
        public void generateStreaming(String systemPrompt, String userPrompt,
                                      java.util.function.Consumer<String> onDelta) {
            if (isJudge(systemPrompt)) {
                judgeCalls.incrementAndGet();
                onDelta.accept("YES");
                return;
            }
            generationCalls.incrementAndGet();
            // 分三段推送，模拟真实的逐字输出
            onDelta.accept("答案");
            onDelta.accept("正文");
            onDelta.accept(" [1]");
        }

        @Override
        public String modelName() {
            return "fake-model";
        }
    }

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeAnswerGenerator fakeAnswerGenerator() {
            return new FakeAnswerGenerator();
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FakeAnswerGenerator fakeGenerator;

    @Autowired
    private AgentMetrics metrics;

    private void index() {
        restTemplate.postForObject("/v1/index", null, Map.class);
    }

    @Test
    @DisplayName("SSE 按 delta / citations / done 的顺序发出事件")
    void should_stream_events_in_order() {
        index();
        resetCounters();

        String body = restTemplate.getForObject(
                "/v1/ask/stream?question={q}",
                String.class,
                "下单接口对订单金额做了哪些校验？");

        assertThat(body).isNotNull();
        // 三段增量文本应当分别出现，且顺序与生成顺序一致
        assertThat(body).contains("event:delta");
        assertThat(body.indexOf("\"答案\"")).isLessThan(body.indexOf("\"正文\""));
        // 引用与结束事件必须在增量文本之后
        assertThat(body.indexOf("event:citations")).isGreaterThan(body.indexOf("event:delta"));
        assertThat(body).contains("event:done");
        assertThat(body).contains("OrderService.java");
        // 一次判官调用 + 一次生成调用
        assertThat(fakeGenerator.generationCalls.get()).isEqualTo(1);
        assertThat(fakeGenerator.judgeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一问题第二次提问命中缓存，不再调用大模型")
    void should_serve_second_identical_question_from_cache() {
        index();
        resetCounters();
        String question = "createOrder 是如何保证幂等的？";

        AskResponse first = ask(question);
        AskResponse second = ask(question);

        assertThat(first.diagnostics().cached()).isFalse();
        assertThat(second.diagnostics().cached()).isTrue();
        assertThat(second.answer()).isEqualTo(first.answer());
        // 关键断言：第二次没有触发生成
        assertThat(fakeGenerator.generationCalls.get()).isEqualTo(1);
        // 缓存命中发生在判官之前，因此第二次连判官都不用调
        assertThat(fakeGenerator.judgeCalls.get()).isEqualTo(1);
        assertThat(second.diagnostics().retrievalMs()).isZero();
    }

    @Test
    @DisplayName("重建索引后缓存失效，避免返回指向旧代码的引用")
    void should_invalidate_cache_after_reindex() {
        index();
        resetCounters();
        String question = "订单有哪几种状态？";

        ask(question);
        assertThat(ask(question).diagnostics().cached()).isTrue();

        this.index();

        assertThat(ask(question).diagnostics().cached()).isFalse();
        assertThat(fakeGenerator.generationCalls.get()).isEqualTo(2);
    }

    private void resetCounters() {
        fakeGenerator.generationCalls.set(0);
        fakeGenerator.judgeCalls.set(0);
    }

    private AskResponse ask(String question) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = Map.of("question", question);
        return restTemplate.postForObject("/v1/ask",
                new HttpEntity<>(payload, headers), AskResponse.class);
    }

    @Test
    @DisplayName("指标接口能反映缓存命中与延迟分布")
    void should_expose_metrics() {
        index();
        // 指标是进程级的，先清空以免受其他用例影响
        metrics.reset();

        ask("订单有哪几种状态？");
        ask("订单有哪几种状态？");

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = restTemplate.getForObject("/v1/metrics", Map.class);

        assertThat(snapshot).isNotNull();
        assertThat(((Number) snapshot.get("asks")).longValue()).isEqualTo(2);
        assertThat(((Number) snapshot.get("cacheHits")).longValue()).isEqualTo(1);
        assertThat(((Number) snapshot.get("cacheHitRatio")).doubleValue()).isEqualTo(0.5);
        // 缓存命中的那次不计入延迟样本，因此分位数只由第一次请求贡献
        assertThat(((Number) snapshot.get("retrievalP50Ms")).longValue()).isGreaterThanOrEqualTo(0);
    }
}
