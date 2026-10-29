package com.example.codeagent.indexer.scan;

import com.example.codeagent.core.exception.ConfigurationException;
import com.example.codeagent.core.model.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceScannerTest {

    @TempDir
    Path tempDir;

    private final SourceScanner scanner = new SourceScanner(
            List.of("**/*.java"),
            List.of("**/target/**", "**/build/**"));

    private void write(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("只扫描 include 命中且未被 exclude 的文件")
    void should_apply_include_and_exclude_rules() throws IOException {
        write("src/main/java/com/acme/A.java", "class A {}\n");
        write("src/main/resources/app.yml", "a: b\n");
        write("target/generated/B.java", "class B {}\n");
        write("build/generated/C.java", "class C {}\n");

        List<SourceFile> files = scanner.scan(tempDir);

        assertThat(files).extracting(SourceFile::path)
                .containsExactly("src/main/java/com/acme/A.java");
    }

    @Test
    @DisplayName("根目录下的文件也能被 **/*.java 命中")
    void should_match_file_at_root_directory() throws IOException {
        write("Root.java", "class Root {}\n");

        List<SourceFile> files = scanner.scan(tempDir);

        assertThat(files).extracting(SourceFile::path).containsExactly("Root.java");
    }

    @Test
    @DisplayName("读取时统一行尾为 LF，并基于规范化内容计算哈希")
    void should_normalize_line_endings() throws IOException {
        write("src/main/java/A.java", "class A {\r\n  int x = 1;\r\n}\r\n");

        SourceFile file = scanner.scan(tempDir).get(0);

        assertThat(file.content()).doesNotContain("\r");
        assertThat(file.content()).isEqualTo("class A {\n  int x = 1;\n}\n");
        assertThat(file.hash()).hasSize(64);
        assertThat(file.lineCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("根目录不存在时抛出配置异常")
    void should_reject_missing_root() {
        Path missing = tempDir.resolve("not-exists");

        assertThatThrownBy(() -> scanner.scan(missing))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("索引根目录不存在");
    }

    @Test
    @DisplayName("相同内容得到相同哈希，内容变化则哈希变化")
    void should_produce_stable_hash() throws IOException {
        write("src/main/java/A.java", "class A {}\n");
        write("src/main/java/B.java", "class A {}\n");
        write("src/main/java/C.java", "class C {}\n");

        List<SourceFile> files = scanner.scan(tempDir);
        SourceFile a = files.stream().filter(f -> f.path().endsWith("A.java")).findFirst().orElseThrow();
        SourceFile b = files.stream().filter(f -> f.path().endsWith("B.java")).findFirst().orElseThrow();
        SourceFile c = files.stream().filter(f -> f.path().endsWith("C.java")).findFirst().orElseThrow();

        assertThat(a.hash()).isEqualTo(b.hash());
        assertThat(a.hash()).isNotEqualTo(c.hash());
    }
}
