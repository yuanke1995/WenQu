package com.wenqu.ai.agentx;

import io.agentscope.harness.agent.HarnessAgent;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.service.ArtifactService;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.List;

/**
 * 建实例的 builder 链（从 AgentscopeChatEngine 的 buildAgent 抽出）。
 *
 * <p>整条链的每一项都带着「为什么这么配」的账（任务清单不开框架 enableTaskList、压缩只按窗口线触发、
 * 工具闸顺序、子智能体/技能归框架等），因此连同注释一起搬过来，不做删减。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxAgentBuilder {

    private final AgentxModelConfig modelConfig;
    private final AgentxFrameworkInfra infra;
    private final WenquAgentxTools tools;
    private final AgentxAgentBinding agentBinding;
    private final AgentxTurnSupport turnSupport;
    private final WenquSkillRepository skillRepository;
    private final ArtifactService artifactService;
    private final AgentxPromptContext promptCtx;
    private final java.util.function.IntSupplier turnSeconds;
    private final java.util.function.IntSupplier maxIters;
    private final AgentxMiddlewareSet middlewareSet;
    private final AgentxModelWiring modelWiring;

    public HarnessAgent.Builder builder(AgentxBuildInput in) {
        var cmd = in.cmd();
        var policy = in.policy();
        var hooks = in.hooks();
        var binding = in.binding();
        var stateDir = in.stateDir();
        var toolkit = in.toolkit();
        var chatModel = in.chatModel();
        var auxModel = in.auxModel();
        var subagentTargets = in.subagentTargets();
        var stepBudget = in.stepBudget();
        var slot = in.slot();
        var frameworkFs = in.frameworkFs();
        var repeatGuard = in.repeatGuard();
        var toolGate = in.toolGate();
        var workspaceMemory = in.workspaceMemory();
        var userMemoryReadonly = in.userMemoryReadonly();
        var localEnv = in.localEnv();
        var teamGuidance = in.teamGuidance();
        HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
                .agentId(AgentscopeChatEngine.AGENT_ID)
                .name("wenqu")
                .description("问渠·AgentScope 运行时")
                // 角色提示词归智能体管：见 rolePrompt 的分层说明。
                // 派遣纪律必须跟着挂：清单本体由框架的 ## Subagents 段渲染，但「id 逐字取自清单、
                // 通用问题不要派」这两句只有问渠说得出（派错一位的成本是一整条子轮）
                // 用户口径那条（正文/计划/清单不写沙盒路径与工具名）也要跟着挂：框架这一路没人再说第二遍，
                // 而框架的 PLAN banner 与沙盒工具说明反而主动把路径喂给模型——不挂，
                // 计划卡就变成「它打算在沙盒里怎么写文件」的说明书
                .sysPrompt(promptCtx.sysPromptOf(cmd, binding.row(), workspaceMemory, userMemoryReadonly,
                        localEnv, subagentTargets.isEmpty() ? "" : tools.dispatchDiscipline(),
                        promptCtx.teamGuidance(cmd), turnSupport.mountedToolNames(cmd, policy, slot),
                        agentBinding.relatedSuggestionsEnabled(cmd.getUserId())));
        modelWiring.apply(agentBuilder, chatModel, auxModel, hooks, cmd, toolkit, binding, stateDir);
        agentBuilder
                // 会话状态恒存 Redis，没有本地文件兜底（容器重建即丢，会让多轮对话退化成每轮独立问答）。
                // Redis 连不上时应用起不来——宁可启动失败，也不要带着「每轮失忆」对外服务。
                .stateStore(infra.stateStore())
                // 框架默认装备子智能体/技能/工作区上下文/tools.json 等旁路能力。
                //
                // 子智能体由**框架驱使**：
                // 去掉 disableSubagents / disableDynamicSubagents 后框架装配 SubagentsMiddleware，
                // 注册 agent_spawn / agent_send / agent_list 三把工具，并每轮在 SYSTEM 消息前
                // 插入子智能体使用指引与异步任务摘要（框架自己从「冻结基线」重建 SYSTEM，
                // 内容不会跨轮累积——见 SubagentsMiddleware 类注释第 3 条）。
                // 问渠不再另挂一把咨询工具：每位「可咨询助手」登记成框架的子智能体
                // （见 {@link WenquAgentxTools#consultSubagentTargets}），派遣时机、并行与后台等待
                // 都归框架；子轮本体仍是问渠那条完整问答通路（临时会话、引用回填、产物收编），
                // 因为框架的子 agent 只回一段文本、没有问渠的问答外壳。
                //
                // 技能由**框架接管**：
                // WenquSkillRepository 实现 RuntimeContextSkillRepository，按当轮 uid 解析可见技能
                // （classpath 内置 + 沙盒工作区 + user-data/skills-users/{uid}/，同名用户版优先）。
                // disableDynamicSkills 去掉——那会拦掉框架的动态技能加载。
                // disableDefaultWorkspaceSkills 保留：工作区里的默认技能集与问渠的技能体系无关。
                .skillRepositories(List.of(skillRepository))
                // 智能体的「技能范围」真正生效的地方：三态 → SkillFilter。
                // 框架的 HarnessSkillMiddleware 按它裁目录（被滤掉的不进提示词、也不注册 load 工具），
                // 所以「限定用哪几个」是边界而不是建议。空白名单/关闭 = 一条都不给。
                // 注意：只能走 builder —— AgentSession 构造时把 RuntimeContext 复制了一份
                // （AgentSession:84），事后往原 ctx put SkillFilter 影响不到已建实例。
                .skillFilter(binding.skillFilter())
                // 助手侧「技能沉淀」仍交给框架 skill_manage：写沙盒工作区，随会话走。
                // 用户自己的技能安装/编辑/删除走 SkillsPage + SkillController（user-data/skills-users/{uid}，
                // 跨会话、不依赖沙箱）；两套并存，本开关只负责「模型自己起草/改写技能」这条路。
                // autoPromote=true：新技能直接落可加载目录，下一轮推理即可见。
                // 框架注释：默认会先进 drafts 目录「不自动加载」，那是给谨慎场景的默认——
                // 问渠的会话内技能本来就是当轮要用，加 drafts 只是多一步人工提升。
                .enableSkillManageTool(io.agentscope.harness.agent.tool.SkillManageConfig.builder()
                        .autoPromote(true)
                        .build())
                .disableDefaultWorkspaceSkills()
                // 记忆由框架接管：去掉 disableMemoryTools 后框架注册
                // memory_search / memory_get / memory_save / session_search 四把。
                //
                // 隔离性核对过：`WorkspaceManager#getMemoryDir(rc)` → `resolveRuntimeDataPath(rc, ...)`
                // 按 RuntimeContext 解析，本轮的 uid 不同则记忆目录不同 —— 满足问渠的 per-user 语义。
                //
                // 已接受的降级：框架 `MemorySearchTool` 是 **keyword 检索**
                // （源码注释：*"Uses keyword-based search through all memory files"*），不是向量检索；
                // 且用户没有「长期记忆」面板的手动管理入口。
                //
                // 不设置 disableWorkspaceContext：该开关会整条关掉 WorkspaceContextMiddleware，
                // 而它承担记忆「读回」的两件事——
                //   ① readMemoryMd(rc) 把 MEMORY.md 注入 HARNESS_CONTEXT（不开则模型看不到已沉淀的记忆）
                //   ② 注入 Memory Recall / Memory Persistence 使用指引（不开则模型不知道
                //      memory_search 四把工具何时用）
                // 副作用核对：该 middleware 注入的 ## Workspace 段落会描述沙箱路径，
                // 但问渠有 PlanCopy.guidance()（提示词「正文/计划/清单不写路径与工具名」）
                // + PlanCopy.stripInternalPaths（计划卡兜底剥路径）两层护栏，与它共存无冲突。


                .disableAtPathExpansion()
                // 跨能力按名禁用交给框架 ToolFilter：deny 名单从 ExecutionPolicy 来，build 期直接从
                // Toolkit 移除（schema/查找/执行一起消失）。三态与具体项仍由问渠业务适配层决定。
                .toolsConfig(tools.toolsConfig(cmd, policy, toolkit.getToolNames()))
                // 沙箱与文件系统：**框架接管**。
                // 关键：框架的工具在 **build 期**绑定文件系统——
                //   HarnessAgent:2895 `new FilesystemTool(filesystem, pathNormalizer)`
                //   HarnessAgent:2903 `new ShellExecuteTool(sandbox)`（filesystem 当沙箱用）
                // 两者都从 builder 字段取，**不看 RuntimeContext**。
                // 往 ctx 里 put AbstractFilesystem 无效，必须用 abstractFilesystem(...) 在 build 期传进去。
                .abstractFilesystem(frameworkFs)
                .artifactDeliveryTarget(new com.wenqu.ai.sandbox.WenquArtifactDeliveryTarget(artifactService))
                .disableWebTools();
        middlewareSet.apply(agentBuilder, cmd, policy, slot, stepBudget, repeatGuard, toolGate,
                !subagentTargets.isEmpty());
        return agentBuilder;
    }


}
