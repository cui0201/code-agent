package com.example.codeagent.core.exception;

/** 扫描、切块或嵌入过程中的失败。 */
public class IndexingException extends CodeAgentException {

    public IndexingException(String message) {
        super(message);
    }

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
