package com.example.codeagent.core.exception;

/**
 * 本项目所有业务异常的基类。
 *
 * <p>原则：基础设施异常一律包装后向上抛出，不吞异常；
 * 已知可降级的场景由调用方捕获并记录，继续处理其余部分。
 */
public class CodeAgentException extends RuntimeException {

    public CodeAgentException(String message) {
        super(message);
    }

    public CodeAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
