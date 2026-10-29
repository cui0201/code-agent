package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.indexer.doc.MarkdownDocChunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileChunkerTest {

    private final ConfigFileChunker chunker = new ConfigFileChunker();

    private static SourceFile yaml(String... lines) {
        return new SourceFile("application.yml", String.join("\n", lines) + "\n", "hash-yaml");
    }

    private static final SourceFile APPLICATION = yaml(
            "server:",                                    // 1
            "  port: 8080",                               // 2
            "",                                           // 3
            "spring:",                                    // 4
            "  ai:",                                      // 5
            "    openai:",                                // 6
            "      base-url: https://api.deepseek.com",   // 7
            "",                                           // 8
            "code-agent:",                                // 9
            "  retrieval:",                               // 10
            "    top-k: 8",                               // 11
            "    # 低于该分数视为无依据",                    // 12
            "    min-score: 0.62",                        // 13
            "  guard:",                                   // 14
            "    citation-retry: 1");                     // 15

    @Test
    @DisplayName("按二级配置块切分，键路径写进元数据")
    void should_chunk_by_config_block() {
        List<Chunk> chunks = chunker.chunk(APPLICATION);

        assertThat(chunks).extracting(chunk -> chunk.metadata().get("configKey"))
                .contains("server", "spring.ai", "code-agent.retrieval", "code-agent.guard");
    }

    @Test
    @DisplayName("配置块里保留键值与其上方注释——注释往往解释了取值的理由")
    void should_keep_value_and_comment() {
        Chunk retrieval = chunker.chunk(APPLICATION).stream()
                .filter(chunk -> "code-agent.retrieval".equals(
                        chunk.metadata().get("configKey")))
                .findFirst()
                .orElseThrow();

        assertThat(retrieval.content()).contains("min-score: 0.62");
        assertThat(retrieval.content()).contains("# 低于该分数视为无依据");
        // 行号要覆盖整个块，引用才能精确落地
        assertThat(retrieval.startLine()).isEqualTo(10);
        assertThat(retrieval.endLine()).isEqualTo(13);
    }

    @Test
    @DisplayName("切块 id 用键路径而不是行号：键在文件里移动不改变 id")
    void should_use_stable_id() {
        Chunk chunk = chunker.chunk(APPLICATION).stream()
                .filter(c -> "code-agent.retrieval".equals(c.metadata().get("configKey")))
                .findFirst().orElseThrow();

        assertThat(chunk.id()).isEqualTo("application.yml#key:code-agent.retrieval");
    }

    @Test
    @DisplayName("properties 文件一行一个键")
    void should_chunk_properties() {
        SourceFile properties = new SourceFile("app.properties",
                "# 服务端口\nserver.port=8080\n\n# 超时时间\ntimeout.seconds=60\n", "hash-props");

        List<Chunk> chunks = chunker.chunk(properties);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("# 服务端口").contains("server.port=8080");
        assertThat(chunks.get(1).metadata()).containsEntry("configKey", "timeout.seconds");
    }

    @Test
    @DisplayName("没有键的文件不产出切块，交给上层降级")
    void should_return_empty_for_non_config_content() {
        SourceFile text = new SourceFile("notes.yml", "这不是配置，只是一段文字。", "hash");

        assertThat(chunker.chunk(text)).isEmpty();
    }

    @Test
    @DisplayName("CompositeChunker 会把 yml 路由到配置切块器")
    void should_route_yaml_via_composite() {
        CompositeChunker composite = new CompositeChunker(
                new LineWindowChunker(60, 10), new MarkdownDocChunker(),
                new LineWindowChunker(60, 10));

        List<Chunk> chunks = composite.chunk(APPLICATION);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).metadata()).containsEntry("language", "yaml");
    }
}
