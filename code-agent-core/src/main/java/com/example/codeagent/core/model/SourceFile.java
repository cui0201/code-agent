package com.example.codeagent.core.model;

/**
 * 被索引的源文件。
 *
 * @param path               相对项目根的路径，统一使用 {@code /} 作为分隔符
 * @param content            文件全文（行尾已统一为 {@code \n}）
 * @param hash               内容哈希（sha256 十六进制），用于增量索引与陈旧检测
 * @param lastModifiedMillis 文件最后修改时间；0 表示未知。
 *                           文档漂移检测要用它比较"文档与它引用的代码谁更新"
 */
public record SourceFile(String path, String content, String hash, long lastModifiedMillis) {

    public SourceFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null");
        }
    }

    /** 不带修改时间的构造方式，便于测试与内部构造。 */
    public SourceFile(String path, String content, String hash) {
        this(path, content, hash, 0L);
    }

    /** 文件扩展名（小写，不含点）；没有扩展名时返回空串。 */
    public String extension() {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 行数（以 {@code \n} 计数，至少为 1）。 */
    public int lineCount() {
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
