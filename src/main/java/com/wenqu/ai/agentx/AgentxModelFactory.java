package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.UsageLedgerService;
import com.wenqu.ai.service.UsageQuotaService;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;

/**
 * 建实例期的模型构建（从 AgentscopeChatEngine 的 buildAgent 抽出）：主链与辅助链两份模型、
 * 共用一条 HTTP 通道与窗口，用量各按 kind 分开记账。
 *
 * <p>模型实例建两份：主链那份带用户这一轮的思考档与温度；辅助那份只服务框架的记忆提炼与上下文压缩——
 * 它们要的是「把话折短」不是「想清楚」，跟着主链开思考就是拿最贵的 token 去写一段算完即扔的推理。
 * 两条请求参数不同只能是两个实例；transport 提出来复用，否则多建一个 OkHttp 客户端＝多一份连接池。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxModelFactory {

    private final AgentxModelConfig modelConfig;
    private final ModelRegistryService modelRegistry;
    private final UsageLedgerService usageLedger;
    private final UsageQuotaService quotaService;
    private final FrameworkSessions sessions;

    /** 主链与辅助链两份模型。 */
    public record Models(io.agentscope.core.model.Model chat, io.agentscope.core.model.Model aux) {
    }

    public Models build(ChatCommand cmd, ModelRegistryService.ModelRoute route, AgentBinding binding,
                        WenquAgentxTools.TurnHooks hooks,
                        io.agentscope.core.model.transport.OkHttpTransport transport) {
        String completionsPath = route.completionsPath() == null || route.completionsPath().isBlank()
                ? "/v1/chat/completions" : route.completionsPath();
        OpenAIChatModel.Builder modelBuilder = OpenAIChatModel.builder()
                .baseUrl(route.baseUrl())
                .apiKey(route.apiKey())
                .modelName(route.modelId())
                .stream(true)
                .httpTransport(transport)
                .generateOptions(modelConfig.generateOptions(cmd, hooks.modelRef(), binding.maxOutput()))
                .endpointPath(completionsPath);
        // 窗口拿得到登记值才交给模型：没拿到时一次都不调，让框架按模型名查它自己的内置表。
        // 显式传 0 会被框架当成「这条模型的窗口是 0」，输入上限掉到 64000 兜底值。
        int contextWindow = binding.contextWindow();
        if (contextWindow > 0) modelBuilder.contextWindowSize(contextWindow);
        io.agentscope.core.model.Model baseModel = modelBuilder.build();
        // 辅助调用那一份：同一个 builder 换参数再 build（build() 只读字段、不复用实例）
        io.agentscope.core.model.Model auxModel = modelBuilder
                .generateOptions(modelConfig.auxGenerateOptions(cmd, hooks.modelRef()))
                .build();
        io.agentscope.core.model.Model chatModel = new MeteredModel(baseModel, usageLedger, quotaService,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null,
                        cmd.branchTurn() ? "subagent" : "chat", cmd.getApiKeyId(), cmd.getShareId()), hooks.modelRef());
        // 主链那份包装器登记到会话表：它是本轮「断流」计数的唯一来源（实例跨轮复用，收尾读差值）
        sessions.noteChatModel(sessions.key(cmd), (MeteredModel) chatModel);
        return new Models(chatModel, auxModel);
    }
}
