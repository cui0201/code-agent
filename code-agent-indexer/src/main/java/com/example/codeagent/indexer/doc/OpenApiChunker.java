package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAPI（springdoc / swagger 生成）切块器：一个接口一个块。
 *
 * <h2>为什么 OpenAPI 值得单独处理</h2>
 *
 * 它是"机器生成的接口契约"，回答接口类问题时比源码更直接：
 * 请求字段、必填性、错误码、响应结构都在里面，而且不会像手写文档那样忘记更新。
 * 但整份 JSON 往往有几千行，按行窗口切会让"某个接口的请求字段"被切散，
 * 所以这里按 {@code paths.<路径>.<方法>} 切块，一个接口一段。
 *
 * <h2>为什么把 JSON 渲染成文字</h2>
 *
 * 检索与提示都更适合自然语言。这里把每个 operation 展开成
 * "METHOD /path + summary + 参数 + 请求体 + 响应"的可读文本，
 * 同时保留 operationId 等元数据，便于与代码里的方法名对应。
 */
public class OpenApiChunker implements Chunker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<Chunk> chunk(SourceFile file) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(file.content());
        } catch (Exception e) {
            // 不是合法 JSON（或是别的 JSON 文件）：不产出切块，交由上层决定是否降级
            return List.of();
        }
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            Iterator<Map.Entry<String, JsonNode>> methodIterator = pathEntry.getValue().fields();
            while (methodIterator.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIterator.next();
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (!isHttpMethod(method)) {
                    continue;
                }
                JsonNode operation = methodEntry.getValue();
                String anchor = method + "-" + slug(path);
                Map<String, Object> metadata = baseMetadata(file);
                metadata.put(MetadataKeys.ANCHOR, anchor);
                metadata.put("docType", "openapi");
                metadata.put("httpMethod", method.toUpperCase(Locale.ROOT));
                metadata.put("httpPath", path);
                metadata.put("docTitle", root.path("info").path("title").asText("OpenAPI"));
                String operationId = operation.path("operationId").asText("");
                if (!operationId.isBlank()) {
                    metadata.put("operationId", operationId);
                    metadata.put("fqn", operationId);
                }
                chunks.add(new Chunk(file.path() + "#" + anchor, file.path(),
                        ChunkKind.DOC_SECTION, render(method, path, operation), 1, 1, metadata));
            }
        }
        return chunks;
    }

    /** 把一个 operation 渲染成便于检索与阅读的文本。 */
    private String render(String method, String path, JsonNode operation) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.toUpperCase(Locale.ROOT)).append(' ').append(path).append('\n');
        appendText(builder, "summary", operation.path("summary").asText(""));
        appendText(builder, "description", operation.path("description").asText(""));
        String operationId = operation.path("operationId").asText("");
        if (!operationId.isBlank()) {
            appendText(builder, "operationId", operationId);
        }

        JsonNode parameters = operation.path("parameters");
        if (parameters.isArray() && !parameters.isEmpty()) {
            builder.append("参数:\n");
            for (JsonNode parameter : parameters) {
                builder.append("  - ").append(parameter.path("name").asText())
                        .append(" (").append(parameter.path("in").asText()).append(", ")
                        .append(parameter.path("required").asBoolean(false) ? "必填" : "可选")
                        .append("): ").append(parameter.path("description").asText("")).append('\n');
            }
        }

        JsonNode requestBody = operation.path("requestBody");
        if (requestBody.isObject()) {
            builder.append("请求体: ").append(requestBody.path("description").asText("")).append('\n');
            appendSchemaFields(builder, requestBody.path("content"), "  ");
        }

        JsonNode responses = operation.path("responses");
        if (responses.isObject()) {
            builder.append("响应:\n");
            Iterator<Map.Entry<String, JsonNode>> responseIterator = responses.fields();
            while (responseIterator.hasNext()) {
                Map.Entry<String, JsonNode> response = responseIterator.next();
                // 4xx/5xx 显式标成"错误码"：用户提问时通常就说"错误码"，
                // 而 OpenAPI 原文只写状态码数字，不标注语义
                boolean errorCode = response.getKey().startsWith("4")
                        || response.getKey().startsWith("5");
                builder.append("  - ").append(errorCode ? "错误码 " : "响应 ")
                        .append(response.getKey()).append(": ")
                        .append(response.getValue().path("description").asText("")).append('\n');
                appendSchemaFields(builder, response.getValue().path("content"), "    ");
            }
        }
        return builder.toString().trim();
    }

    /** 展开 schema 的字段清单：接口类问题最常问的就是"有哪些字段"。 */
    private void appendSchemaFields(StringBuilder builder, JsonNode content, String indent) {
        if (!content.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> contentIterator = content.fields();
        while (contentIterator.hasNext()) {
            JsonNode schema = contentIterator.next().getValue().path("schema");
            JsonNode properties = schema.path("properties");
            if (!properties.isObject()) {
                continue;
            }
            builder.append(indent).append("字段:\n");
            Iterator<Map.Entry<String, JsonNode>> propertyIterator = properties.fields();
            while (propertyIterator.hasNext()) {
                Map.Entry<String, JsonNode> property = propertyIterator.next();
                JsonNode definition = property.getValue();
                builder.append(indent).append("  - ").append(property.getKey())
                        .append(" (").append(definition.path("type").asText("object"));
                if (definition.path("description").asText("").length() > 0) {
                    builder.append("): ").append(definition.path("description").asText());
                } else {
                    builder.append(")");
                }
                builder.append('\n');
            }
        }
    }

    private void appendText(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value).append('\n');
        }
    }

    private boolean isHttpMethod(String method) {
        return switch (method) {
            case "get", "post", "put", "delete", "patch", "head", "options" -> true;
            default -> false;
        };
    }

    /** 路径转锚点：/api/orders/{orderNo} → api-orders-ordernob。 */
    private String slug(String path) {
        return path.toLowerCase(Locale.ROOT)
                .replaceAll("[{}]", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private Map<String, Object> baseMetadata(SourceFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "json");
        metadata.put(MetadataKeys.FILE_HASH, file.hash());
        metadata.put(MetadataKeys.LAST_MODIFIED, file.lastModifiedMillis());
        metadata.put("chunker", "openapi");
        metadata.put("synthesized", false);
        return metadata;
    }
}
