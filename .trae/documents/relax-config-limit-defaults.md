# 系统设置限制性参数默认放开方案

## Context

用户反馈:系统设置参数太多(260 项),不希望在运行时做各种限制(长任务被叫停、请求被限流、内容被拦截等)。
已确认的改法:**参数条目全部保留、校验代码分支保留,只把"限制类"参数的默认值改为不限制/最宽松**,让系统在默认配置下不做拦截;管理员仍可在设置页重新收紧。

参数体系三层默认值(已核实分工,需同步):
1. `src/main/resources/config-schema.json` 的 `def` — 前端渲染与"恢复默认"用
2. `ConfigService.defaults()`(约 230 键)— 运行时权威
3. 各消费类内联 fallback(如 `GoalSettings.DEFAULT_MAX_ROUNDS`)— 防御层

取值优先级:OVERRIDE > USER_OVERRIDE > DB 缓存 > defaults()。DB 已存旧值的行不会因改默认而生效 → 需要一次性启动迁移。

## 参数审计结论(260 字段逐 panel 过完)

### A 类:后端已有"0/关=不限"分支,只改默认值(约 27 键)

- 总开关 def true→false:`ratelimit.enabled`(RateLimitService L68 已支持)、`contentSafety.enabled`(ContentSafetyService L91)
- 目标模式四项硬预算 def→0:`chat.goal.maxRounds/maxTokens/maxMs/maxToolCalls`(GoalSettings L43-95 已有 `v<=0 → UNLIMITED` 哨兵)
- 配额类 def→0:`agent.maxToolSteps`、`webSearch.maxCallsPerTurn`、`scheduled.maxPerUser`、`sandbox.maxPerUser`、`tool.spillMaxMbPerUser`、`context.maxBlocksPerDoc/maxBlocksPerKb`
- 超时类 def→0:`chat.sseTimeoutMs`、`chat.modelStallTimeoutMs`、`chat.agentscopeMaxIters`、`workflow.runTimeoutSeconds`
- 游客分享额度(仅 defaults(),不在设置页):`share.guestDailyTurns`、`share.guestDailyTurnsPerVisitor`、`share.guestDailyTokens` → 0

### B 类:需最小后端改动或取 schema 上限

- B1 加"0=跳过"分支(2 键):`chat.goal.maxCompletionChecks`、`chat.goal.maxVerifyFailures` — GoalSettings 加 `v<=0 → UNLIMITED`,schema `min:1→0`;GoalService 消费点(L805/L848)判断自然永不命中,不改 GoalService
- B2 def 改到 schema max 即最宽松(约 22 键,零后端改动):`chat.maxImagesPerMessage`、`chat.maxImageMb`、`chat.maxQuestionChars`、`upload.maxFileSize`(200→256,受 spring multipart 256MB 物理顶)、`sandbox.projectUploadMaxMb`、`chat.approvalTimeoutMs`、`chat.askTimeoutMs`、`chat.planTimeoutMs`、`scheduled.timeoutMs`、`sandbox.commandTimeoutSeconds`、`parse.queue.capacity`、`parse.taskTimeoutMs`、`parse.retryMaxAttempts`、`webSearch.maxResults`、`webSearch.timeoutMs`、`httpTool.timeoutMs`、`workflow.subagentTimeoutMs`、`workflow.maxSteps`、`workflow.runQueueCapacity`、`agentEval.maxCases`、`agentEval.thresholdRate`、`chat.asyncWorkspaceMax`、`graphrag.maxTriplesPerChunk`
- B3 **保留不动**(有明确理由,防误改):`mcp.server.timeoutMs`(协议上限)、检索质量阈值(`retrieval.minContextScore` 等,是质量门不是拦截)、`cleanup.*` 保留期(永不清理=磁盘写满)、`chat.goal.maxRetries/maxRecoveries`(无限重试放大故障)、SSRF/私网白名单类安全参数、能力启用开关(`tool.enabled` 等是"启用"不是"限制")

