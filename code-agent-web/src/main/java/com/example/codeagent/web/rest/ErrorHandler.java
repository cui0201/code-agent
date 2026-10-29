package com.example.codeagent.web.rest;

import com.example.codeagent.core.exception.CodeAgentException;
import com.example.codeagent.core.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理。
 *
 * <p>目的不是"把异常藏起来"，而是给调用方明确的错误类型与可执行提示，
 * 例如未配置 API Key 时应当直接告诉对方要设置哪个环境变量。
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleConfiguration(ConfigurationException e) {
        log.warn("配置错误: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, "CONFIGURATION_ERROR", e.getMessage());
    }

    @ExceptionHandler(CodeAgentException.class)
    public ResponseEntity<Map<String, Object>> handleCodeAgent(CodeAgentException e) {
        log.error("处理失败: {}", e.getMessage(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "CODE_AGENT_ERROR", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status,
                                                      String code,
                                                      String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
