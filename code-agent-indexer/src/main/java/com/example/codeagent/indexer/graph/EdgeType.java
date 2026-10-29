package com.example.codeagent.indexer.graph;

/**
 * 符号图中的关系类型。
 *
 * <p>为什么要把关系分类型而不是都叫"依赖"：
 * 不同类型回答的问题完全不同，混在一起就没法给出精确答案。
 *
 * <table border="1">
 *   <caption>关系类型与用途</caption>
 *   <tr><th>类型</th><th>含义</th><th>能回答的问题</th></tr>
 *   <tr><td>EXTENDS</td><td>继承父类</td><td>改动父类会影响哪些子类</td></tr>
 *   <tr><td>IMPLEMENTS</td><td>实现接口</td><td>改了接口，哪些实现类要跟着改</td></tr>
 *   <tr><td>CALLS</td><td>方法调用</td><td>改了某个方法，哪些调用点受影响</td></tr>
 *   <tr><td>DECLARES</td><td>类型声明成员</td><td>这个方法属于哪个类</td></tr>
 * </dl>
 */
public enum EdgeType {

    /** 子类到父类。 */
    EXTENDS,

    /** 实现类到接口。 */
    IMPLEMENTS,

    /** 调用方到被调用方。 */
    CALLS,

    /** 类型到它声明的成员（方法/构造函数）。 */
    DECLARES
}
