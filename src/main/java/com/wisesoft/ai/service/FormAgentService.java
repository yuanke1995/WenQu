package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 智能表单 Agent 服务：面向 dynamic-form 表单操作对话的轻量流式管道。
 *
 * <p>与知识库问答（RagService）解耦——本服务不检索知识库、不注入引用，
 * 只做：表单助手角色(System) + 工具(查表/查结构/校验草稿) + 流式输出。
 * P0-b 采用无状态单轮：不入会话库，SSE 事件格式与 /api/ai/chat 对齐
 * （token 增量 / done 收尾 / error 报错），便于前端复用现有渲染。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class FormAgentService {

    private final ChatClient chatClient;
    private final AiAppProperties properties;
    private final FormAgentTools formAgentTools;

    public FormAgentService(ChatClient chatClient,
                            AiAppProperties properties,
                            FormAgentTools formAgentTools) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.formAgentTools = formAgentTools;
    }

    public boolean isEnabled() {
        AiAppProperties.FormAgent fa = properties.getFormAgent();
        return fa != null && fa.isEnabled();
    }

    /** SSE 流式执行一次表单助手问答 */
    public void chat(String question, SseEmitter emitter) {
        if (!isEnabled()) {
            sendEvent(emitter, "error", "智能表单助手未启用（ai-app.form-agent.enabled=false）");
            complete(emitter);
            return;
        }
        AiAppProperties.FormAgent fa = properties.getFormAgent();

        // System：DB 可编辑角色段（chat.systemPrompt）为空时用 formAgent.systemPrompt 兜底
        // 说明：表单助手不走知识库，若用户配了知识库角色段反而不合适，故只取 formAgent 自己的 prompt
        String system = fa.getSystemPrompt() == null || fa.getSystemPrompt().isBlank()
                ? "你是\"智能表单助手\"，协助完成动态表单的设计、填报与审核。可通过工具查看表单清单与结构。回答准确简洁，不编造不存在的表单或字段。"
                : fa.getSystemPrompt();

        try {
            chatClient.prompt()
                    .system(system)
                    .user(question)
                    .options(OpenAiChatOptions.builder()
                            .model(chatModelName())
                            .temperature(0.2d)
                            .build())
                    .defaultTools(formAgentTools)
                    .stream()
                    .content()
                    .doOnNext(token -> sendEvent(emitter, "token", token))
                    .doOnComplete(() -> {
                        sendEvent(emitter, "done", "回答完成");
                        complete(emitter);
                    })
                    .doOnError(err -> {
                        log.error("[FormAgent] 流式回答失败: {}", err.getMessage());
                        sendEvent(emitter, "error", "回答失败：" + err.getMessage());
                        complete(emitter);
                    })
                    .subscribe();
        } catch (Exception e) {
            log.error("[FormAgent] 启动流式失败: {}", e.getMessage(), e);
            sendEvent(emitter, "error", "启动失败：" + e.getMessage());
            complete(emitter);
        }
    }

    /** 当前对话模型名：从配置读取（复用现有模型配置界面入口，无则用 ChatClient 默认） */
    private String chatModelName() {
        return properties.getFormAgent().getModel() == null || properties.getFormAgent().getModel().isBlank()
                ? null : properties.getFormAgent().getModel();
    }

    private void sendEvent(SseEmitter emitter, String type, String content) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" + jsonStr(content) + "}"));
        } catch (IOException e) {
            log.warn("[FormAgent] SSE 客户端断开 (type={}): {}", type, e.getMessage());
        }
    }

    private String jsonStr(String s) {
        return com.alibaba.fastjson2.JSON.toJSONString(s == null ? "" : s);
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }
}
