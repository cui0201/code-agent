package com.example.codeagent.indexer.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 影响面分析：给定一个符号，找出改动它会波及哪些代码。
 *
 * <p>这类问题向量检索完全答不了——答案不在任何一段代码的语义里，
 * 而在代码之间的结构关系里。这也是符号图存在的理由。
 *
 * <p>分析分三步，对应三类不同的影响方式：
 * <ol>
 *   <li><b>改方法</b>：谁调用了它（反向 CALLS），距离越近影响越直接；</li>
 *   <li><b>改接口或父类</b>：哪些类实现或继承了它（反向 IMPLEMENTS/EXTENDS）；</li>
 *   <li><b>看全貌</b>：这个类型下声明了哪些方法（正向 DECLARES）。</li>
 * </ol>
 *
 * <p>结果里同时给出"受影响的文件列表"，因为人最终关心的是"我要改哪几个文件"，
 * 而不是图的形状。
 */
public class ImpactAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalyzer.class);

    /** 单次分析返回的候选符号数量上限，避免模糊匹配时刷屏。 */
    private static final int MAX_SUGGESTIONS = 10;

    private static final Set<EdgeType> CALL_EDGES = Set.of(EdgeType.CALLS);
    private static final Set<EdgeType> TYPE_EDGES = Set.of(EdgeType.EXTENDS, EdgeType.IMPLEMENTS);

    private final SymbolGraph graph;

    public ImpactAnalyzer(SymbolGraph graph) {
        this.graph = graph;
    }

    /**
     * 分析一个符号的影响面。
     *
     * @param query 用户输入，可以是全限定方法名、类名，或只有方法名
     * @param depth 传播跳数；1 只看直接依赖，2~3 适合评估改动波及范围
     */
    public ImpactReport analyze(String query, int depth) {
        int effectiveDepth = Math.max(1, depth);
        if (graph.symbolCount() == 0) {
            log.warn("符号图为空（可能是索引时解析失败），无法做影响面分析");
            return ImpactReport.notFound(query, effectiveDepth, List.of());
        }

        List<String> matches = resolve(query);
        if (matches.isEmpty()) {
            return ImpactReport.notFound(query, effectiveDepth, List.of());
        }
        // 模糊匹配到多个候选时，选最像的那个分析，同时把候选列表带回去让人确认
        if (matches.size() > 1) {
            log.info("符号匹配到 {} 个候选，取第一个: {}", matches.size(), matches.get(0));
        }
        String symbol = matches.get(0);

        List<ImpactReport.ImpactItem> callers = toItems(
                graph.reachableFrom(symbol, effectiveDepth, CALL_EDGES));
        List<ImpactReport.ImpactItem> subtypes = toItems(
                graph.reachableFrom(symbol, effectiveDepth, TYPE_EDGES));
        List<ImpactReport.ImpactItem> members = toItems(
                graph.reachableTo(symbol, 1, Set.of(EdgeType.DECLARES)));

        Set<String> files = new LinkedHashSet<>();
        for (ImpactReport.ImpactItem item : concat(callers, subtypes, members)) {
            if (item.file() != null && !item.file().isBlank()) {
                files.add(item.file());
            }
        }
        // 候选多于一个时，把其余候选放进 affectedFiles 会让人误解，因此只保留受影响的文件
        ImpactReport report = new ImpactReport(query, symbol, true, effectiveDepth,
                callers, subtypes, members, List.copyOf(files),
                matches.size() > 1 ? matches.subList(1, matches.size()) : List.of());
        log.info("影响面分析完成: {} (depth={}) -> 调用方={}, 子类型={}, 成员={}, 受影响文件={}",
                symbol, effectiveDepth, callers.size(), subtypes.size(),
                members.size(), report.affectedFiles().size());
        return report;
    }

    /**
     * 把用户输入解析为图中的确切符号。
     *
     * <p>人不会写全 {@code com.acme.order.OrderService#createOrder(OrderRequest)}，
     * 常见写法是 {@code OrderService#createOrder}（不带包名、不带参数）。
     * 因此这里做的是"结构化后缀匹配"，而不是简单的字符串包含：
     * 把查询与候选都拆成"类型 + 方法名"两段分别比较，参数列表一律忽略。
     *
     * <p>直接对整串做 endsWith 是行不通的——候选形如
     * {@code com.acme.order.OrderService#createOrder(OrderRequest)}，
     * 以 {@code OrderService#createOrder} 结尾并不成立（后面还有参数列表）。
     */
    private List<String> resolve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String value = query.trim();
        Set<String> symbols = graph.symbols();
        if (symbols.contains(value)) {
            return List.of(value);
        }

        String typePart = null;
        String methodPart = value;
        int hash = value.indexOf('#');
        if (hash > 0) {
            typePart = value.substring(0, hash);
            methodPart = value.substring(hash + 1);
        }
        // 查询里写了参数列表也接受，比较时忽略
        int parenthesis = methodPart.indexOf('(');
        if (parenthesis > 0) {
            methodPart = methodPart.substring(0, parenthesis);
        }

        List<String> matches = new ArrayList<>();
        for (String symbol : symbols) {
            String owner = SymbolKeys.ownerOf(symbol);
            String methodName = methodNameOf(symbol);
            if (typePart != null) {
                if (ownerMatches(owner, typePart) && methodPart.equals(methodName)) {
                    matches.add(symbol);
                }
            } else if (ownerMatches(owner, value)
                    || (methodName != null && methodName.equals(methodPart))) {
                matches.add(symbol);
            }
        }
        return matches.stream().sorted().limit(MAX_SUGGESTIONS).toList();
    }

    /** 类型名匹配：全等或后缀（允许省略包名）。 */
    private static boolean ownerMatches(String owner, String given) {
        return owner.equals(given) || owner.endsWith("." + given);
    }

    /** 取符号的方法名；类型符号没有 {@code #}，返回 null。 */
    private static String methodNameOf(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash < 0) {
            return null;
        }
        String method = symbol.substring(hash + 1);
        int parenthesis = method.indexOf('(');
        return parenthesis > 0 ? method.substring(0, parenthesis) : method;
    }

    private List<ImpactReport.ImpactItem> toItems(List<SymbolGraph.Reach> reaches) {
        List<ImpactReport.ImpactItem> items = new ArrayList<>(reaches.size());
        for (SymbolGraph.Reach reach : reaches) {
            items.add(new ImpactReport.ImpactItem(reach.symbol(), reach.distance(),
                    reach.viaType(), reach.file(), reach.line()));
        }
        return List.copyOf(items);
    }

    @SafeVarargs
    private static List<ImpactReport.ImpactItem> concat(List<ImpactReport.ImpactItem>... lists) {
        List<ImpactReport.ImpactItem> all = new ArrayList<>();
        for (List<ImpactReport.ImpactItem> list : lists) {
            all.addAll(list);
        }
        return all;
    }
}
