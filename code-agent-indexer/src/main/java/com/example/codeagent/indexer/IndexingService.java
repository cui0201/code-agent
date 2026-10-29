package com.example.codeagent.indexer;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.exception.IndexingException;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.indexer.doc.DocumentDriftDetector;
import com.example.codeagent.indexer.graph.SpoonGraphBuilder;
import com.example.codeagent.indexer.graph.SymbolGraph;
import com.example.codeagent.indexer.util.Hashing;
import com.example.codeagent.indexer.snapshot.IndexSnapshotStore;
import com.example.codeagent.indexer.IncrementalIndexCache.CachedFileView;
import com.example.codeagent.indexer.IncrementalIndexCache.Counters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引编排：扫描到切块，再到嵌入，最后写入向量存储。
 *
 * <p><b>容错策略</b>：单个文件切块失败或单个切块嵌入失败都只记录到失败清单，
 * 不中断整体索引。工程里总有语法文件或超大文件，一次失败就让整个索引作废是不可接受的。
 *
 * <p><b>写入语义</b>：先清空再批量写入，保证索引与当前代码快照一致。
 * P6 引入增量索引后会改为差量更新。
 */
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    /** 每处理多少条切块打印一次进度。 */
    private static final int PROGRESS_INTERVAL = 200;

    /**
     * 每批送给模型的切块数。
     *
     * <p>取 16 是经验值：太小摊不薄固定开销，太大则单批内存与延迟都上升，
     * 而且一批失败要整体回退重试。真正的批量能力由 {@code embedAll} 提供。
     */
    private static final int EMBED_BATCH_SIZE = 16;

    private final SourceScanner scanner;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final DocumentDriftDetector driftDetector;
    private final SpoonGraphBuilder graphBuilder;
    private final IndexSnapshotStore snapshotStore;

    /** 上次索引的产物，用于增量复用。索引是串行流程，无需并发保护。 */
    private final IncrementalIndexCache incrementalCache = new IncrementalIndexCache();

    /** 上次构建的符号图；没有任何 Java 文件变化时直接复用。 */
    private SymbolGraph lastSymbolGraph = new SymbolGraph();

    public IndexingService(SourceScanner scanner,
                           Chunker chunker,
                           EmbeddingClient embeddingClient,
                           VectorStore vectorStore) {
        this(scanner, chunker, embeddingClient, vectorStore,
                new DocumentDriftDetector(), new SpoonGraphBuilder(), null);
    }

    public IndexingService(SourceScanner scanner,
                           Chunker chunker,
                           EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           DocumentDriftDetector driftDetector,
                           SpoonGraphBuilder graphBuilder) {
        this(scanner, chunker, embeddingClient, vectorStore, driftDetector, graphBuilder, null);
    }

    public IndexingService(SourceScanner scanner,
                           Chunker chunker,
                           EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           DocumentDriftDetector driftDetector,
                           SpoonGraphBuilder graphBuilder,
                           IndexSnapshotStore snapshotStore) {
        this.scanner = scanner;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.driftDetector = driftDetector;
        this.graphBuilder = graphBuilder;
        this.snapshotStore = snapshotStore;
    }

    /**
     * 索引一个工程目录（第二次起自动走增量）。
     *
     * <p><b>执行顺序不能随意调换</b>，每一步都依赖前一步的产物：
     * <ol>
     *   <li><b>扫描</b>：按 include/exclude 规则读入文件，统一行尾并计算内容指纹；</li>
     *   <li><b>逐文件复用或重新切块</b>：文件哈希未变则直接取上次的切块与向量，
     *       变了才重新切块，并把它的向量标记为"待计算"；</li>
     *   <li><b>符号图</b>：只在有 Java 文件变化时重建。Spoon 解析约 70 ms/文件，
     *       是除嵌入之外最贵的一步，没变就不该重算；</li>
     *   <li><b>漂移检测</b>：每次都要跑。它依赖"文件之间的比较结果"，
     *       别的文件变了会让本文档的过期状态变化，不能复用；
     *       但它只改元数据、不碰内容，因此不影响已复用的向量；</li>
     *   <li><b>向量化</b>：只嵌入上一步标记为待计算的切块；</li>
     *   <li><b>写入</b>：清空后批量写，保证索引与当前代码快照一致。</li>
     * </ol>
     *
     * <p><b>增量省掉的是什么</b>：切块与嵌入的计算成本。写入仍是全量替换
     * （内存存储与 Qdrant 都是先清后写），因为按块做差量更新的收益
     * 远小于它带来的下标错位风险。
     *
     * @param root 工程根目录
     * @return 索引报告、切块与符号图
     */
    public IndexingResult index(Path root) {
        long started = System.currentTimeMillis();
        log.info("开始索引: root={}, 嵌入模型={}, 维度={}",
                root.toAbsolutePath(), embeddingClient.modelName(), embeddingClient.dimension());

        List<SourceFile> files = scanner.scan(root);
        // 跳过与失败都记在这里，最后一起返回给调用方——用户需要知道"哪些文件没进去"
        List<String> failures = new ArrayList<>(scanner.skippedFiles());

        // 第 1.5 步：载入上次的快照。
        // 必须在扫描之后（要拿当前哈希做比对），在切块之前（命中就直接复用）。
        // 进程重启后这一步能把"重新嵌入 + 重新解析"整个省掉
        if (snapshotStore != null) {
            snapshotStore.load().ifPresent(snapshot -> {
                snapshot.cached().forEach((path, view) -> incrementalCache.restoreFile(
                        path, snapshot.fileHashes().getOrDefault(path, ""), view));
                lastSymbolGraph = snapshot.graph();
                log.info("已从快照恢复: 文件={}, 符号={}",
                        snapshot.cached().size(), snapshot.graph().symbolCount());
            });
        }

        // 第 2 步：逐文件复用或重新切块。
        // vectors 里为 null 的位置表示"这一块的向量还需计算"——
        // 用空位占住下标，后面才能把新算的向量填回正确位置
        List<Chunk> allChunks = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>();
        Map<String, String> fileHashes = new LinkedHashMap<>();
        Counters counters = new Counters();
        boolean javaChanged = false;
        int processedFiles = 0;

        for (SourceFile file : files) {
            fileHashes.put(file.path(), file.hash());
            CachedFileView cached = incrementalCache.reuse(file.path(), file.hash());
            if (cached != null) {
                allChunks.addAll(cached.chunks());
                vectors.addAll(cached.vectors());
                counters.markReused();
                processedFiles++;
                continue;
            }
            if ("java".equals(file.extension())) {
                javaChanged = true;
            }
            try {
                List<Chunk> fileChunks = chunker.chunk(file);
                allChunks.addAll(fileChunks);
                for (int i = 0; i < fileChunks.size(); i++) {
                    vectors.add(null);
                }
                counters.markRechunked();
                processedFiles++;
            } catch (RuntimeException e) {
                // 单文件切块失败不影响其他文件：真实工程里总有语法异常或超大文件，
                // 一次失败就让整个索引作废是不可接受的
                failures.add(file.path() + " (切块失败: " + e.getMessage() + ")");
                log.warn("切块失败: {}", file.path(), e);
            }
        }
        IncrementalIndexCache.Stats reuseStats = counters.toStats();
        log.info("扫描完成: 文件={}, 切块={}, 复用文件={}, 需重新切块={}",
                processedFiles, allChunks.size(), reuseStats.reusedFiles(),
                reuseStats.rechunkedFiles());

        // 第 3 步：符号图。没有任何 Java 文件变化时直接复用上次的图
        SymbolGraph symbolGraph;
        if (javaChanged || lastSymbolGraph.symbolCount() == 0) {
            symbolGraph = graphBuilder.build(files);
        } else {
            symbolGraph = lastSymbolGraph;
            log.info("没有 Java 文件变化，复用已有符号图: 节点={}, 边={}",
                    symbolGraph.symbolCount(), symbolGraph.edgeCount());
        }
        lastSymbolGraph = symbolGraph;

        // 第 4 步：文档漂移检测。
        // 必须在向量化之前——过期标记会写进元数据，供检索降权与提示组装使用；
        // 而它对向量本身没有影响，所以只要在建索引时算一次即可
        Map<String, Long> fileLastModified = new HashMap<>();
        files.forEach(file -> fileLastModified.put(file.path(), file.lastModifiedMillis()));
        List<Chunk> chunks = driftDetector.markStale(allChunks, fileLastModified);

        List<Chunk> indexedChunks = new ArrayList<>(chunks.size());
        List<float[]> indexedVectors = new ArrayList<>(chunks.size());
        int embeddedChunks = 0;

        // 第 5 步：向量化。只嵌入 vectors 里为 null 的位置（即本次重新切块的块），
        // 并且按批送进模型：批量推理比逐条调用少掉大量固定开销
        // （tokenizer 初始化、张量分配、会话调度）
        for (int start = 0; start < chunks.size(); start += EMBED_BATCH_SIZE) {
            int end = Math.min(start + EMBED_BATCH_SIZE, chunks.size());
            List<Integer> pending = new ArrayList<>();
            for (int i = start; i < end; i++) {
                if (vectors.get(i) == null) {
                    pending.add(i);
                }
            }
            if (!pending.isEmpty()) {
                embedBatch(chunks, vectors, pending, failures);
                embeddedChunks += pending.stream()
                        .filter(i -> vectors.get(i) != null)
                        .count();
            }
            if (end % PROGRESS_INTERVAL < EMBED_BATCH_SIZE) {
                log.info("嵌入进度: {}/{}", end, chunks.size());
            }
        }

        // 组装最终结果：向量为 null 的块（嵌入失败）不进索引。
        // 宁可少一块，也不要让下标与向量错位——那会让检索结果指向错误的代码
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector != null) {
                indexedChunks.add(chunks.get(i));
                indexedVectors.add(vector);
            } else {
                failures.add(chunks.get(i).location() + " (嵌入失败，已跳过)");
            }
        }

        // 第 6 步：写入。先清空再批量写，语义等同于"用当前快照整体替换旧索引"
        vectorStore.clear();
        vectorStore.add(indexedChunks, indexedVectors);

        // 回写增量缓存：按文件分组，供下次索引复用。
        // 嵌入失败的块没有进入 indexedChunks，因此这里的分组天然只包含成功入库的块——
        // 缓存里存的必须是"真正可用"的产物，否则下次复用会把失败结果当成好的
        Map<String, List<Chunk>> chunksByFile = new LinkedHashMap<>();
        Map<String, List<float[]>> vectorsByFile = new LinkedHashMap<>();
        for (int i = 0; i < indexedChunks.size(); i++) {
            String path = indexedChunks.get(i).path();
            chunksByFile.computeIfAbsent(path, key -> new ArrayList<>()).add(indexedChunks.get(i));
            vectorsByFile.computeIfAbsent(path, key -> new ArrayList<>()).add(indexedVectors.get(i));
        }
        incrementalCache.update(fileHashes, chunksByFile, vectorsByFile);

        // 语料指纹：把「路径:哈希」按路径排序后拼接再哈希。
        // 排序是必要的——否则文件遍历顺序变化会导致指纹变化，
        // 同一份代码算出两个指纹，报告就没法比较了
        String corpusFingerprint = computeCorpusFingerprint(fileHashes);

        long elapsed = System.currentTimeMillis() - started;
        IndexReport report = new IndexReport(
                processedFiles, indexedChunks.size(), failures.size(),
                reuseStats.reusedFiles(), embeddedChunks, corpusFingerprint, elapsed, failures);

        // 保存快照：下次启动可直接复用，不必重新嵌入与解析
        if (snapshotStore != null) {
            snapshotStore.save(report, fileHashes, incrementalCache.snapshot(),
                    symbolGraph);
        }
        log.info("索引完成: 文件={}, 块={}, 复用文件={}, 实际嵌入块={}, 失败={}, 耗时={}ms",
                report.fileCount(),
                report.chunkCount(),
                report.reusedFiles(),
                report.embeddedChunks(),
                report.skippedFiles(),
                report.elapsedMs());
        return new IndexingResult(report, indexedChunks, symbolGraph);
    }

    /** 计算语料指纹，见 {@link IndexReport#corpusFingerprint()}。 */
    private static String computeCorpusFingerprint(Map<String, String> fileHashes) {
        StringBuilder builder = new StringBuilder();
        fileHashes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append(entry.getKey())
                        .append(':').append(entry.getValue()).append('\n'));
        return Hashing.sha256(builder.toString()).substring(0, 16);
    }

    /**
     * 嵌入一批切块。
     *
     * <p>批量失败时回退到逐条重试：宁可慢一点，也不该让"某一块的输入异常"
     * 连累整批 16 个块全部进不了索引。
     */
    private void embedBatch(List<Chunk> chunks, List<float[]> vectors,
                            List<Integer> pending, List<String> failures) {
        List<String> texts = pending.stream().map(i -> chunks.get(i).content()).toList();
        try {
            List<float[]> embedded = embeddingClient.embedAll(texts);
            if (embedded.size() != pending.size()) {
                throw new IndexingException("批量嵌入返回数量与输入不一致");
            }
            for (int k = 0; k < pending.size(); k++) {
                vectors.set(pending.get(k), embedded.get(k));
            }
        } catch (RuntimeException batchFailure) {
            log.warn("批量嵌入失败，回退逐条重试: {}", batchFailure.getMessage());
            for (int k = 0; k < pending.size(); k++) {
                int index = pending.get(k);
                try {
                    vectors.set(index, embeddingClient.embed(texts.get(k)));
                } catch (RuntimeException e) {
                    log.warn("嵌入失败: {}", chunks.get(index).location(), e);
                }
            }
        }
    }
}
