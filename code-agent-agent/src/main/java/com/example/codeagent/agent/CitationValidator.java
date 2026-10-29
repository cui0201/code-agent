package com.example.codeagent.agent;

import com.example.codeagent.core.model.Citation;
import com.example.codeagent.core.model.ScoredChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验器：检查答案中的引用编号是否都来自本次注入的证据。
 *
 * <p>为什么必须由程序做，而不是靠提示词约束：
 * 大模型偶尔会编造引用编号（例如只给了 3 个片段，却引用 [7]）。
 * 这类幻觉在提示词层面无法根治，只能在输出端做白名单校验。
 *
 * <p>校验规则：
 * <ul>
 *   <li>提取答案里所有 {@code [数字]}；</li>
 *   <li>编号必须落在 {@code 1..evidence.size()} 闭区间内；</li>
 *   <li>越界编号进入 {@code unauthorized}，供上层告警或触发重试。</li>
 * </ul>
 */
public class CitationValidator {

    /** 匹配 [1]、[12] 这类引用标记。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

    /**
     * @param answer   模型返回的答案文本
     * @param evidence 本次注入的证据列表
     */
    public ValidationResult validate(String answer, List<ScoredChunk> evidence) {
        if (answer == null || answer.isBlank()) {
            return new ValidationResult(false, List.of(), List.of(), false);
        }
        Set<Integer> referenced = extractNumbers(answer);

        List<Citation> citations = new ArrayList<>();
        List<Integer> unauthorized = new ArrayList<>();
        for (Integer index : referenced) {
            if (index >= 1 && index <= evidence.size()) {
                citations.add(Citation.of(evidence.get(index - 1).chunk()));
            } else {
                unauthorized.add(index);
            }
        }
        boolean valid = unauthorized.isEmpty() && !citations.isEmpty();
        return new ValidationResult(valid, List.copyOf(citations), List.copyOf(unauthorized),
                !citations.isEmpty());
    }

    private Set<Integer> extractNumbers(String answer) {
        Set<Integer> numbers = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    /**
     * 校验结果。
     *
     * @param valid        是否全部通过（有引用且无越界）
     * @param citations    合法引用列表，顺序与答案中首次出现顺序一致
     * @param unauthorized 越界的引用编号
     * @param hasCitation  是否至少存在一个合法引用
     */
    public record ValidationResult(
            boolean valid,
            List<Citation> citations,
            List<Integer> unauthorized,
            boolean hasCitation
    ) {
    }
}
