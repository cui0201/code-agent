package com.example.codeagent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 全部可配置项，前缀 {@code code-agent}。
 *
 * <p>字段与 docs/design/dev-design.md 第 7 节的配置清单一一对应；
 * 新增配置必须同时更新那份文档。
 */
@ConfigurationProperties(prefix = "code-agent")
public class AgentProperties {

    private Project project = new Project();
    private Index index = new Index();
    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    private Guard guard = new Guard();
    private Cache cache = new Cache();
    private Store store = new Store();
    private Rerank rerank = new Rerank();

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index) {
        this.index = index;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public void setChunk(Chunk chunk) {
        this.chunk = chunk;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    /** 重排配置。 */
    public static class Rerank {
        /**
         * false：关闭（默认，理由见 application.yml 注释与设计文档 14.7）；
         * auto：模型文件存在则启用；true：强制启用（文件缺失时报错）。
         */
        private String enabled = "false";
        private String modelPath = "";
        private String tokenizerPath = "";
        /** 送入重排的候选数，应明显大于最终注入上下文的块数。 */
        private int candidateK = 30;

        public String getEnabled() {
            return enabled;
        }

        public void setEnabled(String enabled) {
            this.enabled = enabled;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public String getTokenizerPath() {
            return tokenizerPath;
        }

        public void setTokenizerPath(String tokenizerPath) {
            this.tokenizerPath = tokenizerPath;
        }

        public int getCandidateK() {
            return candidateK;
        }

        public void setCandidateK(int candidateK) {
            this.candidateK = candidateK;
        }

        /** 模型与分词器文件是否都可用。 */
        public boolean isModelAvailable() {
            return isReadable(modelPath) && isReadable(tokenizerPath);
        }

        private static boolean isReadable(String path) {
            return path != null && !path.isBlank()
                    && java.nio.file.Files.isReadable(java.nio.file.Path.of(path));
        }
    }

    /** 向量存储配置。 */
    public static class Store {
        /** memory（默认，零依赖）或 qdrant（持久化 + HNSW）。 */
        private String type = "memory";
        private String qdrantUrl = "http://localhost:6333";
        private String collection = "code-agent";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getQdrantUrl() {
            return qdrantUrl;
        }

        public void setQdrantUrl(String qdrantUrl) {
            this.qdrantUrl = qdrantUrl;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }
    }

    /** 项目标识。 */
    public static class Project {
        private String name = "default";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** 索引配置。 */
    public static class Index {
        private String root = ".";
        /**
         * 默认同时索引源码、文档与配置文件。
         *
         * <p>配置文件必须进去：事实型问题里有很大一类是问配置值
         * （"默认拒答阈值是多少""默认向量库是哪个"），
         * 而这些答案的唯一可靠来源就是 application.yml / AgentProperties。
         * 早期只索引 .java 与 .md，导致这类问题要么答不上、要么靠文档里的转述回答。
         */
        private List<String> include = new ArrayList<>(List.of(
                "**/*.java", "**/*.md", "**/*.yml", "**/*.yaml",
                "**/*.properties", "**/pom.xml",
                "**/javadoc/**/*.html", "**/*openapi*.json"));
        private List<String> exclude = new ArrayList<>(List.of("**/target/**", "**/build/**"));
        /**
         * 索引快照目录。
         *
         * <p>保存切块、向量与符号图，重启后可直接复用，不必重新嵌入与解析。
         * 默认放在仓库下的 .codeagent/index（应加入 .gitignore）。
         */
        private String snapshotDir = ".codeagent/index";
        /**
         * 启动时是否自动建立索引。
         *
         * <p>服务模式下必须自动建索引，否则"启动成功"和"能回答问题"是两回事——
         * 用户还得记得手动调 /v1/index。
         * 有快照时这一步只要几百毫秒；没有快照时是完整索引。
         */
        private boolean autoIndex = true;

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude;
        }

        public String getSnapshotDir() {
            return snapshotDir;
        }

        public void setSnapshotDir(String snapshotDir) {
            this.snapshotDir = snapshotDir;
        }

        public boolean isAutoIndex() {
            return autoIndex;
        }

        public void setAutoIndex(boolean autoIndex) {
            this.autoIndex = autoIndex;
        }
    }

    /** 切块配置。 */
    public static class Chunk {
        /** 切块策略：ast（结构化切块）或 line-window（定长窗口）。 */
        private String strategy = "ast";
        private int windowLines = 60;
        private int overlapLines = 10;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public int getWindowLines() {
            return windowLines;
        }

        public void setWindowLines(int windowLines) {
            this.windowLines = windowLines;
        }

        public int getOverlapLines() {
            return overlapLines;
        }

        public void setOverlapLines(int overlapLines) {
            this.overlapLines = overlapLines;
        }
    }

    /** 检索配置。 */
    public static class Retrieval {
        /** 召回策略：vector（仅向量）、keyword（仅关键词）、hybrid（混合，默认）。 */
        private String strategy = "hybrid";
        private int topK = 8;
        private int candidateK = 30;
        /** 向量路的拒答阈值；P4 评测集校准为 0.62（分离度很窄，见设计文档 14.8）。 */
        private double minScore = 0.62;
        /**
         * 关键词路的放行阈值（BM25 分数）。
         *
         * <p>BM25 是无上界分数，这个值只需区分"命中了稀有词"和"完全没命中"。
         * 取 1.0 表示"至少有一个词有明显贡献"，避免只因为"使用""项目"这类常见词命中就放行。
         */
        private double minKeywordScore = 1.0;
        private int maxEvidenceChars = 12_000;

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getCandidateK() {
            return candidateK;
        }

        public void setCandidateK(int candidateK) {
            this.candidateK = candidateK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public double getMinKeywordScore() {
            return minKeywordScore;
        }

        public void setMinKeywordScore(double minKeywordScore) {
            this.minKeywordScore = minKeywordScore;
        }

        public int getMaxEvidenceChars() {
            return maxEvidenceChars;
        }

        public void setMaxEvidenceChars(int maxEvidenceChars) {
            this.maxEvidenceChars = maxEvidenceChars;
        }
    }

    /** 大模型配置。 */
    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "not-configured";
        private String model = "deepseek-chat";
        private double temperature = 0.0;
        private int maxTokens = 1200;
        private int timeoutSeconds = 60;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        /** API Key 是否已配置。 */
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        }
    }

    /** 嵌入模型配置。 */
    public static class Embedding {
        private String model = "bge-small-zh-v15-q";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /** 严格性保证配置。 */
    public static class Guard {
        private boolean requireCitation = true;
        private int citationRetry = 0;
        /**
         * 是否启用 LLM 相关性判官。
         *
         * <p>它回答"这些证据能不能答这个问题"，比相似度阈值可靠得多；
         * 代价是每次问答多一次很小的模型调用（只输出 YES/NO）。
         * 判官调用失败时不会阻断问答，详见 LlmRelevanceJudge。
         */
        private boolean relevanceJudge = true;

        public boolean isRelevanceJudge() {
            return relevanceJudge;
        }

        public void setRelevanceJudge(boolean relevanceJudge) {
            this.relevanceJudge = relevanceJudge;
        }

        public boolean isRequireCitation() {
            return requireCitation;
        }

        public void setRequireCitation(boolean requireCitation) {
            this.requireCitation = requireCitation;
        }

        public int getCitationRetry() {
            return citationRetry;
        }

        public void setCitationRetry(int citationRetry) {
            this.citationRetry = citationRetry;
        }
    }

    /** 缓存配置。 */
    public static class Cache {
        /** 是否启用语义缓存。 */
        private boolean enabled = true;
        /** 语义层判定为"同一个问题"的相似度下限。 */
        private double semanticThreshold = 0.95;
        /** 缓存条目上限，超出后按插入顺序淘汰最旧的。 */
        private int maxEntries = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSemanticThreshold() {
            return semanticThreshold;
        }

        public void setSemanticThreshold(double semanticThreshold) {
            this.semanticThreshold = semanticThreshold;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }
}
