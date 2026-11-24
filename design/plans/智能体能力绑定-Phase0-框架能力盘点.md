# 智能体能力绑定：Phase 0 框架能力盘点与替换清单

- 日期：2026-11-24
- 范围：只读盘点。判断 AgentScope `2.1.0-BETA1` 已提供什么、问渠哪些层在重复实现、哪些应直接接回框架。
- 方法：
  - 框架侧：`javap -public` 读取 `agentscope-core-2.1.0-BETA1.jar`、`agentscope-harness-2.1.0-BETA1.jar` 的公开 API；
  - 问渠侧：检查 `AgentscopeChatEngine` 的 builder 调用与 `WenquAgentxTools` / `ToolAssemblyService` / `ToolMountContext` / provider 体系；
  - 原则：**不自研框架已有的机制；问渠只保留领域适配与胶水。**
- 结论先行：框架已经有 `Toolkit / ToolsConfig / ToolGroup / PermissionEngine / RuntimeContextSkillRepository / SubagentDeclaration / MemoryConfig / SessionProjection / ContextSource / WorkspaceManager`。问渠真正需要自研的只有：多租户、知识库检索、市场、分享安全、个人资产归属；其余装配层应逐步“接回框架、删掉重复”。

---

## 1. 盘点总表

| 关注点 | 框架已有（可直接用） | 问渠现状 | 结论 |
|---|---|---|---|
| Agent 身份/人设 | `HarnessAgent.Builder.name/description/sysPrompt/model` | `AgentscopeChatEngine.buildAgent` 已用 | 保留，无需自研 |
| 工具容器 | `Toolkit`：注册、查找、执行、`ToolGroup`、`ToolGroupScope`、`copy()` | 自建 `Toolkit` 并注册 `frameworkTools` | 保留容器，但**删掉问渠自建的可见性翻译层** |
| 工具白名单/黑名单 | `ToolsConfig{strictAllow, defaultToolsEnabled, allow, deny, mcpServers}` + `ToolFilter.apply()` | `ToolAssemblyService.agentFlagFor` 的「族→列」映射、provider 内 `agentFlag`/`agentSelection`/`guest` 判断 | 改用 `ToolsConfig` 的 allow/deny 语义；问渠只保留「业务列 → 工具名集合」的薄映射 |
| 工具分组/动态激活 | `Toolkit.createToolGroup` / `ToolGroupManager.updateToolGroups` / `setActiveGroups` / `SkillToolGroup` | `WenquAgentxTools.registerWithGroups`、`AgentxToolSurfaceFilter`、`groupedNames` | 工具分组与激活可优先用框架的 ToolGroup；问渠只保留「本轮哪些组可见」的业务选择 |
| 权限/审批 | `PermissionContextState`、`PermissionEngine`、`PermissionRule(ALLOW/DENY/ASK/PASSTHROUGH)`、`PermissionMode` | `PermissionPolicyResolver`、`AgentxPermissions`、`AgentxFrameworkToolGate`、`SpringAiToolAdapter` 多处自定义 | **把业务档位翻译成 `PermissionContextState`**，执行判断交给 `PermissionEngine`；删除重复 gate |
| 技能目录/加载 | `RuntimeContextSkillRepository`（`getAllSkills(RuntimeContext)` / `getSkill(name, ctx)`）、`SkillFilter`、`SkillToolGroup`、`disableDynamicSkills/disableDefaultWorkspaceSkills` | `WenquSkillRepository` 已实现该接口；自建 `SkillRef`、`SkillStatsStore`、`SkillController` 多层 | 保留 `WenquSkillRepository` 作为 Provider；`SkillRef` 只做引用解析；统计/审计优先接 `SkillUsageStore/SkillAuditLog` |
| 技能治理 | `SkillCurator`、`SkillPromotionGate`、`SkillSecurityScanner`、`SkillUsageStore`、`SkillAuditLog` | 自建 `SkillStatsStore`、部分 `/skills` 管理端点 | 若只需统计/审计，接框架 `SkillUsageStore`；不复制完整技能市场治理 |
| MCP | `Toolkit.registerMcpClient(McpClientWrapper)`、`ToolsConfig.mcpServers`、`McpServerRegistrationListener` | `McpClientService` 自建连接池、工具名映射、重连、审计 | 协议/工具注册优先用框架 wrapper；问渠只保留「按 uid 分池、个人资产、游客白名单」 |
| 子智能体 | `SubagentDeclaration`（name/description/model/tools/skills/workspace/maxIters/...）、`subagentFactory`、`SubagentDeclaration.getSkills()/getTools()` | `subagentFactory` + `SubAgentConsultService` + `SubAgentBranchRuntime` | 声明与能力边界交给 `SubagentDeclaration`；问渠只保留「业务候选、租户权限、咨询回填」 |
| 团队协作 | `teamsMode(TeamClient, TeamContext)`、`TeamWakeups`、`TeamClient/LocalTeamClient` | 已对齐 `teamsMode`；仅 `team_task_depend` thin shim | 保持；不再造团队工具/任务表 |
| 记忆 | `MemoryConfig`（model/flushPrompt/consolidationPrompt/flushTrigger/...）、`MemoryFlushMiddleware`、`MemoryMaintenanceMiddleware` | `MemoryService`、`memoryMode` 三态、`markConfigStale` | 记忆域业务策略保留；flush/consolidation 实现优先接 `MemoryConfig` |
| 上下文注入 | `ContextSource`、`ContextPolicy`、`ContextSelectionPolicy`、`ContextSourceOptions`、`ContextManifest` | `TurnPromptAssembler` 手工拼附件/@引用/历史引用/变量 | 把「资料类上下文」迁成 `ContextSource`；提示词只留业务 section |
| 会话/状态 | `SessionLogStore`、`SessionProjection`、`AgentStateStore`、`sessionTranscript`、`inspectSession` | `FrameworkSessions` 常驻实例 + 自建 `AgentxTurnSlot` + 团队事件账本 | 实例缓存是必要胶水；会话状态/投影优先用框架 `SessionProjection` |
| 工作区/文件 | `WorkspaceManager`、`workspace(...)`、`abstractFilesystem(...)`、`filesystemRoute(...)` | `SandboxService` / `EffectiveWorkspace` / `sandboxAttached` | 保留问渠本机/SSH 执行器；文件系统接入用框架 `AbstractFilesystem` |
| 计划模式 | `enablePlanMode`、`planFileDirectory`、`allowShellInPlanMode` | `AgentscopeChatEngine` 已用 `enablePlanMode` | 保留 |
| 任务清单 | `enableTaskList(boolean)`、`TaskRepository` | 自建 `TodoService`/`TodoBridge` | 评估：若框架任务清单满足 UI 语义，删自建；否则保留业务投影 |
| 产物交付 | `artifactDeliveryTarget` | `ArtifactService` + 工具 | 交付目标接框架；产物存储/预览保留业务 |
| 工具结果治理 | `ToolResultEvictionConfig`、`CompactionConfig`、`disableToolResultEviction` | 自定义 `ToolSpillService`、压缩配置 | 保留 spill 业务；清退/压缩策略优先接框架配置 |

