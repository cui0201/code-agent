package com.example.codeagent.core.model;

/**
 * 答案中的引用。
 *
 * <p>代码引用填 {@code path + startLine + endLine}；文档引用填 {@code path + anchor}。
 *
 * @param path      相对路径
 * @param startLine 起始行（文档引用时为 0）
 * @param endLine   结束行（文档引用时为 0）
 * @param anchor    文档锚点（代码引用时为 null）
 * @param chunkId   被引用的切块 id，用于追溯
 */
public record Citation(String path, int startLine, int endLine, String anchor, String chunkId) {

    /** 生成人类可读的引用文本。 */
    public String format() {
        return anchor == null || anchor.isBlank()
                ? path + ":" + startLine + "-" + endLine
                : path + "#" + anchor;
    }

    /**
     * 由切块生成引用。
     *
     * <p>代码块用行号区间定位；文档块用锚点定位（{@code docs/api/order-api.md#请求字段}）——
     * 对文档来说"第 12 到 20 行"这种定位几乎没有价值，而锚点是稳定且人能直接跳转的。
     */
    public static Citation of(Chunk chunk) {
        Object anchor = chunk.metadata().get(MetadataKeys.ANCHOR);
        if (chunk.kind() == ChunkKind.DOC_SECTION && anchor != null) {
            return new Citation(chunk.path(), 0, 0, String.valueOf(anchor), chunk.id());
        }
        return new Citation(chunk.path(), chunk.startLine(), chunk.endLine(), null, chunk.id());
    }
}
