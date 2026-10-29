package com.example.codeagent.indexer.graph;

import java.util.List;

/**
 * 影响面分析结果。
 *
 * @param query          用户原始输入
 * @param symbol         解析后真正被分析的符号；未找到时为 null
 * @param found          是否在图中找到了这个符号
 * @param depth          分析的跳数
 * @param callers        反向可达的调用方（谁会被影响），按距离升序
 * @param subtypes       子类与实现类（改接口/父类时的影响范围）
 * @param members        该类型声明的方法（以类为起点时有值）
 * @param affectedFiles  受影响的源文件去重列表，这是最贴近实际改动的结论
 * @param suggestions    未精确匹配时给出的候选符号，供调用方确认后再分析
 */
public record ImpactReport(
        String query,
        String symbol,
        boolean found,
        int depth,
        List<ImpactItem> callers,
        List<ImpactItem> subtypes,
        List<ImpactItem> members,
        List<String> affectedFiles,
        List<String> suggestions
) {

    public ImpactReport {
        callers = callers == null ? List.of() : List.copyOf(callers);
        subtypes = subtypes == null ? List.of() : List.copyOf(subtypes);
        members = members == null ? List.of() : List.copyOf(members);
        affectedFiles = affectedFiles == null ? List.of() : List.copyOf(affectedFiles);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static ImpactReport notFound(String query, int depth, List<String> suggestions) {
        return new ImpactReport(query, null, false, depth,
                List.of(), List.of(), List.of(), List.of(), suggestions);
    }

    /**
     * 一条影响项。
     *
     * @param symbol   受影响的符号
     * @param distance 与起点的跳数；1 表示直接依赖
     * @param viaType  通过哪种关系受影响；起点自身为 null
     * @param file     所在文件
     * @param line     触发这条边的那一行代码（可用来复核）
     */
    public record ImpactItem(String symbol, int distance, EdgeType viaType, String file, int line) {

        /** 供日志与接口展示的一行摘要。 */
        public String describe() {
            String relation = viaType == null ? "" : " [" + viaType + "]";
            String location = file == null || file.isBlank()
                    ? "" : "  " + file + (line > 0 ? ":" + line : "");
            return symbol + relation + location;
        }
    }
}
