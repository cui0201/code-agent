package com.example.codeagent.retrieval.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenizerTest {

    @Test
    @DisplayName("驼峰标识符同时保留整体形式与拆分形式")
    void should_keep_both_whole_and_split_forms() {
        List<String> tokens = Tokenizer.tokenize("createOrder");

        assertThat(tokens).contains("createorder", "create", "order");
    }

    @Test
    @DisplayName("下划线常量被拆分")
    void should_split_snake_case() {
        List<String> tokens = Tokenizer.tokenize("MAX_AMOUNT");

        assertThat(tokens).contains("max", "amount");
    }

    @Test
    @DisplayName("中文按二元组切分")
    void should_bigram_chinese() {
        List<String> tokens = Tokenizer.tokenize("订单金额校验");

        assertThat(tokens).containsExactly("订单", "单金", "金额", "额校", "校验");
    }

    @Test
    @DisplayName("全限定名被切成包名与类名的组合")
    void should_split_qualified_name() {
        List<String> tokens = Tokenizer.tokenize("com.acme.order.OrderService");

        assertThat(tokens).contains("com", "acme", "order", "orderservice", "service");
    }

    @Test
    @DisplayName("中英混排互不干扰")
    void should_handle_mixed_text() {
        List<String> tokens = Tokenizer.tokenize("createOrder 是如何保证幂等的？");

        assertThat(tokens).contains("createorder", "如何", "保证", "幂等");
    }

    @Test
    @DisplayName("空文本与单字符处理")
    void should_handle_edge_cases() {
        assertThat(Tokenizer.tokenize(null)).isEmpty();
        assertThat(Tokenizer.tokenize("   ")).isEmpty();
        assertThat(Tokenizer.tokenize("a")).isEmpty();
        assertThat(Tokenizer.tokenize("中")).containsExactly("中");
    }
}
