package com.example.codeagent.indexer.embed;

import com.example.codeagent.indexer.util.Hashing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 嵌入结果缓存。
 *
 * <p>嵌入是索引阶段最贵的操作，而工程里存在大量重复文本（空行模板、getter/setter、
 * 重复的注释块）。以内容哈希为键缓存，可以让重复文本零成本。
 *
 * <p>淘汰策略刻意保持简单：条目数达到上限时整体清空。P0 不追求最优淘汰策略，
 * 只要能避免无界增长即可。
 *
 * <p>命中统计用于回答"缓存是否真的有用"，是后续优化的依据。
 */
public class EmbeddingCache {

    public static final int DEFAULT_MAX_ENTRIES = 50_000;

    private final ConcurrentHashMap<String, float[]> cache = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public EmbeddingCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries 必须大于 0");
        }
        this.maxEntries = maxEntries;
    }

    public static EmbeddingCache withDefaultSize() {
        return new EmbeddingCache(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 命中则返回缓存向量，否则调用 loader 计算并缓存。
     *
     * @param text   原始文本（内部会转成哈希作为键）
     * @param loader 真正的嵌入逻辑
     */
    public float[] computeIfAbsent(String text, Function<String, float[]> loader) {
        String key = Hashing.sha256(text);
        float[] cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        float[] computed = loader.apply(text);
        if (cache.size() >= maxEntries) {
            cache.clear();
        }
        cache.put(key, computed);
        return computed;
    }

    public int size() {
        return cache.size();
    }

    /** 只查询不计算；未命中返回 null。批量嵌入时先查一遍，避免把命中项也塞进模型。 */
    public float[] get(String text) {
        return text == null ? null : cache.get(Hashing.sha256(text));
    }

    /** 直接写入（批量推理算完后统一回填）。 */
    public void put(String text, float[] vector) {
        if (text == null || vector == null) {
            return;
        }
        if (cache.size() >= maxEntries) {
            cache.clear();
        }
        cache.put(Hashing.sha256(text), vector);
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    /** 缓存命中率，无查询时返回 0。 */
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    public void clear() {
        cache.clear();
    }
}
