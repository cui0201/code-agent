package com.example.codeagent.eval;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.indexer.graph.EdgeType;
import com.example.codeagent.indexer.graph.ImpactAnalyzer;
import com.example.codeagent.indexer.graph.ImpactReport;
import com.example.codeagent.indexer.graph.SpoonGraphBuilder;
import com.example.codeagent.indexer.graph.SymbolGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 符号图与影响面分析的验收测试。
 *
 * <p>这里断言的不是"函数返回了非空结果"，而是<b>具体的事实</b>：
 * 改动 {@code OrderService#createOrder} 确实会波及 {@code OrderController#create}。
 * 这类事实人可以逐条核对，因此可以作为回归基线——
 * 一旦图构建或解析逻辑退化，测试会直接指出哪条关系丢了。
 */
class SymbolGraphIT {

    private static final Path DEMO_PROJECT = Path.of("..", "examples", "demo-project");

    private final SymbolGraph graph = buildDemoGraph();

    private static SymbolGraph buildDemoGraph() {
        SourceScanner scanner = new SourceScanner(
                List.of("**/*.java"), List.of("**/target/**", "**/build/**"));
        List<SourceFile> files = scanner.scan(DEMO_PROJECT);
        return new SpoonGraphBuilder().build(files);
    }

    @Test
    @DisplayName("能从样例工程构建出非空的符号图")
    void should_build_graph() {
        // 7 个 Java 文件，节点数含类型与方法，边含声明与调用
        assertThat(graph.symbolCount()).isGreaterThan(30);
        assertThat(graph.edgeCount()).isGreaterThan(20);
    }

    @Test
    @DisplayName("改动 OrderService#createOrder 会波及 OrderController#create")
    void should_find_callers_of_create_order() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("OrderService#createOrder", 2);

        assertThat(report.found()).isTrue();
        assertThat(report.symbol()).isEqualTo(
                "com.acme.order.OrderService#createOrder(OrderRequest)");
        assertThat(report.callers())
                .extracting(ImpactReport.ImpactItem::symbol)
                .contains("com.acme.order.OrderController#create(OrderRequest)");
        // 影响面最终要落到"要改哪个文件"，这条断言比图的形状更贴近实际用途
        assertThat(report.affectedFiles())
                .contains("src/main/java/com/acme/order/OrderController.java");
        assertThat(report.callers().get(0).distance()).isEqualTo(1);
        assertThat(report.callers().get(0).viaType()).isEqualTo(EdgeType.CALLS);
    }

    @Test
    @DisplayName("私有方法的调用链能追溯到具体行号")
    void should_trace_private_method_call_with_line() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("OrderService#validateAmount", 1);

        assertThat(report.found()).isTrue();
        assertThat(report.callers()).hasSize(1);
        ImpactReport.ImpactItem caller = report.callers().get(0);
        assertThat(caller.symbol())
                .isEqualTo("com.acme.order.OrderService#validateRequest(OrderRequest)");
        assertThat(caller.file())
                .isEqualTo("src/main/java/com/acme/order/OrderService.java");
        // 行号让人能直接跳到调用点复核，行号丢失会让结论无法验证
        assertThat(caller.line()).isPositive();
    }

    @Test
    @DisplayName("以类型为起点能列出它声明的成员")
    void should_list_members_of_type() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("OrderService", 1);

        assertThat(report.found()).isTrue();
        assertThat(report.members())
                .allSatisfy(item -> assertThat(item.viaType()).isEqualTo(EdgeType.DECLARES));
        assertThat(report.members())
                .extracting(ImpactReport.ImpactItem::symbol)
                .contains(
                        "com.acme.order.OrderService#createOrder(OrderRequest)",
                        "com.acme.order.OrderService#validateAmount(BigDecimal)");
    }

    @Test
    @DisplayName("接口没有实现类时子类型为空，但成员照常给出")
    void should_report_empty_subtypes_for_unimplemented_interface() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("OrderRepository", 2);

        assertThat(report.found()).isTrue();
        assertThat(report.subtypes()).isEmpty();
        assertThat(report.members()).hasSize(3);
    }

    @Test
    @DisplayName("未知符号返回 found=false，而不是抛异常或给空结论")
    void should_report_not_found() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("NoSuchService#doThing", 2);

        assertThat(report.found()).isFalse();
        assertThat(report.callers()).isEmpty();
    }

    @Test
    @DisplayName("方法名简写能解析到唯一候选")
    void should_resolve_shorthand() {
        ImpactReport report = new ImpactAnalyzer(graph).analyze("OrderService#getOrder", 1);

        assertThat(report.found()).isTrue();
        assertThat(report.symbol()).isEqualTo("com.acme.order.OrderService#getOrder(String)");
    }
}
