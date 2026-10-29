package com.example.codeagent.web.service;

import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.web.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 服务启动时自动建立索引。
 *
 * <h2>为什么必须有这一步</h2>
 *
 * 没有它的话，"容器启动成功"只代表 Web 服务起来了，向量库里还是空的：
 * 用户第一次提问会得到"未找到依据"，必须先想起来调一次 POST /v1/index。
 * 这是部署体验里最容易踩的坑——服务健康检查通过，功能却不可用。
 *
 * <h2>为什么在启动阶段同步执行</h2>
 *
 * 有索引快照时只需几百毫秒，完全可以同步；
 * 没有快照时是完整索引（大工程可能几十秒），此时同步执行的代价是
 * 健康检查会更晚通过——但那恰恰是对的：索引没建好之前，服务本来就不该被视为就绪。
 *
 * <h2>什么时候跳过</h2>
 *
 * <ul>
 *   <li>{@code --index} / {@code --ask}：命令行模式会自己索引，避免重复；</li>
 *   <li>{@code --mcp}：MCP 模式有自己的索引逻辑；</li>
 *   <li>已经通过快照恢复出数据：{@code vectorStore.size() > 0}；</li>
 *   <li>配置里显式关闭了 {@code code-agent.index.auto-index}。</li>
 * </ul>
 */
@Component
public class StartupIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIndexer.class);

    private final IndexingFacade indexingFacade;
    private final VectorStore vectorStore;
    private final AgentProperties properties;

    public StartupIndexer(IndexingFacade indexingFacade,
                          VectorStore vectorStore,
                          AgentProperties properties) {
        this.indexingFacade = indexingFacade;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getIndex().isAutoIndex()) {
            log.info("启动自动索引已关闭（code-agent.index.auto-index=false）");
            return;
        }
        if (args.containsOption("index") || args.containsOption("mcp")
                || args.containsOption("ask")) {
            return;
        }
        if (vectorStore.size() > 0) {
            log.info("向量库已有 {} 条切块，跳过启动索引", vectorStore.size());
            return;
        }

        Path root = Path.of(properties.getIndex().getRoot());
        log.info("启动自动索引: {}", root.toAbsolutePath());
        long started = System.currentTimeMillis();
        IndexReport report = indexingFacade.index(root).report();
        log.info("启动索引完成: 文件={}, 切块={}, 复用={}, 实际嵌入={}, 耗时={}ms",
                report.fileCount(), report.chunkCount(), report.reusedFiles(),
                report.embeddedChunks(), System.currentTimeMillis() - started);
    }
}
