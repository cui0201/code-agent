package com.example.codeagent.web;

import com.example.codeagent.web.mcp.CodeAgentMcpTools;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 工具的验收测试。
 *
 * <p>直接调用工具处理器而不是模拟 stdio 协议：这里要验证的是
 * <b>工具返回的内容对不对</b>（有没有定位信息、结论是否正确），
 * 协议层由官方 SDK 负责。stdio 的端到端连通性另外用脚本验证
 * （见 {@code scripts/mcp-smoke-test.sh}）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "code-agent.index.root=../examples/demo-project",
                "code-agent.retrieval.min-score=0.2"
        })
class McpToolsIT {

    @Autowired
    private CodeAgentMcpTools tools;

    @Autowired
    private TestRestTemplate restTemplate;

    private void index() {
        restTemplate.postForObject("/v1/index", null, Map.class);
    }

    @Test
    @DisplayName("声明了四个工具，且都有名称与描述")
    void should_declare_tools() {
        List<String> names = tools.toolNames();

        assertThat(names).containsExactly(
                "search_code", "get_symbol", "impact_analysis", "index_status");
        tools.specifications().forEach(spec -> {
            assertThat(spec.tool().description()).isNotBlank();
            assertThat(spec.tool().inputSchema()).isNotNull();
        });
    }

    @Test
    @DisplayName("search_code 返回带定位信息的证据")
    void should_search_with_locations() {
        index();

        McpSchema.CallToolResult result = tools.callTool("search_code",
                Map.of("query", "下单接口对订单金额做了哪些校验？", "topK", 3));

        assertThat(result.isError()).isFalse();
        String text = CodeAgentMcpTools.textOf(result);
        assertThat(text).contains("OrderService.java");
        // 定位信息是模型能引用的前提：必须带行号或锚点
        assertThat(text).containsPattern(":\\d+-\\d+");
    }

    @Test
    @DisplayName("impact_analysis 给出正确的调用方与受影响文件")
    void should_analyze_impact() {
        index();

        McpSchema.CallToolResult result = tools.callTool("impact_analysis",
                Map.of("symbol", "OrderService#createOrder", "depth", 2));

        assertThat(result.isError()).isFalse();
        String text = CodeAgentMcpTools.textOf(result);
        assertThat(text).contains("com.acme.order.OrderController#create");
        assertThat(text).contains("OrderController.java");
    }

    @Test
    @DisplayName("未知符号给出可执行的提示，而不是报错")
    void should_guide_on_unknown_symbol() {
        index();

        McpSchema.CallToolResult result = tools.callTool("impact_analysis",
                Map.of("symbol", "NoSuchService#doThing"));

        assertThat(result.isError()).isFalse();
        assertThat(CodeAgentMcpTools.textOf(result)).contains("没有在符号图中找到");
    }

    @Test
    @DisplayName("index_status 能说明索引是否就绪")
    void should_report_index_status() {
        index();

        String text = CodeAgentMcpTools.textOf(tools.callTool("index_status", Map.of()));

        assertThat(text).contains("切块").contains("符号图");
    }

    @Test
    @DisplayName("参数缺失时返回错误结果而不是抛出异常")
    void should_return_error_for_missing_argument() {
        index();

        McpSchema.CallToolResult result = tools.callTool("search_code", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(CodeAgentMcpTools.textOf(result)).contains("query 不能为空");
    }
}
