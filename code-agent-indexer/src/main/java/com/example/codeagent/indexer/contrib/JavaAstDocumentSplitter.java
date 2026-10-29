package com.example.codeagent.indexer.contrib;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.indexer.chunk.JavaAstChunker;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Java 源码按 AST 结构切成 {@link TextSegment}，可直接用于 LangChain4j 的索引流水线。
 *
 * <h2>为什么这是一个"可上游贡献"的组件</h2>
 *
 * LangChain4j 自带的分割器（按字符、按段落、按句子）都是<b>面向自然语言</b>的，
 * 用在源码上会把方法从中间切断——而代码的语义单元是类与方法，
 * 切断了既影响检索质量（"这一块讲什么"的信号丢了），也让引用无法精确落到行号。
 *
 * <p>上游缺的正是"按语言结构切分"这一类实现。这个类只依赖 LangChain4j 自己的
 * {@code DocumentSplitter} 接口与本项目的一个纯 Java 工具类，改动面很小，
 * 符合上游对贡献组件的期望：单一职责、无额外依赖、行为可测。
 *
 * <h2>与项目内部实现的关系</h2>
 *
 * 内部使用 {@link Chunk}（带丰富的元数据），这里把它映射成上游的
 * {@link TextSegment}：内容原样保留，行号等定位信息写进元数据。
 * 映射关系是<b>单向且无损</b>的——行号与符号名都能在段落的元数据里取回，
 * 因此引用依然可以精确落地。
 */
public class JavaAstDocumentSplitter implements DocumentSplitter {

    /** 元数据键：文件路径。与上游 {@link Document#FILE_NAME} 保持一致。 */
    public static final String KEY_FILE_NAME = Document.FILE_NAME;

    /** 元数据键：切块类型（CLASS / METHOD / BLOCK）。 */
    public static final String KEY_CHUNK_KIND = "chunk_kind";

    /** 元数据键：稳定 id（全限定名 + 方法签名，不含行号）。 */
    public static final String KEY_CHUNK_ID = "chunk_id";

    /** 元数据键：全限定名（方法块为 类型#方法(参数)）。 */
    public static final String KEY_FQN = "fqn";

    /** 元数据键：起始行（1 起，闭区间）。 */
    public static final String KEY_START_LINE = "start_line";

    /** 元数据键：结束行（1 起，闭区间）。 */
    public static final String KEY_END_LINE = "end_line";

    private final JavaAstChunker chunker;

    public JavaAstDocumentSplitter() {
        this(JavaAstChunker.withLineWindowFallback(60, 10));
    }

    /**
     * @param chunker 允许注入自定义切块器，便于上层统一配置降级策略
     */
    public JavaAstDocumentSplitter(JavaAstChunker chunker) {
        this.chunker = chunker;
    }

    @Override
    public List<TextSegment> split(Document document) {
        if (document == null || document.text() == null || document.text().isBlank()) {
            return List.of();
        }
        String path = document.metadata() == null
                ? "unknown.java"
                : String.valueOf(document.metadata().getString(KEY_FILE_NAME));

        // SourceFile 需要的第三个参数是内容哈希；这里文档已经在内存里，
        // 用空哈希表示"未知"——它只用于增量索引，不影响切块结果
        SourceFile file = new SourceFile(path, document.text(), "");

        List<Chunk> chunks = chunker.chunk(file);
        List<TextSegment> segments = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            segments.add(toSegment(chunk));
        }
        return segments;
    }

    /** 把内部切块映射成上游段落，保留全部定位信息。 */
    private static TextSegment toSegment(Chunk chunk) {
        Metadata metadata = new Metadata();
        metadata.put(KEY_FILE_NAME, chunk.path());
        metadata.put(KEY_CHUNK_ID, chunk.id());
        metadata.put(KEY_CHUNK_KIND, chunk.kind().name());
        metadata.put(KEY_START_LINE, chunk.startLine());
        metadata.put(KEY_END_LINE, chunk.endLine());
        Object fqn = chunk.metadata().get("fqn");
        if (fqn != null) {
            metadata.put(KEY_FQN, String.valueOf(fqn));
        }
        Object annotations = chunk.metadata().get("annotations");
        if (annotations instanceof List<?> list && !list.isEmpty()) {
            // 注解对"哪些接口暴露给外部"这类问题很有价值，扁平化成字符串保留下来
            metadata.put("annotations", list.stream().map(String::valueOf)
                    .reduce((a, b) -> a + "," + b).orElse(""));
        }
        return TextSegment.from(chunk.content(), metadata);
    }
}
