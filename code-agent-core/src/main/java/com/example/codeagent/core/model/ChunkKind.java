package com.example.codeagent.core.model;

/**
 * 切块类型。
 *
 * <p>P0 阶段只产出 {@link #BLOCK}（定长行窗口）；P1 引入 AST 切块后会出现
 * {@link #CLASS} 与 {@link #METHOD}；P4 引入文档通道后出现 {@link #DOC_SECTION}。
 */
public enum ChunkKind {

    /** 整个文件（用于文件级摘要）。 */
    FILE,

    /** 类、接口或枚举。 */
    CLASS,

    /** 方法或构造函数。 */
    METHOD,

    /** 代码片段（定长窗口或方法内的语句段）。 */
    BLOCK,

    /** 文档章节（标题层级切块）。 */
    DOC_SECTION
}
