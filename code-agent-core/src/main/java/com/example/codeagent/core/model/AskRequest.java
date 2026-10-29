package com.example.codeagent.core.model;

/**
 * 一次问答请求。
 *
 * @param question 用户问题（自然语言，允许中文）
 * @param project  项目标识
 * @param ref      版本引用（分支或 commit；P0 记为 working-tree）
 * @param topK     注入上下文的证据块数量上限
 * @param stream   是否流式返回（P6 启用）
 */
public record AskRequest(String question, String project, String ref, int topK, boolean stream) {

    public AskRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须为正数，当前: " + topK);
        }
        project = (project == null || project.isBlank()) ? "default" : project;
        ref = (ref == null || ref.isBlank()) ? "working-tree" : ref;
    }

    public static AskRequest of(String question) {
        return new AskRequest(question, "default", "working-tree", 8, false);
    }

    public static AskRequest of(String question, int topK) {
        return new AskRequest(question, "default", "working-tree", topK, false);
    }
}
