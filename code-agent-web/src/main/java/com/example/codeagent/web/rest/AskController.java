package com.example.codeagent.web.rest;

import com.example.codeagent.agent.QaService;
import com.example.codeagent.core.model.AskRequest;
import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.model.Citation;
import com.example.codeagent.core.model.IndexReport;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.web.config.AgentProperties;
import com.example.codeagent.web.service.IndexingFacade;
import com.example.codeagent.web.service.SymbolGraphHolder;
import com.example.codeagent.web.service.AgentMetrics;
import com.example.codeagent.indexer.graph.ImpactReport;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对外 REST 接口。
 *
 * <p>提供同步的 {@code /v1/ask}（含引用与诊断信息）、流式的 {@code /v1/ask/stream}
 * （SSE）、影响面分析 {@code /v1/impact} 与索引管理接口。
 * 返回体直接使用领域模型，是为了让调用方能拿到引用与诊断信息
 * （这两样恰好是"可验证"的关键，不该被藏起来）。
 */
@RestController
@RequestMapping("/v1")
public class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    /**
     * SSE 专用线程池：每个请求都要阻塞等待大模型的流式响应。
     *
     * <p>用虚拟线程而不是固定大小线程池：这里的阻塞几乎全是网络等待，
     * 虚拟线程在等待时不占用操作系统线程，因此不需要按并发数估算池大小，
     * 也不会出现"池满了新请求只能排队"的情况。
     */
    private final ExecutorService streamingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    private final QaService qaService;
    private final IndexingFacade indexingFacade;
    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final AgentProperties properties;
    private final SymbolGraphHolder symbolGraphHolder;
    private final AgentMetrics metrics;

    public AskController(QaService qaService,
                         IndexingFacade indexingFacade,
                         VectorStore vectorStore,
                         EmbeddingClient embeddingClient,
                         AgentProperties properties,
                         SymbolGraphHolder symbolGraphHolder,
                         AgentMetrics metrics) {
        this.qaService = qaService;
        this.indexingFacade = indexingFacade;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.properties = properties;
        this.symbolGraphHolder = symbolGraphHolder;
        this.metrics = metrics;
    }

    /** 请求体。字段都可为空，缺省值从配置读取。 */
    public record AskPayload(String question, Integer topK, Boolean stream) {
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskPayload payload) {
        if (payload == null || payload.question() == null || payload.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        int topK = payload.topK() != null && payload.topK() > 0
                ? payload.topK()
                : properties.getRetrieval().getTopK();
        AskRequest request = new AskRequest(
                payload.question(),
                properties.getProject().getName(),
                "working-tree",
                topK,
                Boolean.TRUE.equals(payload.stream()));
        AskResponse response = qaService.ask(request);
        metrics.record(response);
        return response;
    }

    @PostMapping("/index")
    public IndexReport index() {
        return indexingFacade.index(Path.of(properties.getIndex().getRoot())).report();
    }

    /**
     * 健康检查。
     *
     * <p><b>未建索引时返回 503</b>：Web 容器在 ApplicationRunner 执行之前就已经开始接收请求，
     * 如果此时返回 200，容器编排会把"服务已起、但问不出任何东西"误判成就绪。
     * 健康检查要回答的是"能不能干活"，而不只是"进程还活着"。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean ready = vectorStore.size() > 0;
        status.put("status", ready ? "ready" : "not-indexed");
        status.put("chunks", vectorStore.size());
        status.put("indexRoot", properties.getIndex().getRoot());
        status.put("embeddingModel", embeddingClient.modelName());
        status.put("embeddingDimension", embeddingClient.dimension());
        status.put("llmModel", properties.getLlm().getModel());
        status.put("llmConfigured", properties.getLlm().isConfigured());
        status.put("symbols", symbolGraphHolder.get().symbolCount());
        status.put("graphEdges", symbolGraphHolder.get().edgeCount());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(status);
    }

    /**
     * 影响面分析。
     *
     * <p>为什么用查询参数而不是路径变量：符号名里含有 {@code #} 和 {@code (}，
     * 它们在 URL 里分别是片段分隔符和保留字符，放进路径会被截断或需要多重转义，
     * 调用方很容易踩坑。查询参数由框架统一处理编码，更稳妥。
     *
     * @param symbol 符号名，例如 {@code OrderService#createOrder}（支持省略参数的简写）
     * @param depth  传播跳数，默认 2
     */
    @GetMapping("/impact")
    public ImpactReport impact(@RequestParam("symbol") String symbol,
                               @RequestParam(value = "depth", defaultValue = "2") int depth) {
        if (symbol == null || symbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol 不能为空");
        }
        return symbolGraphHolder.analyze(symbol, depth);
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code delta}：增量文本，前端逐段拼接显示；</li>
     *   <li>{@code citations}：引用列表。只在全部文本产出后一次性给出——
     *       引用校验依赖完整答案，无法边生成边校验；</li>
     *   <li>{@code done}：本次问答的诊断信息（耗时、是否命中缓存）；</li>
     *   <li>{@code error}：出错说明。</li>
     * </ul>
     *
     * <p>用查询参数而不是请求体：浏览器的原生 {@code EventSource} 只支持 GET，
     * 把问题放在查询参数里兼容性最好。
     */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreaming(@RequestParam("question") String question,
                                   @RequestParam(value = "topK", required = false) Integer topK) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        int effectiveTopK = topK != null && topK > 0
                ? topK : properties.getRetrieval().getTopK();
        AskRequest request = new AskRequest(question, properties.getProject().getName(),
                "working-tree", effectiveTopK, true);

        // 0 表示不设超时：大模型偶尔会慢，超时交给 HTTP 客户端自己控制更合适
        SseEmitter emitter = new SseEmitter(0L);
        streamingExecutor.submit(() -> streamAnswer(request, emitter));
        return emitter;
    }

    /** 在虚拟线程里跑完整链路，把每一步的结果转成 SSE 事件。 */
    private void streamAnswer(AskRequest request, SseEmitter emitter) {
        try {
            AskResponse response = qaService.askStreaming(request,
                    delta -> sendEvent(emitter, "delta", Map.of("text", delta)));
            metrics.record(response);
            sendEvent(emitter, "citations", Map.of(
                    "items", response.citations().stream().map(Citation::format).toList()));
            sendEvent(emitter, "done", Map.of(
                    "refused", response.refused(),
                    "retrievalMs", response.diagnostics() == null
                            ? 0 : response.diagnostics().retrievalMs(),
                    "cached", response.diagnostics() != null && response.diagnostics().cached()));
            emitter.complete();
        } catch (RuntimeException e) {
            // 错误也以事件形式发出，而不是让连接莫名其妙断掉——
            // 前端需要能区分"生成失败"和"网络断了"
            log.warn("流式问答失败: {}", e.getMessage());
            try {
                sendEvent(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
            } catch (RuntimeException ignored) {
                // 连接已断，无需再处理
            }
            emitter.complete();
        }
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端断开是正常现象（用户关掉页面），记 debug 即可
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    /**
     * 运行指标快照。
     *
     * <p>它回答的是"系统现在表现如何"：缓存有没有真的减轻负担、
     * 拒答是不是过于频繁、延迟的尾部有多长。
     * 生产环境应把这些指标交给 Micrometer 导出到 Prometheus，
     * 当前用进程内快照是因为这个阶段只需要能看。
     */
    @GetMapping("/metrics")
    public AgentMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }
}
