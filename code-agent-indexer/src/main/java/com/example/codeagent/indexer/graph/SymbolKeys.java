package com.example.codeagent.indexer.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 符号键的构造与规范化。
 *
 * <p><b>这个类存在的唯一目的：让两套工具产出同一把钥匙。</b>
 *
 * <p>我们的切块 id 由 JavaParser 生成，形如
 * {@code com.acme.order.OrderService#createOrder(OrderRequest)}；
 * 而符号图由 Spoon 生成，它给出的参数类型是<b>全限定名</b>，
 * 形如 {@code com.acme.order.OrderService#createOrder(com.acme.order.OrderRequest)}。
 *
 * <p>两者指向同一个方法，字面上却不相等。如果不做归一化，
 * 检索命中的切块 id 在图里就查不到对应节点——"引用能落地"这条底线会直接失效。
 *
 * <p>归一化规则（两边都按同一规则处理，因此只要语义相同就能对上）：
 * <ol>
 *   <li>参数类型去掉包名，只留简单名；</li>
 *   <li>去掉泛型参数（{@code List<String>} 与 {@code java.util.List} 视为同一类型）；</li>
 *   <li>去掉数组标记之外的空白；</li>
 *   <li>参数之间用逗号连接，不保留参数名（参数名不影响方法身份）。</li>
 * </ol>
 */
public final class SymbolKeys {

    private SymbolKeys() {
    }

    /**
     * 构造方法键。
     *
     * @param typeName   所属类型的全限定名，例如 {@code com.acme.order.OrderService}
     * @param methodName 方法名（构造函数传类名）
     * @param paramTypes 参数类型（可以是全限定名，也可以是源码里写的简单名）
     * @return 归一化后的方法键，例如 {@code com.acme.order.OrderService#createOrder(OrderRequest)}
     */
    public static String methodKey(String typeName, String methodName, List<String> paramTypes) {
        List<String> simpleParams = new ArrayList<>(paramTypes == null ? 0 : paramTypes.size());
        if (paramTypes != null) {
            for (String paramType : paramTypes) {
                simpleParams.add(simplifyType(paramType));
            }
        }
        return typeName + "#" + methodName + "(" + String.join(",", simpleParams) + ")";
    }

    /**
     * 把任意形式的类型名归一化为简单名。
     *
     * <p>处理顺序很关键：先截断泛型再取简单名，
     * 否则 {@code java.util.List<java.lang.String>} 会因为 {@code <} 之后还有包名而取错。
     */
    public static String simplifyType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        // 1. 去掉泛型：List<String> -> List
        int generic = value.indexOf('<');
        if (generic > 0) {
            value = value.substring(0, generic);
        }
        // 2. 去掉数组/可变参数标记后单独处理，避免把 [] 当成包名的一部分
        String suffix = "";
        while (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
            suffix = "[]" + suffix;
        }
        // 3. 去掉包名：java.util.List -> List
        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0) {
            value = value.substring(lastDot + 1);
        }
        return value + suffix;
    }

    /** 从方法键中取出所属类型，例如 {@code com.acme.order.OrderService#createOrder(OrderRequest)} 到类型名。 */
    public static String ownerOf(String symbol) {
        int hash = symbol.indexOf('#');
        return hash > 0 ? symbol.substring(0, hash) : symbol;
    }
}
