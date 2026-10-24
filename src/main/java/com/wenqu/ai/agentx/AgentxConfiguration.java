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
                               com.wenqu.ai.service.ProjectFileService projectFileService,
                               com.wenqu.ai.skill.WenquSkillRepository skillRepository,
                               com.wenqu.ai.service.OrgService orgService,
                               com.wenqu.ai.service.UsageLedgerService usageLedger,
                               com.wenqu.ai.service.GoalService goalService,
                               com.wenqu.ai.service.ChatRunBus chatRunBus,
                               com.wenqu.ai.service.McpToolLabelService mcpToolLabelService,
                               JournalRotation journalRotation,
                               com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper,
                               com.wenqu.ai.mapper.AiDocumentMapper documentMapper) {
        AgentscopeChatEngine engine = new AgentscopeChatEngine(modelRegistry, configService, properties, tools,
                sessions, sessionService, attachments, userImages, keywordExtractor, artifactService,
                sandboxService, consultService, citations, redisProperties, qaLog, toolRegistry, projectService,
                projectFileService, skillRepository, orgService, usageLedger, goalService, chatRunBus, mcpToolLabelService,
                journalRotation, knowledgeMapper, documentMapper,
                chatService::resolveTurnModel);
        factory.bindFramework(engine);
        // 目标模式跨轮续跑的发起口：控制流归框架，问渠只数轮数——续跑轮由引擎 submit + 跟随
        // （幂等键 goal:{goalId}:{n}，后台轮走收集型通道 + RESUME_POOL，前端按 goal_turn 自动接流）。
        // 走 submitGoalTurn 而非 continueGoalTurn 直接绑：把实例冷启动装配（sessions.open 同步重建
        // AgentScope 实例，沙盒供给/MCP/索引可达数十秒）挪出 POST /goal 请求线程，建行即回包、界面立刻可见，
        // 后台轮就绪后登记总线、前端 2.5s 轮询接流
        goalService.bindTurnDriver(engine::submitGoalTurn);
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
        // 配置热监听同理要显式注册（@PostConstruct 在 new 出来的实例上不执行）：
        // memory.* 与目标确认等待时间变了要标脏重建常驻实例，否则「改了没生效」
        engine.registerMemoryConfigHotReload();
    }
}
