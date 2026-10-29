package com.example.codeagent.indexer.scan;

import com.example.codeagent.core.exception.ConfigurationException;
import com.example.codeagent.core.exception.IndexingException;
import com.example.codeagent.core.model.SourceFile;
import com.example.codeagent.indexer.util.Hashing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 源码扫描器：按 glob 规则遍历工程目录，读取文本文件并计算内容指纹。
 *
 * <p><b>关于 glob 匹配</b>：Java 的 {@code **} 不匹配位于路径开头的目录，
 * 因此模式 {@code **}{@code /target/**} 无法命中 {@code target/x.java}。
 * 这里通过"给相对路径补一个前导斜杠"来消除这个差异，使
 * {@code **}{@code /*.java} 既能命中根目录文件，也能命中深层文件。
 *
 * <p><b>关于文本规范化</b>：读取时统一把 CRLF 与 CR 转为 LF，
 * 哈希也基于规范化后的内容计算，保证"行号切分"与"内容指纹"使用同一份文本。
 */
public class SourceScanner {

    /** 单个文件的体积上限，超过则跳过，避免把大文件读进内存。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;
    private final List<String> skipped = new ArrayList<>();

    public SourceScanner(List<String> includeGlobs, List<String> excludeGlobs) {
        this.includes = compile(includeGlobs, List.of("**/*.java"));
        this.excludes = compile(excludeGlobs, List.of());
    }

    /**
     * 扫描目录。
     *
     * @param root 工程根目录
     * @return 命中 include 且未被 exclude 的文本文件
     * @throws ConfigurationException 根目录不存在或不是目录
     * @throws IndexingException      遍历过程发生 IO 错误
     */
    public List<SourceFile> scan(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new ConfigurationException("索引根目录不存在或不是目录: " + normalizedRoot);
        }
        skipped.clear();
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> normalizedRoot.relativize(path))
                    .filter(this::accept)
                    .map(path -> read(normalizedRoot, path))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (UncheckedIOException e) {
            throw new IndexingException("遍历目录失败: " + normalizedRoot, e.getCause());
        } catch (IOException e) {
            throw new IndexingException("遍历目录失败: " + normalizedRoot, e);
        }
    }

    /** 被跳过的文件（体积超限或读取失败），供索引报告使用。 */
    public List<String> skippedFiles() {
        return List.copyOf(skipped);
    }

    private boolean accept(Path relativePath) {
        // 传进来的 relativePath 形如 src/main/java/A.java。
        // 前面补一个斜杠的原因：Java 的 glob 里 ** 不匹配路径开头的目录，
        // 所以 **/target/** 这种模式匹配不到 target/x.java；
        // 补成 /target/x.java 之后，** 可以匹配到开头的空串，模式就能正常命中。
        String withLeadingSlash = "/" + relativePath.toString().replace('\\', '/');
        Path candidate = Path.of(withLeadingSlash);
        if (!matchesAny(includes, candidate)) {
            return false;
        }
        return !matchesAny(excludes, candidate);
    }

    private boolean matchesAny(List<PathMatcher> matchers, Path candidate) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private SourceFile read(Path root, Path relativePath) {
        Path absolute = root.resolve(relativePath);
        try {
            long size = Files.size(absolute);
            if (size > MAX_FILE_BYTES) {
                skipped.add(relativePath + " (超过 " + MAX_FILE_BYTES + " 字节)");
                return null;
            }
            String raw = Files.readString(absolute, StandardCharsets.UTF_8);
            String normalized = normalizeLineEndings(raw);
            String path = relativePath.toString().replace('\\', '/');
            long lastModified = Files.getLastModifiedTime(absolute).toMillis();
            return new SourceFile(path, normalized, Hashing.sha256(normalized), lastModified);
        } catch (IOException | RuntimeException e) {
            skipped.add(relativePath + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return null;
        }
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<PathMatcher> compile(List<String> globs, List<String> defaults) {
        List<String> effective = (globs == null || globs.isEmpty()) ? defaults : globs;
        List<PathMatcher> matchers = new ArrayList<>(effective.size());
        for (String glob : effective) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        }
        return List.copyOf(matchers);
    }
}
