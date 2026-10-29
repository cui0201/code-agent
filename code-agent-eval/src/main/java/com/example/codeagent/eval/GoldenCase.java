package com.example.codeagent.eval;

import java.util.List;

/**
 * 一条评测用例。
 *
 * @param id             用例编号，报告与回归对比都以它为准
 * @param category       用例类别（事实查询 / 实现解释 / 关系分析 / 文档 / 拒答 …）。
 *                       按类别分组看指标，才能发现"系统在哪类问题上弱"——
 *                       只有一个总分时，某类问题全错也可能被整体成绩掩盖
 * @param question       用户问题（中文）
 * @param expectedEvidence 期望命中的证据，命中任意一条即算召回成功
 * @param expectRefusal  是否期望拒答；为 true 时该用例不计入召回率，而计入拒答准确率
 * @param note           备注，例如这条用例在考察什么
 */
public record GoldenCase(
        String id,
        String category,
        String question,
        List<ExpectedEvidence> expectedEvidence,
        boolean expectRefusal,
        String note
) {

    public GoldenCase {
        category = category == null || category.isBlank() ? "未分类" : category;
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        note = note == null ? "" : note;
    }
}
