package com.example.codeagent.agent;

import com.example.codeagent.core.model.ScoredChunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示构造器：把检索到的证据组装成送给大模型的提示。
 *
 * <p>三条设计要点：
 * <ol>
 *   <li><b>编号即引用</b>：证据按顺序编号 {@code [1] [2] ...}，
 *       编号与证据列表下标一一对应，答案里的引用编号因此可以被程序校验；</li>
 *   <li><b>预算裁剪</b>：按字符数上限裁剪证据，宁少勿滥。
 *       塞入过多低相关片段只会增加延迟与幻觉；</li>
 *   <li><b>规则写死在系统提示里</b>：把"只能依据片段回答""无依据就拒答"
 *       等约束固化，而不是依赖每次调用时临场描述。</li>
 * </ol>
 */
public class PromptBuilder {

    /** 系统提示：定义角色与不可协商的规则。 */
    public static final String SYSTEM_PROMPT = """
            你是 code-agent，一个严格依据项目源码回答问题的 Java 项目助手。

            必须遵守以下规则：
            1. 只依据下面提供的【代码片段】回答，不得使用片段之外的任何知识。
            2. 每一条结论后面必须标注引用编号，格式为 [1]、[2]，编号只能来自提供的片段。
            3. 如果片段中没有足够依据，直接回答"未在项目代码中找到相关依据"，不要推测。
            4. 代码与文档冲突时以代码为准，并明确指出文档可能已过期。
            5. 标记为"可能已过期"的文档只能作为线索，不要仅凭它下结论。
            6. 不要复述大段代码，只解释关键逻辑；提到类名、方法名、字段名时必须与片段逐字一致。
            """;

    private final int maxEvidenceChars;

    public PromptBuilder(int maxEvidenceChars) {
        if (maxEvidenceChars < 500) {
            throw new IllegalArgumentException("证据预算过小: " + maxEvidenceChars);
        }
        this.maxEvidenceChars = maxEvidenceChars;
    }

    /**
     * 组装提示。
     *
     * @param question 用户问题
     * @param evidence 检索到的证据（已按相关性降序）
     * @return 提示对象，包含系统提示、用户提示与<b>实际使用</b>的证据列表
     */
    public Prompt build(String question, List<ScoredChunk> evidence) {
        List<ScoredChunk> used = selectWithinBudget(evidence);
        // 展示时按"代码在前、文档在后"分组，编号按展示顺序连续分配；
        // 返回的 evidence 也必须是这个顺序，否则 [n] 会指向错误的片段
        List<ScoredChunk> codeChunks = used.stream()
                .filter(scored -> scored.chunk().kind() != ChunkKind.DOC_SECTION)
                .toList();
        List<ScoredChunk> docChunks = used.stream()
                .filter(scored -> scored.chunk().kind() == ChunkKind.DOC_SECTION)
                .toList();
        List<ScoredChunk> presented = new ArrayList<>(codeChunks);
        presented.addAll(docChunks);

        StringBuilder user = new StringBuilder();
        int index = 0;
        if (!codeChunks.isEmpty()) {
            user.append("【代码片段】\n");
            for (ScoredChunk scored : codeChunks) {
                user.append(render(++index, scored, "java"));
            }
        }
        if (!docChunks.isEmpty()) {
            user.append("【文档片段】\n");
            for (ScoredChunk scored : docChunks) {
                user.append(render(++index, scored, "markdown"));
            }
        }
        user.append("【问题】").append(question).append('\n');
        user.append("【输出要求】先给出直接结论，再分点说明依据；"
                + "每一条依据后面必须带上对应的引用编号。");
        return new Prompt(SYSTEM_PROMPT, user.toString(), presented);
    }

