package com.example.codeagent.indexer.embed;

import com.example.codeagent.core.exception.IndexingException;
import com.example.codeagent.core.math.VectorMath;
import com.example.codeagent.core.port.EmbeddingClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 内的本地 ONNX 嵌入客户端。
 *
 * <p>模型选用 bge-small-zh-v1.5 的量化版：模型文件打包在依赖 jar 中，
 * 首次使用无需联网下载，这对"除大模型外全部本地化"的约束是必要的。
 *
 * <p><b>关于语言适配</b>：该模型面向中文优化，适合"用中文提问"的场景；
 * 它对英文标识符（类名、方法名）的语义区分能力一般，P2 会通过关键词路召回补齐。
 *
 * <p>输出向量已做 L2 归一化，检索端可直接使用点积。
 */
public class LocalOnnxEmbeddingClient implements EmbeddingClient {

    private static final String MODEL_NAME = "bge-small-zh-v15-q";

    private final EmbeddingModel model;
    private final EmbeddingCache cache;
    private volatile int dimension = -1;

    public LocalOnnxEmbeddingClient() {
        this(new BgeSmallZhV15QuantizedEmbeddingModel(), EmbeddingCache.withDefaultSize());
    }

    /**
     * 供测试注入自定义模型实现。
     *
     * @param model 底层模型
     * @param cache 嵌入缓存
     */
    public LocalOnnxEmbeddingClient(EmbeddingModel model, EmbeddingCache cache) {
        this.model = model;
        this.cache = cache;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待嵌入文本不能为空");
        }
        return cache.computeIfAbsent(text, this::doEmbed);
    }

    private float[] doEmbed(String text) {
        try {
            Response<Embedding> response = model.embed(text);
            Embedding embedding = response == null ? null : response.content();
            if (embedding == null) {
                throw new IndexingException("嵌入模型返回空结果");
            }
            return VectorMath.normalized(embedding.vector());
        } catch (IndexingException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IndexingException("嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量嵌入：先查缓存，未命中的一次性交给模型批量推理。
     *
     * <p>ONNX 的批量推理会把多条文本一次性送进网络，比逐条调用少了大量固定开销。
     * 缓存命中项直接复用，不参与推理。
     */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> results = new ArrayList<>(texts.size());
        List<Integer> missingIndexes = new ArrayList<>();
        List<TextSegment> missingSegments = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("待嵌入文本不能为空");
            }
            float[] cached = cache.get(text);
            results.add(cached);
            if (cached == null) {
                missingIndexes.add(i);
                missingSegments.add(TextSegment.from(text));
            }
        }
        if (missingSegments.isEmpty()) {
            return results;
        }
        try {
            Response<List<Embedding>> response = model.embedAll(missingSegments);
            List<Embedding> embeddings = response == null ? null : response.content();
            if (embeddings == null || embeddings.size() != missingSegments.size()) {
                throw new IndexingException("批量嵌入返回的向量数量与输入不一致");
            }
            for (int i = 0; i < embeddings.size(); i++) {
                float[] vector = VectorMath.normalized(embeddings.get(i).vector());
                int index = missingIndexes.get(i);
                results.set(index, vector);
                cache.put(texts.get(index), vector);
            }
            return results;
        } catch (RuntimeException e) {
            throw new IndexingException("批量嵌入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimension() {
        int current = dimension;
        if (current < 0) {
            current = model.dimension();
            dimension = current;
        }
        return current;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    /** 底层嵌入缓存，供索引报告输出命中率。 */
    public EmbeddingCache cache() {
        return cache;
    }
}