---

## 2. 问渠重复层清单（按可删除优先级）

### P0：最明显的重复

1. **`ToolAssemblyService.agentFlagFor` 的族→列映射**
   - 框架 `ToolsConfig.allow/deny` + `ToolFilter.apply()` 已能表达「允许/禁止工具名」。
   - 问渠应只保留「业务三态/白名单 → 工具名集合」的翻译，不再让 provider 各自读 Agent 列。
   - 删除目标：provider 内 `agentFlag()` / `agentSelection()` / `guest` 判断统一上移到装配点。

2. **`PermissionPolicyResolver` + `AgentxPermissions` + `AgentxFrameworkToolGate` 三套权限表达**
   - 框架已有 `PermissionContextState` + `PermissionRule(ALLOW/DENY/ASK)` + `PermissionEngine.checkPermission`。
   - 问渠应把 `research/standard/autonomous` 档位翻译成 `PermissionMode` + rules；执行判断交给框架，不再每层写 if。
   - 删除目标：`AgentxFrameworkToolGate` 只看「业务上是否该暴露」，不再做第二套权限决策。

3. **`WenquAgentxTools.registerWithGroups` + `AgentxToolSurfaceFilter` + `groupedNames`**
   - 框架 `Toolkit` 已有 `ToolGroup` / `ToolGroupManager` / `setActiveGroups` / `SkillToolGroup`。
   - 问渠只应决定「本轮激活哪些业务组」，分组机制交给框架。

### P1：可回收的重复

