package com.example.codeagent.indexer.contrib;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游贡献组件的测试。
 *
 * <p>这一组断言刻意围绕"上游会关心什么"来写：切出来的段落能不能定位到行、
 * 元数据键是否稳定、边界输入是否安全。因为将来提交 PR 时，
 * 评审看的第一件事就是测试覆盖了哪些行为。
 */
class JavaAstDocumentSplitterTest {

    private final JavaAstDocumentSplitter splitter = new JavaAstDocumentSplitter();

    private static Document document(String text) {
        Metadata metadata = new Metadata();
        metadata.put(Document.FILE_NAME, "src/main/java/com/acme/OrderService.java");
        return Document.from(text, metadata);
    }

    private static final String SOURCE = """
            package com.acme;

            /** 订单服务。 */
            public class OrderService {

                /** 创建订单。 */
                public Order createOrder(OrderRequest request) {
                    return null;
                }

                @Transactional
                private void validate(OrderRequest request) {
                    if (request == null) {
                        throw new IllegalArgumentException("请求不能为空");
                    }
                }
            }
            """;

    @Test
    @DisplayName("按类与方法切分，而不是按长度切")
    void should_split_by_structure() {
        List<TextSegment> segments = splitter.split(document(SOURCE));

        // 类块 + 两个方法块
        assertThat(segments).hasSize(3);
        assertThat(segments).extracting(segment -> segment.metadata().getString(
                JavaAstDocumentSplitter.KEY_CHUNK_KIND))
                .containsExactlyInAnyOrder("CLASS", "METHOD", "METHOD");
    }

    @Test
    @DisplayName("段落元数据保留行号，引用才能精确落地")
    void should_preserve_line_numbers() {
        List<TextSegment> segments = splitter.split(document(SOURCE));

        TextSegment createOrder = segments.stream()
                .filter(segment -> segment.text().contains("public Order createOrder"))
                .findFirst()
                .orElseThrow();
        // 注释行到方法结束行：上游使用者需要这个区间来标注引用
        assertThat(createOrder.metadata().getInteger(JavaAstDocumentSplitter.KEY_START_LINE))
                .isEqualTo(6);
        assertThat(createOrder.metadata().getInteger(JavaAstDocumentSplitter.KEY_END_LINE))
                .isEqualTo(9);
        assertThat(createOrder.metadata().getString(JavaAstDocumentSplitter.KEY_FQN))
                .isEqualTo("com.acme.OrderService#createOrder(OrderRequest)");
    }

    @Test
    @DisplayName("文件名写进元数据，便于多文件索引时区分来源")
    void should_carry_file_name() {
        List<TextSegment> segments = splitter.split(document(SOURCE));

        assertThat(segments).allSatisfy(segment -> assertThat(
                segment.metadata().getString(JavaAstDocumentSplitter.KEY_FILE_NAME))
                .isEqualTo("src/main/java/com/acme/OrderService.java"));
    }

    @Test
    @DisplayName("注解被保留：对“哪些接口暴露给外部”这类问题有价值")
    void should_keep_annotations() {
        List<TextSegment> segments = splitter.split(document(SOURCE));

        TextSegment validate = segments.stream()
                .filter(segment -> segment.text().contains("private void validate"))
                .findFirst()
                .orElseThrow();
        assertThat(validate.metadata().getString("annotations")).contains("Transactional");
    }

    @Test
    @DisplayName("语法错误时降级为定长窗口，不抛异常")
    void should_fall_back_on_broken_source() {
        List<TextSegment> segments = splitter.split(document("这不是 Java 代码 !!!"));

        assertThat(segments).isNotEmpty();
    }

    @Test
    @DisplayName("空文档与无文件名都安全")
    void should_handle_edge_cases() {
        assertThat(splitter.split(null)).isEmpty();

        // 注意：Document.from 本身会拒绝空白文本（上游的校验），
        // 因此"空白文档"这种输入在类型层面就不可能出现，不需要在这里构造
        List<TextSegment> segments = splitter.split(Document.from(SOURCE));
        assertThat(segments).isNotEmpty();
    }
}
