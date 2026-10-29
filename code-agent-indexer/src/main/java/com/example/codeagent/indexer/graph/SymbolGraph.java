package com.example.codeagent.indexer.graph;

import org.jgrapht.Graph;
import org.jgrapht.graph.DirectedPseudograph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 符号图：节点是类型与方法，边是继承、实现、调用、声明关系。
 *
 * <p>它解决的是向量检索根本解决不了的一类问题：
 * "改动 {@code OrderService.createOrder} 会影响哪些对外接口"——
 * 答案不在任何一段代码的语义里，而在代码之间的<b>结构关系</b>里。
 * 再好的嵌入模型也无法从单个方法的文本中推出谁调用了它。
 *
 * <h2>为什么用 JGraphT 而不是自己写 Map</h2>
 *
 * 图算法本身不难，但这个类需要的是：
 * 多重边（两个方法之间可能既是继承又要调用）、自环、
 * 正反向邻接查询、以及后续可能加入的最短路径与连通分量。
 * 这些 JGraphT 都已实现且经过测试，自己写反而容易在边界上出错。
 *
 * <p>线程安全：构建期单线程写入，构建完成后只读。查询方法不加锁，
 * 依赖"构建完成后再对外发布"这一约定（与索引的写入语义一致）。
 */
public class SymbolGraph {

    /** 多重有向图：允许两个节点之间存在多条不同类型的边。 */
    private final Graph<String, SymbolEdge> graph =
            new DirectedPseudograph<>(SymbolEdge.class);

    /** 记录每个节点所属的源文件，便于把影响面换算成"要改哪些文件"。 */
    private final Map<String, String> fileOfSymbol = new LinkedHashMap<>();

    /** 一条边：类型 + 位置，位置用于让人能直接跳到调用点复核。 */
    public record SymbolEdge(EdgeType type, String file, int line) {
    }

