package com.example.codeagent.eval;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 YAML 读取评测集。
 *
 * <p>用手工映射而不是对象绑定库：结构只有三层，手工映射更透明，
 * 也避免为了一个评测文件引入额外依赖。
 */
public final class GoldenSetLoader {

    private GoldenSetLoader() {
    }

    public static GoldenSet load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, path.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测集失败: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static GoldenSet load(InputStream input, String name) {
        Map<String, Object> root = new Yaml().load(input);
        if (root == null) {
            return new GoldenSet(name, List.of());
        }
        String setName = root.get("name") == null ? name : String.valueOf(root.get("name"));
        List<Map<String, Object>> rawCases =
                (List<Map<String, Object>>) root.getOrDefault("cases", List.of());

        List<GoldenCase> cases = new ArrayList<>(rawCases.size());
        for (Map<String, Object> raw : rawCases) {
            cases.add(toCase(raw));
        }
        return new GoldenSet(setName, cases);
    }

    @SuppressWarnings("unchecked")
    private static GoldenCase toCase(Map<String, Object> raw) {
        Object id = raw.get("id");
        Object question = raw.get("question");
        if (id == null || question == null) {
            throw new IllegalArgumentException("评测用例缺少 id 或 question: " + raw);
        }

        List<ExpectedEvidence> evidence = new ArrayList<>();
        Object rawEvidence = raw.get("expected_evidence");
        if (rawEvidence instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> entry = (Map<String, Object>) item;
                evidence.add(new ExpectedEvidence(
                        String.valueOf(entry.get("path")),
                        toInteger(entry.get("start_line")),
                        toInteger(entry.get("end_line")),
                        entry.get("anchor") == null ? null : String.valueOf(entry.get("anchor"))));
            }
        }

        boolean expectRefusal = Boolean.TRUE.equals(raw.get("expect_refusal"));
        String note = raw.get("note") == null ? "" : String.valueOf(raw.get("note"));
        String category = raw.get("category") == null
                ? "未分类" : String.valueOf(raw.get("category"));
        return new GoldenCase(String.valueOf(id), category, String.valueOf(question),
                evidence, expectRefusal, note);
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }
}
