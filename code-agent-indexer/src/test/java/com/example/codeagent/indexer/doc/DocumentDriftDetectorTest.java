package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDriftDetectorTest {

    private static final long CODE_TIME = 1_700_000_000_000L;
    private static final long DOC_NEWER = CODE_TIME + 60_000L;
    private static final long DOC_OLDER = CODE_TIME - 60_000L;

    private final DocumentDriftDetector detector = new DocumentDriftDetector();

    private static Chunk codeSymbol(String fqn, String path) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fqn", fqn);
        return new Chunk(fqn, path, ChunkKind.METHOD, "code", 1, 10, metadata);
    }

    private static Chunk docSection(String path, long modified, List<String> refs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MetadataKeys.ANCHOR, "请求字段");
        metadata.put(MetadataKeys.LAST_MODIFIED, modified);
        metadata.put(MetadataKeys.CODE_REFS, refs);
        return new Chunk(path + "#请求字段", path, ChunkKind.DOC_SECTION,
                "doc content", 1, 10, metadata);
    }

    private static Map<String, Long> modifiedTimes() {
        Map<String, Long> times = new LinkedHashMap<>();
        times.put("src/main/java/com/acme/order/OrderService.java", CODE_TIME);
        times.put("src/main/java/com/acme/order/OrderRequest.java", CODE_TIME);
        return times;
    }

    @Test
    @DisplayName("符号存在且文档比代码新：不过期")
    void should_not_mark_stale_when_document_is_newer() {
        List<Chunk> chunks = List.of(
                codeSymbol("com.acme.order.OrderService#createOrder(OrderRequest)",
                        "src/main/java/com/acme/order/OrderService.java"),
                docSection("docs/api/order-api.md", DOC_NEWER,
                        List.of("OrderService#createOrder")));

        Chunk doc = detector.markStale(chunks, modifiedTimes()).get(1);

        assertThat(doc.metadata()).containsEntry(MetadataKeys.STALE, false);
        assertThat(doc.metadata()).containsEntry(MetadataKeys.LINKED_CODE_PATHS,
                List.of("src/main/java/com/acme/order/OrderService.java"));
    }

    @Test
    @DisplayName("文档引用了代码中已不存在的符号：过期")
    void should_mark_stale_when_symbol_is_missing() {
        List<Chunk> chunks = List.of(
                codeSymbol("com.acme.order.OrderService#createOrder(OrderRequest)",
                        "src/main/java/com/acme/order/OrderService.java"),
                docSection("docs/api/order-api.md", DOC_NEWER,
                        List.of("OrderService#cancelOrder")));

        Chunk doc = detector.markStale(chunks, modifiedTimes()).get(1);

        assertThat(doc.metadata()).containsEntry(MetadataKeys.STALE, true);
        assertThat(String.valueOf(doc.metadata().get(MetadataKeys.STALE_REASON)))
                .contains("OrderService#cancelOrder")
                .contains("已不存在");
    }

    @Test
    @DisplayName("代码文件比文档新：过期")
    void should_mark_stale_when_code_is_newer() {
        List<Chunk> chunks = List.of(
                codeSymbol("com.acme.order.OrderService#createOrder(OrderRequest)",
                        "src/main/java/com/acme/order/OrderService.java"),
                docSection("docs/api/order-api.md", DOC_OLDER,
                        List.of("OrderService#createOrder")));

        Chunk doc = detector.markStale(chunks, modifiedTimes()).get(1);

        assertThat(doc.metadata()).containsEntry(MetadataKeys.STALE, true);
        assertThat(String.valueOf(doc.metadata().get(MetadataKeys.STALE_REASON)))
                .contains("在文档之后被修改")
                .contains("OrderService.java");
    }

    @Test
    @DisplayName("引用类型下的字段（OrderRequest#requestId）不算失效")
    void should_resolve_field_reference_to_its_type() {
        List<Chunk> chunks = List.of(
                codeSymbol("com.acme.order.OrderRequest",
                        "src/main/java/com/acme/order/OrderRequest.java"),
                docSection("docs/api/order-api.md", DOC_NEWER, List.of("OrderRequest#requestId")));

        Chunk doc = detector.markStale(chunks, modifiedTimes()).get(1);

        // 我们的索引里没有字段级切块，能落到所属类型就认为有效，避免误报
        assertThat(doc.metadata()).containsEntry(MetadataKeys.STALE, false);
        assertThat(doc.metadata()).containsEntry(MetadataKeys.LINKED_CODE_PATHS,
                List.of("src/main/java/com/acme/order/OrderRequest.java"));
    }

    @Test
    @DisplayName("没有符号引用的文档块不会被判定过期")
    void should_not_mark_stale_without_references() {
        List<Chunk> chunks = new ArrayList<>();
        chunks.add(codeSymbol("com.acme.order.OrderService",
                "src/main/java/com/acme/order/OrderService.java"));
        chunks.add(docSection("docs/guide/getting-started.md", DOC_OLDER, List.of()));

        Chunk doc = detector.markStale(chunks, modifiedTimes()).get(1);

        assertThat(doc.metadata()).containsEntry(MetadataKeys.STALE, false);
    }

    @Test
    @DisplayName("代码块本身不被改动")
    void should_leave_code_chunks_untouched() {
        Chunk code = codeSymbol("com.acme.order.OrderService",
                "src/main/java/com/acme/order/OrderService.java");
        List<Chunk> chunks = List.of(code,
                docSection("docs/api/order-api.md", DOC_NEWER, List.of("OrderService")));

        Chunk result = detector.markStale(chunks, modifiedTimes()).get(0);

        assertThat(result).isSameAs(code);
        assertThat(result.metadata()).doesNotContainKey(MetadataKeys.STALE);
    }
}
