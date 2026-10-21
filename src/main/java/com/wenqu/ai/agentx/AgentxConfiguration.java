package com.wenqu.ai.agentx;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.engine.ChatEngineFactory;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 把框架引擎注册进分派工厂。
 * <p>用构造期回调而不是让 {@code ChatEngineFactory} 直接依赖本包：工厂是分派层，认不得具体引擎，
 * 否则「谁可选」这件事就得反过来改上层。注册动作本身无副作用——{@code HarnessAgent} 要到某一轮
 * 真走框架引擎时才建，注册动作本身不拉起任何框架运行时。
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
                               com.wenqu.ai.service.ChatAttachmentService attachments,
                               com.wenqu.ai.service.UserImageService userImages,
                               com.wenqu.ai.service.KeywordExtractor keywordExtractor,
                               com.wenqu.ai.service.ArtifactService artifactService,
                               com.wenqu.ai.service.SandboxService sandboxService,
                               com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                               com.wenqu.ai.agent.context.CitationIntegrityService citations,
                               RedisProperties redisProperties,
                               com.wenqu.ai.service.QaLogService qaLog,
                               com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                               com.wenqu.ai.service.ChatService chatService,
                               com.wenqu.ai.agent.chat.ChatRunRuntime runtime,
                               com.wenqu.ai.service.ProjectService projectService,
                               com.wenqu.ai.skill.WenquSkillRepository skillRepository,
                               com.wenqu.ai.service.OrgService orgService,
                               com.wenqu.ai.service.UsageLedgerService usageLedger) {
        AgentscopeChatEngine engine = new AgentscopeChatEngine(modelRegistry, configService, properties, tools,
                sessions, sessionService, attachments, userImages, keywordExtractor, artifactService,
                sandboxService, consultService, citations, redisProperties, qaLog, toolRegistry, projectService,
                skillRepository, orgService, usageLedger, chatService::resolveTurnModel);
        factory.bindFramework(engine);
        // 重启后的待办由引擎接回：表里有归属、框架状态在 stateStore 里，续跑是做得到的事，
        // 不该回一句「已批准」然后什么都不发生
        // 重启后的待办、以及「答完时已经没有跟随者」的待办都由引擎接回：表里有归属、框架状态在
        // stateStore 里，续跑是做得到的事，不该回一句「已批准」然后什么都不发生
        sessions.bindResume(engine);
        // 运行中插话走框架自己的会话 steer（送达点在下一次调模型之前，没收到的本轮不收尾）。
        // 按登记方向留口而不是让 agent.chat 反过来依赖本包：引擎已经用 ChatCommand/TurnRegistry 这套上层类型
        runtime.bindSteer(sessions::steer);
        // 咨询子轮要的是「再起一整轮」，走的是带轮级外壳的那个入口（即时落库、会话互斥、断线续跑），
        // 而不是框架引擎的内层循环。按函数注册避免 ChatService↔agentx 构造环
        tools.bindBranchTurn(chatService::chat);
        // 启动自检：框架会话状态恒存 Redis，连不上就不该对外服务（引擎是这里 new 的，
        // 不归 Spring 管，@PostConstruct 不会跑，自检得显式做）。
        engine.requireRedisForSessionState();
    }
}
