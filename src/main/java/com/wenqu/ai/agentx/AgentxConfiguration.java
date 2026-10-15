package com.wenqu.ai.agentx;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.engine.ChatEngineFactory;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import org.springframework.context.annotation.Configuration;

/**
 * 把框架引擎注册进分派工厂。
 * <p>用构造期回调而不是让 {@code ChatEngineFactory} 直接依赖本包：工厂是分派层，认不得具体引擎，
 * 否则「谁可选」这件事就得反过来改上层。注册动作本身无副作用——{@code HarnessAgent} 要到某一轮
 * 真走框架引擎时才建，切回 legacy 后框架代码一行不跑。
 */
@Configuration
public class AgentxConfiguration {

    public AgentxConfiguration(ChatEngineFactory factory,
                               ModelRegistryService modelRegistry,
                               ConfigService configService,
                               AppProperties properties,
                               WenquAgentxTools tools,
                               FrameworkSessions sessions,
                               com.wenqu.ai.service.SessionService sessionService,
                               com.wenqu.ai.service.KeywordExtractor keywordExtractor,
                               com.wenqu.ai.service.ArtifactService artifactService,
                               com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                               com.wenqu.ai.agent.context.CitationIntegrityService citations,
                               com.wenqu.ai.agent.plan.PlanModeService plan,
                               com.wenqu.ai.service.QaLogService qaLog,
                               com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                               com.wenqu.ai.service.ChatService chatService) {
        AgentscopeChatEngine engine = new AgentscopeChatEngine(modelRegistry, configService, properties, tools,
                sessions, sessionService, keywordExtractor, artifactService, consultService, citations, plan,
                qaLog, toolRegistry, chatService::resolveTurnModel);
        factory.bindFramework(engine);
        // 重启后的待办由引擎接回：表里有归属、框架状态在 stateStore 里，续跑是做得到的事，
        // 不该回一句「已批准」然后什么都不发生
        // 重启后的待办、以及「答完时已经没有跟随者」的待办都由引擎接回：表里有归属、框架状态在
        // stateStore 里，续跑是做得到的事，不该回一句「已批准」然后什么都不发生
        sessions.bindResume(engine);
        // 咨询子轮要的是「再起一整轮」，走的是带轮级外壳的那个入口（即时落库、会话互斥、断线续跑），
        // 而不是框架引擎的内层循环。按函数注册避免 ChatService↔agentx 构造环
        tools.bindBranchTurn(chatService::chat);
    }
}
