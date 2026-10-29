package com.example.codeagent.retrieval.query;

import com.example.codeagent.retrieval.keyword.Tokenizer;
import com.example.codeagent.core.text.CodeIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从问题中识别"代码标识符形态"的词。
 *
 * <p>这是 P2 评测得出的一条关键判据。评测中有两条用例失败：
 * 问题里明明写了 {@code createOrder}、{@code OrderController}，
 * 向量检索却匹配不上，反被语义相近的文件挤掉。
 * 而在代码问答场景里，<b>用户点名了一个真实存在的符号</b>，
 * 本身就是最强、最可靠的证据信号。
 *
 * <p>因此这里区分两类 ASCII 词：
 * <ul>
 *   <li>{@code createOrder}、{@code OrderController}、{@code MAX_AMOUNT}
 *       —— 标识符形态，视为"点名了符号"；</li>
 *   <li>{@code Spring}、{@code Boot}、{@code version}
 *       —— 普通英文单词，不视为符号引用（这类词在注释里也常见，容易误匹配）。</li>
 * </ul>
 */
public final class IdentifierExtractor {

    /** 标识符最短长度，过滤掉 for、int 这类无区分度的短词。 */
    private static final int MIN_LENGTH = 3;

    private IdentifierExtractor() {
    }

    /**
     * @param text 用户问题
     * @return 标识符形态的词，保持原始大小写，已去重
     */
    public static List<String> extract(String text) {
        List<String> identifiers = new ArrayList<>();
        for (String run : Tokenizer.asciiRuns(text)) {
            if (isIdentifierShaped(run) && !containsIgnoreCase(identifiers, run)) {
                identifiers.add(run);
            }
        }
        return identifiers;
    }

    private static boolean isIdentifierShaped(String run) {
        return run.length() >= MIN_LENGTH && CodeIdentifiers.isIdentifierShaped(run);
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(lower));
    }
}
