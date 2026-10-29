package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.text.CodeIdentifiers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从文档中抽取代码符号引用。
 *
 * <p>只认反引号包起来的内容，例如 {@code `OrderService#createOrder`}。
 * 这个约定不是随意定的：Markdown 里反引号就是"这是代码"的显式标记，
 * 只认它可以把误报压到最低；而正文里裸写的类名（比如"订单服务"）本来也不该当成符号。
 *
 * <p>抽取结果有两个用途：建立文档与代码的关联，以及检测文档是否引用了
 * 已经不存在的符号（代码被删或改名了，文档没跟上）。
 */
public final class DocSymbolExtractor {

    private DocSymbolExtractor() {
    }

    /**
     * @param text 文档片段
     * @return 形如 {@code OrderService#createOrder} 的符号引用，去重且保持出现顺序
     */
    public static List<String> extract(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> symbols = new LinkedHashSet<>();
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf('`', index);
            if (start < 0) {
                break;
            }
            int end = text.indexOf('`', start + 1);
            if (end < 0) {
                break;
            }
            String candidate = normalize(text.substring(start + 1, end));
            if (candidate != null) {
                symbols.add(candidate);
            }
            index = end + 1;
        }
        return new ArrayList<>(symbols);
    }

    /**
     * 规范化反引号里的内容。
     *
     * <p>去掉方法参数列表（{@code createOrder(OrderRequest)} 只保留 {@code createOrder}），
     * 因为参数类型对"这个符号是否存在"的判断没有帮助，反而会让匹配变得脆弱。
     *
     * @return 规范化后的符号；不像符号则返回 null
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int parenthesis = value.indexOf('(');
        if (parenthesis > 0) {
            value = value.substring(0, parenthesis);
        }
        if (value.length() < 3 || value.length() > 120) {
            return null;
        }
        // 以下内容不是符号引用，必须排除：
        //   SQL 片段 `(user_id, request_id)`、HTTP 路径 `GET /api/orders/{orderNo}`、
        //   文件路径 `docs/api/order-api.md`、命令 `mvn clean verify`
        // 判据是"含空白、斜杠、花括号或逗号"——合法的 Java 符号不会出现这些字符。
        if (value.chars().anyMatch(c -> Character.isWhitespace(c)
                || c == '/' || c == '{' || c == '}' || c == ',')) {
            return null;
        }
        if (value.contains("#")) {
            // 形如 OrderService#createOrder 或 com.acme.OrderService#createOrder
            String owner = value.substring(0, value.indexOf('#'));
            return owner.length() >= 3 && startsWithTypeName(owner) ? value : null;
        }
        if (value.contains(".")) {
            // 全限定名或嵌套类型：com.acme.order.OrderService、Order.OrderStatus
            String lastSegment = value.substring(value.lastIndexOf('.') + 1);
            return startsWithTypeName(lastSegment) ? value : null;
        }
        // 裸词：只接受类型名（首字母大写）。
        // 像 requestId 这样的字段名/参数名首字母小写，接受它会造成大量误报——
        // 它们在索引里没有独立切块，永远解析不到。
        return startsWithTypeName(value) ? value : null;
    }

    /** 类型名以大写字母开头；单驼峰（OrderService）或全大写常量都算。 */
    private static boolean startsWithTypeName(String value) {
        return !value.isEmpty()
                && Character.isUpperCase(value.charAt(0))
                && CodeIdentifiers.isIdentifierShaped(value);
    }
}
