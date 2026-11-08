# 方案：智能体对外能力第二批——工作流当工具 / webhook 出站 / agent 级记忆 / 管道层

> 2026-11-08 立项并当日全量落地。路线图账本「智能体对外能力」行指向本文件。
> 第一批方案见 `.zcode/plans/plan-agent-http-tools-and-variables.md`（ed4ebbb3/cbffeec4，已落地）。

## 目标

把「智能体嵌入其他项目（agent-as-a-service）」从第一批的两块积木（HTTP 工具 + 调用变量）
补成完整闭环：智能体能调度确定性流程（工作流当工具）、跑完主动回报（webhook 出站）、
按域记住宿主业务（agent 级记忆）、任何 OpenAI SDK 项目零改造接入（管道层）。

## 提交清单（4 笔 + 1 笔顺手修复 + 方案留痕）

| 笔 | 内容 | hash |
|---|---|---|
| 2a | 工作流当工具（防环闸+链深贯穿） | bbcd2ca7 |
| 顺手修复 | 工具说明精简中间件丢工具（单句说明的工具全数消失） | cc4972d4 |
| 2b | 轮终 webhook 出站（HMAC 签名、持久重试、收编出站投递） | 4670cf3e |
| 2c | 智能体记忆三态（专属分域与全局只读叠加） | 80473bf7 |
| 2d | OpenAI 兼容端点与 CORS 白名单（API Key 直调+计量归属） | 91113c7b |

## 2a 工作流当工具（bbcd2ca7）

- `ToolFamily.WORKFLOW`（不受审批 off 管辖，对齐 HTTP_API）；`WorkflowToolProvider/WorkflowToolService`
  照抄 HTTP 工具动态族样板；`c_ai_agent` 加 `tool_workflow` 三态 + `workflow_tools`（id 数组 ≤8）
- 工具名 `wf_{清洗名}_{id短8}`；入参 schema 由 start 节点声明生成（DSL 可选 `type`/`desc`，
  FlowEditor 编辑行加类型下拉与说明输入，存量缺省零变化）
- 执行复用 `runForAgent`；**防环双闸**：编译闸前置（保存/发布/回滚拦「subagent 节点引用绑定本
  工作流的智能体」，指名报错）+ 运行时 `wfDepth` 链深贯穿（ChatCommand→runForAgent→
  WorkflowRunCtx→子智能体节点，上限 2 层，超深 fail-loud）；工具族装配期到顶不挂、分支轮/游客不挂
- 装配期定死执行身份（`Principal.ofUser` 现查库）——工具回调线程不依赖 ThreadLocal
- AgentService 五处（VERSION_FIELDS/toEntity/updateColumns/toolDeny 白名单/applyConfig）
- 验证：真机轮模型自主调工具→审批卡 fail-closed→批准→工作流 success→出参回喂转述；
  自环更新被编译闸拦截；版本快照含新字段；未配置智能体零变化

## 顺手修复（cc4972d4，E2E 卡在集成边界时抓到的存量 bug）

`AgentxToolDescriptionCompact.compactList` 在已有条目被压缩（out 已初始化）后，
后续 `firstSentence` 取不出短句的工具走 `continue` 被**静默丢出发模型的清单**——
34 把注册、请求只发 25 把，丢的 7 把全是单句说明（含新工作流工具与框架 session_*/agent_list/
task_output/list_files）。修法：out 已初始化时原样保留该条目。

## 2b 轮终 webhook 出站（4670cf3e）

- **收编**：`WorkflowService.postCallback`（HMAC-SHA256 签名头 X-Wenqu-Signature、事件头、
  10s 超时、URL scheme+SSRF 校验）抽 `util/OutboundWebhook`，工作流委托之（不加第二真相源）
- `c_ai_agent` 加 `callback_url`/`callback_secret`（JWE、回显脱敏 @JsonIgnore 手写 getter——
  首版字段注解被 Jackson getter 绕过实测泄漏、值不变不重加密）；保存期 URL 过 SSRF fail-loud；
  callbackUrl 进版本快照（密钥不进——凭据非调优内容）
- `c_ai_webhook_delivery` 投递表：先落行再发的至少一次语义（幂等键 turnId/messageId）；
  ScheduleCenter 新注册「智能体回调重试」扫描（60s 节拍，退避 60s/5min/30min/2h，
  `agentWebhook.retryMax` 0-10 热更），耗尽置 exhausted + 站内通知 `agent.webhook.failed`
