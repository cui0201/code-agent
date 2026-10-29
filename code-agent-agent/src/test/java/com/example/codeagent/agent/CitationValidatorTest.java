package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    private final List<ScoredChunk> evidence = List.of(
            TestChunks.scored("src/A.java", 1, 10, 0.9),
            TestChunks.scored("src/B.java", 20, 30, 0.8));

    @Test
    @DisplayName("合法引用被正确解析为 Citation")
    void should_accept_valid_citations() {
        CitationValidator.ValidationResult result =
                validator.validate("下单会校验金额 [1]，并通过仓储保存 [2]。", evidence);

        assertThat(result.valid()).isTrue();
        assertThat(result.hasCitation()).isTrue();
        assertThat(result.unauthorized()).isEmpty();
        assertThat(result.citations()).hasSize(2);
        assertThat(result.citations().get(0).format()).isEqualTo("src/A.java:1-10");
        assertThat(result.citations().get(1).format()).isEqualTo("src/B.java:20-30");
    }

    @Test
    @DisplayName("越界引用被识别，且不进入合法引用列表")
    void should_detect_out_of_range_citation() {
        CitationValidator.ValidationResult result =
                validator.validate("依据片段 [1] 与 [7]。", evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.unauthorized()).containsExactly(7);
        assertThat(result.citations()).hasSize(1);
    }

    @Test
    @DisplayName("完全没有引用时 hasCitation 为 false")
    void should_report_missing_citation() {
        CitationValidator.ValidationResult result =
                validator.validate("下单会校验金额。", evidence);

        assertThat(result.hasCitation()).isFalse();
        assertThat(result.valid()).isFalse();
        assertThat(result.citations()).isEmpty();
    }

    @Test
    @DisplayName("重复引用只保留一次，且保持首次出现顺序")
    void should_deduplicate_citations() {
        CitationValidator.ValidationResult result =
                validator.validate("先看 [2]，再看 [1]，最后再看 [2]。", evidence);

        assertThat(result.citations()).hasSize(2);
        assertThat(result.citations().get(0).path()).isEqualTo("src/B.java");
        assertThat(result.citations().get(1).path()).isEqualTo("src/A.java");
    }

    @Test
    @DisplayName("空答案被视为校验不通过")
    void should_reject_blank_answer() {
        CitationValidator.ValidationResult result = validator.validate("   ", evidence);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasCitation()).isFalse();
    }
}
