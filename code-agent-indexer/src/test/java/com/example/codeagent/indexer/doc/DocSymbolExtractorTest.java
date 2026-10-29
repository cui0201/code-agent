package com.example.codeagent.indexer.doc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocSymbolExtractorTest {

    @Test
    @DisplayName("抽取反引号中的方法符号，并去掉参数列表")
    void should_extract_method_symbol_without_parameters() {
        assertThat(DocSymbolExtractor.extract("见 `OrderService#createOrder(OrderRequest)`"))
                .containsExactly("OrderService#createOrder");
    }

    @Test
    @DisplayName("全限定名与嵌套类型都能识别")
    void should_extract_qualified_names() {
        assertThat(DocSymbolExtractor.extract("`com.acme.order.OrderService` 与 `Order.OrderStatus`"))
                .containsExactly("com.acme.order.OrderService", "Order.OrderStatus");
    }

    @Test
    @DisplayName("普通英文单词不被当成符号")
    void should_ignore_plain_words() {
        assertThat(DocSymbolExtractor.extract("这是 `Spring` 与 `boot` 的说明")).isEmpty();
    }

    @Test
    @DisplayName("重复引用去重且保持出现顺序")
    void should_deduplicate() {
        assertThat(DocSymbolExtractor.extract(
                "`OrderService#createOrder` 与 `OrderService#createOrder` 以及 `PaymentService#refund`"))
                .containsExactly("OrderService#createOrder", "PaymentService#refund");
    }

    @Test
    @DisplayName("命令片段不被当成符号")
    void should_ignore_commands() {
        List<String> symbols = DocSymbolExtractor.extract("运行 `mvn clean verify` 即可");

        assertThat(symbols).noneMatch(symbol -> symbol.contains(" "));
    }

    @Test
    @DisplayName("字段名、SQL 片段、HTTP 路径、文件路径都不当作符号引用")
    void should_reject_non_symbol_backticks() {
        // 这些都是真实文档里出现过的反引号内容，早期版本把它们当成符号，
        // 导致漂移检测误报"符号已不存在"
        assertThat(DocSymbolExtractor.extract("使用 `requestId` 作为幂等键")).isEmpty();
        assertThat(DocSymbolExtractor.extract("唯一索引 `(user_id, request_id)`")).isEmpty();
        assertThat(DocSymbolExtractor.extract("`GET /api/orders/{orderNo}`")).isEmpty();
        assertThat(DocSymbolExtractor.extract("`docs/api/order-api.md`")).isEmpty();
        assertThat(DocSymbolExtractor.extract("`mvn clean verify`")).isEmpty();
    }

    @Test
    @DisplayName("类型名与全限定名仍然被识别")
    void should_still_accept_type_names() {
        assertThat(DocSymbolExtractor.extract("`OrderService`"))
                .containsExactly("OrderService");
        assertThat(DocSymbolExtractor.extract("`com.acme.order.OrderService`"))
                .containsExactly("com.acme.order.OrderService");
        assertThat(DocSymbolExtractor.extract("`OrderService#createOrder`"))
                .containsExactly("OrderService#createOrder");
    }

    @Test
    @DisplayName("空输入与无反引号输入都安全")
    void should_handle_empty_input() {
        assertThat(DocSymbolExtractor.extract(null)).isEmpty();
        assertThat(DocSymbolExtractor.extract("")).isEmpty();
        assertThat(DocSymbolExtractor.extract("没有反引号的内容")).isEmpty();
    }
}
