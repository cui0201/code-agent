package com.example.codeagent.agent;

import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.model.Citation;
import com.example.codeagent.core.model.Diagnostics;
import com.example.codeagent.core.port.EmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheTest {

    /**
     * 可编排的假嵌入模型。
     *
     * <p>语义缓存的测试必须能精确控制"两个问题的相似度是多少"，
     * 用真模型无法做到（也无法稳定复现）。因此这里按文本显式指定向量，
     * 未指定的文本退化为基于哈希的单位向量。
     */
    private static class StubEmbeddingClient implements EmbeddingClient {
        private final Map<String, float[]> presets = new HashMap<>();
        private int calls;

        void preset(String text, float x, float y) {
            presets.put(text, new float[]{x, y});
        }

        @Override
        public float[] embed(String text) {
            calls++;
            float[] preset = presets.get(text);
            if (preset != null) {
                return preset;
            }
            float a = Math.abs(text.hashCode() % 100) + 1f;
            return new float[]{a, 1f};
        }

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public String modelName() {
            return "stub";
        }
    }

    private final StubEmbeddingClient embeddingClient = new StubEmbeddingClient();

    private final SemanticCache cache = new SemanticCache(embeddingClient, 0.95, 3);

    private static AskResponse answer(String text) {
        return new AskResponse(text,
                List.of(new Citation("src/A.java", 1, 5, null, "id-1")),
                false, List.of(), Diagnostics.empty());
    }

    @Test
    @DisplayName("字面相同的问题走精确层，命中且不比较向量")
    void should_hit_exact_layer() {
        embeddingClient.preset("下单有哪些校验", 1f, 0f);
        cache.store("下单有哪些校验", new float[]{1f, 0f}, answer("金额必须大于 0 [1]"));

        SemanticCache.LookupResult result = cache.lookup("  下单有哪些校验  ");

        assertThat(result.hit()).isTrue();
        assertThat(result.cachedResponse().answer()).contains("金额必须大于 0");
        assertThat(cache.hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("表述不同但语义相同的问题走语义层")
    void should_hit_semantic_layer() {
        cache.store("下单有哪些校验", new float[]{1f, 0f}, answer("金额必须大于 0 [1]"));
        // 与缓存问题几乎同向：余弦 1.0
        embeddingClient.preset("下单时有什么校验规则", 1f, 0f);

        SemanticCache.LookupResult result = cache.lookup("下单时有什么校验规则");

        assertThat(result.hit()).isTrue();
        assertThat(result.cachedResponse().answer()).contains("金额必须大于 0");
    }

    @Test
    @DisplayName("相似度低于阈值时不命中")
    void should_miss_when_below_threshold() {
        cache.store("下单有哪些校验", new float[]{1f, 0f}, answer("金额必须大于 0 [1]"));
        // 与缓存问题正交：余弦 0
        embeddingClient.preset("数据库连接池怎么配置", 0f, 1f);

        SemanticCache.LookupResult result = cache.lookup("数据库连接池怎么配置");

        assertThat(result.hit()).isFalse();
        assertThat(cache.missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒答结果不写入缓存")
    void should_not_cache_refusal() {
        AskResponse refused = AskResponse.refused("未找到依据", Diagnostics.empty());
        cache.store("项目用了什么消息队列", new float[]{1f, 0f}, refused);

        assertThat(cache.size()).isZero();
        assertThat(cache.lookup("项目用了什么消息队列").hit()).isFalse();
    }

    @Test
    @DisplayName("索引重建后缓存整体失效")
    void should_invalidate_all() {
        cache.store("问题一", new float[]{1f, 0f}, answer("答案一"));
        cache.store("问题二", new float[]{0f, 1f}, answer("答案二"));
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidate();

        assertThat(cache.size()).isZero();
        assertThat(cache.lookup("问题一").hit()).isFalse();
    }

    @Test
    @DisplayName("超过容量时淘汰最旧的条目")
    void should_evict_oldest_when_full() {
        cache.store("一", new float[]{1f, 0f}, answer("答案一"));
        cache.store("二", new float[]{0f, 1f}, answer("答案二"));
        cache.store("三", new float[]{1f, 1f}, answer("答案三"));
        cache.store("四", new float[]{1f, 2f}, answer("答案四"));

        // 容量是 3，第一个应被淘汰
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.peekExact("一")).isEmpty();
        assertThat(cache.peekExact("四")).isPresent();
    }

    @Test
    @DisplayName("lookup 无论是否命中都返回问题向量，避免重复嵌入")
    void should_always_return_query_vector() {
        int before = embeddingClient.calls;

        SemanticCache.LookupResult result = cache.lookup("新问题");

        assertThat(result.queryVector()).isNotNull();
        assertThat(embeddingClient.calls).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("命中率统计正确")
    void should_report_hit_ratio() {
        cache.store("问题", new float[]{1f, 0f}, answer("答案"));
        // 显式给一个正交向量：桩的默认向量在数值上可能非常接近 {1,0}，
        // 那会让"不相关问题"意外命中，掩盖统计逻辑本身的问题
        embeddingClient.preset("不相关的问题", 0f, 1f);
        cache.lookup("问题");
        cache.lookup("不相关的问题");

        SemanticCache.Stats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRatio()).isEqualTo(0.5);
    }
}
