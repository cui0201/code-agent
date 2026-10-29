package com.example.codeagent.web.config;

import com.example.codeagent.agent.CitationValidator;
import com.example.codeagent.agent.PromptBuilder;
import com.example.codeagent.agent.QaService;
import com.example.codeagent.agent.RefusalPolicy;
import com.example.codeagent.agent.SemanticCache;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.core.exception.ConfigurationException;
import com.example.codeagent.core.port.Chunker;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.indexer.IndexingService;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import com.example.codeagent.indexer.chunk.LineWindowChunker;
import com.example.codeagent.indexer.chunk.CompositeChunker;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import com.example.codeagent.indexer.snapshot.IndexSnapshotStore;
import com.example.codeagent.indexer.embed.LocalOnnxEmbeddingClient;
import com.example.codeagent.indexer.scan.SourceScanner;
import com.example.codeagent.retrieval.VectorRetriever;
import com.example.codeagent.retrieval.HybridRetriever;
import com.example.codeagent.retrieval.fusion.RrfFuser;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import com.example.codeagent.retrieval.keyword.KeywordRetriever;
import com.example.codeagent.retrieval.RerankingRetriever;
import com.example.codeagent.retrieval.rerank.NoOpReranker;
import com.example.codeagent.retrieval.rerank.OnnxCrossEncoderReranker;
import com.example.codeagent.retrieval.store.InMemoryVectorStore;
import com.example.codeagent.retrieval.store.QdrantVectorStore;
import com.example.codeagent.core.port.Reranker;
import com.example.codeagent.web.llm.SpringAiAnswerGenerator;
import com.example.codeagent.web.service.IndexingFacade;
import com.example.codeagent.web.service.SymbolGraphHolder;
import com.example.codeagent.web.service.AgentMetrics;
import com.example.codeagent.agent.LlmRelevanceJudge;
import com.example.codeagent.core.port.RelevanceJudge;
import com.example.codeagent.web.mcp.CodeAgentMcpTools;
import io.modelcontextprotocol.json.McpJsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 手工装配全部组件。
 *
 * <p>刻意不使用组件扫描式注解：装配关系集中在一处，更容易看清
 * "哪个接口由哪个实现承担"，替换实现时也只改这一个文件。
 * 这与设计文档中"接口先行、实现可替换"的原则一致。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    @Bean
    public Chunker chunker(AgentProperties properties) {
        int windowLines = properties.getChunk().getWindowLines();
        int overlapLines = properties.getChunk().getOverlapLines();
        if ("line-window".equalsIgnoreCase(properties.getChunk().getStrategy())) {
            return new LineWindowChunker(windowLines, overlapLines);
        }
        // 按文件类型分发：Java 走 AST，Markdown 走标题分章节，
        // 其他类型（以及 AST 解析失败时）降级为定长窗口
        return new CompositeChunker(
                JavaAstChunker.withLineWindowFallback(windowLines, overlapLines),
                new MarkdownDocChunker(),
                new LineWindowChunker(windowLines, overlapLines));
    }

    @Bean
    public SourceScanner sourceScanner(AgentProperties properties) {
        return new SourceScanner(
                properties.getIndex().getInclude(),
                properties.getIndex().getExclude());
    }

    /** 本地 ONNX 嵌入模型，运行在 JVM 内，无需额外服务。 */
    @Bean
    public EmbeddingClient embeddingClient() {
        return new LocalOnnxEmbeddingClient();
    }

    /**
     * 向量存储。
     *
     * <p>默认用进程内实现（零依赖）；配置 {@code code-agent.store.type=qdrant}
     * 后切换到 Qdrant，获得持久化与 HNSW 近似检索。
     * 上层检索器与问答链路完全不需要改动——这是端口抽象的意义所在。
     */
    @Bean
    public VectorStore vectorStore(AgentProperties properties, EmbeddingClient embeddingClient) {
        AgentProperties.Store store = properties.getStore();
        if ("qdrant".equalsIgnoreCase(store.getType())) {
            QdrantVectorStore qdrant = new QdrantVectorStore(
                    store.getQdrantUrl(), store.getCollection(), embeddingClient.dimension());
            if (!qdrant.isReachable()) {
                throw new ConfigurationException(
                        "配置为使用 Qdrant，但无法连接 " + store.getQdrantUrl()
                                + "。请先启动服务，例如："
                                + "docker run -d --name code-agent-qdrant -p 6333:6333 qdrant/qdrant");
            }
            qdrant.ensureCollection();
            return qdrant;
        }
        return new InMemoryVectorStore();
    }

    @Bean
    public Retriever retriever(EmbeddingClient embeddingClient,
                               VectorStore vectorStore,
                               Bm25Index keywordIndex,
                               Reranker reranker,
                               AgentProperties properties,
                               ObjectProvider<RelevanceJudge> judgeProvider) {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        String strategy = retrieval.getStrategy() == null ? "hybrid" : retrieval.getStrategy();
        // 有判官时检索层不再拒答：排序归检索、判断归判官，职责不重叠。
        // 没有判官时退化为严格闸门，用符号与向量分数兜底。
        HybridRetriever.GateMode gateMode = judgeProvider.getIfAvailable() != null
                ? HybridRetriever.GateMode.NONE
                : HybridRetriever.GateMode.STRICT;
        log.info("检索层拒答闸门模式: {}", gateMode);

        Retriever base = switch (strategy.toLowerCase()) {
            case "vector" -> new VectorRetriever(
                    embeddingClient, vectorStore, retrieval.getMinScore());
            case "keyword" -> new KeywordRetriever(keywordIndex, 0.0);
            default -> new HybridRetriever(
                    embeddingClient,
                    vectorStore,
                    keywordIndex,
                    RrfFuser.withEqualWeights(2),
                    retrieval.getMinScore(),
                    retrieval.getMinKeywordScore(),
                    retrieval.getCandidateK(),
                    gateMode);
        };
        // 统一套一层重排装饰器：未启用时是空实现，只做截断，行为与不包装一致
        return new RerankingRetriever(base, reranker, properties.getRerank().getCandidateK());
    }

    /**
     * 重排器。
     *
     * <p>{@code auto} 模式下模型缺失只会记录一条提示日志，不会让应用启动失败——
     * 重排是锦上添花的能力，不该成为使用门槛；但如果显式配成 {@code true}，
     * 模型缺失就必须报错，避免"以为开了其实没开"。
     */
    @Bean
    public Reranker reranker(AgentProperties properties) {
        AgentProperties.Rerank rerank = properties.getRerank();
        String mode = rerank.getEnabled() == null ? "auto" : rerank.getEnabled().toLowerCase();
        if ("false".equals(mode)) {
            log.info("重排已关闭（code-agent.rerank.enabled=false）");
            return NoOpReranker.INSTANCE;
        }
        if (!"auto".equals(mode) && !"true".equals(mode)) {
            throw new ConfigurationException(
                    "code-agent.rerank.enabled 取值非法: " + rerank.getEnabled()
                            + "（可选 auto / true / false）");
        }
        if (!rerank.isModelAvailable()) {
            if ("true".equals(mode)) {
                throw new ConfigurationException(
                        "配置为强制启用重排，但模型文件不可读: " + rerank.getModelPath()
                                + "。请先运行 scripts/download-reranker-model.sh");
            }
            log.info("未找到重排模型，本次不启用重排（运行 scripts/download-reranker-model.sh 可启用）");
            return NoOpReranker.INSTANCE;
        }
        return new OnnxCrossEncoderReranker(
                rerank.getModelPath(), rerank.getTokenizerPath(), "bge-reranker-base-q");
    }

    /**
     * 关键词索引（BM25）。
     *
     * <p>做成单例：索引阶段写入、检索阶段读取，必须共享同一份实例。
     */
    @Bean
    public Bm25Index keywordIndex() {
        return new Bm25Index();
    }

    @Bean
    public SymbolGraphHolder symbolGraphHolder() {
        return new SymbolGraphHolder();
    }

    @Bean
    public AgentMetrics agentMetrics() {
        return new AgentMetrics();
    }

    /**
     * MCP 的 JSON 映射器。
     *
     * <p>单独建一个 ObjectMapper 而不是复用 Spring MVC 的那个：
     * MCP 协议对字段格式有自己要求，共用一个实例会让双方的配置互相影响。
     */
    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(new ObjectMapper());
    }

    @Bean
    public CodeAgentMcpTools codeAgentMcpTools(Retriever retriever,
                                               Bm25Index keywordIndex,
                                               VectorStore vectorStore,
                                               SymbolGraphHolder symbolGraphHolder,
                                               McpJsonMapper mcpJsonMapper) {
        return new CodeAgentMcpTools(retriever, keywordIndex, vectorStore,
                symbolGraphHolder, mcpJsonMapper);
    }

    @Bean
    public IndexingFacade indexingFacade(IndexingService indexingService,
                                         Bm25Index keywordIndex,
                                         SymbolGraphHolder symbolGraphHolder,
                                         ObjectProvider<SemanticCache> cacheProvider) {
        return new IndexingFacade(indexingService, keywordIndex, symbolGraphHolder,
                cacheProvider.getIfAvailable());
    }

    @Bean
    public IndexingService indexingService(SourceScanner scanner,
                                           Chunker chunker,
                                           EmbeddingClient embeddingClient,
                                           VectorStore vectorStore,
                                           AgentProperties properties) {
        IndexSnapshotStore snapshotStore = new IndexSnapshotStore(
                java.nio.file.Path.of(properties.getIndex().getSnapshotDir()));
        return new IndexingService(scanner, chunker, embeddingClient, vectorStore,
                new com.example.codeagent.indexer.doc.DocumentDriftDetector(),
                new com.example.codeagent.indexer.graph.SpoonGraphBuilder(),
                snapshotStore);
    }

    @Bean
    public PromptBuilder promptBuilder(AgentProperties properties) {
        return new PromptBuilder(properties.getRetrieval().getMaxEvidenceChars());
    }

    @Bean
    public CitationValidator citationValidator() {
        return new CitationValidator();
    }

    @Bean
    public RefusalPolicy refusalPolicy(AgentProperties properties) {
        return new RefusalPolicy(properties.getRetrieval().getMinScore());
    }

    @Bean
    public AnswerGenerator answerGenerator(ChatClient.Builder chatClientBuilder,
                                           AgentProperties properties) {
        return new SpringAiAnswerGenerator(chatClientBuilder, properties.getLlm());
    }

    @Bean
    public QaService qaService(Retriever retriever,
                               AnswerGenerator answerGenerator,
                               PromptBuilder promptBuilder,
                               CitationValidator citationValidator,
                               RefusalPolicy refusalPolicy,
                               AgentProperties properties,
                               ObjectProvider<SemanticCache> cacheProvider,
                               ObjectProvider<RelevanceJudge> judgeProvider) {
        return new QaService(retriever, answerGenerator, promptBuilder,
                citationValidator, refusalPolicy,
                properties.getGuard().isRequireCitation(),
                properties.getGuard().getCitationRetry(),
                cacheProvider.getIfAvailable(),
                judgeProvider.getIfAvailable());
    }

    /**
     * LLM 相关性判官。
     *
     * <p>与生成共用同一个模型客户端：判官提示只要求输出 YES/NO，
     * 输入也只有前三条证据，因此成本远低于一次完整生成。
     */
    @Bean
    @ConditionalOnProperty(prefix = "code-agent.guard", name = "relevance-judge",
            havingValue = "true", matchIfMissing = true)
    public RelevanceJudge relevanceJudge(AnswerGenerator answerGenerator) {
        log.info("已启用 LLM 相关性判官（拒答判据不再依赖相似度阈值）");
        return new LlmRelevanceJudge(answerGenerator, null);
    }

    /**
     * 语义缓存。
     *
     * <p>用 {@code @ConditionalOnProperty} 而不是"返回 null"：
     * Spring 里返回 null 的 @Bean 会变成 NullBean，注入时容易出意外。
     * 关闭时干脆不注册这个 Bean，QaService 通过 ObjectProvider 拿不到就按"无缓存"运行。
     */
    @Bean
    @ConditionalOnProperty(prefix = "code-agent.cache", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SemanticCache semanticCache(AgentProperties properties,
                                       EmbeddingClient embeddingClient) {
        AgentProperties.Cache cache = properties.getCache();
        log.info("语义缓存已启用: 相似度阈值={}, 容量={}",
                cache.getSemanticThreshold(), cache.getMaxEntries());
        return new SemanticCache(embeddingClient, cache.getSemanticThreshold(),
                cache.getMaxEntries());
    }
}
