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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文件切块器：按"配置块"而不是"行窗口"切分。
 *
 * <h2>为什么配置文件需要单独的切块器</h2>
 *
 * 事实型问题里有很大一类是问配置值："默认的拒答阈值是多少""默认向量库是哪个"。
 * 这类问题的答案就写在 YAML 的一个键上，但如果：
 * <ul>
 *   <li>配置文件根本没被索引（最初就是这样，只索引了 .java 与 .md），答案必然找不到；</li>
 *   <li>按 60 行窗口切，一个键会和几十个无关键混在一起，
 *       检索分数被稀释，模型也容易看错行。</li>
 * </ul>
 *
 * <p>所以这里按 YAML 的二级键（{@code server}、{@code spring.ai}、
 * {@code code-agent.retrieval}）切块：一行键连同它的子项与上方注释成为一个块，
 * 既保留了"这个值属于哪个配置"的上下文，又不会混进无关配置。
 *
 * <h2>为什么把注释一起带上</h2>
 *
 * 本项目大量使用注释解释"为什么取这个值"（例如 0.62 是怎么校准出来的）。
 * 只取键值会丢掉最有价值的那部分信息——而这恰恰是文档类问题的答案来源。
 */
public class ConfigFileChunker implements Chunker {

    /** YAML 键行：缩进 + 键名 + 冒号 + 可选值。 */
    private static final Pattern YAML_KEY =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(.*)$");

    /** properties 键值行。 */
    private static final Pattern PROPERTY_KEY =
            Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*[=:]\\s*(.*)$");

    /** 只切到二级键：更深层的内容跟随它的父块，避免块过碎。 */
    private static final int MAX_KEY_INDENT = 2;

    @Override
    public List<Chunk> chunk(SourceFile file) {
        return switch (file.extension()) {
            case "yml", "yaml" -> chunkYaml(file);
            case "properties" -> chunkProperties(file);
            default -> List.of();
        };
    }

    /** YAML：按缩进不超过 2 的键切块，块内保留完整子树与上方注释。 */
    private List<Chunk> chunkYaml(SourceFile file) {
        String[] lines = splitLines(file.content());
        List<int[]> blockStarts = new ArrayList<>();   // {lineIndex, indent}
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = YAML_KEY.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            int indent = matcher.group(1).length();
            if (indent <= MAX_KEY_INDENT && !matcher.group(2).isBlank()) {
                blockStarts.add(new int[]{i, indent});
            }
        }
        if (blockStarts.isEmpty()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>(blockStarts.size());
        for (int i = 0; i < blockStarts.size(); i++) {
            int keyLine = blockStarts.get(i)[0];
            int indent = blockStarts.get(i)[1];
            int endExclusive = (i + 1 < blockStarts.size())
                    ? blockStarts.get(i + 1)[0] : lines.length;
            int start = includeLeadingComments(lines, keyLine);

            // 计算完整的键路径：二级键要带上一级键作为前缀
            String key = keyName(lines[keyLine]);
            if (indent > 0) {
                String parent = enclosingKey(lines, keyLine, indent);
                if (!parent.isBlank()) {
                    key = parent + "." + key;
                }
            }
            chunks.add(buildChunk(file, lines, start, endExclusive, "yaml", "yaml-block", key));
        }
        return chunks;
    }

    /** properties：每个键一个块，上方注释一起带上。 */
    private List<Chunk> chunkProperties(SourceFile file) {
        String[] lines = splitLines(file.content());
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = PROPERTY_KEY.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            int start = includeLeadingComments(lines, i);
            // properties 是一行一个键，块就是这个键（加上注释）
            chunks.add(buildChunk(file, lines, start, i + 1, "properties", "properties-entry",
                    matcher.group(1)));
        }
        return chunks;
    }

    private Chunk buildChunk(SourceFile file, String[] lines, int start, int endExclusive,
                             String language, String chunkerName, String configKey) {
        int startLine = start + 1;
        int endLine = Math.max(startLine, endExclusive);
        String content = String.join("\n", Arrays.copyOfRange(lines, start, endExclusive));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", language);
        metadata.put("fileHash", file.hash());
        metadata.put("lastModifiedMillis", file.lastModifiedMillis());
        metadata.put("chunker", chunkerName);
        metadata.put("configKey", configKey);

        // 键名进 id：同一个键在文件里移动位置不会改变 id，引用依然稳定
        return new Chunk(file.path() + "#key:" + configKey, file.path(),
                ChunkKind.BLOCK, content, startLine, endLine, metadata);
    }

    /**
     * 把紧邻上方的注释行一并纳入块。
     *
     * <p>只往前吃连续的非空注释行；遇到空行就停——
     * 空行是配置块之间的自然分隔，跨空行会把上一块的注释误挂到这一块上。
     */
    private int includeLeadingComments(String[] lines, int keyLine) {
        int start = keyLine;
        while (start > 0) {
            String previous = lines[start - 1].trim();
            if (previous.startsWith("#")) {
                start--;
            } else {
                break;
            }
        }
        return start;
    }

    private String keyName(String line) {
        Matcher matcher = YAML_KEY.matcher(line);
        return matcher.matches() ? matcher.group(2) : "";
    }

    /** 往上找缩进更小、最靠近的那个键作为父键。 */
    private String enclosingKey(String[] lines, int keyLine, int indent) {
        for (int i = keyLine - 1; i >= 0; i--) {
            Matcher matcher = YAML_KEY.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            int candidateIndent = matcher.group(1).length();
            if (candidateIndent < indent) {
                return matcher.group(2);
            }
        }
        return "";
    }

    /** 按行切分并丢弃文件末尾换行产生的空行，与其它切块器保持一致。 */
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
