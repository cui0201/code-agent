package com.example.codeagent.web.llm;

import com.example.codeagent.core.exception.ConfigurationException;
import com.example.codeagent.core.exception.LlmInvocationException;
import com.example.codeagent.core.port.AnswerGenerator;
import com.example.codeagent.web.config.AgentProperties;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 基于 Spring AI 的答案生成器。
 *
 * <p>走 OpenAI 兼容协议，因此只要改 {@code base-url} 与 {@code model}，
 * 就能在 DeepSeek、通义千问、智谱、Kimi、OpenAI 之间切换，
 * 不需要改一行代码。
 *
 * <p>这也是整个系统唯一的对外网络调用点，便于审计"什么内容被送了出去"。
 */
public class SpringAiAnswerGenerator implements AnswerGenerator {

    private final ChatClient chatClient;
    private final AgentProperties.Llm llm;

    public SpringAiAnswerGenerator(ChatClient.Builder chatClientBuilder, AgentProperties.Llm llm) {
        this.chatClient = chatClientBuilder.build();
        this.llm = llm;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        requireConfigured();
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new LlmInvocationException("大模型返回空内容");
            }
            return content;
        } catch (ConfigurationException | LlmInvocationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmInvocationException("调用大模型失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String modelName() {
        return llm.getModel();
    }

    /**
     * 流式生成。
     *
     * <p>用 {@code toIterable()} 把响应式流转成阻塞迭代：本项目的调用线程
     * 是 SSE 场景下专门开的工作线程，阻塞在这里不会影响其他请求，
     * 却能避免把 Reactor 类型泄漏到 core 的端口定义里。
     */
    @Override
    public void generateStreaming(String systemPrompt, String userPrompt,
                                  java.util.function.Consumer<String> onDelta) {
        requireConfigured();
        try {
            for (String delta : chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .toIterable()) {
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } catch (ConfigurationException | LlmInvocationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmInvocationException("流式调用大模型失败: " + e.getMessage(), e);
        }
    }

    private void requireConfigured() {
        if (!llm.isConfigured()) {
            throw new ConfigurationException(
                    "未配置大模型 API Key。请设置环境变量 LLM_API_KEY，"
                            + "并确认 code-agent.llm.base-url（当前为 " + llm.getBaseUrl() + "）与 "
                            + "code-agent.llm.model（当前为 " + llm.getModel() + "）指向可用的服务。");
        }
    }
}
