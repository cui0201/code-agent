package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavadocHtmlChunkerTest {

    private final JavadocHtmlChunker chunker = new JavadocHtmlChunker();

    private static final String PAGE = """
            <html><head><title>OrderService</title></head><body>
              <h1 class="title">Class OrderService</h1>
              <div class="sub-title">com.acme.order</div>
              <section class="detail" id="createOrder(com.acme.order.OrderRequest)">
                <h3>createOrder</h3>
                <div class="member-signature">public Order createOrder(OrderRequest request)</div>
                <div class="block">创建订单，校验金额并做幂等判断。</div>
              </section>
              <section class="detail" id="validateAmount(java.math.BigDecimal)">
                <h3>validateAmount</h3>
                <div class="member-signature">private void validateAmount(BigDecimal amount)</div>
                <div class="block">金额必须落在 0.01 到 100000 元之间。</div>
              </section>
            </body></html>
            """;

    private static SourceFile file(String content) {
        return new SourceFile("docs/generated/javadoc/com/acme/order/OrderService.html",
                content, "hash", 1_700_000_000_000L);
    }

    @Test
    @DisplayName("按成员详情切块，锚点取 Javadoc 的 id")
    void should_chunk_by_member() {
        List<Chunk> chunks = chunker.chunk(file(PAGE));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(chunk -> chunk.metadata().get(MetadataKeys.ANCHOR))
                .containsExactlyInAnyOrder("createOrder(com.acme.order.OrderRequest)",
                        "validateAmount(java.math.BigDecimal)");
    }

    @Test
    @DisplayName("块内容包含签名与说明，能直接回答问题")
    void should_render_member_text() {
        Chunk createOrder = chunker.chunk(file(PAGE)).stream()
                .filter(chunk -> String.valueOf(chunk.metadata().get(MetadataKeys.ANCHOR))
                        .startsWith("createOrder"))
                .findFirst().orElseThrow();

        assertThat(createOrder.content())
                .contains("createOrder")
                .contains("public Order createOrder(OrderRequest request)")
                .contains("校验金额并做幂等判断");
    }

    @Test
    @DisplayName("把类名与包名拼成全限定名，与代码切块的 fqn 对齐")
    void should_derive_fqn() {
        Chunk chunk = chunker.chunk(file(PAGE)).get(0);

        assertThat(chunk.metadata()).containsEntry("docType", "javadoc");
        assertThat(String.valueOf(chunk.metadata().get("fqn")))
                .startsWith("com.acme.order.OrderService#");
    }

    @Test
    @DisplayName("没有成员 section 的页面整体成块，不至于丢失")
    void should_fallback_to_page_chunk() {
        String page = "<html><body><h1 class=\"title\">Package com.acme</h1>"
                + "<div class=\"sub-title\">com.acme</div><p>包说明</p></body></html>";

        List<Chunk> chunks = chunker.chunk(file(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata()).containsEntry(MetadataKeys.ANCHOR, "page");
        assertThat(chunks.get(0).content()).contains("包说明");
    }
}
