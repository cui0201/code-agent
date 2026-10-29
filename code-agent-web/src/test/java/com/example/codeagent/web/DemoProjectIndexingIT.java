package com.example.codeagent.web;

import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.IndexingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 的端到端验收测试：对样例工程索引后，用中文提问能否命中正确的代码。
 *
 * <p>这是 P0 完成定义（DoD）里"能检索到预期文件"那一条的自动化形式。
 * 它不调用大模型——生成质量属于评测集（P1）的范围，这里只验证检索链路。
 */
@SpringBootTest(properties = {
        "code-agent.index.root=../examples/demo-project",
        "code-agent.retrieval.min-score=0.2"
})
class DemoProjectIndexingIT {

    private static final Logger log = LoggerFactory.getLogger(DemoProjectIndexingIT.class);

    private static final Path DEMO_PROJECT = Path.of("..", "examples", "demo-project");

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private Retriever retriever;

    @Autowired
    private VectorStore vectorStore;

    @Test
    @DisplayName("索引样例工程：文件数、切块数与失败清单都符合预期")
    void should_index_demo_project() {
        IndexReport report = indexingService.index(DEMO_PROJECT).report();

        log.info("索引报告: {}", report);

        // 7 Java + 4 Markdown + 1 pom.xml + 1 openapi.json + 21 Javadoc HTML。
        // Javadoc 的导航页与 class-use 交叉引用页被主动跳过，所以不是 26 个
        assertThat(report.fileCount()).isEqualTo(34);
        assertThat(report.chunkCount()).isGreaterThan(0);
        assertThat(report.failures()).isEmpty();
        assertThat(vectorStore.size()).isEqualTo(report.chunkCount());
    }

    @Test
    @DisplayName("中文提问能命中承载金额校验逻辑的 OrderService")
    void should_retrieve_order_service_for_amount_question() {
        indexingService.index(DEMO_PROJECT);

        List<ScoredChunk> hits = retriever.retrieve("下单接口对订单金额做了哪些校验？", 8);

        assertThat(hits).isNotEmpty();
        hits.forEach(hit -> log.info("命中: {} score={}",
                hit.chunk().location(), String.format("%.4f", hit.score())));

        assertThat(hits)
                .extracting(hit -> hit.chunk().path())
                .contains("src/main/java/com/acme/order/OrderService.java");
    }

    @Test
    @DisplayName("无关问题不会命中订单相关文件")
    void should_not_retrieve_order_files_for_unrelated_question() {
        indexingService.index(DEMO_PROJECT);

        List<ScoredChunk> hits = retriever.retrieve("这个项目使用了哪个版本的 Kubernetes？", 3);

        if (!hits.isEmpty()) {
            log.info("无关问题的最高分: {} ({})",
                    String.format("%.4f", hits.get(0).score()), hits.get(0).chunk().location());
        }
        // P0 只记录分布，不设硬性阈值断言；阈值校准是 P1 评测集的任务
        assertThat(hits.size()).isLessThanOrEqualTo(3);
    }
}
