package com.example.codeagent.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码标识符的识别与切分规则。
 *
 * <p>这套规则被两处用到，所以放在 core 而不是各自的模块里：
 * <ul>
 *   <li><b>查询侧</b>：判断用户问题里是否"点名"了某个符号，
 *       这是混合检索里关键词路的强证据判据；</li>
 *   <li><b>文档侧</b>：从文档正文里抽取形如 {@code OrderService#createOrder} 的符号引用，
 *       用来建立文档与代码的关联，并做漂移检测。</li>
 * </ul>
 *
 * <p>如果两处各写一份，一旦规则不一致，就会出现"文档说引用了某个符号、
 * 但查询侧认不出来"这类难查的问题。
 */
public final class CodeIdentifiers {

    /** 标识符最短长度，过滤掉 for、int 这类无区分度的短词。 */
    private static final int MIN_LENGTH = 3;

    private CodeIdentifiers() {
    }

    /** 抽出文本中的连续 ASCII 片段（保留原始大小写）。 */
    public static List<String> asciiRuns(String text) {
        List<String> runs = new ArrayList<>();
        if (text == null) {
            return runs;
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                buffer.append(current);
            } else if (!buffer.isEmpty()) {
                runs.add(buffer.toString());
                buffer.setLength(0);
            }
        }
        if (!buffer.isEmpty()) {
            runs.add(buffer.toString());
        }
        return runs;
    }

    /** 按驼峰边界切分，例如 createOrderRequest 切成 create、Order、Request。 */
    public static List<String> splitCamelCase(String word) {
        List<String> parts = new ArrayList<>();
        if (word == null || word.isEmpty()) {
            return parts;
        }
        int start = 0;
        for (int i = 1; i < word.length(); i++) {
            boolean boundary = Character.isUpperCase(word.charAt(i))
                    && Character.isLowerCase(word.charAt(i - 1));
            if (boundary) {
                parts.add(word.substring(start, i));
                start = i;
            }
        }
        parts.add(word.substring(start));
        return parts;
    }

    /**
     * 判断一个词是否"看起来像代码符号"。
     *
     * <p>满足任一条即可：包含下划线；含点号（全限定名）；能按驼峰切成两段以上；
     * 全大写（常量）。
     *
     * <p>反例：{@code Spring}、{@code Boot} 这类普通英文单词不算——
     * 它们在自然语言注释里也常见，把它们当符号引用会造成大量误匹配。
     */
    public static boolean isIdentifierShaped(String token) {
        if (token == null || token.length() < MIN_LENGTH) {
            return false;
        }
        if (token.indexOf('_') >= 0 || token.indexOf('.') >= 0) {
            return true;
        }
        if (splitCamelCase(token).size() >= 2) {
            return true;
        }
        return token.chars().allMatch(Character::isUpperCase);
    }
}
