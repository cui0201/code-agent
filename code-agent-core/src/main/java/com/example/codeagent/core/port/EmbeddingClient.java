package com.example.codeagent.core.port;

/**
 * 嵌入客户端：文本转向量。
 *
 * <p>约定：返回的向量已做 L2 归一化，检索端可直接用点积代替余弦相似度。
 *
 * <p>P0 实现为 JVM 内的 ONNX 模型（LangChain4j）；后续可选接入远程嵌入服务，
 * 但接口保持不变。
 */
public interface EmbeddingClient {

    /**
     * @param text 待嵌入文本
     * @return 归一化后的向量
     */
    float[] embed(String text);

    /**
     * 批量嵌入。
     *
     * <p>默认实现逐个调用，保证已有实现不需要改动；真正支持批量的实现应当覆写它。
     * 批量推理比逐条调用快，因为模型可以一次处理多个输入，摊薄了每次调用的固定开销
     * （tokenizer 初始化、张量分配、ONNX 会话调度）。
     *
     * <p>返回顺序必须与入参一一对应。
     */
    default java.util.List<float[]> embedAll(java.util.List<String> texts) {
        java.util.List<float[]> result = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    /** 向量维度。 */
    int dimension();

    /** 模型标识，写入诊断信息与日志。 */
    String modelName();
}
