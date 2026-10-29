package com.example.codeagent.core.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VectorMathTest {

    @Test
    @DisplayName("相同向量的余弦相似度为 1")
    void should_return_one_for_identical_vectors() {
        float[] v = {1.0f, 2.0f, 3.0f};

        assertThat(VectorMath.cosine(v, v)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("正交向量的余弦相似度为 0")
    void should_return_zero_for_orthogonal_vectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};

        assertThat(VectorMath.cosine(a, b)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("方向相反时余弦相似度为 -1")
    void should_return_minus_one_for_opposite_vectors() {
        float[] a = {1.0f, 1.0f};
        float[] b = {-1.0f, -1.0f};

        assertThat(VectorMath.cosine(a, b)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    @DisplayName("归一化后点积等价于余弦相似度")
    void should_make_dot_equal_cosine_after_normalization() {
        float[] a = {0.3f, -1.2f, 4.5f, 0.9f};
        float[] b = {-2.0f, 0.4f, 1.1f, 3.3f};

        double cosine = VectorMath.cosine(a, b);
        double dotOfNormalized = VectorMath.dot(
                VectorMath.normalized(a), VectorMath.normalized(b));

        assertThat(dotOfNormalized).isCloseTo(cosine, within(1e-6));
    }

    @Test
    @DisplayName("归一化不修改入参")
    void should_not_mutate_input_while_normalizing() {
        float[] original = {3.0f, 4.0f};
        float[] snapshot = original.clone();

        VectorMath.normalized(original);

        assertThat(original).containsExactly(snapshot);
    }

    @Test
    @DisplayName("归一化后向量模长为 1")
    void should_produce_unit_vector() {
        float[] normalized = VectorMath.normalized(new float[]{3.0f, 4.0f});

        assertThat(VectorMath.cosine(normalized, normalized)).isCloseTo(1.0, within(1e-6));
        assertThat(VectorMath.dot(normalized, normalized)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("零向量不产生 NaN")
    void should_handle_zero_vector() {
        float[] zero = {0.0f, 0.0f};

        assertThat(VectorMath.cosine(zero, zero)).isEqualTo(0.0);
        assertThat(VectorMath.normalized(zero)).containsExactly(0.0f, 0.0f);
    }

    @Test
    @DisplayName("维度不一致时抛出异常")
    void should_reject_dimension_mismatch() {
        assertThatThrownBy(() -> VectorMath.cosine(new float[]{1.0f}, new float[]{1.0f, 2.0f}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度不一致");
    }
}
