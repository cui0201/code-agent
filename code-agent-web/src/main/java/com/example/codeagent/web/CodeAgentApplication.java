package com.example.codeagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import com.example.codeagent.web.mcp.McpStdioChannel;

/**
 * 应用入口。
 *
 * <p>两种运行方式：
 * <ul>
 *   <li>不带参数启动 → 常驻服务，对外提供 REST 接口；</li>
 *   <li>带 {@code --index} / {@code --ask} → 执行一次任务后退出（由 CliRunner 处理）；</li>
 *   <li>带 {@code --mcp} → 以 stdio 方式运行 MCP Server（由 McpServerRunner 处理）。</li>
 * </ul>
 *
 * <p>命令行模式下刻意<b>不启动 Web 容器</b>：一是省掉约 4 秒的 Tomcat 启动时间
 * （对索引这种秒级任务影响很大），二是避免关闭时产生无意义的
 * {@code Socket accept failed} 错误日志。
 */
@SpringBootApplication
public class CodeAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CodeAgentApplication.class);
        if (isCommandLineMode(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        // 必须在 Spring 启动之前：启动横幅与容器日志都在这个阶段写出来，
        // 晚一步就会污染 MCP 的协议通道（详见 McpStdioChannel 的说明）
        if (hasArgument(args, "--mcp")) {
            McpStdioChannel.prepare();
        }
        application.run(args);
    }

    /** 判断是否为一次性命令模式。 */
    private static boolean isCommandLineMode(String[] args) {
        return hasArgument(args, "--index") || hasArgument(args, "--mcp") || hasPrefix(args, "--ask");
    }

    private static boolean hasArgument(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrefix(String[] args, String prefix) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