### C 类:纯行为/连接/并发/文案类约 190 键 — 不动

## 实施步骤

### Step 1 — config-schema.json
- A 类 24 个键 def 改 0/false;B1 两键 min 1→0 且 def→0;B2 约 22 键 def 改上限
- 同步修订受影响字段的 `note` 文案(如"0=永不叫停"、限流关闭警告、token 预算"默认已不限,请确认上游额度")
- 注意:凡 def 改 0 而 min>0 的必须同时降 min,否则 `ConfigSchemaService.validate()` 会拒存

### Step 2 — ConfigService.java
- `defaults()` 同步约 50 个字面量(与 schema def×factor 单位换算一致,如 upload.maxFileSize schema=MB、defaults=字节)
- 新增 `migrateRelaxedLimits()`:挂在 `init()` 中 `migrateMergedRetentionAndCadence()` 之后、`ensureDefaults()` 之前;逻辑为 `UPDATE c_ai_config SET value=新 WHERE key=? AND value=旧出厂值` — 只覆盖仍是旧默认的行,管理员自定义值不动,天然幂等
- `driftReport()` 增加第四条判据:schema def(×factor) vs defaults() 不一致进 WARN(复用现有 auditConfigDrift 框架)
- **顺手修复存量缺陷**:`resetDefaults()` 白名单(L1552-1554)只有 10 组,goal/contentSafety/tool/sandbox 等 12 个面板的"恢复本组默认"必然 400;改为按 schema panel 解析(ConfigSchemaService 加 `keysOfPanel()`),保留敏感项/personalOnly 跳过

### Step 3 — GoalSettings.java
- `DEFAULT_MAX_ROUNDS 40→0`;maxTokens/maxMs/maxToolCalls 内联默认→0
- `maxCompletionChecks()/maxVerifyFailures()` 加 `v<=0 → UNLIMITED` 分支(正数仍 clamp)

### Step 4 — 各消费点内联 fallback 同步
ToolAssemblyService、TurnSourceRegistry、ScheduledJobService、SandboxService、ToolSpillService、AgentscopeChatEngine、WorkflowService、ChatController、ShareController、ShareBudgetService、ContentSafetyService(L91 fallback true→false)、ConfigDefaults 受影响常量。

### Step 5 — 前端
无结构改动(设置页完全 schema 驱动)。

## 风险提示(写入 note)

| 改动 | 风险 |
|---|---|
| goal 四项预算全 0 | 失控长任务无限烧 token,note 升级为"默认已不限,请确认上游供应商额度" |
| ratelimit 默认关 | 公开端点无频率保护,公网部署慎关 |
| contentSafety 默认关 | 合规拦截记录消失 |
| 上传 256MB / 沙盒配额 0 | 内存与磁盘无界占用 |

## 验证

1. 单测:新增 `GoalSettingsTest`(0→UNLIMITED 哨兵、正数 clamp);`mvn test` 全量通过
2. 启动日志:无 `[Config][对账]` 新 WARN;`[Config][放宽]` 迁移点名旧默认行被改写、自定义行不动
3. 设置页:目标模式/内容安全等面板显示新默认;"恢复本组默认"按钮在 goal/contentSafety/tool/sandbox 面板不再报 400
4. 行为验证:跑一个超 40 轮长任务不被叫停;连发高频请求无 429;含拦截规则词提问直通;单轮超 15 步工具调用不截断
5. 回滚:git revert 即可;DB 已被迁移改写的行用反向 UPDATE 一条 SQL 还原,无结构变更

## Critical Files

- src/main/resources/config-schema.json
- src/main/java/com/wenqu/ai/service/ConfigService.java
- src/main/java/com/wenqu/ai/agent/goal/GoalSettings.java
- src/main/java/com/wenqu/ai/config/ConfigSchemaService.java
- 各消费点(Step 4 清单)
