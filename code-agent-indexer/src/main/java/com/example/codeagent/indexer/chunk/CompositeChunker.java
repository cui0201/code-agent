package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.core.port.Chunker;
import com.example.codeagent.indexer.doc.JavadocHtmlChunker;
import com.example.codeagent.indexer.doc.OpenApiChunker;

import java.util.List;
import java.util.Locale;

/**
 * 按文件类型分发的切块器。
 *
 * <p>代码与文档的切块逻辑没有共性：代码要按 AST 切类和方法，文档要按标题切章节。
 * 与其在一个类里写 if-else，不如让两个切块器各自专注，
 * 由这一层按扩展名分发——新增文件类型时只改这里。
 */
public class CompositeChunker implements Chunker {

    private final Chunker codeChunker;
    private final Chunker docChunker;
    private final Chunker configChunker;
    private final Chunker javadocChunker;
    private final Chunker openApiChunker;
    private final Chunker fallbackChunker;

    public CompositeChunker(Chunker codeChunker, Chunker docChunker, Chunker fallbackChunker) {
        this(codeChunker, docChunker, new ConfigFileChunker(),
                new JavadocHtmlChunker(), new OpenApiChunker(), fallbackChunker);
    }

    public CompositeChunker(Chunker codeChunker, Chunker docChunker,
                            Chunker configChunker, Chunker fallbackChunker) {
        this(codeChunker, docChunker, configChunker,
                new JavadocHtmlChunker(), new OpenApiChunker(), fallbackChunker);
    }

    public CompositeChunker(Chunker codeChunker, Chunker docChunker, Chunker configChunker,
                            Chunker javadocChunker, Chunker openApiChunker,
                            Chunker fallbackChunker) {
        this.codeChunker = codeChunker;
        this.docChunker = docChunker;
        this.configChunker = configChunker;
        this.javadocChunker = javadocChunker;
        this.openApiChunker = openApiChunker;
        this.fallbackChunker = fallbackChunker;
    }

    @Override
    public List<Chunk> chunk(SourceFile file) {
        String extension = file.extension().toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "java" -> codeChunker.chunk(file);
            case "md", "markdown" -> docChunker.chunk(file);
            // 配置文件按"键块"切，而不是按行窗口——否则一个配置值会和几十个无关键混在一起
            case "yml", "yaml", "properties" -> configChunker.chunk(file);
            // 生成文档：Javadoc 按成员切、OpenAPI 按接口切
            case "html" -> javadocChunker.chunk(file);
            case "json" -> openApiChunker.chunk(file);
            default -> fallbackChunker.chunk(file);
        };
    }
}
