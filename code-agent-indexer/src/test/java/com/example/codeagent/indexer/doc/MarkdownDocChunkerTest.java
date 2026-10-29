package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocChunkerTest {

    private final MarkdownDocChunker chunker = new MarkdownDocChunker();

    private static SourceFile file(String... lines) {
        return new SourceFile("docs/api/order-api.md", String.join("\n", lines) + "\n",
                "hash-doc", 1_700_000_000_000L);
    }

    @Test
    @DisplayName("按标题层级切章节，锚点与标题路径都正确")
    void should_chunk_by_heading() {
        List<Chunk> chunks = chunker.chunk(file(
                "# 订单 API",              // 1
                "",                        // 2
                "总览。",                   // 3
                "",                        // 4
                "## 创建订单",              // 5
                "",                        // 6
                "POST /api/orders",        // 7
                "",                        // 8
                "### 请求字段",             // 9
                "",                        // 10
                "| 字段 | 说明 |"));        // 11

        assertThat(chunks).extracting(Chunk::id).containsExactly(
                "docs/api/order-api.md#订单-api",
                "docs/api/order-api.md#创建订单",
                "docs/api/order-api.md#请求字段");

        Chunk fields = chunks.get(2);
        assertThat(fields.kind()).isEqualTo(ChunkKind.DOC_SECTION);
        assertThat(fields.startLine()).isEqualTo(9);
        assertThat(fields.endLine()).isEqualTo(11);
        assertThat(fields.metadata())
                .containsEntry(MetadataKeys.ANCHOR, "请求字段")
                .containsEntry("headingPath", "订单 API > 创建订单 > 请求字段")
                .containsEntry("docTitle", "订单 API");
        // 文档块的内容必须包含标题行本身，否则检索时丢失"这一节讲什么"的信号
        assertThat(fields.content()).startsWith("### 请求字段");
    }

    @Test
    @DisplayName("首个标题之前的正文单独成块，锚点为 preamble")
    void should_keep_preamble_chunk() {
        List<Chunk> chunks = chunker.chunk(file(
                "本文件由生成流水线产出。",   // 1
                "",                          // 2
                "# 标题",                     // 3
                "",                          // 4
                "正文"));                     // 5

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).id()).isEqualTo("docs/api/order-api.md#preamble");
        assertThat(chunks.get(0).content()).contains("本文件由生成流水线产出。");
        assertThat(chunks.get(1).id()).isEqualTo("docs/api/order-api.md#标题");
    }

    @Test
    @DisplayName("围栏代码块里的 # 不会被当成标题")
    void should_ignore_headings_inside_code_fence() {
        List<Chunk> chunks = chunker.chunk(file(
                "# 用法",                     // 1
                "",                          // 2
                "```bash",                    // 3
                "# 这是注释，不是标题",         // 4
                "mvn clean verify",           // 5
                "```",                        // 6
                "",                           // 7
                "## 结束"));                   // 8

        assertThat(chunks).extracting(Chunk::id).containsExactly(
                "docs/api/order-api.md#用法",
                "docs/api/order-api.md#结束");
        assertThat(chunks.get(0).content()).contains("# 这是注释，不是标题");
    }

    @Test
    @DisplayName("抽取反引号中的符号引用")
    void should_extract_code_references() {
        List<Chunk> chunks = chunker.chunk(file(
                "# 实现",                                                  // 1
                "",                                                       // 2
                "入口是 `OrderController#create`，内部调用 `OrderService#createOrder`。")); // 3

        assertThat(chunks.get(0).metadata()).containsEntry(MetadataKeys.CODE_REFS,
                List.of("OrderController#create", "OrderService#createOrder"));
    }

    @Test
    @DisplayName("没有标题的文档整体作为一个块")
    void should_handle_document_without_heading() {
        List<Chunk> chunks = chunker.chunk(file("只有一行说明，没有任何标题。"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).id()).isEqualTo("docs/api/order-api.md#preamble");
    }

    @Test
    @DisplayName("锚点生成规则：空格转连字符、去掉标点")
    void should_slugify_anchor() {
        assertThat(MarkdownDocChunker.slugify("请求字段")).isEqualTo("请求字段");
        assertThat(MarkdownDocChunker.slugify("Create Order")).isEqualTo("create-order");
        // 斜杠被丢弃，两个空格合并为一个连字符
        assertThat(MarkdownDocChunker.slugify("概览 / Overview")).isEqualTo("概览-overview");
    }

    @Test
    @DisplayName("同名小节生成不同锚点，避免切块 id 冲突")
    void should_make_duplicate_anchors_unique() {
        List<Chunk> chunks = chunker.chunk(file(
                "# 接口一",              // 1
                "",                     // 2
                "### 相关实现",          // 3
                "",                     // 4
                "见 `A#b`。",            // 5
                "",                     // 6
                "### 相关实现",          // 7
                "",                     // 8
                "见 `C#d`。"));          // 9

        assertThat(chunks).extracting(Chunk::id).containsExactly(
                "docs/api/order-api.md#接口一",
                "docs/api/order-api.md#相关实现",
                "docs/api/order-api.md#相关实现-1");
        assertThat(chunks).extracting(Chunk::id).doesNotHaveDuplicates();
    }
}
