package com.example.codeagent.agent;

import com.example.codeagent.core.model.AskRequest;
import com.example.codeagent.core.model.AskResponse;
import com.example.codeagent.core.model.Diagnostics;
import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.core.port.Retriever;
import com.example.codeagent.core.port.RelevanceJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 问答主编排：检索、拒答判定、生成、引用校验、组装响应。
 *
 * <p>流程刻意保持"固定管线"而非自由 Agent 循环：
 * 事实型问题占绝大多数，一次检索加一次模型调用就能解决，
 * 固定管线的延迟与成本可预测，失败也可复现。
 *
 * <p>依赖全部是接口，因此单元测试可以用假实现完整覆盖，
 * 不需要网络与 API Key——这也是后续评测能自动化运行的前提。
 */
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /** 引用校验未通过时追加到答案末尾的提示。 */
    private static final String CITATION_WARNING = "\n\n> 提示：本回答的引用校验未通过，请以检索到的代码原文为准。";

    private final Retriever retriever;
    private final AnswerGenerator answerGenerator;
    private final PromptBuilder promptBuilder;
    private final CitationValidator citationValidator;
    private final RefusalPolicy refusalPolicy;
    private final boolean requireCitation;
    private final int citationRetry;
    private final SemanticCache cache;
    private final RelevanceJudge relevanceJudge;

    public QaService(Retriever retriever,
                     AnswerGenerator answerGenerator,
                     PromptBuilder promptBuilder,
                     CitationValidator citationValidator,
                     RefusalPolicy refusalPolicy,
                     boolean requireCitation,
                     int citationRetry) {
        this(retriever, answerGenerator, promptBuilder, citationValidator, refusalPolicy,
                requireCitation, citationRetry, null);
    }

    public QaService(Retriever retriever,
                     AnswerGenerator answerGenerator,
                     PromptBuilder promptBuilder,
                     CitationValidator citationValidator,
                     RefusalPolicy refusalPolicy,
                     boolean requireCitation,
                     int citationRetry,
                     SemanticCache cache) {
        this(retriever, answerGenerator, promptBuilder, citationValidator, refusalPolicy,
                requireCitation, citationRetry, cache, null);
    }

    public QaService(Retriever retriever,
                     AnswerGenerator answerGenerator,
                     PromptBuilder promptBuilder,
                     CitationValidator citationValidator,
                     RefusalPolicy refusalPolicy,
                     boolean requireCitation,
                     int citationRetry,
                     SemanticCache cache,
                     RelevanceJudge relevanceJudge) {
        this.retriever = retriever;
        this.answerGenerator = answerGenerator;
        this.promptBuilder = promptBuilder;
        this.citationValidator = citationValidator;
        this.refusalPolicy = refusalPolicy;
        this.requireCitation = requireCitation;
        this.citationRetry = Math.max(0, citationRetry);
        this.cache = cache;
        this.relevanceJudge = relevanceJudge;
    }

    /**
     * 处理一次问答请求。
     *
     * @param request 请求
     * @return 回答（可能为拒答）
     */
    public AskResponse ask(AskRequest request) {
        return ask(request, null);
    }

    /**
     * 流式处理一次问答请求：生成过程中每段增量文本都会回调给调用方。
     *
     * <p>与 {@link #ask} 共用同一条流水线，唯一区别是生成方式。
     * 引用校验只能在<b>全部文本产出之后</b>做——校验依赖完整的答案文本，
     * 因此流式模式下前端会先看到答案，再收到引用与校验结果。
     *
     * <p>流式模式<b>不做引用重试</b>：重试意味着把第二段回答也推给前端，
     * 用户会看到两段互相矛盾的答案。这是流式的固有代价，写在这里以免以后被当成 bug。
     *
     * @param onDelta 增量文本回调；为 null 时等同于非流式
     */
    public AskResponse askStreaming(AskRequest request,
                                    java.util.function.Consumer<String> onDelta) {
        return ask(request, onDelta);
    }

    private AskResponse ask(AskRequest request, java.util.function.Consumer<String> onDelta) {
        // 缓存查询放在最前面：命中就完全不必检索、不必调用大模型，
        // 这是延迟优化里收益最大的一步
        SemanticCache.LookupResult lookup = cache == null
                ? null : cache.lookup(request.question());
        if (lookup != null && lookup.hit()) {
            AskResponse cached = lookup.cachedResponse();
            log.info("问答命中缓存: questionLength={}, 引用数={}",
                    request.question().length(), cached.citations().size());
            if (onDelta != null) {
                // 缓存命中也走流式回调：前端不需要区分"这次是缓存还是生成"，
                // 统一按一种事件流处理即可
                onDelta.accept(cached.answer());
            }
            return new AskResponse(cached.answer(), cached.citations(), false,
                    cached.evidence(), Diagnostics.cached(answerGenerator.modelName(),
                    cached.citations().size()));
        }

        long retrievalStart = System.currentTimeMillis();
        List<ScoredChunk> evidence = retriever.retrieve(request.question(), request.topK());
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        Optional<String> refusal = refusalPolicy.refusalReason(evidence);
        if (refusal.isPresent()) {
            log.info("拒答: questionLength={}, 召回={}, 原因={}",
                    request.question().length(), evidence.size(), refusal.get());
            return AskResponse.refused(refusal.get(), new Diagnostics(
                    retrievalMs, 0, evidence.size(), 0, 0, answerGenerator.modelName(), false));
        }

        // 第二道闸门：让判官回答"这些证据能不能答"。
        // 第一道闸门（相似度阈值）衡量的是"像不像"，在中文语料上已经不够用——
        // 主题相近但没有答案的片段相似度可以高到 0.9（详见 RelevanceJudge 的说明）
        if (relevanceJudge != null) {
            RelevanceJudge.Judgement judgement =
                    relevanceJudge.judge(request.question(), evidence);
            if (!judgement.canAnswer()) {
                long judgedMs = System.currentTimeMillis() - retrievalStart;
                // 把判官看到的证据位置记下来：拒答原因除了"判官说不行"，
                // 还需要能回答"它当时看到了什么"，否则无法判断该改检索还是改判官
                log.info("判官判定证据不足，拒答: 判据={}, 依据={}, 证据={}",
                        relevanceJudge.name(), judgement.reason(),
                        evidence.stream().limit(5)
                                .map(scored -> scored.chunk().path()
                                        + ":" + scored.chunk().startLine())
                                .toList());
                return AskResponse.refused(
                        "未在项目代码中找到足以回答该问题的依据（" + judgement.reason() + "）。",
                        new Diagnostics(judgedMs, 0, evidence.size(), 0, 0,
                                answerGenerator.modelName(), false));
            }
        }

        PromptBuilder.Prompt prompt = promptBuilder.build(request.question(), evidence);
        long generationStart = System.currentTimeMillis();
        // 流式与非流式共用一次生成：把增量回调收集成完整文本，
        // 这样引用校验拿到的永远是完整的答案
        StringBuilder collected = new StringBuilder();
        java.util.function.Consumer<String> collector = delta -> {
            collected.append(delta);
            if (onDelta != null) {
                onDelta.accept(delta);
            }
        };
        answerGenerator.generateStreaming(prompt.system(), prompt.user(), collector);
        String rawAnswer = collected.toString();
        CitationValidator.ValidationResult validation =
                citationValidator.validate(rawAnswer, prompt.evidence());

        // 引用校验未通过时，把问题反馈给模型重答，最多 citationRetry 次
        int attempt = 0;
        // 流式模式下不做重试：已经推给前端的文本无法收回，
        // 再推一段第二版答案只会让用户困惑
        int effectiveRetry = onDelta == null ? citationRetry : 0;
        while (!validation.valid() && attempt < effectiveRetry) {
            attempt++;
            log.warn("引用校验未通过，发起第 {} 次重试: 越界编号={}, 是否有引用={}, 可用证据数={}",
                    attempt, validation.unauthorized(), validation.hasCitation(), prompt.usedChunks());
            String corrective = promptBuilder.buildCorrectionPrompt(
                    prompt, validation.unauthorized(), !validation.hasCitation());
            rawAnswer = answerGenerator.generate(prompt.system(), corrective);
            validation = citationValidator.validate(rawAnswer, prompt.evidence());
        }
        long generationMs = System.currentTimeMillis() - generationStart;

        String answer = rawAnswer;
        if (!validation.valid()) {
            log.warn("引用校验最终未通过: 越界编号={}, 是否有引用={}, 重试次数={}",
                    validation.unauthorized(), validation.hasCitation(), attempt);
            if (!validation.unauthorized().isEmpty()
                    || (!validation.hasCitation() && requireCitation)) {
                answer = rawAnswer + CITATION_WARNING;
            }
        }

        Diagnostics diagnostics = new Diagnostics(
                retrievalMs,
                generationMs,
                evidence.size(),
                prompt.usedChunks(),
                prompt.totalChars(),
                answerGenerator.modelName(),
                false);

        log.info("问答完成: 召回={}, 使用={}, retrievalMs={}, generationMs={}, promptChars={}, "
                        + "引用数={}, 引用校验={}",
                evidence.size(),
                prompt.usedChunks(),
                retrievalMs,
                generationMs,
                prompt.totalChars(),
                validation.citations().size(),
                validation.valid() ? "通过" : "未通过（重试 " + attempt + " 次）");

        AskResponse response = new AskResponse(
                answer,
                validation.citations(),
                false,
                prompt.evidence(),
                diagnostics);
        if (cache != null) {
            cache.store(request.question(), lookup.queryVector(), response);
        }
        return response;
    }
}
