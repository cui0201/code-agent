package com.example.codeagent.retrieval.keyword;

import com.example.codeagent.core.text.CodeIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 面向"中文提问 + 英文代码"场景的分词器。
 *
 * <p>分词质量直接决定关键词检索的成败，这里做了三件事：
 * <ol>
 *   <li><b>标识符整体保留</b>：{@code createOrder} 除了拆成 {@code create} 与 {@code order}，
 *       还会保留整体小写形式 {@code createorder}。整体形式才是强区分信号——
 *       拆开后的 create、order 几乎出现在每个文件里，IDF 权重极低。</li>
 *   <li><b>驼峰与下划线切分</b>：{@code MAX_AMOUNT} 切成 max、amount，
 *       让用户用任一写法都能命中。</li>
 *   <li><b>中文按二元组切分</b>：中文没有空格，用相邻两字组合近似词，
 *       这是无词典场景下最简单可靠的做法。</li>
 * </ol>
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /** 把文本切成检索用的词元。 */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (isCjk(current)) {
                flushAscii(ascii, tokens);
                cjk.append(current);
            } else if (Character.isLetterOrDigit(current)) {
                flushCjk(cjk, tokens);
                ascii.append(current);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    /**
     * 抽出文本中的连续 ASCII 片段（保持原始大小写）。
     *
     * <p>供标识符识别使用：判断一个问题里是否"点名"了某个代码符号。
     */
    public static List<String> asciiRuns(String text) {
        return CodeIdentifiers.asciiRuns(text);
    }

    private static void flushAscii(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        String original = buffer.toString();
        buffer.setLength(0);

        String whole = original.toLowerCase(Locale.ROOT);
        if (whole.length() > 1) {
            tokens.add(whole);
        }
        for (String part : splitCamelCase(original)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.length() > 1 && !lower.equals(whole)) {
                tokens.add(lower);
            }
        }
    }

    private static void flushCjk(StringBuilder buffer, List<String> tokens) {
        if (buffer.isEmpty()) {
            return;
        }
        String run = buffer.toString();
        buffer.setLength(0);
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }

    /** 按驼峰边界切分，例如 createOrderRequest 切成 create、Order、Request。 */
    public static List<String> splitCamelCase(String word) {
        return CodeIdentifiers.splitCamelCase(word);
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }
}
