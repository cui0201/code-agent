package com.example.codeagent.web.mcp;

import com.example.codeagent.core.model.Citation;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.graph.ImpactReport;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import com.example.codeagent.web.service.SymbolGraphHolder;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把本项目的能力暴露成 MCP 工具。
 *
 * <h2>为什么要做 MCP</h2>
 *
 * MCP 是"模型与外部工具/数据"之间的通用协议。把检索与影响面分析做成 MCP 工具后，
 * IDE、编码助手这类客户端可以直接调用，而不需要每个客户端各写一套集成。
 *
 * <p>它也是本项目里**唯一与具体模型解耦**的对外能力：
 * HTTP 接口面向"人写的程序"，MCP 面向"模型驱动的工具调用"。
 *
 * <h2>工具设计的两条原则</h2>
 *
 * <ol>
 *   <li><b>工具少而正交</b>。四个工具分别对应"找证据""取单个符号""看影响面""看索引状态"，
 *       不做"万能工具"——工具描述是给模型看的，含糊的描述会让它乱调；</li>
 *   <li><b>返回值自带定位信息</b>。每个结果都带 {@code 路径:行号} 或 {@code 路径#锚点}，
 *       因为模型需要能引用，使用者需要能核对。</li>
 * </ol>
 */
public class CodeAgentMcpTools {

    private static final Logger log = LoggerFactory.getLogger(CodeAgentMcpTools.class);

    private final Retriever retriever;
    private final Bm25Index keywordIndex;
    private final VectorStore vectorStore;
    private final SymbolGraphHolder symbolGraphHolder;
    private final McpJsonMapper jsonMapper;

    public CodeAgentMcpTools(Retriever retriever,
                             Bm25Index keywordIndex,
                             VectorStore vectorStore,
                             SymbolGraphHolder symbolGraphHolder,
                             McpJsonMapper jsonMapper) {
        this.retriever = retriever;
        this.keywordIndex = keywordIndex;
        this.vectorStore = vectorStore;
        this.symbolGraphHolder = symbolGraphHolder;
        this.jsonMapper = jsonMapper;
    }

    /** 构造全部工具的声明。 */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("search_code",
                "在项目源码与文档中检索，返回带文件路径和行号/锚点的证据片段。"
                        + "适合回答“某段逻辑在哪、怎么实现的”。",
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"自然语言问题，可以是中文，也可以直接写类名/方法名"},
                  "topK":{"type":"integer","description":"返回条数，默认 5"}
                },"required":["query"]}
                """,
                this::searchCode));

        tools.add(tool("get_symbol",
                "按符号名取出源码，例如 OrderService#createOrder。"
                        + "支持省略包名与参数列表。",
                """
                {"type":"object","properties":{
                  "symbol":{"type":"string","description":"符号名，如 OrderService#createOrder"}
                },"required":["symbol"]}
                """,
                this::getSymbol));

        tools.add(tool("impact_analysis",
                "分析改动某个符号的影响面：谁调用了它、有哪些子类或实现类、涉及哪些文件。"
                        + "适合回答“改了这里会影响什么”。",
                """
                {"type":"object","properties":{
                  "symbol":{"type":"string","description":"符号名，如 OrderService#createOrder"},
                  "depth":{"type":"integer","description":"传播跳数，默认 2"}
                },"required":["symbol"]}
                """,
                this::impact));

        tools.add(tool("index_status",
                "查看当前索引状态：切块数、符号数、是否已完成索引。"
                        + "在检索结果为空时先调用它，可以判断是“没索引”还是“确实没有”。",
                """
                {"type":"object","properties":{}}
                """,
                args -> indexStatus()));
        return tools;
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name, String description, String inputSchema,
            java.util.function.Function<Map<String, Object>, String> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                // inputSchema 用 JSON 字符串声明，由 SDK 的 mapper 解析成 JsonSchema
                .inputSchema(jsonMapper, inputSchema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                // 用 callHandler（带完整请求对象）而不是 call（只带参数 Map）：
                // 前者能在出错时把请求信息一起记进日志，排查更容易
                .callHandler((exchange, request) -> {
                    try {
                        return new McpSchema.CallToolResult(
                                handler.apply(request.arguments()), false);
                    } catch (RuntimeException e) {
                        // 工具内部异常要作为结果返回，而不是让协议层报错——
                        // 模型需要看到失败原因才能决定下一步怎么做
                        log.warn("MCP 工具 {} 执行失败: {}", name, e.getMessage());
                        return new McpSchema.CallToolResult("工具执行失败: " + e.getMessage(), true);
                    }
                })
                .build();
    }

    /** 检索：返回带定位的片段，格式与 HTTP 接口保持一致，便于交叉核对。 */
    private String searchCode(Map<String, Object> arguments) {
        String query = stringArg(arguments, "query");
        int topK = intArg(arguments, "topK", 5);
        List<ScoredChunk> hits = retriever.retrieve(query, topK);
        if (hits.isEmpty()) {
            return "没有找到相关证据（可能是索引为空，或问题超出项目范围）。"
                    + "可以先调用 index_status 确认索引状态。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("检索到 ").append(hits.size()).append(" 条证据：\n\n");
        int index = 0;
        for (ScoredChunk hit : hits) {
            builder.append('[').append(++index).append("] ").append(locationOf(hit.chunk()))
                    .append("  (相关度 ").append(String.format("%.3f", hit.score())).append(")\n")
                    .append("```\n").append(truncate(hit.chunk().content(), 1200)).append("\n```\n\n");
        }
        builder.append("引用上述内容时请使用方括号里的编号对应的位置。");
        return builder.toString();
    }

    /** 按符号名取源码：先用关键词索引精确定位，再返回该切块内容。 */
    private String getSymbol(Map<String, Object> arguments) {
        String symbol = stringArg(arguments, "symbol");
        // 不能只取 BM25 前 5 名再过滤：符号名中的通用词（create、order）
        // 会把相关文档排到前面，真正的方法块可能排在截断范围之外。
        List<ScoredChunk> hits = keywordIndex.search(symbol, keywordIndex.size()).stream()
                .filter(hit -> matchesSymbol(hit.chunk(), symbol))
                .toList();
        if (hits.isEmpty()) {
            ImpactReport report = symbolGraphHolder.analyze(symbol, 1);
            if (!report.found()) {
                return "没有找到符号 " + symbol
                        + "（可用 impact_analysis 查看相近的符号名）。";
            }
            return "符号 " + report.symbol() + " 存在于符号图中，但没有对应的切块内容。";
        }
        // get_symbol 的语义是“取源码”。生成的 Javadoc/OpenAPI 也可能命中同一符号，
        // 因此必须在所有匹配中优先代码块，文档块只在没有源码块时兜底。
        ScoredChunk best = hits.stream()
                .filter(hit -> hit.chunk().kind() == ChunkKind.METHOD
                        || hit.chunk().kind() == ChunkKind.CLASS
                        || hit.chunk().kind() == ChunkKind.BLOCK)
                .findFirst()
                .orElse(hits.get(0));
        return "符号位置: " + locationOf(best.chunk()) + "\n\n```\n"
                + truncate(best.chunk().content(), 2000) + "\n```";
    }

    /** 影响面分析：直接复用 P5 的能力。 */
    private String impact(Map<String, Object> arguments) {
        String symbol = stringArg(arguments, "symbol");
        int depth = intArg(arguments, "depth", 2);
        ImpactReport report = symbolGraphHolder.analyze(symbol, depth);
        if (!report.found()) {
            String suggestions = report.suggestions().isEmpty()
                    ? "" : "\n相近的符号：" + String.join("、", report.suggestions());
            return "没有在符号图中找到 " + symbol + "。" + suggestions;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("符号: ").append(report.symbol())
                .append("（深度 ").append(report.depth()).append("）\n\n");
        appendItems(builder, "调用方（改动会影响谁）", report.callers());
        appendItems(builder, "子类与实现类", report.subtypes());
        appendItems(builder, "该类型声明的方法", report.members());
        if (!report.affectedFiles().isEmpty()) {
            builder.append("受影响的文件:\n");
            report.affectedFiles().forEach(file -> builder.append("  - ").append(file).append('\n'));
        }
        return builder.toString();
    }

    private String indexStatus() {
        return String.format("索引状态: 切块 %d 条, 关键词词表 %d 个, 符号图 %d 个节点 / %d 条边",
                vectorStore.size(), keywordIndex.termCount(),
                symbolGraphHolder.get().symbolCount(), symbolGraphHolder.get().edgeCount());
    }

    private void appendItems(StringBuilder builder, String title,
                             List<ImpactReport.ImpactItem> items) {
        if (items.isEmpty()) {
            return;
        }
        builder.append(title).append(":\n");
        for (ImpactReport.ImpactItem item : items) {
            builder.append("  - ").append(item.describe()).append('\n');
        }
        builder.append('\n');
    }

    /** 文档块用锚点，代码块用行号——与提示组装里的规则保持一致。 */
    private String locationOf(com.example.codeagent.core.model.Chunk chunk) {
        Object anchor = chunk.metadata().get("anchor");
        if (anchor != null) {
            return chunk.path() + "#" + anchor;
        }
        return chunk.location();
    }

    /** 判断切块是否对应某个符号：比较 id 与 fqn，允许省略包名与参数。 */
    private static boolean matchesSymbol(com.example.codeagent.core.model.Chunk chunk, String symbol) {
        Object fqn = chunk.metadata().get("fqn");
        String candidate = fqn == null ? chunk.id() : String.valueOf(fqn);
        String normalized = candidate.contains("(")
                ? candidate.substring(0, candidate.indexOf('(')) : candidate;
        return normalized.equals(symbol)
                || normalized.endsWith("." + symbol)
                || normalized.contains("#" + symbol);
    }

    private static String stringArg(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return String.valueOf(value).trim();
    }

    private static int intArg(Map<String, Object> arguments, String name, int defaultValue) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 模型偶尔会把整数写成字符串，解析失败时用默认值而不是报错
            }
        }
        return defaultValue;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n... (已截断)";
    }

    /** 供测试与诊断：列出已注册的工具名。 */
    public List<String> toolNames() {
        return specifications().stream().map(spec -> spec.tool().name()).toList();
    }

    /** 供测试：暴露单个工具的调用入口。 */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return specifications().stream()
                .filter(spec -> spec.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知工具: " + name))
                .callHandler().apply(null, new McpSchema.CallToolRequest(name, arguments));
    }

    /** 供测试：把结果里的文本拼起来，便于断言。 */
    public static String textOf(McpSchema.CallToolResult result) {
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                builder.append(text.text());
            }
        }
        return builder.toString();
    }

    /** 引用格式的统一出口，方便将来调整。 */
    public static String format(Citation citation) {
        return citation.format();
    }
}
