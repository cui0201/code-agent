package com.example.codeagent.core.exception;

/** 配置缺失或非法，例如索引目录不存在、未配置 API Key。 */
public class ConfigurationException extends CodeAgentException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
