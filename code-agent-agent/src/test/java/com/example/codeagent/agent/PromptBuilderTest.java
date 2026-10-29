package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    @Test
    @DisplayName("超出预算的证据被裁掉，且至少保留一条")
    void should_trim_evidence_within_budget() {
        String content = "x".repeat(300);
        List<ScoredChunk> evidence = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            evidence.add(TestChunks.scored(
                    "src/File" + i + ".java", 1, 200, 0.9 - i * 0.1, content));
        }
        // 每个 chunk 内容 300 字符，预算 1000 字符时只应保留 3 条
        PromptBuilder builder = new PromptBuilder(1000);

        PromptBuilder.Prompt prompt = builder.build("问题", evidence);

        assertThat(prompt.usedChunks()).isGreaterThanOrEqualTo(1);
        assertThat(prompt.usedChunks()).isLessThan(evidence.size());
        assertThat(prompt.system()).contains("只依据下面提供的");
    }

    @Test
    @DisplayName("预算充足时保留全部证据")
    void should_keep_all_evidence_when_budget_is_enough() {
        List<ScoredChunk> evidence = List.of(
                TestChunks.scored("src/A.java", 1, 5, 0.9),
                TestChunks.scored("src/B.java", 6, 9, 0.8));

        PromptBuilder.Prompt prompt = new PromptBuilder(10_000).build("问题", evidence);

        assertThat(prompt.usedChunks()).isEqualTo(2);
        assertThat(prompt.user()).contains("【问题】问题");
    }

    @Test
    @DisplayName("预算过小时构造失败，避免误配置")
    void should_reject_too_small_budget() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PromptBuilder(100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
