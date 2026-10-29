package com.example.codeagent.retrieval;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.port.EmbeddingClient;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.VectorStore;
import com.example.codeagent.retrieval.fusion.RrfFuser;
import com.example.codeagent.retrieval.keyword.Bm25Index;
import com.example.codeagent.retrieval.query.IdentifierExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;

/**
 * 混合检索器：稠密向量 + BM25 关键词，RRF 融合。
 *
 * <p>两路召回的互补性在真实数据上非常明显：
 * 向量路擅长"这段逻辑是干什么的"，关键词路擅长"这个名字出现在哪"。
 * P1 评测里失败的两条用例都属于后者，所以这一层是 P2 的核心。
 *
 * <h2>拒答判定：为什么不能只看分数</h2>
 *
 * RRF 分数只由排名决定，数值区间依赖候选集大小，不具备绝对含义，不能用于阈值判断。
 * 更重要的是，评测暴露了纯分数判定的两个坑：
 *
 * <ul>
 *   <li>问"项目里使用的是哪个消息队列"，BM25 给出 <b>14.86</b> 的高分——
 *       因为 OrderEventPublisher 的注释里逐字写着"消息队列"。
 *       词法匹配无法区分"提到过"与"就是答案"；</li>
 *   <li>问"Spring Boot 版本是多少"，BM25 也有 2.80 分——
 *       来自注释里的"使用"二字与问题中的"使用"撞上，属于典型的中文常见词误匹配。</li>
 * </ul>
 *
 * <p>所以最终采用的判据是<b>混合判据</b>：
 * <ol>
 *   <li><b>向量路</b>用校准过的余弦阈值判断语义相关性；</li>
 *   <li><b>关键词路</b>不设分数阈值，而是要求问题里出现了
 *       <b>标识符形态的词</b>（驼峰、下划线、全大写）<b>且该符号确实存在于索引中</b>。
 *       这比调分数稳健得多——代码问答场景里，"用户点名了一个真实存在的符号"
 *       就是最强证据，而不是"某个词出现了"。</li>
 * </ol>
 *
 * <p>两路都不满足才拒答。这样既救回了 "createOrder 幂等" 这类被向量漏掉的用例，
 * 又不会被注释里的常见词带偏。
 */
