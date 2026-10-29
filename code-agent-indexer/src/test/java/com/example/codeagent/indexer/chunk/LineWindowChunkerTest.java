package com.example.codeagent.indexer.chunk;

import com.example.codeagent.core.model.Chunk;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineWindowChunkerTest {

    private static SourceFile fileWithLines(int lineCount) {
        String content = IntStream.rangeClosed(1, lineCount)
                .mapToObj(i -> "line" + i)
                .collect(Collectors.joining("\n")) + "\n";
        return new SourceFile("src/A.java", content, "hash-of-a");
    }

    @Test
    @DisplayName("无重叠时按窗口步进切分，行号连续且不越界")
    void should_chunk_without_overlap() {
        LineWindowChunker chunker = new LineWindowChunker(4, 0);

        List<Chunk> chunks = chunker.chunk(fileWithLines(10));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks.get(0).endLine()).isEqualTo(4);
        assertThat(chunks.get(0).content()).isEqualTo("line1\nline2\nline3\nline4");
        assertThat(chunks.get(1).startLine()).isEqualTo(5);
        assertThat(chunks.get(1).endLine()).isEqualTo(8);
        assertThat(chunks.get(2).startLine()).isEqualTo(9);
        assertThat(chunks.get(2).endLine()).isEqualTo(10);
    }

    @Test
    @DisplayName("重叠行数生效，相邻窗口共享指定行数")
    void should_apply_overlap() {
        LineWindowChunker chunker = new LineWindowChunker(4, 2);

        List<Chunk> chunks = chunker.chunk(fileWithLines(10));

        // 步长为 4-2=2，窗口依次为 1-4、3-6、5-8、7-10；
        // 最后一个窗口已覆盖到文件末尾，因此不再产生尾部小窗口
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks.get(0).endLine()).isEqualTo(4);
        assertThat(chunks.get(1).startLine()).isEqualTo(3);
        assertThat(chunks.get(1).endLine()).isEqualTo(6);
        assertThat(chunks.get(3).startLine()).isEqualTo(7);
        assertThat(chunks.get(3).endLine()).isEqualTo(10);
    }

    @Test
    @DisplayName("文件行数不足一个窗口时只产出一个切块")
    void should_produce_single_chunk_for_short_file() {
        LineWindowChunker chunker = new LineWindowChunker(60, 10);

        List<Chunk> chunks = chunker.chunk(fileWithLines(3));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks.get(0).endLine()).isEqualTo(3);
    }

    @Test
    @DisplayName("空文件不产出切块")
    void should_produce_nothing_for_empty_file() {
        LineWindowChunker chunker = new LineWindowChunker(60, 10);

        assertThat(chunker.chunk(new SourceFile("src/Empty.java", "", "hash"))).isEmpty();
    }

    @Test
    @DisplayName("全空行窗口被跳过")
    void should_skip_blank_windows() {
        LineWindowChunker chunker = new LineWindowChunker(3, 0);
        SourceFile file = new SourceFile("src/Blank.java", "\n\n\n\n\n\n", "hash");

        assertThat(chunker.chunk(file)).isEmpty();
    }

    @Test
    @DisplayName("元数据包含语言与文件哈希，便于增量索引")
    void should_carry_metadata() {
        LineWindowChunker chunker = new LineWindowChunker(60, 10);

        Chunk chunk = chunker.chunk(fileWithLines(3)).get(0);

        assertThat(chunk.metadata()).containsEntry("language", "java");
        assertThat(chunk.metadata()).containsEntry("fileHash", "hash-of-a");
        assertThat(chunk.kind().name()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("非法参数被拒绝，避免死循环")
    void should_reject_invalid_parameters() {
        assertThatThrownBy(() -> new LineWindowChunker(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineWindowChunker(5, -1))
                .isInstanceOf(IllegalArgumentException.class);
        // 重叠行数大于等于窗口行数会被收敛到 windowLines - 1，保证步长至少为 1
        assertThat(new LineWindowChunker(5, 10).chunk(fileWithLines(6))).isNotEmpty();
    }
}
