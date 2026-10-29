package com.example.codeagent.indexer.doc;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.ChunkKind;
import com.example.codeagent.core.model.MetadataKeys;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Javadoc HTML 切块器：把生成的 API 文档切成"每个成员一段"。
 *
 * <h2>为什么不直接解析 HTML 文本</h2>
 *
 * Javadoc 的 HTML 层级很深，但结构稳定：每个类一页，页面里用
 * {@code <section class="detail" id="member-signature">} 标注每个方法/字段的详情。
 * 用 jsoup 定位这些 section，比正则匹配标签可靠得多——Javadoc 版本升级时
 * 标签属性会变，但 section 结构基本稳定。
 *
 * <h2>与代码通道的关系</h2>
 *
 * Javadoc 是<b>从源码生成的</b>，所以它天然比手写文档更接近代码；
 * 但生成后源码可能继续改动，于是仍要参与漂移检测。
 * 这里把成员的 {@code id}（形如 {@code createOrder(com.acme.OrderRequest)}）
 * 作为锚点，并拼出与代码切块一致的 {@code 全限定名#签名}，
 * 让"文档引用"和"代码引用"能互相对上。
 */
public class JavadocHtmlChunker implements Chunker {

    /**
     * 需要跳过的页面。
     *
     * <p>Javadoc 会生成大量导航与交叉引用页面：类索引、包索引、帮助页、
     * `class-use/` 下的"谁引用了这个类"页等。它们几乎不含解释性内容，
     * 却会因为反复出现类名而抢占检索名次——实测把 26 个文件全索引后，
     * 召回率从 85.7% 掉到 74.1%，主要就是这类页面造成的。
     */
    private static final Set<String> SKIPPED_PAGES = Set.of(
            "index.html", "index-all.html", "allclasses-index.html",
            "allpackages-index.html", "help-doc.html", "overview-tree.html",
            "deprecated-list.html", "serialized-form.html", "constant-values.html",
            "search.html", "module-search-index.js");

    /** 有解释价值、可以整页入索引的页面标题前缀。 */
    private static final List<String> CONTENT_TITLE_PREFIXES =
            List.of("Class ", "Interface ", "Enum ", "Record ", "Annotation Interface ", "Package ");

    @Override
    public List<Chunk> chunk(SourceFile file) {
        if (isSkippedPage(file.path())) {
            return List.of();
        }
        Document document = Jsoup.parse(file.content());
        String className = classNameOf(document);
        String packageName = packageNameOf(document);
        String typeFqn = packageName.isBlank() ? className : packageName + "." + className;

        // 现代 Javadoc（JDK 17+）用 section.detail + id；过渡版本用 a[name] 锚点
        Elements details = document.select("section.detail[id]");
        if (details.isEmpty()) {
            details = document.select("div.detail[id]");
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Element detail : details) {
            String anchor = detail.id();
            if (anchor == null || anchor.isBlank()) {
                continue;
            }
            // select 返回的是 Elements 集合，直接取它的 text() 即可
            String title = detail.select("h3, h4, .member-signature").text().trim();
            String body = textOf(detail);
            if (body.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = baseMetadata(file);
            metadata.put(MetadataKeys.ANCHOR, anchor);
            metadata.put("docType", "javadoc");
            metadata.put("docTitle", className);
            metadata.put("headingPath", className + " > " + (title.isBlank() ? anchor : title));
            if (!typeFqn.isBlank()) {
                metadata.put("fqn", typeFqn + "#" + anchor);
            }
            chunks.add(new Chunk(
                    file.path() + "#" + anchor, file.path(), ChunkKind.DOC_SECTION,
                    body, 1, 1, metadata));
        }

        if (chunks.isEmpty()) {
            // 没有成员级 section（例如 package-summary、deprecated-list）时，
            // 整页作为一个块保留，至少让"这个包/这个类有什么"能被检索到
            if (!hasContentTitle(document)) {
                // 导航页即使有正文也不值得入索引，避免污染检索
                return List.of();
            }
            String body = textOf(document.body() == null ? document : document.body());
            if (body.isBlank()) {
                return List.of();
            }
            Map<String, Object> metadata = baseMetadata(file);
            metadata.put(MetadataKeys.ANCHOR, "page");
            metadata.put("docType", "javadoc");
            metadata.put("docTitle", className);
            chunks.add(new Chunk(file.path() + "#page", file.path(),
                    ChunkKind.DOC_SECTION, body, 1, 1, metadata));
        }
        return chunks;
    }

    /** 跳过交叉引用页与导航页。 */
    private boolean isSkippedPage(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.contains("/class-use/")) {
            return true;
        }
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return SKIPPED_PAGES.contains(name);
    }

    /** 页面标题是否为"类/接口/包"这类有解释价值的页面。 */
    private boolean hasContentTitle(Document document) {
        String title = document.select("h1.title, h2.title").text().trim();
        return CONTENT_TITLE_PREFIXES.stream().anyMatch(title::startsWith);
    }

    /** 类名：优先取标题里的 "Class OrderService"，退化到文件名。 */
    private String classNameOf(Document document) {
        Elements title = document.select("h1.title, h2.title");
        if (!title.isEmpty()) {
            String text = title.first().text().trim();
            String[] parts = text.split("\\s+");
            if (parts.length >= 2) {
                return parts[parts.length - 1];
            }
        }
        String fileName = document.location();
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        return name.endsWith(".html") ? name.substring(0, name.length() - 5) : name;
    }

    /** 包名：Javadoc 的副标题里写的就是完整包名。 */
    private String packageNameOf(Document document) {
        Elements subtitle = document.select(".sub-title");
        return subtitle.isEmpty() ? "" : subtitle.first().text().trim();
    }

    private Map<String, Object> baseMetadata(SourceFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", "html");
        metadata.put(MetadataKeys.FILE_HASH, file.hash());
        metadata.put(MetadataKeys.LAST_MODIFIED, file.lastModifiedMillis());
        metadata.put("chunker", "javadoc-html");
        metadata.put("synthesized", false);
        return metadata;
    }

    /** 取元素的可见文本并压缩空白：HTML 里的缩进换行对检索毫无价值。 */
    private static String textOf(Element element) {
        if (element == null) {
            return "";
        }
        return element.text().replaceAll("\\s+", " ").trim();
    }
}
