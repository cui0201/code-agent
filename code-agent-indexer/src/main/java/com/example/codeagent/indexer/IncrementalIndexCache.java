package com.example.codeagent.indexer;

import com.example.codeagent.core.model.Chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 增量索引缓存：按文件缓存上一次的切块与向量。
 *
 * <h2>为什么以"文件"为复用单位</h2>
 *
 * 看起来以代码块为单位更精细，但行不通：改动一个方法会让它之后的所有
 * 代码块行号整体位移，导致"块 id 还能对应上、但行号全变了"。
 * 文件是天然的变更边界（git diff 也是按文件报的），一个文件的内容变了
 * 就整体重新切块、重新嵌入，逻辑简单且不会出错。
 *
 * <h2>为什么复用向量的前提是"内容哈希相同"</h2>
 *
 * 向量只由切块内容决定，与元数据无关。因此即使漂移检测改写了文档块的
 * 元数据（标记过期），只要内容没变，向量依然有效。
 * 这也是这里按"文件哈希"而不是"切块哈希"缓存的原因：文件没变，
 * 它切出来的块与向量必然没变。
 *
 * <p>线程安全：索引是串行流程，这里不做并发保护，但对外只暴露不可变视图。
 */
public class IncrementalIndexCache {

    /** 单个文件上一次的索引产物。 */
    private record CachedFile(String fileHash, List<Chunk> chunks, List<float[]> vectors) {

        CachedFile {
            chunks = List.copyOf(chunks);
            vectors = List.copyOf(vectors);
        }
    }

    private final Map<String, CachedFile> cache = new HashMap<>();

    /** 缓存中的文件数。 */
    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    /**
     * 尝试复用某个文件的切块与向量。
     *
     * @param path     文件相对路径
     * @param fileHash 当前内容哈希
     * @return 命中时有值；未命中或内容已变时为空
     */
    public CachedFileView reuse(String path, String fileHash) {
        CachedFile cached = cache.get(path);
        if (cached == null || !cached.fileHash().equals(fileHash)) {
            return null;
        }
        return new CachedFileView(cached.chunks(), cached.vectors());
    }

    /**
     * 一个文件的复用结果。
     *
     * <p>切块与向量一一对应，且顺序一致——调用方必须保持这个对应关系，
     * 否则会把向量配到错误的切块上。
     */
    public record CachedFileView(List<Chunk> chunks, List<float[]> vectors) {
    }

    /**
     * 用本次索引结果整体替换缓存。
     *
     * <p>只保留本次真正入库的文件：已删除的文件自然被淘汰，
     * 不需要额外的失效逻辑。
     *
     * @param files  本次扫描到的文件（路径 → 哈希）
     * @param chunks 最终入库的切块（已按文件分组顺序排列）
     * @param vectors 与 chunks 一一对应的向量
     */
    public void update(Map<String, String> files,
                       Map<String, List<Chunk>> chunksByFile,
                       Map<String, List<float[]>> vectorsByFile) {
        Set<String> alive = new HashSet<>(files.keySet());
        cache.keySet().retainAll(alive);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            List<Chunk> chunks = chunksByFile.getOrDefault(path, List.of());
            List<float[]> vectors = vectorsByFile.getOrDefault(path, List.of());
            if (chunks.size() != vectors.size()) {
                // 数量不一致说明上游有 bug（嵌入失败没被记录下来）。
                // 这里选择跳过缓存而不是缓存错误数据——下次索引会重新计算，代价可控。
                cache.remove(path);
                continue;
            }
            cache.put(path, new CachedFile(entry.getValue(), chunks, vectors));
        }
    }

    /** 供报告使用的统计信息。 */
    public record Stats(int reusedFiles, int rechunkedFiles, int embeddedChunks) {

        public static Stats empty() {
            return new Stats(0, 0, 0);
        }
    }

    /** 累加统计用的可变计数器，避免在索引主流程里散落一堆 int。 */
    public static class Counters {
        private int reusedFiles;
        private int rechunkedFiles;
        private int embeddedChunks;

        public void markReused() {
            reusedFiles++;
        }

        public void markRechunked() {
            rechunkedFiles++;
        }

        public void addEmbedded(int count) {
            embeddedChunks += count;
        }

        public Stats toStats() {
            return new Stats(reusedFiles, rechunkedFiles, embeddedChunks);
        }
    }

    /** 便于测试断言：返回缓存中所有文件的路径。 */
    public List<String> cachedPaths() {
        return new ArrayList<>(cache.keySet());
    }

    /**
     * 导出当前全部缓存，供持久化使用。
     *
     * <p>只导出真正可用的产物：切块与向量数量必须一致，否则说明上游有嵌入失败，
     * 这类不一致的条目绝不能落盘——下次启动把它当成好的复用，就会让索引缺块。
     */
    public Map<String, CachedFileView> snapshot() {
        Map<String, CachedFileView> result = new HashMap<>();
        cache.forEach((path, cached) -> {
            if (cached.chunks().size() == cached.vectors().size()) {
                result.put(path, new CachedFileView(cached.chunks(), cached.vectors()));
            }
        });
        return result;
    }

    /** 用持久化的快照恢复缓存（通常在进程启动时调用）。 */
    public void restore(Map<String, CachedFileView> saved) {
        cache.clear();
        if (saved == null) {
            return;
        }
        saved.forEach((path, view) -> {
            if (view == null || view.chunks().size() != view.vectors().size()) {
                return;
            }
            // 文件哈希在快照里单独保存，恢复时由上层写入 SourceFile.hash；
            // 这里先用空串占位，真正的命中判断仍然比对哈希，不会误命中
            cache.put(path, new CachedFile("", view.chunks(), view.vectors()));
        });
    }

    /**
     * 用快照恢复一个文件。
     *
     * <p>与 {@link #restore} 的区别是带上了文件哈希，因此恢复后可以直接命中复用。
     */
    public void restoreFile(String path, String fileHash, CachedFileView view) {
        if (path == null || fileHash == null || view == null
                || view.chunks().size() != view.vectors().size()) {
            return;
        }
        cache.put(path, new CachedFile(fileHash, view.chunks(), view.vectors()));
    }
}