4. **`SkillStatsStore` / 自定义技能审计**
   - 框架 `SkillUsageStore`、`SkillAuditLog`、`SkillCurator` 已提供技能使用统计/审计/治理。
   - 问渠若只要「30 天读取、零调用、停用」，可接框架 store；保留业务展示层。
5. **`TurnPromptAssembler` 手工拼上下文**
   - 框架 `ContextSource` / `ContextPolicy` 已能注册资料源、按预算加载、输出 `ContextManifest`。
   - 可先把「附件正文 / @知识库 / #历史引用 / 调用变量」迁成 `ContextSource`；提示词只写业务 section。
6. **`TodoService` / 团队任务账本**
   - 框架 `enableTaskList` + `TaskRepository` 已有任务清单；团队任务事件是投影。
   - 先评估 UI/业务语义差异，再决定删哪一层；不要新造状态机。

### P2：保留但收薄

- `McpClientService`：保留按 uid 分池与个人资产；只把协议连接/工具注册接框架 `McpClientWrapper`。
- `SandboxService` / `EffectiveWorkspace`：保留本机、SSH、沙箱路由；文件系统接口接框架 `AbstractFilesystem`。
- `MarketService`：保留市场业务；安装 = 生成安装人自己的版本，不做「复制行 + 逐字段裁剪」。
- `AgentService` 版本快照：保留 `c_ai_agent_version`；`resolveForTurn` 是读取薄层，不新造 revision 模型。

---

## 3. 为什么有些不能直接复用（需要问渠胶水）

| 事项 | 不能直接复用的原因 | 问渠保留的最小职责 |
|---|---|---|
| 多租户/权限 | 框架不认识问渠的 tenant/department/role 数据模型 | 解析可见性、把结果翻译成框架 `PermissionContextState` |
| 知识库检索 | 框架没有问渠的向量+关键词+重排+库界+引用体系 | 保留 `KnowledgeRetrievalTool` / `HybridRetrievalService` |
| 个人 MCP 资产 | 框架按进程/全局配置，问渠按 `uid` 分池、个人凭据加密 | 保留 `McpClientService` 的归属与凭据层，协议接框架 |
| 市场/分享 | 业务资产、token、游客额度是问渠产品语义 | 保留 `MarketService` / `ShareService`；安全策略用统一 envelope |
| 本机/SSH 执行器 | 框架没有问渠的 executor 概念 | 保留 `SandboxService` / `EffectiveWorkspace` |
| 团队任务板 DAG | 框架 team 有基础任务，问渠有业务 DAG/投影 | 保留 thin `team_task_depend` 与事件投影 |

---

## 4. Phase 0 出口与下一步

### 出口（本阶段只读）

- [x] 框架 `HarnessAgent.Builder` 公开能力盘点完成（`javap` 2.1.0-BETA1）。
- [x] 问渠 builder 实际使用点盘点完成：`toolkit`、`skillRepositories`、`skillFilter`、`teamsMode`、`subagentFactory`、`memory`、`permissionContext`、`workspace`、`abstractFilesystem`、`middleware`、`compaction`、`enablePlanMode` 已接；`ToolsConfig` 被 `disableToolsConfig()` 主动关掉。
- [x] 重复层清单与「保留胶水」边界完成。
- [x] 基线：`mvn test` 477 tests 全绿（作为后续等价性回归）。

### 下一步（建议按此顺序动代码）

1. **Phase 1-A：工具可见性回接 `ToolsConfig`**
   - 保留 `ToolMountContext` 作为业务聚合，但输出改为「本轮可见工具名集合」；
   - 用 `ToolsConfig.allow/deny` + `ToolFilter.apply` 替代 provider 内重复三态 if；
   - 删除 `agentFlagFor` 的族→列翻译，只留「业务开关 → 工具名」一处。
2. **Phase 1-B：权限回接 `PermissionEngine`**
   - 把 `PermissionPolicyResolver` 的档位翻译成 `PermissionContextState`；
   - `AgentxFrameworkToolGate` 只保留「是否暴露」的业务判断，执行决策交框架。
3. **Phase 1-C：技能统计/上下文源**
   - 评估 `SkillUsageStore` 替换 `SkillStatsStore`；
   - 将附件/@知识库/历史引用迁 `ContextSource`，删 `TurnPromptAssembler` 中重复拼装。
4. **Phase 2：统一 Agent 解析入口**（上一轮 `resolveForTurn` 已具备雏形，接入主体后删除各 mount 的重复读取）。

