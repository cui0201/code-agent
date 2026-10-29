package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档切块：按标题层级切章节。
 *
 * <p>为什么按标题切而不是按长度切：文档的语义单元就是章节，
 * 而且标题天然给出了锚点，可以生成 {@code docs/api/order-api.md#请求字段}
 * 这样的可定位引用。按长度切会让引用只能落到"某一行"，
 * 对文档来说这个定位几乎没有价值。
 *
 * <p>实现上没有引入 Markdown 解析库：当前只需要识别标题层级、
 * 跳过围栏代码块、抽取反引号符号这三件事，几十行正则即可，
 * 而完整的 AST 解析能力在这里用不上。等需要处理表格嵌套、
 * 引用链接等复杂结构时再引入依赖。
 */
public class MarkdownDocChunker implements Chunker {

    /** 标题行：1~6 个 # 加空格加标题文本。 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /** 围栏代码块起止标记。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");

    @Override
    public List<Chunk> chunk(SourceFile file) {
        String[] lines = splitLines(file.content());
        List<Heading> headings = findHeadings(lines);
        if (headings.isEmpty()) {
            return List.of(preambleChunk(file, lines));
        }

        String docTitle = headings.stream()
                .filter(heading -> heading.level == 1)
                .map(heading -> heading.text)
                .findFirst()
                .orElse(file.path());

        List<Chunk> chunks = new ArrayList<>();
        Map<String, Integer> anchorCounters = new LinkedHashMap<>();
        // 首个标题之前的正文（例如"本文件由生成流水线产出，请勿手工修改"）
        if (isNotBlank(lines, 0, headings.get(0).lineIndex)) {
            chunks.add(preambleChunk(file, lines));
        }
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int endExclusive = (i + 1 < headings.size())
                    ? headings.get(i + 1).lineIndex
                    : lines.length;
            String content = joinLines(lines, heading.lineIndex, endExclusive);
            chunks.add(toChunk(file, heading, content, docTitle, endExclusive,
                    uniqueAnchor(heading.anchor, anchorCounters)));
        }
        return chunks;
    }

    /**
     * 保证同一文件内锚点唯一。
     *
     * <p>文档里出现两个同名小节是常有的事（例如每个接口下都写一个"相关实现"），
     * 它们生成的锚点完全相同。如果不加区分，两个切块会拿到<b>相同的 id</b>，
     * 后果是：Qdrant 按 id 覆盖导致少一个点、RRF 融合把它们合并成一条、
     * 引用也无法指到具体那一节。
     *
     * <p>后缀规则与 GitHub 渲染保持一致（第二个同名标题加 {@code -1}），
     * 这样生成的引用链接在渲染后的文档里也能跳到正确位置。
     */
    private static String uniqueAnchor(String anchor, Map<String, Integer> counters) {
        int occurrence = counters.merge(anchor, 1, Integer::sum);
        return occurrence == 1 ? anchor : anchor + "-" + (occurrence - 1);
    }

    private Chunk toChunk(SourceFile file, Heading heading, String content,
                          String docTitle, int endExclusive, String anchor) {
        Map<String, Object> metadata = baseMetadata(file);
        metadata.put(MetadataKeys.ANCHOR, anchor);
        metadata.put(MetadataKeys.HEADING_PATH, heading.path());
        metadata.put("headingLevel", heading.level);
        metadata.put("docTitle", docTitle);
        metadata.put("synthesized", false);
        List<String> codeRefs = DocSymbolExtractor.extract(content);
        if (!codeRefs.isEmpty()) {
            metadata.put(MetadataKeys.CODE_REFS, codeRefs);
        }
        // 文档块的 id 用 path#anchor：标题不变则 id 不变，行号变动不影响引用
        return new Chunk(
                file.path() + "#" + anchor,
                file.path(),
                ChunkKind.DOC_SECTION,
                content,
                heading.lineIndex + 1,
                Math.max(heading.lineIndex + 1, endExclusive),
                metadata);
    }

    /** 首个标题之前的正文，锚点固定为 preamble。 */
    private Chunk preambleChunk(SourceFile file, String[] lines) {
        Map<String, Object> metadata = baseMetadata(file);
        metadata.put(MetadataKeys.ANCHOR, "preamble");
        metadata.put(MetadataKeys.HEADING_PATH, "");
        metadata.put("headingLevel", 0);
        metadata.put("synthesized", false);
        return new Chunk(
                file.path() + "#preamble",
                file.path(),
                ChunkKind.DOC_SECTION,
                joinLines(lines, 0, lines.length),
                1,
                Math.max(1, lines.length),
                metadata);
    }

    private Map<String, Object> baseMetadata(SourceFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "markdown");
        metadata.put(MetadataKeys.FILE_HASH, file.hash());
        metadata.put(MetadataKeys.LAST_MODIFIED, file.lastModifiedMillis());
        metadata.put("chunker", "markdown-heading");
        return metadata;
    }

    /** 扫描标题行，跳过围栏代码块内部的内容。 */
    private List<Heading> findHeadings(String[] lines) {
        List<Heading> headings = new ArrayList<>();
        Deque<Heading> stack = new ArrayDeque<>();
        boolean insideFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).matches()) {
                insideFence = !insideFence;
                continue;
            }
            if (insideFence) {
                continue;
            }
            Matcher matcher = HEADING.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int level = matcher.group(1).length();
            String text = matcher.group(2).trim();
            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop();
            }
            // stack 的迭代顺序是"最近的祖先在前"，构造路径时需要反过来（从最外层开始）
            List<Heading> ancestors = new ArrayList<>(stack);
            java.util.Collections.reverse(ancestors);
            Heading heading = new Heading(level, text, i, slugify(text), List.copyOf(ancestors));
            headings.add(heading);
            stack.push(heading);
        }
        return headings;
    }

    /** 生成与主流 Markdown 渲染器一致的锚点：小写、空格转连字符、去掉标点。 */
    static String slugify(String heading) {
        String value = heading.trim().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || isCjk(c)) {
                slug.append(c);
            } else if (c == ' ' && !slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        return slug.isEmpty() ? "section" : slug.toString();
    }

    private static String joinLines(String[] lines, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(lines.length, to);
        if (start >= end) {
            return "";
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
    }

    /**
     * 按行切分，并丢弃文件以换行结尾时产生的尾随空串。
     *
     * <p>不丢的话，最后一个章节的行号区间会多出末尾那一个空行，
     * 引用范围就不精确了。这里与代码切块保持一致的处理方式。
     */
    private static String[] splitLines(String content) {
        if (content.isEmpty()) {
            return new String[0];
        }
        String[] lines = content.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            return java.util.Arrays.copyOf(lines, lines.length - 1);
        }
        return lines;
    }

    private static boolean isNotBlank(String[] lines, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(lines.length, to); i++) {
            if (!lines[i].isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    /** 一个标题及其在层级树中的位置。 */
    private record Heading(int level, String text, int lineIndex, String anchor,
                           List<Heading> ancestors) {

        /** 形如 {@code 订单 API > 创建订单 > 请求字段}，用于给检索到的片段提供上下文。 */
        String path() {
            StringBuilder builder = new StringBuilder();
            for (Heading ancestor : ancestors) {
                builder.append(ancestor.text).append(" > ");
            }
            return builder.append(text).toString();
        }
    }
}
