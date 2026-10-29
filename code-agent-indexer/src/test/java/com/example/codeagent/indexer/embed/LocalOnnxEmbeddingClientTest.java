package com.example.codeagent.indexer.embed;

import com.example.codeagent.core.math.VectorMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 本地 ONNX 嵌入模型的验收测试。
 *
 * <p>这是整个方案"除大模型外全部本地化"能否成立的关键假设，
 * 因此必须用真实模型验证，而不是打桩：
 * 模型能否加载、维度是否符合预期、相关文本的相似度是否确实高于无关文本。
 *
 * <p>该测试不访问网络（模型文件已打包在依赖 jar 内），但首次加载需要数秒。
 */
class LocalOnnxEmbeddingClientTest {

    private final LocalOnnxEmbeddingClient client = new LocalOnnxEmbeddingClient();

    @Test
    @DisplayName("输出 512 维、已归一化的向量")
    void should_produce_normalized_vector() {
        float[] vector = client.embed("订单金额校验规则");

        assertThat(vector).hasSize(512);
        assertThat(client.dimension()).isEqualTo(512);
        assertThat(VectorMath.dot(vector, vector)).isCloseTo(1.0, within(1e-3));
        assertThat(client.modelName()).isEqualTo("bge-small-zh-v15-q");
    }

    @Test
    @DisplayName("相同文本走缓存，不重复计算")
    void should_cache_identical_text() {
        client.embed("幂等键 requestId 不能为空");
        client.embed("幂等键 requestId 不能为空");

        assertThat(client.cache().hitCount()).isEqualTo(1);
        assertThat(client.cache().missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一主题文本的相似度显著高于无关主题")
    void should_rank_related_text_higher_than_unrelated() {
        float[] query = client.embed("下单时对订单金额有哪些校验？");
        float[] related = client.embed(
                "订单金额必须大于等于 0.01 元，并且不得超过 10 万元，金额最多保留两位小数。");
        float[] unrelated = client.embed(
                "数据库连接池的最大连接数与空闲连接回收时间配置说明。");

        double relatedScore = VectorMath.cosine(query, related);
        double unrelatedScore = VectorMath.cosine(query, unrelated);

        assertThat(relatedScore).isGreaterThan(unrelatedScore);
        // 留出足够裕量，避免把偶发的近似分数当成通过
        assertThat(relatedScore - unrelatedScore).isGreaterThan(0.05);
    }
}
