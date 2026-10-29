package com.example.codeagent.core.exception;

/** 大模型调用失败：网络、限流、超时或响应异常。 */
public class LlmInvocationException extends CodeAgentException {

    public LlmInvocationException(String message) {
        super(message);
    }

    public LlmInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
