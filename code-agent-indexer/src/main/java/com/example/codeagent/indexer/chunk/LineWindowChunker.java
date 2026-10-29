package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0 的定长行窗口切块器。
 *
 * <p><b>这是临时实现，不是最终方案。</b>它的存在有两个目的：
 * <ol>
 *   <li>让 P0 的最小闭环能跑通；</li>
 *   <li>为 P1 的 AST 切块提供对照基线——用同一套评测集比较召回率，
 *       才能说明"结构化切块到底带来了多少收益"。</li>
 * </ol>
 *
 * <p>已知缺陷：会把一个方法从中间切断，导致语义不完整；也无法识别类、方法边界。
 * 这些正是 P1 要解决的问题。
 *
 * <p>行号规则：窗口按行号切分，{@code startLine} 与 {@code endLine} 均为 1-based 闭区间，
 * 且窗口内容由原始行重新拼接而成，保证与源文件一致。
 */
public class LineWindowChunker implements Chunker {

    private static final String LANGUAGE = "java";

    private final int windowLines;
    private final int overlapLines;

    /**
     * @param windowLines  每个窗口包含的行数，必须大于等于 1
     * @param overlapLines 相邻窗口的重叠行数，必须大于等于 0 且小于 windowLines
     */
    public LineWindowChunker(int windowLines, int overlapLines) {
        if (windowLines < 1) {
            throw new IllegalArgumentException("windowLines 必须大于等于 1，当前: " + windowLines);
        }
        if (overlapLines < 0) {
            throw new IllegalArgumentException("overlapLines 不能为负，当前: " + overlapLines);
        }
        this.windowLines = windowLines;
        // 重叠行数必须小于窗口行数，否则步长为 0 会导致死循环
        this.overlapLines = Math.min(overlapLines, windowLines - 1);
    }

    @Override
    public List<Chunk> chunk(SourceFile file) {
        String[] lines = splitLines(file.content());
        if (lines.length == 0) {
            return List.of();
        }
        int step = windowLines - overlapLines;
        List<Chunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.length; start += step) {
            int endExclusive = Math.min(start + windowLines, lines.length);
            if (!isBlank(lines, start, endExclusive)) {
                chunks.add(toChunk(file, lines, start, endExclusive));
            }
            if (endExclusive == lines.length) {
                break;
            }
        }
        return chunks;
    }

    private Chunk toChunk(SourceFile file, String[] lines, int start, int endExclusive) {
        int startLine = start + 1;
        int endLine = endExclusive;
        String content = String.join("\n", Arrays.copyOfRange(lines, start, endExclusive));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", LANGUAGE);
        metadata.put("fileHash", file.hash());
        metadata.put("chunker", "line-window");
        metadata.put("windowLines", windowLines);
        metadata.put("overlapLines", overlapLines);

        // P0 的 id 暂用位置信息；P1 起改为"全限定名 + 签名"，不再受行号变化影响
        String id = file.path() + "#L" + startLine + "-" + endLine;
        return new Chunk(id, file.path(), ChunkKind.BLOCK, content, startLine, endLine, metadata);
    }

    private static boolean isBlank(String[] lines, int start, int endExclusive) {
        for (int i = start; i < endExclusive; i++) {
            if (!lines[i].isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按行切分。
     *
     * <p>文件以换行结尾时会产生一个尾随空串，这里丢弃它，
     * 避免最后一行为空的窗口拉长行号区间。
     */
    private static String[] splitLines(String content) {
        if (content.isEmpty()) {
            return new String[0];
        }
        String[] lines = content.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            return Arrays.copyOf(lines, lines.length - 1);
        }
        return lines;
    }
}
