package com.example.codeagent.web.mcp;

import java.io.PrintStream;

/**
 * MCP stdio 模式的输出通道管理。
 *
 * <h2>为什么必须在 Spring 启动之前就改道</h2>
 *
 * stdio 模式下标准输出是 JSON-RPC 协议通道，任何非协议内容都会让客户端解析失败。
 * 而 Spring Boot 的启动横幅、Tomcat 初始化日志都在<b>容器启动阶段</b>就写出来了——
 * 那时 ApplicationRunner 还没执行。换句话说，把改道写进 Runner 已经太晚了：
 * 横幅早就混进协议通道了。
 *
 * <p>所以这个动作必须放在 {@code main()} 的第一时间，由这里统一管理：
 * 先抓住真正的 stdout，再把 {@code System.out} 指向 {@code System.err}，
 * 之后所有日志自然走 stderr。
 */
public final class McpStdioChannel {

    /** 真正的标准输出，只用于 MCP 协议。未启用 stdio 模式时为 null。 */
    private static volatile PrintStream protocolOut;

    private McpStdioChannel() {
    }

    /**
     * 准备 stdio 通道：保留原始 stdout，并把 System.out 改道到 stderr。
     *
     * <p>幂等：重复调用不会把已经改道过的流再次当作协议通道。
     */
    public static void prepare() {
        if (protocolOut != null) {
            return;
        }
        protocolOut = System.out;
        System.setOut(System.err);
    }

    /** 取得协议通道；未初始化时抛异常，避免把协议写进错误的流。 */
    public static PrintStream protocolOut() {
        PrintStream out = protocolOut;
        if (out == null) {
            throw new IllegalStateException(
                    "MCP stdio 通道尚未初始化，应在 main() 中先调用 McpStdioChannel.prepare()");
        }
        return out;
    }

    /** 是否已处于 stdio 模式。 */
    public static boolean isPrepared() {
        return protocolOut != null;
    }
}
