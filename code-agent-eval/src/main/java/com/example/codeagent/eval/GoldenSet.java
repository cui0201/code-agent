package com.example.codeagent.eval;

import java.util.List;

/**
 * 一个评测集。
 *
 * @param name   评测集名称，例如 demo-project
 * @param cases  用例列表
 */
public record GoldenSet(String name, List<GoldenCase> cases) {

    public GoldenSet {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public List<GoldenCase> evidenceCases() {
        return cases.stream().filter(item -> !item.expectRefusal()).toList();
    }

    public List<GoldenCase> refusalCases() {
        return cases.stream().filter(GoldenCase::expectRefusal).toList();
    }
}
