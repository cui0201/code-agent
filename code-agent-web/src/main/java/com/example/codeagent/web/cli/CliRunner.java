package com.example.codeagent.web.cli;

import com.example.codeagent.agent.QaService;
import com.example.codeagent.core.model.AskRequest;
import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.model.Citation;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.web.service.IndexingFacade;
import com.example.codeagent.web.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令行入口。
 *
 * <p>支持的参数：
 * <ul>
 *   <li>{@code --index}：索引一次后退出；</li>
 *   <li>{@code --ask "问题"}：提问一次后退出；索引为空时会先自动索引；</li>
 *   <li>两者可同时使用，按 {@code index -> ask} 顺序执行；</li>
 *   <li>不带任何参数时不做处理，应用作为常驻服务运行。</li>
 * </ul>
 *
 * <p>这里的 {@code System.out} 是刻意保留的：CLI 的输出就是产品本身。
 * 业务代码中仍禁止使用标准输出，一律走 SLF4J。
 */
@Component
public class CliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    /** 索引失败清单最多打印多少条，避免刷屏。 */
    private static final int MAX_FAILURE_PRINT = 10;

    private final IndexingFacade indexingFacade;
    private final QaService qaService;
    private final VectorStore vectorStore;
    private final AgentProperties properties;
    private final ApplicationContext applicationContext;

    public CliRunner(IndexingFacade indexingFacade,
                     QaService qaService,
                     VectorStore vectorStore,
                     AgentProperties properties,
                     ApplicationContext applicationContext) {
        this.indexingFacade = indexingFacade;
        this.qaService = qaService;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean doIndex = args.containsOption("index");
        List<String> askValues = args.getOptionValues("ask");
        boolean doAsk = askValues != null && !askValues.isEmpty();
        if (!doIndex && !doAsk) {
            return;
        }

        int exitCode = 0;
        try {
            if (doIndex) {
                runIndex();
            }
            if (doAsk) {
                runAsk(String.join(" ", askValues));
            }
        } catch (RuntimeException e) {
            log.error("命令执行失败", e);
            System.err.println("执行失败: " + e.getMessage());
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(applicationContext, () -> code));
    }

    private void runIndex() {
        Path root = Path.of(properties.getIndex().getRoot());
        System.out.println("开始索引: " + root.toAbsolutePath());
        IndexReport report = indexingFacade.index(root).report();
        System.out.printf("索引完成: 文件 %d 个, 切块 %d 个, 复用文件 %d 个, 实际嵌入 %d 块, "
                        + "失败 %d 个, 耗时 %d ms%n",
                report.fileCount(),
                report.chunkCount(),
                report.reusedFiles(),
                report.embeddedChunks(),
                report.skippedFiles(),
                report.elapsedMs());
        if (!report.failures().isEmpty()) {
            System.out.println("失败明细（最多显示 " + MAX_FAILURE_PRINT + " 条）:");
            report.failures().stream().limit(MAX_FAILURE_PRINT)
                    .forEach(item -> System.out.println("  - " + item));
        }
    }

    private void runAsk(String question) {
        if (vectorStore.size() == 0) {
            System.out.println("当前索引为空，先自动执行一次索引。");
            runIndex();
        }
        AskResponse response = qaService.ask(
                AskRequest.of(question, properties.getRetrieval().getTopK()));
        printResponse(response);
    }

    private void printResponse(AskResponse response) {
        System.out.println();
        System.out.println("=== 回答 ===");
        System.out.println(response.answer());

        if (!response.citations().isEmpty()) {
            System.out.println();
            System.out.println("=== 引用 ===");
            for (int i = 0; i < response.citations().size(); i++) {
                Citation citation = response.citations().get(i);
                System.out.println("[" + (i + 1) + "] " + citation.format());
            }
        }

        if (log.isDebugEnabled() && !response.evidence().isEmpty()) {
            System.out.println();
            System.out.println("=== 证据（debug）===");
            for (ScoredChunk scored : response.evidence()) {
                System.out.printf("  %.4f  %s%n", scored.score(), scored.chunk().location());
            }
        }

        if (response.diagnostics() != null) {
            System.out.println();
            System.out.printf("耗时: 检索 %d ms, 生成 %d ms, 使用证据 %d 块, 提示 %d 字符, 模型 %s%n",
                    response.diagnostics().retrievalMs(),
                    response.diagnostics().generationMs(),
                    response.diagnostics().usedChunks(),
                    response.diagnostics().promptChars(),
                    response.diagnostics().model());
        }
    }
}
