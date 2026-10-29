package com.example.codeagent.core.model;

import java.util.Map;

/**
 * 索引与检索的最小单元。
 *
 * <p><b>不变式</b>：
 * <ul>
 *   <li>{@code content} 必须与源文件对应行区间逐字一致（不得 trim、不得改写），
 *       否则引用回填会错位；</li>
 *   <li>{@code id} 不含行号语义，保证跨版本稳定（P0 例外，见下）；</li>
 *   <li>行号为 1-based 闭区间。</li>
 * </ul>
 *
 * <p>P0 的 {@code id} 暂用 {@code path#L开始-结束}，因为此时尚无符号信息；
 * P1 引入 AST 切块后会改为 {@code 全限定名+签名}，那是本方案"引用不因行号变化而失效"的关键。
 *
 * @param id        稳定标识
 * @param path      相对路径
 * @param kind      切块类型
 * @param content   原文内容
 * @param startLine 起始行（含）
 * @param endLine   结束行（含）
 * @param metadata  附加元数据（language / fileHash / fqn / annotations / docRefs ...）
 */
public record Chunk(
        String id,
        String path,
        ChunkKind kind,
        String content,
        int startLine,
        int endLine,
        Map<String, Object> metadata
) {

    public Chunk {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为 null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException(
                    "非法行号区间: " + startLine + "-" + endLine + " (" + path + ")");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 人类可读定位，直接用于引用展示。 */
    public String location() {
        return path + ":" + startLine + "-" + endLine;
    }

    /** 行数（含首尾）。 */
    public int lineCount() {
        return endLine - startLine + 1;
    }
}
