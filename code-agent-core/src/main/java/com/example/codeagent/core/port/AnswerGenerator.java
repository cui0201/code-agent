package com.example.codeagent.core.port;

/**
 * 答案生成器：提示转答案文本。
 *
 * <p>把大模型调用隔离在这个接口后面有两个目的：
 * 一是单元测试可以用假实现，不需要网络与 API Key；
 * 二是将来更换模型供应商时只需替换实现。
 *
 * <p>本接口只负责生成，引用校验与拒答判定由上层负责。
 */
public interface AnswerGenerator {

    /**
     * @param systemPrompt 系统提示，定义角色与规则
     * @param userPrompt   用户提示，包含证据与问题
     * @return 模型返回的原始文本
     */
    String generate(String systemPrompt, String userPrompt);

    /**
     * 流式生成：每产出一段文本就回调一次。
     *
     * <p>默认实现退化为"一次生成、一次回调"——这样已有实现不需要改动，
     * 也让"不支持流式"这件事表现为"首字延迟高"而不是编译错误。
     * 真正支持流式的实现（如基于 Spring AI 的实现）覆写它即可。
     *
     * <p>为什么用回调而不是返回 Flux/Stream：core 层不能依赖响应式库或
     * 任何具体框架，回调是唯一不引入依赖的表达方式。
     *
     * @param onDelta 每段增量文本的回调，实现必须保证按顺序调用
     */
    default void generateStreaming(String systemPrompt, String userPrompt,
                                   java.util.function.Consumer<String> onDelta) {
        String answer = generate(systemPrompt, userPrompt);
        if (answer != null && !answer.isEmpty()) {
            onDelta.accept(answer);
        }
    }

    /** 模型标识，用于诊断信息。 */
    default String modelName() {
        return "unknown";
    }
}