    /** 渲染单条上下文。文档块用锚点定位，并显式标出过期状态。 */
    private String render(int index, ScoredChunk scored, String fenceLanguage) {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(index).append("] ").append(locationOf(scored));
        if (scored.chunk().kind() == ChunkKind.DOC_SECTION) {
            Object stale = scored.chunk().metadata().get(MetadataKeys.STALE);
            if (Boolean.TRUE.equals(stale)) {
                builder.append("  【可能已过期：")
                        .append(scored.chunk().metadata().get(MetadataKeys.STALE_REASON))
                        .append('】');
            } else {
                builder.append("  (文档)");
            }
        }
        return builder.append('\n')
                .append("```").append(fenceLanguage).append('\n')
                .append(scored.chunk().content())
                .append("\n```\n\n")
                .toString();
    }

    /** 代码块用 路径:起始行-结束行；文档块用 路径#锚点。 */
    private String locationOf(ScoredChunk scored) {
        Object anchor = scored.chunk().metadata().get(MetadataKeys.ANCHOR);
        if (scored.chunk().kind() == ChunkKind.DOC_SECTION && anchor != null) {
            return scored.chunk().path() + "#" + anchor;
        }
        return scored.chunk().location();
    }

    /**
     * 构造"引用校验未通过"后的纠正提示。
     *
     * <p>模型偶尔会编造引用编号（只给了 3 个片段却引用 [7]），
     * 或者整段回答不带引用。程序能检测出这两类问题，
     * 而把检测结果明确告诉模型、并要求它重答，是成本最低的修复手段——
     * 比重试整个检索链路便宜得多。
     *
     * <p>注意：重试提示必须<b>重新带上证据</b>，因为每次调用都是无状态的，
     * 模型看不到上一轮的上下文。
     *
     * @param previous        上一次的提示（含证据）
     * @param unauthorized    越界的引用编号
     * @param missingCitation 是否完全没有引用
     * @return 纠正后的用户提示
     */
    public String buildCorrectionPrompt(Prompt previous,
                                        java.util.List<Integer> unauthorized,
                                        boolean missingCitation) {
        StringBuilder correction = new StringBuilder();
        correction.append("你上一次的回答未通过引用校验，请重新回答。\n\n");
        if (!unauthorized.isEmpty()) {
            correction.append("问题：你引用了不存在的编号 ")
                    .append(unauthorized)
                    .append("，本次可用编号只有 1 到 ")
                    .append(previous.usedChunks())
                    .append("。\n\n");
        }
        if (missingCitation) {
            correction.append("问题：你的回答完全没有带引用编号。\n\n");
        }
        correction.append("要求：每一条结论后面必须标注 [编号]，编号必须来自下面提供的片段；")
                .append("如果没有足够依据，直接回答未在项目代码中找到相关依据。\n\n");
        correction.append("---- 以下是本次可用的片段 ----\n\n").append(previous.user());
        return correction.toString();
    }

    /**
     * 按预算裁剪证据，至少保留 1 条。
     *
     * <p>即便第一条就超出预算也会保留它：宁可只给一条完整证据，
     * 也不返回"零证据"让模型自由发挥。
     */
    private List<ScoredChunk> selectWithinBudget(List<ScoredChunk> evidence) {
        List<ScoredChunk> used = new ArrayList<>();
        int totalChars = 0;
        for (ScoredChunk scored : evidence) {
            int cost = scored.chunk().content().length();
            if (!used.isEmpty() && totalChars + cost > maxEvidenceChars) {
                break;
            }
            used.add(scored);
            totalChars += cost;
        }
        return List.copyOf(used);
    }

    /**
     * 组装好的提示。
     *
     * @param system   系统提示
     * @param user     用户提示
     * @param evidence 实际注入的证据，下标 i 对应引用编号 i+1
     */
    public record Prompt(String system, String user, List<ScoredChunk> evidence) {

        public Prompt {
            evidence = List.copyOf(evidence);
        }

        public int usedChunks() {
            return evidence.size();
        }

        public int totalChars() {
            return system.length() + user.length();
        }
    }
}
