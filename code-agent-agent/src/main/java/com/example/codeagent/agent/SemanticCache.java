package com.example.codeagent.agent;

import com.example.codeagent.core.math.VectorMath;
import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.port.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义缓存：把问过的问题与答案缓存起来，减少重复提问的延迟与成本。
 *
 * <h2>两级结构，各自解决不同的问题</h2>
 *
 * <ul>
 *   <li><b>精确层</b>（HashMap）：归一化后字面相同的问题。命中零成本，
 *       覆盖"用户反复问同一句"这种最常见的情况；</li>
 *   <li><b>语义层</b>（线性扫描 + 余弦相似度）：字面不同但意思相同的问题，
 *       例如"下单接口校验了哪些金额规则"与"下单时金额有什么限制"。
 *       这一层承担主要收益，但需要一次嵌入计算。</li>
 * </ul>
 *
 * <h2>为什么失效策略是"整体清空"</h2>
 *
 * 缓存里的答案包含引用，而引用绑定在具体版本的代码上。
 * 代码一变，缓存里的答案与引用就可能全部失效。
 * 与其做精细的按块失效（复杂、且一旦漏判就会给出错误答案），
 * 不如在每次重建索引时整体清空——重建索引本来就是低频操作。
 *
 * <p>本类不做持久化：缓存是"加速器"而不是"数据源"，
 * 进程重启后重建即可，没必要为它引入存储与序列化的复杂度。
 */
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    /** 一条缓存记录。 */
    private record Entry(String normalizedQuestion, float[] vector, AskResponse response) {
    }

    private final EmbeddingClient embeddingClient;
    private final double threshold;
    private final int maxEntries;

    private final Map<String, Entry> exactLayer = new ConcurrentHashMap<>();
    /** 语义层用双端队列维持插入顺序，容量满时淘汰最旧的。 */
    private final Deque<Entry> semanticLayer = new ArrayDeque<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public SemanticCache(EmbeddingClient embeddingClient, double threshold, int maxEntries) {
        this.embeddingClient = embeddingClient;
        this.threshold = threshold;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * 查询结果。
     *
     * @param cachedResponse 命中时的答案；未命中为 null
     * @param queryVector    本次问题的向量。无论是否命中都会返回，
     *                       这样未命中后可以直接用它写入缓存，不必再嵌入一次
     */
    public record LookupResult(AskResponse cachedResponse, float[] queryVector) {

        public boolean hit() {
            return cachedResponse != null;
        }
    }

    /**
     * 查缓存。无论是否命中都会先算一次问题向量。
     */
    public LookupResult lookup(String question) {
        String normalized = normalize(question);
        float[] vector = embeddingClient.embed(question);

        Entry exact = exactLayer.get(normalized);
        if (exact != null) {
            hits.incrementAndGet();
            return new LookupResult(exact.response(), vector);
        }

        Entry best = null;
        double bestScore = 0.0;
        // 线性扫描：条目数上限是几千，单次比较是纯内存计算（微秒级），
        // 相比一次嵌入（几十毫秒）可以忽略，因此不需要引入向量库
        synchronized (semanticLayer) {
            for (Entry entry : semanticLayer) {
                double score = VectorMath.cosine(vector, entry.vector());
                if (score > bestScore) {
                    bestScore = score;
                    best = entry;
                }
            }
        }
        if (best != null && bestScore >= threshold) {
            hits.incrementAndGet();
            log.info("语义缓存命中: 相似度={}, 缓存问题={}",
                    String.format("%.4f", bestScore), best.normalizedQuestion());
            return new LookupResult(best.response(), vector);
        }
        misses.incrementAndGet();
        return new LookupResult(null, vector);
    }

    /**
     * 写入缓存。
     *
     * @param queryVector 由 {@link #lookup} 返回的向量，避免重复计算
     */
    public void store(String question, float[] queryVector, AskResponse response) {
        if (response == null || response.refused() || queryVector == null) {
            // 拒答结果不缓存：拒答往往是因为问题表述不好或索引不全，
            // 缓存它会让"用户换个说法再问"也拿不到答案
            return;
        }
        Entry entry = new Entry(normalize(question), queryVector, response);
        exactLayer.put(entry.normalizedQuestion(), entry);
        synchronized (semanticLayer) {
            semanticLayer.addLast(entry);
            while (semanticLayer.size() > maxEntries) {
                Entry evicted = semanticLayer.removeFirst();
                // 只有当精确层里还是这条记录时才移除，避免误删同名的新记录
                exactLayer.remove(evicted.normalizedQuestion(), evicted);
            }
        }
    }

    /**
     * 让全部缓存失效。
     *
     * <p>索引重建后必须调用：缓存答案里的引用（文件、行号、锚点）
     * 是针对旧版本代码生成的，代码一变就可能指向错误的位置。
     */
    public void invalidate() {
        int size = exactLayer.size();
        exactLayer.clear();
        synchronized (semanticLayer) {
            semanticLayer.clear();
        }
        if (size > 0) {
            log.info("语义缓存已失效（索引重建）: 清除 {} 条", size);
        }
    }

    public int size() {
        return exactLayer.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    /** 命中率，用于评估缓存是否真的有用。 */
    public double hitRatio() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    /**
     * 问题归一化：去掉首尾空白、压缩连续空白、统一小写。
     *
     * <p>只做这些"显然等价"的处理。不做同义词替换或去标点——
     * 那属于语义层的工作，放这里只会让精确层变得不可预测。
     */
    static String normalize(String question) {
        return question == null ? ""
                : question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 供日志与接口展示的统计信息。 */
    public record Stats(int size, long hits, long misses, double hitRatio) {
    }

    public Stats stats() {
        return new Stats(size(), hitCount(), missCount(), hitRatio());
    }

    /** 便于诊断：判断某个问题是否会命中精确层。 */
    public Optional<AskResponse> peekExact(String question) {
        Entry entry = exactLayer.get(normalize(question));
        return entry == null ? Optional.empty() : Optional.of(entry.response());
    }
}