> 再次强调：以上不是「再补几个 if」，而是**把问渠自建的装配/权限/上下文层替换成框架现成机制，并删除旧路径**。

---

## 5. 实施进度（2026-11-24 更新）

### 已完成：Phase 1-A 第一刀（工具按名禁用交给框架）

- 新增 `ToolConfigFactory`：把 `ExecutionPolicy.deny` 翻译成框架 `ToolsConfig`。
- `AgentscopeChatEngine.buildAgent` 去掉 `disableToolsConfig()`，改用 `toolsConfig(...)`；框架 `ToolFilter.apply` 在 build 期统一从 Toolkit 移除被禁工具。
- 删除 `McpToolProvider` / `HttpApiToolProvider` / `WorkflowToolProvider` 三处重复的 `removeIf(mc.deniesTool(...))`；按名禁用不再每个动态 provider 各写一遍。
- 新增 `ToolConfigFactoryTest`；全量 `mvn test`：479 tests / 0 failures / 0 errors / 2 skipped。
- 边界：本次只迁「按名禁用」；三态族开关与具体项白名单仍在业务适配层，下一刀再迁「允许集合」。

### 下一步：Phase 1-A 第二刀（三态族 → ToolsConfig allow）

- 已落：`ToolConfigFactory.fromPolicyAndAllow` + `WenquAgentxTools.toolsConfig(cmd, policy, registeredNames)`：
  业务判据由 `ToolRegistry.exposes` 单点计算允许集合，静态 provider 改为挂候选工具，由框架
  `ToolFilter.apply` 做最终裁剪；`FRAMEWORK_SURFACE_TOOLS`、`HarnessPlatformTools.NAMES`、
  `load_skill_through_path` 显式放行，避免误伤框架后挂工具。
- 出口：provider 内不再按 `exposes` 过滤；全量 `mvn test` 480 tests 全绿。
- 待办：把 `ToolAssemblyService.agentFlagFor` 的族→列映射迁成独立 `AgentToolSelection` 业务适配器，
  让提示词侧与工具表侧都只读同一份 selection（此项不改变行为，只是删除重复入口）。

### 已完成：Phase 1-A 收尾 + Phase 1-B 契约核对（2026-11-24）

- 新增 `ToolSelection` / `AgentToolSelectionAdapter`：`ToolAssemblyService` 不再认识 Agent 的族→列映射；
  `ToolMountContext.agentFlag/agentSelection` 统一从 `selection()` 读取。装配层、provider、提示词侧
  共享同一份业务选择。
- `AgentxPermissions` 已确认是 `ExecutionPolicy → PermissionContextState/PermissionMode/PermissionRule`
  的薄翻译，没有另造判定；新增 `AgentxPermissionsTest` 锁住三档映射与 deny 规则。
- `AgentxFrameworkToolGate` 保留为**业务补充 middleware**（完成检查轮、子轮禁写记忆、中文拒绝文案），
  它不是第二套通用权限引擎；通用只读/deny 仍由框架 `PermissionEngine` + 本适配器表达。
- 全量 `mvn test`：483 tests / 0 failures / 0 errors / 2 skipped。

### Phase 1-B 剩余（如需进一步收薄）

- 把 `AgentxFrameworkToolGate` 的通用 deny/readOnly 判断删到只剩业务补充（completionCheck/memory），
  需要先确认框架 `PermissionEngine` 的中间件执行顺序对框架后挂工具 100% 覆盖；在确认前保持双保险。
- 将 `ToolRegistry.requiresApproval` 的 ask 语义翻译成框架 `PermissionRule(ASK)`，让 `SpringAiToolAdapter`
  只做协议搬运，不再自己决定 `ask/deny`。

### Phase 1-C 结论（2026-11-24）

- `SkillUsageStore/SkillAuditLog`：按 workspace/文件系统，没有 uid/tenant 维度，不能替换问渠按 uid 聚合的管理统计；保留 `SkillStatsStore` 业务投影。
- `ContextSource`：`ContextRequest` 不带本轮附件/@引用正文，硬迁会引入跨轮单例/ThreadLocal 串轮风险；保留 `TurnPromptAssembler`，只借鉴其分层形状。
- 详见 [智能体能力绑定-Phase1C-框架复用评估.md](智能体能力绑定-Phase1C-框架复用评估.md)。
