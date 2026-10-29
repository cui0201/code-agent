package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiChunkerTest {

    private final OpenApiChunker chunker = new OpenApiChunker();

    private static final String SPEC = """
            {
              "openapi":"3.0.1",
              "info":{"title":"订单服务 API","version":"1.0.0"},
              "paths":{
                "/api/orders":{
                  "post":{
                    "operationId":"createOrder",
                    "summary":"创建订单",
                    "parameters":[{"name":"X-Request-Id","in":"header","required":true,"description":"幂等键"}],
                    "requestBody":{"content":{"application/json":{"schema":{"properties":{
                      "amount":{"type":"number","description":"订单金额"}}}}}},
                    "responses":{"400":{"description":"参数校验失败"}}
                  }
                },
                "/api/orders/{orderNo}":{
                  "get":{"operationId":"getOrder","summary":"查询订单"}
                }
              }
            }
            """;

    private static SourceFile file(String content) {
        return new SourceFile("docs/generated/openapi.json", content, "hash", 1_700_000_000_000L);
    }

    @Test
    @DisplayName("一个接口一个块，锚点由方法与路径生成")
    void should_chunk_per_operation() {
        List<Chunk> chunks = chunker.chunk(file(SPEC));

        assertThat(chunks).extracting(chunk -> chunk.metadata().get(MetadataKeys.ANCHOR))
                .containsExactlyInAnyOrder("post-api-orders", "get-api-orders-orderno");
    }

    @Test
    @DisplayName("块内容展开请求字段、参数与响应，便于直接回答接口问题")
    void should_render_operation_details() {
        Chunk createOrder = chunker.chunk(file(SPEC)).stream()
                .filter(chunk -> "createOrder".equals(chunk.metadata().get("operationId")))
                .findFirst().orElseThrow();

        assertThat(createOrder.content())
                .contains("POST /api/orders")
                .contains("summary: 创建订单")
                .contains("X-Request-Id")
                .contains("amount")
                .contains("400: 参数校验失败");
    }

    @Test
    @DisplayName("operationId 写进元数据，可与代码里的方法名对应")
    void should_expose_operation_id() {
        Chunk getOrder = chunker.chunk(file(SPEC)).stream()
                .filter(chunk -> "getOrder".equals(chunk.metadata().get("operationId")))
                .findFirst().orElseThrow();

        assertThat(getOrder.metadata()).containsEntry("fqn", "getOrder");
        assertThat(getOrder.metadata()).containsEntry("httpMethod", "GET");
        assertThat(getOrder.metadata()).containsEntry("httpPath", "/api/orders/{orderNo}");
    }

    @Test
    @DisplayName("非法 JSON 与非 OpenAPI 的 JSON 不产出切块")
    void should_ignore_non_openapi_json() {
        assertThat(chunker.chunk(file("not json at all"))).isEmpty();
        assertThat(chunker.chunk(file("{\"foo\":1}"))).isEmpty();
    }
}