    public void addVertex(String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            graph.addVertex(symbol);
        }
    }

    /** 记录符号所在的文件；同一符号可能出现在多个切块里，取第一次出现的即可。 */
    public void addSymbolFile(String symbol, String file) {
        if (symbol != null && file != null) {
            fileOfSymbol.putIfAbsent(symbol, file);
        }
    }

    /**
     * 添加一条边。
     *
     * <p>自环（方法递归调用自己）会被 JGraphT 接受，
     * 但在遍历时要用"已访问集合"拦住，否则会无限循环。
     */
    public void addEdge(String from, String to, EdgeType type, String file, int line) {
        if (from == null || to == null || from.isBlank() || to.isBlank() || type == null) {
            return;
        }
        graph.addVertex(from);
        graph.addVertex(to);
        graph.addEdge(from, to, new SymbolEdge(type, file, line));
    }

    public Set<String> symbols() {
        return Set.copyOf(graph.vertexSet());
    }

    public boolean contains(String symbol) {
        return symbol != null && graph.containsVertex(symbol);
    }

    public int symbolCount() {
        return graph.vertexSet().size();
    }

    public int edgeCount() {
        return graph.edgeSet().size();
    }

    /**
     * 导出全部边，供持久化使用。
     *
     * <p>节点集合单独通过 {@link #symbols()} 导出；边里已经包含两端的符号名，
     * 但孤立节点（没有任何边的类型或方法）只靠边是恢复不出来的，
     * 所以持久化时必须同时保存节点与边。
     */
    public List<EdgeView> edges() {
        List<EdgeView> result = new ArrayList<>(graph.edgeSet().size());
        for (SymbolEdge edge : graph.edgeSet()) {
            result.add(new EdgeView(graph.getEdgeSource(edge), graph.getEdgeTarget(edge),
                    edge.type(), edge.file(), edge.line()));
        }
        return result;
    }

    /** 符号到源文件的映射，供持久化使用。 */
    public Map<String, String> symbolFiles() {
        return Map.copyOf(fileOfSymbol);
    }

    /** 一条可序列化的边。 */
    public record EdgeView(String from, String to, EdgeType type, String file, int line) {

        public void restoreInto(SymbolGraph target) {
            target.addEdge(from, to, type, file, line);
        }
    }

    public String fileOf(String symbol) {
        return fileOfSymbol.get(symbol);
    }

    /**
     * 反向可达：谁依赖了这个符号（调用方 / 子类 / 实现类）。
     *
     * @param symbol    起点
     * @param maxDepth  最大跳数；1 表示只看直接调用者
     * @param edgeTypes 只沿这些类型反向走；传空表示不限
     * @return 距离与到达路径，按距离升序（越近的影响越直接）
     */
    public List<Reach> reachableFrom(String symbol, int maxDepth, Set<EdgeType> edgeTypes) {
        return traverse(symbol, maxDepth, edgeTypes, true);
    }

    /**
     * 正向可达：这个符号依赖了谁（被调用方 / 父类 / 接口）。
     */
    public List<Reach> reachableTo(String symbol, int maxDepth, Set<EdgeType> edgeTypes) {
        return traverse(symbol, maxDepth, edgeTypes, false);
    }

    /**
     * 广度优先遍历。
     *
     * <p>用 BFS 而不是 DFS 的原因：影响面分析关心的是"多少跳之内"，
     * BFS 天然按距离分层，且能在同一层内一次性收集全部节点；
     * DFS 需要额外维护距离，还容易在环上重复访问。
     *
     * @param reverse true 表示沿边的反方向走（找调用方），false 表示顺方向走
     */
    private List<Reach> traverse(String symbol, int maxDepth, Set<EdgeType> edgeTypes,
                                 boolean reverse) {
        List<Reach> result = new ArrayList<>();
        if (symbol == null || !graph.containsVertex(symbol) || maxDepth <= 0) {
            return result;
        }

        // 已访问集合是必须的：方法之间的循环调用会形成环
        Set<String> visited = new LinkedHashSet<>();
        visited.add(symbol);

        Deque<Reach> queue = new ArrayDeque<>();
        queue.add(new Reach(symbol, 0, null, fileOfSymbol.get(symbol), -1));

        while (!queue.isEmpty()) {
            Reach current = queue.poll();
            if (current.distance() >= maxDepth) {
                continue;
            }
            for (Neighbor neighbor : neighborsOf(current.symbol(), edgeTypes, reverse)) {
                if (!visited.add(neighbor.symbol())) {
                    continue;
                }
                Reach next = new Reach(neighbor.symbol(), current.distance() + 1,
                        neighbor.type(), neighbor.file(), neighbor.line());
                result.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    /** 取得一个节点的邻居（按方向与边类型过滤）。 */
    private List<Neighbor> neighborsOf(String symbol, Set<EdgeType> edgeTypes, boolean reverse) {
        List<Neighbor> neighbors = new ArrayList<>();
        // incomingEdgesOf / outgoingEdgesOf 是 Graph 接口自带的方法，
        // 不需要（也无法）走 Graphs 工具类
        List<SymbolEdge> edges = new ArrayList<>(reverse
                ? graph.incomingEdgesOf(symbol)
                : graph.outgoingEdgesOf(symbol));
        for (SymbolEdge edge : edges) {
            if (edgeTypes != null && !edgeTypes.isEmpty() && !edgeTypes.contains(edge.type())) {
                continue;
            }
            String other = reverse
                    ? graph.getEdgeSource(edge)
                    : graph.getEdgeTarget(edge);
            neighbors.add(new Neighbor(other, edge.type(), edge.file(), edge.line()));
        }
        return neighbors;
    }

    /** 遍历过程中的邻居。 */
    private record Neighbor(String symbol, EdgeType type, String file, int line) {
    }

    /**
     * 一次可达结果。
     *
     * @param symbol   到达的符号
     * @param distance 距离起点的跳数
     * @param viaType  通过哪种关系到达；起点自身为 null
     * @param file     该符号所在文件
     * @param line     触发这条边的代码行（-1 表示未知）
     */
    public record Reach(String symbol, int distance, EdgeType viaType, String file, int line) {
    }
}