- 总闸 `agentWebhook.enabled`（默认开）：关闭时扫描把存量 pending 就地耗尽（关=真的安静）
- 挂点：引擎 finish 的 done 发射后 + failLoud；发起方过滤对齐 TurnNotifyService 口径
  （null/空 source 按用户轮放行——首版判据过严被 E2E 抓到）；线程池拒投显式出路=落到期 pending 行
- 验证：mock 接收器 HMAC 验签过、投递行 success；retryMax=0 首发失败即 exhausted+通知；
  总闸关闭存量耗尽；工作流收编回归 workflow.run.finished 验签过

## 2c 智能体记忆三态（80473bf7；引擎部分随并行 19c21942 入库，提交信息已注明）

- `c_ai_agent.memory_mode` 三态：NULL/0=跟随全局、1=专属、2=叠加（**不能用 toTri**——它把 2 归一成 1）
- `MemoryPaths.agentRoot`（agentId 与 uid 同一白名单防穿越）；SandboxService 记忆路由三处
  + 聊天档 scratch 全部支持记忆域；注入/工具读写/flush/合并随根走
- 叠加档只读注入走 workspaceMemoryContext 同款消毒信封；全局记忆不因本智能体轮改写
- 记忆面板：智能体下拉切换查看/编辑/清空专属记忆，六个端点加可选 agentId
- 验证：专属档 memory_save 落 agent 根、用户根零污染、模型注入侧读到专属记忆；叠加档
  同时看到全局+专属、写入仍落 agent 根；关档回落用户根逐字一致

## 2d 管道层（91113c7b）

- `POST /api/ai/v1/chat/completions`（v1 命名空间；Key 白名单放行、不进 isKeyDeniedEndpoint）：
  model=智能体 id（可见性判定，不可见 404）；messages 历史→临时会话（跑完即删，无状态多轮）；
  system 消息消毒信封拼题面；非流式 JSON / 流式 chunk + [DONE]；OpenAI error 形状+真实状态码
- 计量归属：`UsageAttr.Attr` 加 apiKeyId（第五字段+兼容构造）→ MeteredModel →
  `c_ai_usage_log.api_key_id`（**VARCHAR**——首版误用 BIGINT，Key 主键是字符串，实测修正）；
  ApiKey 列表带近 30 天调用/tokens 小计
- CORS：Filter 形态（`addCorsMappings` 启动期注册一次做不到热更），白名单 `apiOpen.allowedOrigins`
  每请求现读，空=不回 CORS 头=现状；schema 校验闸拦下首版 group+短键拼不出 backendKey 的键名
- 验证：X-Api-Key 非流式/流式全通；台账落 api_key_id；Key 列表用量小计；CORS 三态+热更

## 坑账（本轮新增）

1. **并行提交卷走未提交 hunk（反向事故）**：19c21942 把我未提交的引擎 hunk 整体卷走
   （账本此前只记过「我卷别人」）；内容完整经 E2E 验证，2c 提交注明归属，不重写别人的提交。
2. **worktree 分离 HEAD 钉死**：`git worktree add <path> HEAD` 的分离 HEAD 不随后续提交移动，
   `git checkout --` 恢复的是**创建时刻**的版本——验证栈要 `checkout --detach master` 快进后再
   重放未提交增量。
3. **Entity 手写 getter 压过 Lombok 才能挂 @JsonIgnore**：字段注解拦不住 Jackson 走 getter
   序列化（2b 密文泄漏实测）。
4. **正常轮的 source 可为 null**：发起方过滤必须对齐 `TurnNotifyService` 的 null 宽容口径。
5. **ApiKey.id 是 VARCHAR**：计量归属键全程 String，BIGINT 首版实测打空。

## 边界（不做，与第一批同口径延续）

sysPrompt 每轮模板；HTTP 工具游客放行（等分享安全 P3/P4）；工作流当工具的游客放行（同口径）；
专用审计表（v1 结构化日志）；OpenAI 端点的逐 token 真增量流式（v1 收集型通道单块下发，
语义等价）；responses API / function calling 出参（v1 只做 chat/completions 文本面）。

## 验证环境

8091 worktree 栈（HEAD+未提交增量，含分离 HEAD 快进教训）；模型千问 token-plan
（ce472bef）；mock 接收器 /tmp/wh_mock.py；IDEA 内置 mvn 离线编译。
