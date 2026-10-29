package com.example.codeagent.eval;

/**
 * 期望被检索到的证据。
 *
 * @param path      期望命中的文件路径（比较时使用后缀匹配，容忍不同项目根的写法）
 * @param startLine 期望的行号区间起点，可为 null
 * @param endLine   期望的行号区间终点，可为 null
 * @param anchor    期望命中的文档锚点，可为 null。文档证据必须能定位到具体章节，
 *                  只匹配"某个文件里的任意一段"会让评测过于宽松
 */
public record ExpectedEvidence(String path, Integer startLine, Integer endLine, String anchor) {

    public static ExpectedEvidence ofPath(String path) {
        return new ExpectedEvidence(path, null, null, null);
    }
}