public class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    /**
     * 检索层的拒答闸门模式。
     *
     * <p>这道闸门是"有没有依据"的代理判据，它的必要性取决于有没有真正的判官：
     * <ul>
     *   <li>{@link #NONE}：不拒答，全部交给上层判官决定。
     *       有 LLM 判官时用这个——检索层只管排序，判官只管判断，职责清晰；</li>
     *   <li>{@link #LOOSE}：向量、关键词、符号三路任一够强就放行。
     *       没有判官、又想要较高召回时用，但误答风险高；</li>
     *   <li>{@link #STRICT}：只认向量分数与符号命中。没有判官时用它把误答压到最低，
     *       代价是像"用什么构建工具"这类靠关键词才能命中的问题会被误拒。</li>
     * </ul>
     */
    public enum GateMode { NONE, LOOSE, STRICT }

    /**
     * 过期文档的降权系数。
     *
     * <p>不直接过滤掉：文档过期不代表内容全错，它往往仍是找到正确代码的最佳线索，
     * 只是不该压在代码证据前面。所以选中它、但排在后面，并在提示里标注过期。
     */
    private static final double STALE_DOCUMENT_PENALTY = 0.5;

    /**
     * 问"默认值/配置"时的意图词。
     *
     * <p>这类问题的答案几乎一定在配置文件里，而不是在某段代码里。
     * 但语义检索往往会把"向量库"匹配到 EmbeddingClient、
     * 把"缓存容量"匹配到 SemanticCache——那些代码确实在讲这个概念，
     * 却没有回答问题本身。
     *
     * <p>因此当问题里出现这些词时，给带 {@code configKey} 元数据的块一个加权。
     * 这是一个可解释的意图规则，而不是靠调分数硬凑。
     */
    private static final List<String> CONFIG_INTENT_WORDS =
            List.of("默认", "配置", "设置", "开启", "关闭", "是多少");

    /** 命中"配置意图"时的加权系数。 */
    private static final double CONFIG_INTENT_BOOST = 1.3;

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final Bm25Index keywordIndex;
    private final RrfFuser fuser;
    private final double minDenseScore;
    private final double minKeywordScore;
    private final int candidateK;
    private final GateMode gateMode;

    public HybridRetriever(EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           Bm25Index keywordIndex,
                           RrfFuser fuser,
                           double minDenseScore,
                           double minKeywordScore,
                           int candidateK) {
        this(embeddingClient, vectorStore, keywordIndex, fuser,
                minDenseScore, minKeywordScore, candidateK, GateMode.STRICT);
    }

    public HybridRetriever(EmbeddingClient embeddingClient,
                           VectorStore vectorStore,
                           Bm25Index keywordIndex,
                           RrfFuser fuser,
                           double minDenseScore,
                           double minKeywordScore,
                           int candidateK,
                           GateMode gateMode) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.fuser = fuser;
        this.minDenseScore = minDenseScore;
        this.minKeywordScore = minKeywordScore;
        this.candidateK = candidateK;
        this.gateMode = gateMode;
    }

    @Override
    public List<ScoredChunk> retrieve(String question, int topK) {
        if (vectorStore.size() == 0 && keywordIndex.size() == 0) {
            log.warn("索引为空，请先执行索引");
            return List.of();
        }

        List<ScoredChunk> dense = vectorStore.search(embeddingClient.embed(question), candidateK);
        List<ScoredChunk> sparse = keywordIndex.search(question, candidateK);

        double denseTop = dense.isEmpty() ? 0.0 : dense.get(0).score();
        double sparseTop = sparse.isEmpty() ? 0.0 : sparse.get(0).score();
        String matchedSymbol = matchSymbolInIndex(question);

        boolean denseOk = denseTop >= minDenseScore;
        boolean sparseOk = sparseTop >= minKeywordScore;
        boolean symbolOk = matchedSymbol != null;
        // 按模式决定放行条件：
        //   STRICT 只认向量与符号；LOOSE 再加上关键词；NONE 一律放行交给判官
        boolean sufficient = switch (gateMode) {
            case NONE -> true;
            case STRICT -> denseOk || symbolOk;
            case LOOSE -> denseOk || sparseOk || symbolOk;
        };
        if (!sufficient) {
            log.info("三路证据均不足，拒答: 向量最高分={}, 关键词最高分={}, 未命中符号",
                    String.format("%.4f", denseTop), String.format("%.4f", sparseTop));
            return List.of();
        }

        // 问题点名了索引里真实存在的符号时，提高关键词路权重。
        // 原因：RRF 奖励"两路都靠前"，而符号查询往往只有关键词路强命中
        // （向量路对 createOrder、allowOrder 这类标识符不敏感），
        // 等权融合会把关键词排第 1 的块挤出结果集（实测 q018、q012 都是这样丢的）。
        RrfFuser effectiveFuser = symbolOk
                ? new RrfFuser(RrfFuser.DEFAULT_K, List.of(1.0, 2.0))
                : fuser;
        // 先按候选规模融合，降权后再截断，否则被降权的文档挤不掉
        List<ScoredChunk> fused = effectiveFuser.fuse(candidateK, List.of(dense, sparse));
        List<ScoredChunk> ranked = fused.stream()
                .map(HybridRetriever::applyStalePenalty)
                .map(scored -> applyConfigIntentBoost(question, scored))
                .sorted()
                .limit(topK)
                .toList();

        // 硬保证：问题点名了真实符号时，关键词路的第一名必须进入结果集。
        // 只调权重不够——实测把关键词权重提到 2 倍，`RiskClient#allowOrder`
        // 这种"只被一路强命中"的块依然会被"两路都中等"的块挤出去。
        if (symbolOk && !sparse.isEmpty()) {
            ScoredChunk best = sparse.get(0);
            boolean present = ranked.stream()
                    .anyMatch(scored -> scored.chunk().id().equals(best.chunk().id()));
            if (!present) {
                List<ScoredChunk> guaranteed = new ArrayList<>(ranked.size() + 1);
                // 放在首位：用户已经点名了这个符号，它比融合分数更权威
                guaranteed.add(new ScoredChunk(best.chunk(), best.score(), "sparse-guaranteed"));
                for (ScoredChunk scored : ranked) {
                    if (!scored.chunk().id().equals(best.chunk().id())) {
                        guaranteed.add(scored);
                    }
                }
                ranked = guaranteed.subList(0, Math.min(topK, guaranteed.size()));
                log.info("符号 {} 的最佳词法命中不在融合结果内，已强制纳入: {}",
                        matchedSymbol, best.chunk().location());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("混合检索: 向量候选={}, 关键词候选={}, 融合后={}",
                    dense.size(), sparse.size(), ranked.size());
            ranked.forEach(hit -> log.debug("  {} score={}",
                    hit.chunk().location(), String.format("%.4f", hit.score())));
        }
        log.info("混合检索完成: 向量最高分={}, 命中符号={}, 融合返回={}",
                String.format("%.4f", denseTop),
                matchedSymbol == null ? "无" : matchedSymbol,
                ranked.size());
        return ranked;
    }

    private static ScoredChunk applyStalePenalty(ScoredChunk scored) {
        boolean isStaleDocument = scored.chunk().kind() == ChunkKind.DOC_SECTION
                && Boolean.TRUE.equals(scored.chunk().metadata().get(MetadataKeys.STALE));
        if (!isStaleDocument) {
            return scored;
        }
        return new ScoredChunk(scored.chunk(),
                scored.score() * STALE_DOCUMENT_PENALTY, scored.source());
    }

    /**
     * 问题在问默认值/配置时，优先展示配置文件里的条目。
     *
     * <p>配置块由 {@code ConfigFileChunker} 产出，元数据里带 {@code configKey}；
     * 用它作为判据比"路径以 .yml 结尾"更稳——后者会把无关的 YAML 也抬上来。
     */
    private static ScoredChunk applyConfigIntentBoost(String question, ScoredChunk scored) {
        if (!asksForConfiguration(question)) {
            return scored;
        }
        boolean isConfigEntry = scored.chunk().metadata().get("configKey") != null;
        return isConfigEntry
                ? new ScoredChunk(scored.chunk(), scored.score() * CONFIG_INTENT_BOOST,
                        scored.source())
                : scored;
    }

    private static boolean asksForConfiguration(String question) {
        for (String word : CONFIG_INTENT_WORDS) {
            if (question.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断问题是否点名了索引中真实存在的符号。
     *
     * @return 命中的符号名；没有则返回 null
     */
    private String matchSymbolInIndex(String question) {
        for (String identifier : IdentifierExtractor.extract(question)) {
            String normalized = identifier.toLowerCase(java.util.Locale.ROOT);
            if (keywordIndex.containsTerm(normalized)) {
                return identifier;
            }
        }
        return null;
    }
}
