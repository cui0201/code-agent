package com.example.codeagent.core.math;

/**
 * 向量数学工具。
 *
 * <p>手写而非调用框架，是本项目刻意的教学设计：理解了余弦相似度与归一化，
 * 才能解释归一化后点积为何等价于余弦相似度，以及它省掉了多少计算。
 *
 * <p>所有方法均为纯函数，无状态、线程安全。
 */
public final class VectorMath {

    private VectorMath() {
    }

    /**
     * 计算余弦相似度。
     *
     * @throws IllegalArgumentException 维度不一致或入参为 null 时抛出
     */
    public static double cosine(float[] a, float[] b) {
        requireSameDimension(a, b);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 计算点积，要求两个向量都已归一化。 */
    public static double dot(float[] a, float[] b) {
        requireSameDimension(a, b);
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /**
     * 返回 L2 归一化后的新数组，不修改入参。
     *
     * <p>零向量原样返回，避免产生 NaN。
     */
    public static float[] normalized(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        float[] result = vector.clone();
        if (norm == 0.0) {
            return result;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < result.length; i++) {
            result[i] = result[i] * scale;
        }
        return result;
    }

    private static void requireSameDimension(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("向量不能为 null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "向量维度不一致: " + a.length + " vs " + b.length);
        }
    }
}
