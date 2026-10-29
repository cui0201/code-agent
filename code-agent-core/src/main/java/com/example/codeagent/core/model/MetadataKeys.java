package com.example.codeagent.core.model;

/**
 * 切块元数据的公共键名。
 *
 * <p>元数据是跨模块传递信息的通道：索引阶段写入，检索与提示组装阶段读取。
 * 如果各处直接写字符串字面量，一旦拼错或改名就会静默失效
 * （比如检索端读 {@code stale} 而索引端写 {@code isStale}，不报错，只是永远为 null）。
 * 因此把跨模块使用的键集中在这里。
 */
public final class MetadataKeys {

    /** 文档是否已过期（Boolean）。 */
    public static final String STALE = "stale";

    /** 过期的原因，用于日志与答案提示（String）。 */
    public static final String STALE_REASON = "staleReason";

    /** 文档关联到的代码文件路径列表（List&lt;String&gt;）。 */
    public static final String LINKED_CODE_PATHS = "linkedCodePaths";

    /** 文档中引用的代码符号（List&lt;String&gt;）。 */
    public static final String CODE_REFS = "codeRefs";

    /** 文档章节锚点（String）。 */
    public static final String ANCHOR = "anchor";

    /** 文档章节的标题路径，形如 {@code 订单 API > 创建订单}（String）。 */
    public static final String HEADING_PATH = "headingPath";

    /** 文件内容哈希（String）。 */
    public static final String FILE_HASH = "fileHash";

    /** 文件最后修改时间（Long）。 */
    public static final String LAST_MODIFIED = "lastModifiedMillis";

    private MetadataKeys() {
    }
}
