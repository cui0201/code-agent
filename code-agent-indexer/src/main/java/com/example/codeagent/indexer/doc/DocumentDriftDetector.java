package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档漂移检测：判断文档是否已经跟不上代码。
 *
 * <p>这是"文档只能作为第二信源"这条原则的落地机制。文档会过期，
 * 而过期的文档比没有文档更危险——它会给出看起来权威、实际错误的答案。
 * 与其信任文档，不如先判断它是否还值得信任。
 *
 * <p>两条判定规则：
 * <ol>
 *   <li><b>符号已消失</b>：文档引用了 {@code OrderService#cancelOrder}，
 *       而当前代码里找不到这个符号——说明代码被删或改名了，文档必然过期；</li>
 *   <li><b>代码比文档新</b>：文档引用的代码文件最后修改时间晚于文档本身，
 *       说明代码改过而文档没重新生成。</li>
 * </ol>
 *
 * <p>两条规则都不需要保存历史状态，只依赖当前这一份索引，
 * 因此每次重建索引都能重新得出结论，不会残留脏标记。
 */
public class DocumentDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(DocumentDriftDetector.class);

    /**
     * 标记文档块的过期状态。
     *
     * @param chunks            本次索引的全部切块（代码 + 文档）
     * @param fileLastModified  文件路径到修改时间的映射
     * @return 新的切块列表，文档块被补充了 stale 等元数据
     */
    public List<Chunk> markStale(List<Chunk> chunks, Map<String, Long> fileLastModified) {
        Map<String, CodeSymbol> codeSymbols = collectCodeSymbols(chunks, fileLastModified);
        List<Chunk> result = new ArrayList<>(chunks.size());
        int staleCount = 0;

        for (Chunk chunk : chunks) {
            if (chunk.kind() != ChunkKind.DOC_SECTION) {
                result.add(chunk);
                continue;
            }
            Chunk evaluated = evaluate(chunk, codeSymbols, fileLastModified);
            if (Boolean.TRUE.equals(evaluated.metadata().get(MetadataKeys.STALE))) {
                staleCount++;
                log.info("文档块已过期: {} ({})",
                        evaluated.id(), evaluated.metadata().get(MetadataKeys.STALE_REASON));
            }
            result.add(evaluated);
        }
        log.info("文档漂移检测完成: 文档块={}, 过期={}",
                result.stream().filter(chunk -> chunk.kind() == ChunkKind.DOC_SECTION).count(),
                staleCount);
        return result;
    }

    /** 代码符号及其所属文件。 */
    private record CodeSymbol(String path, long lastModified) {
    }

    /** 收集代码块的符号（方法签名里的参数列表会被去掉）。 */
    private Map<String, CodeSymbol> collectCodeSymbols(List<Chunk> chunks,
                                                       Map<String, Long> fileLastModified) {
        Map<String, CodeSymbol> symbols = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            if (chunk.kind() == ChunkKind.DOC_SECTION) {
                continue;
            }
            Object fqn = chunk.metadata().get("fqn");
            String symbol = normalizeSymbol(fqn == null ? chunk.id() : String.valueOf(fqn));
            symbols.putIfAbsent(symbol,
                    new CodeSymbol(chunk.path(), fileLastModified.getOrDefault(chunk.path(), 0L)));
        }
        return symbols;
    }

    private Chunk evaluate(Chunk chunk,
                           Map<String, CodeSymbol> codeSymbols,
                           Map<String, Long> fileLastModified) {
        Object rawRefs = chunk.metadata().get(MetadataKeys.CODE_REFS);
        List<String> references = rawRefs instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        Set<String> linkedPaths = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String reference : references) {
            CodeSymbol resolved = resolve(reference, codeSymbols);
            if (resolved == null) {
                missing.add(reference);
            } else {
                linkedPaths.add(resolved.path());
            }
        }

        String reason = null;
        if (!missing.isEmpty()) {
            reason = "引用的符号在当前代码中已不存在: " + String.join(", ", missing);
        } else {
            long documentTime = asLong(chunk.metadata().get(MetadataKeys.LAST_MODIFIED));
            for (String path : linkedPaths) {
                long codeTime = fileLastModified.getOrDefault(path, 0L);
                if (codeTime > 0 && documentTime > 0 && codeTime > documentTime) {
                    reason = "被引用的代码在文档之后被修改: " + path;
                    break;
                }
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
        metadata.put(MetadataKeys.STALE, reason != null);
        if (reason != null) {
            metadata.put(MetadataKeys.STALE_REASON, reason);
        }
        if (!linkedPaths.isEmpty()) {
            metadata.put(MetadataKeys.LINKED_CODE_PATHS, List.copyOf(linkedPaths));
        }
        return new Chunk(chunk.id(), chunk.path(), chunk.kind(), chunk.content(),
                chunk.startLine(), chunk.endLine(), metadata);
    }

    /**
     * 把文档里的符号引用解析到代码符号。
     *
     * <p>先做完整匹配；匹配不到时退一步，只用 {@code #} 前面的类型部分再匹配一次。
     * 这一步是必要的：文档常写 {@code OrderRequest#requestId} 指代 record 的字段，
     * 而字段在我们的索引里没有独立切块，只能落到它所属的类型上。
     * 否则会产生大量"符号不存在"的误报。
     *
     * @return 命中的代码符号；无法解析时返回 null
     */
    private CodeSymbol resolve(String reference, Map<String, CodeSymbol> codeSymbols) {
        String normalized = normalizeSymbol(reference);
        CodeSymbol direct = findBySuffix(normalized, codeSymbols);
        if (direct != null) {
            return direct;
        }
        int hash = normalized.indexOf('#');
        if (hash > 0) {
            return findBySuffix(normalized.substring(0, hash), codeSymbols);
        }
        return null;
    }

    private CodeSymbol findBySuffix(String symbol, Map<String, CodeSymbol> candidates) {
        for (Map.Entry<String, CodeSymbol> entry : candidates.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.equals(symbol)
                    || candidate.endsWith("." + symbol)
                    || candidate.endsWith("#" + symbol)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 去掉方法参数列表，只保留符号本身。 */
    private static String normalizeSymbol(String symbol) {
        int parenthesis = symbol.indexOf('(');
        return parenthesis > 0 ? symbol.substring(0, parenthesis) : symbol;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
