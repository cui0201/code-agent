package com.example.codeagent.web.service;

import com.example.codeagent.core.model.AskResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量指标收集器。
 *
 * <h2>为什么不直接用 Micrometer</h2>
 *
 * 生产环境该用 Micrometer + Prometheus，这个项目也预留了迁移路径
 * （见 README 的说明）。但当前阶段需要的只有四个计数器和两个延迟分位数，
 * 为此引入 actuator + micrometer + 导出器一整套依赖并不划算——
 * 尤其是这个项目的构建环境下载依赖很慢。
 *
 * <p>这里的实现只依赖 JDK，接口形态刻意与 Micrometer 对齐
 * （计数器 + 计时器 + 快照），将来替换时改动面很小。
 *
 * <h2>为什么要记分位数</h2>
 *
 * 只看平均值会把问题盖住：一次 5 秒的慢请求在 100 次平均里只体现为多 50 ms，
 * 但用户体验是"偶尔卡得没法用"。P95 才能暴露这类问题。
 */
public class AgentMetrics {

    /** 延迟样本的保留条数：够算分位数，又不会无限增长。 */
    private static final int MAX_SAMPLES = 500;

    private final AtomicLong askCount = new AtomicLong();
    private final AtomicLong refusalCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();
    private final AtomicLong invalidCitationCount = new AtomicLong();
    private final AtomicLong totalRetrievalMs = new AtomicLong();
    private final AtomicLong totalGenerationMs = new AtomicLong();

    private final Deque<Long> retrievalSamples = new ArrayDeque<>();
    private final Deque<Long> generationSamples = new ArrayDeque<>();

    /** 记录一次问答。 */
    public void record(AskResponse response) {
        if (response == null) {
            return;
        }
        askCount.incrementAndGet();
        if (response.refused()) {
            refusalCount.incrementAndGet();
        }
        // 引用校验未通过时答案里会被追加提示，这里用它作为"校验失败"的信号
        if (response.answer() != null && response.answer().contains("引用校验未通过")) {
            invalidCitationCount.incrementAndGet();
        }
        if (response.diagnostics() == null) {
            return;
        }
        if (response.diagnostics().cached()) {
            cacheHitCount.incrementAndGet();
        } else {
            recordSample(retrievalSamples, response.diagnostics().retrievalMs());
            recordSample(generationSamples, response.diagnostics().generationMs());
            totalRetrievalMs.addAndGet(response.diagnostics().retrievalMs());
            totalGenerationMs.addAndGet(response.diagnostics().generationMs());
        }
    }

    private void recordSample(Deque<Long> samples, long value) {
        synchronized (samples) {
            samples.addLast(value);
            while (samples.size() > MAX_SAMPLES) {
                samples.removeFirst();
            }
        }
    }

    /** 指标快照，用于对外暴露。 */
    public record Snapshot(
            long asks,
            long refusals,
            long cacheHits,
            long invalidCitations,
            double cacheHitRatio,
            double refusalRatio,
            long retrievalP50Ms,
            long retrievalP95Ms,
            long generationP50Ms,
            long generationP95Ms
    ) {
    }

    public Snapshot snapshot() {
        long asks = askCount.get();
        return new Snapshot(
                asks,
                refusalCount.get(),
                cacheHitCount.get(),
                invalidCitationCount.get(),
                asks == 0 ? 0.0 : (double) cacheHitCount.get() / asks,
                asks == 0 ? 0.0 : (double) refusalCount.get() / asks,
                percentile(retrievalSamples, 0.50),
                percentile(retrievalSamples, 0.95),
                percentile(generationSamples, 0.50),
                percentile(generationSamples, 0.95));
    }

    /** 计算分位数；样本为空时返回 0。 */
    private long percentile(Deque<Long> samples, double ratio) {
        List<Long> sorted;
        synchronized (samples) {
            if (samples.isEmpty()) {
                return 0L;
            }
            sorted = new ArrayList<>(samples);
        }
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(ratio * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** 清空全部指标，供测试使用。 */
    public void reset() {
        askCount.set(0);
        refusalCount.set(0);
        cacheHitCount.set(0);
        invalidCitationCount.set(0);
        totalRetrievalMs.set(0);
        totalGenerationMs.set(0);
        synchronized (retrievalSamples) {
            retrievalSamples.clear();
        }
        synchronized (generationSamples) {
            generationSamples.clear();
        }
    }
}
