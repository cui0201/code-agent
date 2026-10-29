package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAstChunkerTest {

    private final JavaAstChunker chunker = JavaAstChunker.withLineWindowFallback(60, 10);

    /** 逐行构造源码，便于精确断言行号。 */
    private static String source(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static SourceFile file(String content) {
        return new SourceFile("src/main/java/com/acme/order/OrderService.java", content, "hash-1");
    }

    private static SourceFile serviceFile() {
        return file(source(
                "package com.acme.order;",                                          // 1
                "",                                                                 // 2
                "import java.math.BigDecimal;",                                     // 3
                "",                                                                 // 4
                "/**",                                                              // 5
                " * 订单服务。",                                                     // 6
                " */",                                                              // 7
                "public class OrderService {",                                      // 8
                "",                                                                 // 9
                "    private static final BigDecimal MAX = new BigDecimal(\"100\");", // 10
                "",                                                                 // 11
                "    /**",                                                          // 12
                "     * 创建订单。",                                                 // 13
                "     */",                                                          // 14
                "    public Order createOrder(OrderRequest request) {",             // 15
                "        validate(request.amount());",                              // 16
                "        return null;",                                             // 17
                "    }",                                                            // 18
                "",                                                                 // 19
                "    private void validate(BigDecimal amount) {",                   // 20
                "        if (amount == null) {",                                    // 21
                "            throw new IllegalArgumentException(\"金额不能为空\");",  // 22
                "        }",                                                        // 23
                "    }",                                                            // 24
                "",                                                                 // 25
                "    static class Inner {",                                         // 26
                "        void inner() {",                                           // 27
                "        }",                                                        // 28
                "    }",                                                            // 29
                "}"                                                                 // 30
        ));
    }

    private static Chunk chunkOf(List<Chunk> chunks, String id) {
        return chunks.stream()
                .filter(chunk -> chunk.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到切块: " + id
                        + "，实际为 " + chunks.stream().map(Chunk::id).toList()));
    }

    @Test
    @DisplayName("按类与方法切块：类、方法、嵌套类各一个块")
    void should_produce_class_and_method_chunks() {
        List<Chunk> chunks = chunker.chunk(serviceFile());

        assertThat(chunks).extracting(Chunk::id).containsExactlyInAnyOrder(
                "com.acme.order.OrderService",
                "com.acme.order.OrderService#createOrder(OrderRequest)",
                "com.acme.order.OrderService#validate(BigDecimal)",
                "com.acme.order.OrderService.Inner",
                "com.acme.order.OrderService.Inner#inner()");
    }

    @Test
    @DisplayName("方法块的行号覆盖 Javadoc 起始行到方法结束行")
    void should_include_javadoc_in_method_line_range() {
        List<Chunk> chunks = chunker.chunk(serviceFile());

        Chunk createOrder = chunkOf(chunks, "com.acme.order.OrderService#createOrder(OrderRequest)");
        assertThat(createOrder.kind()).isEqualTo(ChunkKind.METHOD);
        assertThat(createOrder.startLine()).isEqualTo(12);
        assertThat(createOrder.endLine()).isEqualTo(18);
        assertThat(createOrder.content()).startsWith("    /**");
        assertThat(createOrder.content()).contains("validate(request.amount());");

        Chunk validate = chunkOf(chunks, "com.acme.order.OrderService#validate(BigDecimal)");
        assertThat(validate.startLine()).isEqualTo(20);
        assertThat(validate.endLine()).isEqualTo(24);
    }

    @Test
    @DisplayName("类块的行号覆盖整个类，内容是合成摘要而非全文")
    void should_build_class_summary_chunk() {
        List<Chunk> chunks = chunker.chunk(serviceFile());

        Chunk clazz = chunkOf(chunks, "com.acme.order.OrderService");
        assertThat(clazz.kind()).isEqualTo(ChunkKind.CLASS);
        assertThat(clazz.startLine()).isEqualTo(5);
        assertThat(clazz.endLine()).isEqualTo(30);
        assertThat(clazz.metadata()).containsEntry("synthesized", true);
        assertThat(clazz.metadata()).containsEntry("fqn", "com.acme.order.OrderService");

        // 摘要保留字段与方法签名
        assertThat(clazz.content()).contains("public class OrderService");
        assertThat(clazz.content()).contains("BigDecimal MAX");
        assertThat(clazz.content()).contains("Order createOrder(OrderRequest request);");

        // 但不包含方法体，避免同一个方法体被索引两次
        assertThat(clazz.content()).doesNotContain("validate(request.amount())");
    }

    @Test
    @DisplayName("嵌套类使用外层类名作为前缀")
    void should_prefix_nested_type_fqn() {
        List<Chunk> chunks = chunker.chunk(serviceFile());

        Chunk inner = chunkOf(chunks, "com.acme.order.OrderService.Inner");
        assertThat(inner.startLine()).isEqualTo(26);
        assertThat(inner.endLine()).isEqualTo(29);
        assertThat(inner.metadata()).containsEntry("parentFqn", "com.acme.order.OrderService");
    }

    @Test
    @DisplayName("方法块 ID 不含行号，插入空行后 ID 保持不变")
    void should_keep_stable_ids_when_line_numbers_shift() {
        Set<String> before = chunker.chunk(serviceFile()).stream()
                .map(Chunk::id).collect(Collectors.toSet());

        // 在文件头部插入 3 个空行，所有行号都会下移，但语义未变
        SourceFile shifted = file("\n\n\n" + serviceFile().content());
        Set<String> after = chunker.chunk(shifted).stream()
                .map(Chunk::id).collect(Collectors.toSet());

        assertThat(after).isEqualTo(before);
        // 行号确实变了，证明"ID 稳定"不是巧合
        assertThat(chunkOf(chunker.chunk(shifted), "com.acme.order.OrderService#validate(BigDecimal)")
                .startLine()).isEqualTo(23);
    }

    @Test
    @DisplayName("接口、枚举与 record 都能正确切块")
    void should_handle_interface_enum_and_record() {
        SourceFile file = file(source(
                "package com.acme;",
                "",
                "public interface Foo {",
                "    void bar();",
                "}",
                "",
                "enum Color {",
                "    RED, GREEN",
                "}",
                "",
                "record Point(int x, int y) {",
                "}"));

        List<Chunk> chunks = chunker.chunk(file);

        assertThat(chunks).extracting(Chunk::id).containsExactlyInAnyOrder(
                "com.acme.Foo", "com.acme.Foo#bar()", "com.acme.Color", "com.acme.Point");
        assertThat(chunkOf(chunks, "com.acme.Foo").content()).contains("public interface Foo");
        assertThat(chunkOf(chunks, "com.acme.Color").content()).contains("enum Color");
        assertThat(chunkOf(chunks, "com.acme.Point").content()).contains("record Point");
    }

    @Test
    @DisplayName("语法错误时降级为定长窗口切块，而不是整体失败")
    void should_fall_back_when_parsing_fails() {
        SourceFile broken = file(source(
                "这不是 Java 代码",
                "%%% 完全没有语法结构 %%%",
                "",
                "!!!"));

        List<Chunk> chunks = chunker.chunk(broken);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata()).containsEntry("chunker", "line-window"));
    }

    @Test
    @DisplayName("注解进入元数据，便于 Spring 相关问题检索")
    void should_collect_annotations() {
        SourceFile file = file(source(
                "package com.acme;",
                "",
                "public class Service {",
                "    @Transactional",
                "    public void save() {",
                "    }",
                "}"));

        List<Chunk> chunks = chunker.chunk(file);

        assertThat(chunkOf(chunks, "com.acme.Service#save()").metadata())
                .containsEntry("annotations", List.of("@Transactional"));
    }
}
