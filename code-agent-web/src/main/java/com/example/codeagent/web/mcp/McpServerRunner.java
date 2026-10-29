package com.example.codeagent.web.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import com.example.codeagent.web.config.AgentProperties;
import com.example.codeagent.web.service.IndexingFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * MCP Server 入口：以 stdio 方式运行，供 IDE、编码助手等客户端调用。
 *
 * <h2>最关键的一点：stdout 是协议通道，日志必须改道</h2>
 *
 * stdio 模式的 MCP 把 JSON-RPC 消息写在标准输出上。
 * 只要有一行日志混进 stdout，客户端就会收到无法解析的内容并断开连接——
 * 这是 stdio 类服务最经典的坑，而且现象往往表现为"连不上"，很难联想到日志。
 *
 * <p>因此这里在启动 Spring 之前就把 {@code System.out} 换成 {@code System.err}，
 * 只把最初捕获的原始 stdout 留给协议使用。
 *
 * <h2>生命周期</h2>
 *
 * stdio 服务由客户端启动与终止：客户端关闭 stdin 或结束进程时服务随之退出。
 * 所以这里在启动完成后阻塞等待，并注册关闭钩子做优雅关闭。
 */
@Component
public class McpServerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

    private static final String SERVER_NAME = "code-agent";
    private static final String SERVER_VERSION = "1.0.0";

    private final CodeAgentMcpTools tools;
    private final McpJsonMapper jsonMapper;
    private final IndexingFacade indexingFacade;
    private final AgentProperties properties;
    private final com.example.codeagent.core.port.VectorStore vectorStore;

    public McpServerRunner(CodeAgentMcpTools tools,
                           McpJsonMapper jsonMapper,
                           IndexingFacade indexingFacade,
                           AgentProperties properties,
                           com.example.codeagent.core.port.VectorStore vectorStore) {
        this.tools = tools;
        this.jsonMapper = jsonMapper;
        this.indexingFacade = indexingFacade;
        this.properties = properties;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("mcp")) {
            return;
        }
        runStdioServer();
    }

    private void runStdioServer() {
        // 协议通道已经在 main() 里准备好了（必须早于 Spring 启动，否则横幅会污染它）
        PrintStream protocolOut = McpStdioChannel.protocolOut();
        log.info("MCP Server 启动（stdio）: 日志已改道 stderr，stdout 仅用于协议");

        // 启动时若索引为空就自动建一次：stdio 服务没有 HTTP 入口可以触发索引，
        // 不自动建的话客户端拿到的永远是"没有找到相关证据"，很难排查
        if (vectorStore.size() == 0) {
            Path root = Path.of(properties.getIndex().getRoot());
            log.info("索引为空，先建立索引: {}", root.toAbsolutePath());
            indexingFacade.index(root);
        }

        // 包一层输入流，用来感知 stdin 被关闭（客户端正是这样终止 stdio 服务的）。
        // 不感知的话，进程会在客户端退出后一直挂着，只能被强杀。
        CountDownLatch stdinClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                jsonMapper, eofSignalingStream(System.in, stdinClosed), protocolOut);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("""
                        这是一个 Java 项目的代码助手。它基于项目源码与文档的混合检索，
                        以及一个符号图（继承/实现/调用关系）。
                        回答关于项目的问题时，先用 search_code 找证据；
                        问“改动会影响什么”时用 impact_analysis；
                        检索结果为空时先调用 index_status 确认索引状态。
                        所有结果都带文件路径与行号/锚点，引用时请原样使用这些定位信息。
                        """)
                .tools(tools.specifications())
                .build();
        log.info("MCP Server 就绪: 工具={}", tools.toolNames());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (RuntimeException e) {
                log.debug("MCP Server 关闭时出现异常: {}", e.getMessage());
            }
        }, "mcp-shutdown"));

        try {
            // 阻塞主线程，直到 stdin 关闭（客户端退出）或进程被终止
            stdinClosed.await();
            log.info("stdin 已关闭，MCP Server 退出");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("MCP Server 被中断，准备退出");
        }
    }

    /**
     * 包装输入流：读到 EOF 时释放等待中的主线程。
     *
     * <p>只在真正读到流末尾（read 返回 -1）时触发，不影响正常读取，
     * 也不额外消耗字节——协议内容原样透传给 SDK。
     */
    private static InputStream eofSignalingStream(InputStream source, CountDownLatch onEof) {
        return new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value < 0) {
                    onEof.countDown();
                }
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, length);
                if (count < 0) {
                    onEof.countDown();
                }
                return count;
            }
        };
    }
}
