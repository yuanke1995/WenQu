# 问渠开发路线图账本

> 本文件是问渠（wenqu）开发路线的**单一事实源**。此前的 roadmap 与排期文档曾两度随 gitignored 的 `docs/` 清理散失，2026-11-06 从会话记忆与 git 考古重建入库。
>
> **维护规则**：
> - 新立项 / 状态变化 / 完工 / 拍板落定时**必须回写本文件，随手改，不攒批**。
> - 完工条目必须带 commit hash 与日期；内容与 git log 冲突时以 git 为准并顺手更正本文件。
> - 未核实时效的条目（来自会话记忆的转述）标注「待核」，动相关线前先 git log 复核。

## 当前基调（已拍板，勿推翻）

- **工作区定位**（2026-10-24 定稿）：隔离 / 专注 / 圈地 / 做完事；完整版蓝图 = A 上下文边界 + B 专注 + C 推进器 + D 模板。
- **路线口径**（2026-10-24 拍板）：不走商用/商标/合规路线，只做产品功能；评估一律功能视角。
- **阶段建议**（2026-10-24）：10 月功能爆发（630+ 提交）后转加固期，测试地基 = 最大结构性债。
- **兼容口径**：新功能默认兼容存量（老表单/老会话/存量库行行为零变化）；确不做兼容必须用户拍板（先例：列宽 % 改造）。
- **部署**：腾讯云轻量 docker 栈（49.232.129.201，2026-10-04 起）；本机 8090 为 IDEA 调试进程勿杀。

## 活跃线（进行中 / 待办）

| 线 | 状态 | 现状与下一步 |
|---|---|---|
| 对外集成能力补齐 | 🚧 落地中 2026-11-10 | 2026-11-10 立项（用户拍板「全面补齐、内外兼顾、JS+Python SDK」）：方案 `design/plans/plan-external-integration.md`。范围：Embeddings OpenAI 兼容端点 + 可用向量模型清单、知识库检索/管理对外 API（v1，kb.read/kb.write 分层）、API Key scope 机制（chat/embedding/kb.read/kb.write/workflow.run，默认集不含 kb.write）、ShareChatPage 嵌入参数化（no-tool/compact/color/placeholder/autoOpen）、`wenqu-embed.js` 浮动挂件（web 根静态）、对接文档四新章节、`sdk/js`（@wenqu/sdk，43 测全绿：v1 标准面 + `client.native` 原生问答深度集成模块——人在回路三张卡/artifact/resume/steer/stop，`nativeRoot`+`headers` 支持「Key 在业务代理侧」拓扑，dynamic-form 表单设计助手即此形态）与 `sdk/python`（wenqu-ai，40 测全绿）；`sdk/` 目录已在开发规范登记 |
| max_tokens 下发治本 | ✅ 2026-11-07 | 当日立项当日落地（701183fa）：框架引擎 AgentBinding 烤入登记 max_output+主链下发、手写引擎全平台必经点按请求注入；辅助链刻意不下发（runaway 护栏）；登记空=不下发零变化。token-plan 真机验证：请求体带 max_tokens=384000、单次补全 12300 tokens 破 8192 旧帽、原 presentArtifact 空参案场景复验过。方案 `design/plans/plan-max-tokens-dispatch.md`（§6 落地记录）；**遗留**=其余供应商逐家复核（别烧 deepseek/方舟 key，有真实用量时看 output 是否破 8192 即判）；当日另修 related 孤儿闭标签泄漏（53f567a1） |
| 工作区完整版 | 🚧 | A 上下文边界 + B 专注 + C1 动态 ✅（2760e4b8、12d8d433）；**剩 C2 事项清单、C3 进入即推进、A4 记忆分域、D 模板** |
| 测试地基 | ❌ 结构性债 | 自动化测试近乎为零（RBAC 判定核 19 测是起点，且 src/test/ 约定不入库）；公共件改动目前靠人工回归——这正是「改烂旧功能」风险的主因 |
| bug 移交池 | 🚧 | 2026-11-04 第二波前后端猎捕移交 13 项（0-token 误标、编辑重发互斥、审批超时绕 finish 等），细节在 11-04 会话记忆 |
| 延迟线尾巴 | 🚧 | **2026-11-06 14:32 云端实测（新流水线）**：两条新会话 `1+1=?` 整轮 **1.44s / 1.58s**（开轮前准备 252/195ms，机器 1.19/1.39s），对比改前 5.66s（准备 3.48s）/12.46s（准备 10.4s）。prompt 降至 9.9k（system 4.66k + toolSchema 5.25k），cached 2048；本地两条新会话的最终 system prompt 指纹完全一致，工作区档也未捕获到 `AgentStateStore ID`/container id——剩余缓存上限不是前缀稳定性，待查供应商缓存粒度。历史记录：稳态首包 6s→1.2s（2026-11-06）；新会话首轮曾付「开轮前准备」~3.5s（HarnessAgent 冷构建 + 沙盒冷装配）；16.8k prompt 缓存仅中 2k。 |
| 问答流水线重做 | 🚧 | 参考方案见 `design/plans/plan-chat-pipeline-rebuild.md`（**DSH 问答架构与矩阵**，DeepSeek Harness 开源实现）；问渠自有 A/B 方案已另存 `design/plans/plan-chat-pipeline-wenqu.md`。**M1/M2 代码已完成并本地验证**：聊天档免沙盒（按意图分流）、宿主 scratch（可配 `chat.scratchDir`，多副本须共享卷）、跳过 run_code/MCP（`chat.mcpEnabled` 默认关，开时仍受 `mcp.turnWaitMs` 有界等待）、前缀稳定器、MCP 等待有界、`chat.profileAuto` 回滚开关、scratch 保留清理；tokens 落 `profile=chat/workspace`。本地实测：新会话 `1+1=?` 1.2~1.3s、同会话二轮 0.68~0.72s；聊天档 `write_file`/`presentArtifact` 均无沙盒；`profileAuto=false` 可回滚；挂死 MCP 只等 1.5s；scratch 过期目录 5s 清理；工作区沙盒 E2E 已本地验证（默认 dev token，冷态 4.76s / 热态 2.79s，`write_file` 214ms，文件在容器内）；隐式会话异步升级机制已验证（默认关；首轮 1.33s + 后台 2.1s 建沙盒，二轮 1.08s 切 workspace，粘性 0.48s；首轮按方案 1 隐藏文件/命令工具，mock 验到 `write_file=false`、无 scratch 文件，交接问题关闭）；静态+运行时+分享/访客夹具全过（2 项按参数 SKIP）。**云端真实模型已验证（2026-11-06 14:32 两条 `1+1=?`）**：整轮 1.44s/1.58s、准备 252/195ms、prompt 9.9k、cached 2048；本地新会话 system prompt 指纹一致，说明聊天/工作区档的前缀本身稳定，2048 缓存上限待查供应商侧。**M3 已做**：全局 cap=6、每用户 `maxPerUser=2` LRU（f4f47ab2）、`sandbox.userScope` 用户级热复用（8c423b90）、启动热池预热 `sandbox.poolWarmUsers`（d0fc4fcd；本地实测预热 1.7s，新会话直接复用，开轮前准备 493ms；真实模型下 1+1 整轮 2.65s）。**剩余**：多副本共享存储、核心工具面继续裁剪（第 1 档：内置 10 把已物理退役，4079f387；第 2 档安全子集：goal_* 按活跃目标、agent/task 按子智能体存在性，4b0a19e5；plan/skill 保留为功能入口）；跨用户通用预创建池在当前「按 uid 挂载用户卷」下不安全，等效实现即每用户预热（已做）。 |
| 智能体对外能力 | ✅ 第二批 2026-11-08 | 第一批（2026-11-07，ed4ebbb3=自定义 HTTP 工具 OpenAPI 导入 + cbffeec4=调用变量注入含分享级开关）方案 `design/plans/plan-agent-http-tools-and-variables.md`。**第二批当日立项当日全量落地（方案 `design/plans/plan-agent-external-batch2.md`）**：2a 工作流当工具（bbcd2ca7，防环编译闸+wfDepth 链深贯穿、审批 fail-closed）+ 顺手修复工具说明精简中间件丢工具（cc4972d4，单句说明的工具被静默丢出发模型——存量 bug，E2E 抓到）+ 2b 轮终 webhook 出站（4670cf3e，收编 OutboundWebhook、c_ai_webhook_delivery 至少一次重试、耗尽通知、总闸）+ 2c 智能体记忆三态（80473bf7，专属分域/全局只读叠加、记忆面板下拉；引擎部分随并行 19c21942 入库已注明）+ 2d OpenAI 兼容端点（91113c7b，`/api/ai/v1/chat/completions`、计量落 c_ai_usage_log.api_key_id、CORS Filter 热更白名单、ApiKey 列表 30 天用量）。真机 E2E 全过（worktree 栈）。遗留（后排）：OpenAI 端点逐 token 真增量流式/responses API/function calling；工作流当工具游客放行（等分享安全 P3/P4 拍板）；webhook 事件面扩展（v1 只有轮终一类）。注意与工作流第 4 期的 webhook 是两回事（那边=入站事件触发，这边=结果出站回调） |
| 智能体评测与发布门禁 | ✅ 2026-11-07 P1+P1.5 | 当日立项当日落地（方案 `design/plans/plan-agent-eval.md` + 效果图 `design/mockups/agent-eval/`，后端 e9c3ee49 / 前端 4b4e0de6）：工作台第 7 个「评测」Tab（菜单种子 menu-agents-tab-eval，全员可见）+ 4 张评测表 + 真实流水线回归（source=eval 只读、需审批工具自动拒、temp 会话跑完即删）+ 引用召回/忠实度/相关性打分（评判模型 agentEval.judgeModel 空回落 eval.judgeModel）+ 门禁五态（none/unevaluated/changed/running/pass/below）+ 保存前检查弹窗（hint 默认/block 可配，无套件静默直存存量行为零变化）+ 两次运行对比/设基线 + 会话采样（差评优先）+ 用量 kind=eval 单列「智能体评测」+ 通知 agentEval.done/failed + 重启残留清理。真机四场景 E2E + 回归面（无套件保存//evaluation 页/temp 泄漏）全过。**坑账**：① `trigger` 是 MySQL 保留字（MP 生成 SELECT 直接语法错）→ 列名 trigger_kind；② CollectingSseEmitter 的 done 载荷是「字符串套 JSON」双重序列化，直接 parseObject 静默丢 sources/tokens；③ 评判是辅助调用，默认开思考的模型会把 max_tokens 花在推理上正文本空——复用 reasoningOffBody（恒思考族放宽 maxTokens）；④ 评判 maxTokens 200 会被长 issue 截成半截 JSON；⑤ Math.rint 是 Java API，进 Vue 模板整页渲染炸。**遗留**：P2=历史版本重测（config override 收口）/工具期望评分/多轮/定时回归/评测集市场模板；存量库每个租户要在权限页给普通角色勾一次 agentEval:read/write（新库由 RbacUserRoleSeeder 自动推导）；门禁 block 只在前端拦（API 层可绕，P2 配草稿态时收口到服务端） |
| MCP 外部工具页升级 | 🚧 | 2026-11-07 立项（用户拍板）：UI/UX 打磨 + 工具试调用 Playground + 个人调用统计 + 定时健康巡检；方案 `design/plans/plan-mcp-panel-upgrade.md`（含效果图制作输入 §1 与后端端点规格 §4）；**效果图由外部工具制作，定稿后实施** |
| 市场三期 | ✅ 2026-11-07 | MCP 目录当日立项当日落地（a0ad0a5a，方案 `plan-market-mcp-directory.md`）：市场第 4 页签 + 管理员目录维护（无 ref 概念、条目统一挂平台官方、普通用户 403）+ 安装落 c_ai_user_mcp（同名同址拒重装/同名异址拒覆盖/每人 10 个配额沿用）+ 列表四态后端算（已装>同名>超限>可装）+ 名称/地址/传输校验收编 util/McpServerRules（McpController 委托同源）+ 测试连接复用 /mcp/probe + 前端 MCP 卡片/详情弹窗（认证提醒常驻）/管理员新增编辑弹窗照效果图；curl API 链 + admin/yuanke 双用户真机 UI 全过（四态、安装即时翻转、去 MCP 页跳转、技能页签回归）；dev 库留 4 条目录样例（DeepWiki/GitHub/天气/高德，可在 UI 下架）；二期（应用模板 fork/工作流导入导出/官方模板）2026-10-25 完毕 26 项 E2E，**已入库（58ba17e1，2026-11-07 git 考古核实）** |
| RBAC 批次 F | ⏸ 排 M7 | v1.0 已于 2026-11-03~04 全量落地（10 提交）；仅剩批次 F 排期 M7；D2 粒度是后续唯一阻塞项 |
| 工作流升级第 4 期 | ✅ 2026-11-07 | 第 0~3 期 ✅（ff637d20 / 5e7fef65 / 78f7053e / ebdea58c）；**第 4 期当日重建排期+落地（4 提交 61ec1025 / 3af31d43 / 4b146a89 / 8cec6c8d）+遗留债清偿（97252ccf / 30c5bf1b）**：事件触发（webhook 入站 + KB 解析完成事件 + 链深防环熔断 depth≥3）/ kb_write 知识写入节点（create/update 双模式）/ 并发可视化（node_start 实时事件+画布呼吸灯 / 运行历史甘特时间轴+并行分组标）/ trace 摘要三键可配 + eventTriggerEnabled 总闸 / 导入导出接线+过时文案修缮；UI 四处经用户拍板（自动化单弹窗分区/双模式表单/呼吸灯圆点/历史页甘特+分组标）；真机 E2E 全过（webhook 三守卫、事件闭环、防环 1→2→3 熔断、呼吸灯 DOM 实证、**审批挂起恢复与检查点续跑复验**（短路回放零重复执行）、历史页并行渲染+老数据降级、问答线 3.1s 回归）。**遗留债**：多订阅者共写同一文档时链深登记会被并发覆盖（真实链条各写各文档，不受影响）。**附带修缮（1c98ce4c）**：StateGraph「并行分支必须同点汇聚」的框架语义保留不动，但编译闸前置到 create/update/publish——「画得出、存得进、跑时才炸且报错晦涩」的问题改为保存/发布当场拦截+人话指引（复用引擎编译 dry-run，不自建第二套汇聚分析） |

| P1 必补能力（五项） | ✅ 四项落地 + function calling 未做 2026-11-08 | 方案 `design/plans/plan-p1-gaps.md`；效果图 `design/mockups/tenancy-lifecycle-ux/`（6 景）、`design/mockups/notification-outbound-ux/`（4 景）、`design/mockups/mcp-panel-upgrade/` 补 04/05（真机渲染验证）。**落地**：① 多租户生命周期——邀请流（`c_ai_tenant_invite`，Token 只存 SHA-256、名额 CAS 占用/失败归还、`/join/{token}` 匿名预览与注册、MembersPage 邀请弹窗与在途列表）、登录品牌（tenant 四列 + `/auth/brand` + 登录页租户品牌 + 租户品牌弹窗）、归档（status=2 + archive/restore + 列表按钮）、首登引导（`/tenant/onboarding` 五项按真实数据 + 顶部 checklist）；3E 当前租户指示此前并行已落地（auth.js tenantStateSync + 侧栏 pill + 必 403 控件收口）。② 邮件外发——`spring-boot-starter-mail` + `c_ai_mail_delivery` 台账（至少一次重试/聚合窗口/skipped 留痕）+ 个人邮箱 `PUT /user/email` 与通知偏好 mailTypes + 设置页「通知外发」面板（258 字段）+ 测试发送与投递记录；总闸默认关，站内/WebPush 零变化。③ OpenAI 端点——`/v1/chat/completions` 真增量流式（CollectingSseEmitter.forwardTokens 转发正文 token、usage 尾帧、[DONE]）+ `/v1/responses`（非流式对象与 response.created/output_text.delta/completed 流事件）+ API Key 白名单；**function calling 已实现**（tools ≤32 / schema ≤64KB、副作用工具 fail-closed、只读交集映射 tool_calls（非流式 finish_reason=tool_calls、流式 tool_calls 尾帧）、role=tool 回传恢复历史；映射与过滤 4 个单测全绿；本机 E2E 受模型工具轮挂起限制，待链路正常时线上复验）。④ MCP——补健康巡检（`mcp.patrolIntervalMs`，非空闲池 connected 服务 listTools 失败翻红接退避）+ 编辑弹窗 URL 自动识别/名称查重 + 批量重连；后端 schema/invoke/stats 三件与前端试调用/统计/失败卡三景此前并行已落地。⑤ 工作流游客放行 B 档——只读节点集（start/end/llm/retrieval/condition/loop/template）+ 工具装配与执行双闸 + chatflow 游客闸。**验证**：后端编译/打包/启动通过（81 表、515 端点、SchemaMigrator 补 5 列）；邀请注册端到端通过（一次性、落 default、发 JWT）；匿名 `/join` 与 `/auth/brand` 冒烟通过；前端 build 通过；OpenAI chat/completions 真增量流式已实测（role 帧 + 多段 content + finish + usage）；function calling 映射与副作用过滤 4 个单测全绿（真实模型已触发 tool_call，本机工具轮收尾受环境链路限制）。**遗留**：邮件模板自定义/附件；品牌 logo 只支持填路径不提供上传；邀请未接邮件发送（复用邮件通道待接线）。 |
| P0 必补收敛 | 🚧 2026-11-08 | 方案 `design/plans/plan-p0-gaps.md`。**已落地**：多租户刀一（必 403 控件收口 + 当前租户 pill + 游客行/台账按发布者租户）；检索门统一 `RetrievalGate`（分域门 + 全灭保底 top-1 + 低置信显形，四调用点）；分享 P3 `ShareBudgetService`（分享/访客日轮次 + 分享日 token，shareId 贯穿台账）；P4 通道复核并补「附件正文信封」；测试地基 T1/T2/T3 起步/T4（`src/test` 白名单入库、5 存量测试修好、新增 3 后端测试类 + 前端 Node 纯模块测试并接入 CI）；工作区事项 C2/C3/D（`c_ai_project_item` + 去聊预填/会话关联 + 模板复制 + 删工作区清理事项）。**验证**：`mvn test`、`npm test`、`npm run build` 通过；本地 8091 真机冒烟事项增删改查、模板复制（目录+事项 open）、删工作区事项清零全过。**评估回放（硅基流动 bge-reranker-v2-m3，25 条）**：top1 与 off 两组的 recall@5/10/20、MRR、hitRate 完全相同（0.722/0.875/0.889/0.722/1.0），保底策略不降召回；A/B 后配置已还原 top1。**bug 池按用户口径关闭**（11-04 原 13 项清单已找不到，不再追）。**剩余**：T3 契约层仍只覆盖事项接口（分享游客/配额/RBAC 契约后续补）、本批改动未提交 git。 |
| 多租户完善 | 🚧 立项 | 2026-11-07 三路并行审计立项：**底座已真**（62 表行级过滤 / 租户键控 RBAC 快照 / 配额限流审计租户维度 / SSO 建号落认证源租户 / 按租户备份），「玩具感」来自面 + 资源侧 + 生命周期——面上必 403 控件×2（TenantsPage 落点清单按钮、MembersPage 租户管理弹窗）、全站无「当前租户」指示（/auth/me 下发 tenant 前端不消费）、游客分享产生的行落 default（结算口径失真）、供应商/模型全局且注释与「谁建归谁」漂移、市场租户私有档未做（代码已预告）、无邀请流/清退/登录品牌。方案 `design/plans/plan-tenancy-real.md` 三刀：**刀一 P0 面修真**（约 1 天，不依赖拍板可开工）→ 刀二 P1 资源归属（条件刀，供应商口径待拍板）→ 刀三 P2 生命周期与门面。**补登记：本线 a7877128 起 25+ 提交此前从未回写账本（并行会话遗漏）** |
| H5 整体优化 | 🚧 P0 第一版落地 | 2026-11-09 立项并出效果图 `design/mockups/h5-optimization/h5-ux-upgrade.html`（主屏三态/横屏/状态与来源/图片查看器/附件与会话/暗色）；方案 `design/plans/plan-h5-optimization.md`。**已落地**：登录页窄屏居中缺陷修复（390×844 / 390×420 / 1280×800 三档实测）；输入草稿本机持久化（按用户+会话、300ms 防抖、发送/清空/删会话/退出清除，与引擎未发插话恢复不串会话）；图片查看器手势（双指 1–4x、双击缩放、放大拖动、左右滑切换、下滑关闭、返回键先复位）；附件页拆「拍照 / 从相册选择」；PWA 新版本就绪提示（不自动刷新、每版本一次）；首屏分块改造（H5 首屏 JS 686.3→540.0KB gz，index.html 只预加载 vue-vendor）。Playwright 390×844 全项通过、生产构建 SW 更新提示实测、PC 1280×800 无变化 |

## 待拍板（用户未决）

- KB 导出导入通用化两路线：纯文档 zip vs Dify 格式
- 游客审批卡 fail-closed + 数据字典注入
- 分享安全：P3 费用面 / P4 注入面（P1 ClientIp、P2 share-read 桶已修）
- 工作区上下文装配 0.4s（延迟线尾巴）
- FormCopilot「copilot 切 API Key」——已初评语义倒退，待终裁
- 多租户刀二前置：供应商口径（租户自备 key BYOK vs 平台统一供 key）+ 租户定位（外部客户 vs 内部分域）——见 `design/plans/plan-tenancy-real.md` §4
- P1 邮件通道是否平台必备（定位纯内部工具则可只保留邀请链接复制、不做 SMTP）；见 `plan-p1-gaps.md` §7
- P1 OpenAI function calling：**已按只读交集实现**（副作用工具不暴露、未声明工具不返回）；本机 E2E 受模型/工具轮挂起限制，待线上复验；见 `plan-p1-gaps.md` §3.4
- P1 工作流游客放行：**已按 B 档落地**（只读节点集 + 装配/执行双闸 + chatflow 游客闸）；C 档仍等分享安全 P3/P4
- H5 整体优化：顶栏方案 A/B、横屏热区是否降到 40px、状态与来源用分段锚点还是只折叠、图谱弹窗是否纳入本轮、草稿是否仅本机；见 `design/plans/plan-h5-optimization.md` §9

## 已完成台账

### 2026 Q4 roadmap（14 节；原文散失，本表 2026-11-06 考古重建；§2/§3 标题已不可考）

| 节 | 内容 | 状态 |
|---|---|---|
| §1 | Agentic RAG 检索-反思循环（证据不足自主多轮检索） | ✅ ed07750 |
| §2 | 标题散失（2026-10-06 审计时已完成） | ✅ |
| §3 | 标题散失（同上） | ✅ |
| §4 | 会话内 @ 智能体（轮级委派，不破坏会话绑定） | ✅ c38dc7a |
| §5 | GraphRAG 图谱可视化（聚合视图/实体溯源/搜索定位） | ✅ 8dac2b5 |
| §6 | 文档增量更新（content_hash 对比式重建，未变块保向量/QA 对） | ✅ |
| §7 | NL2SQL（原文自标「放后」） | ⏸ 未做 |
| §8 | 知识库健康度面板 | ✅ 986ddbb5 |
| §9 | 智能体配置版本管理（快照+diff+回滚） | ✅ 06a7c0d |
| §10 | 智能体评估集（EvaluationPage 无智能体 Tab，评测集是单份全局 JSON） | ✅ 2026-11-07 由「智能体评测与发布门禁」线完成（e9c3ee49/4b4e0de6，按智能体建评测集+回归+门禁；/evaluation 检索评估保持原样并存） |
| §11 | 深度研究预设（§1 已解锁，纯组合零新引擎，高性价比候选） | ❌ 未做 |
| §12 | 通知中心 | ✅ c82beb9 |
| §13 | Token 成本报表（模型单价+台账×单价聚合） | ✅ 14380d7c |
| §14 | 移动端 H5（M0~M3 分包/引擎/移动壳/SW + M4~P6 收官） | ✅ |

### 其他已完成流

- **子智能体运行视角**（2026-11-07，41142f14）：实时直播+事后回放落地——收集通道转发白名单加宽（retrieved/artifact/warn/ask_user）、分支收束统一写一条 `type=subagent` 账本事件（挂父会话+父轮，关键帧 ≤240×2000 字，`appendLarge` 200k 上限、payload 列升 MEDIUMTEXT 已 ALTER）、前端 `SubAgentRunFrames.vue` 共用渲染（派遣卡「完整过程」抽屉直播吃 `_frames`/终态账本懒取 + 事件面板 subagent 聚合块）。四轮真机 E2E 全过（直播帧/刷新回放/账本聚合/老消息优雅空态）；**顺带修复**：DSH 平铺行路径原本不挂 SubAgentChatCard，subChat（问它/它的回答）在新口径下不可见——已在平铺行详情挂载，两条路径同构。遗留债：BranchMode(digest/full) 在框架通路未按档分流。

- **市场一期技能**：✅ c92db191。
- **RBAC 升级 v1.0**（2026-11-03~04）：10 提交、判定核 19 测全绿、三态双跑/enforce 全验证；方案在 design/plans/rbac-upgrade-plan.md。坑账：新 RBAC 表必须进 GLOBAL_TABLES（三踩）、种子须反向折算 role_api。
- **每轮文件改动卡**（DSH workspace-changes 口径，2026-11-05）：执行器快照+捕获+比对、c_ai_message.changes、改动卡+预览台 diff 档。
- **问答延迟线**（2026-11-06）：五笔根因修四笔半（沙盒往返 / journal 回读 / 技能扫盘 / 方舟冷连），近五笔提交 b6825773 / 93705109 / a91272ac / d2c91185 等。
- **问答流水线重做 M1/M2/M3**（2026-11-06）：聊天/工作区双档、宿主 scratch、工具面过滤、前缀稳定器、MCP 有界、异步升级、每用户租约、userScope 热复用、启动热池预热。提交 164d3097 / a873bb16 / 22e8380e / f742997b / f4f47ab2 / 8c423b90 / d0fc4fcd；细节与实测见「活跃线·问答流水线重做」。
- **内置工具退役**（2026-11-06）：10 把纯计算工具（calculate/currentDateTime/daysBetween/addDays/randomNumber/uuid/unitConvert/textStats/base64/hash）已物理退役——`@Tool` 实现、描述符、注册入口全部删除，内置族只剩 `askUser`；`BuiltinToolProvider` 改名 `AskUserToolProvider`。前端 `projections.js` 只保留历史消息的工具卡展示名，不影响老会话回显。提交 93d5d6df（先默认收窄）→ 4079f387（物理退役+改名）→ f1d18e1c（族名改 ASK_USER，code=ask_user、显示名「提问工具」）。
- **编排工具第 2 档安全子集**（2026-11-06）：`AgentxToolSurfaceFilter` 按本轮能力收窄工具表——无活跃目标隐藏 goal_note/goal_submit，无子智能体隐藏 agent_*/task_*/wait_async_results；目标续跑轮按 turnId=goal: 兜底。plan_*/skill_* 保留为功能入口（隐藏等于关功能，且 plan 支持模型中途 plan_enter）。本地 1+1 隐藏 goal 2 把（当前用户有子智能体，agent/task 保留），workspace 文件链路回归通过。提交 4b0a19e5；续跑轮补标 042b8f2e。
- **journal 读放大**（2026-11-01）：纸表轮收尾空窗 57s 根因修（逐 token 落账回放），chunk 聚合是上游待办。
- **定时任务**：容量评估完成、dispatch 队列满泄漏（bf434f3b）与 cron 错峰两 bug 已修。

### FormCopilot × 报表系统（三仓联动线，主战场在 dynamic-form / dynamic-form-vue 仓）

后台管理+真实登录（a625c73b 族）、B 档后端代理（问渠 token 不落浏览器）、增量补丁协议（__patch + ops）、列宽 px/% 双单位、设计器 UX 包、沙盒双重加密修复（68d8bb6b）等。细节以三仓 git log 为准，本账本只记联动概要。

## 遗留债 / 技术注记

- ~~工具说明精简中间件丢工具~~ 已修（2026-11-08 cc4972d4）：compactList 在 out 初始化后对压不了说明的条目 continue 导致静默丢工具；教训=「优化只改发出去的文本」类中间件必须保序保量，工具面双跑比对只覆盖组管理名单、抓不到动态族的丢失（动态工具当年就漏了）。
- **并行会话提交可能卷走未提交 hunk（双向事故）**：19c21942 曾把对外能力第二批未提交的引擎 hunk 整体卷走（此前账本只记过「我卷别人」）；处置=内容经 E2E 验证后在己方提交注明归属，不重写别人的提交。
- **验证栈 worktree 分离 HEAD 钉死**：`git worktree add <path> HEAD` 的分离 HEAD 不随后续提交移动，`git checkout --` 恢复的是创建时刻版本；长批次验证中途要把 worktree `checkout --detach master` 快进后再重放未提交增量。
- 多数 deepseek 模型上下文登记值仍 65536（SQL 改值后须重启后端——供应商/模型是启动期内存缓存）。
- AgentScope 2.1.0-BETA1 ingestion 在 ~2 万字处丢字+尾部重投（seq 幂等闸已兜；chunk 聚合是上游待办）。
- GraphRAG 抽取模型退役后，存量 c_ai_user_config 行未清（库已迁 wenqu_ai）。
- 沙盒双重加密修复（68d8bb6b）后，DB 内旧 token 需设置页重填。
- 检索两缺口：元数据过滤（c_ai_knowledge 无 metadata/tags 字段）、检索内容注入防护与来源可信度。
- 「后端有前端没接」5 处（2026-10-07 盘点：单轮删除撤销、清空全部会话、沙盒文件浏览三接口、关键词索引 stats/reindex 无 UI、运维端点无入口）——现状待核，动相关域时顺手核销。
- 配置默认值三层口径：schema def 只给前端、defaults() 是运行时权威、getDouble fallback 纯防御；多读点 key 默认值进 ConfigDefaults 常量类。
- 显式本机工作区（ExecutorFilesystem）的服务器沙盒白建已修：`frameworkSandboxContext` 对显式工作区直接返回 null，不再创建无用的服务器容器；待本机执行器配对后真机验证（2026-11-06 代码审计/修复）。
- ~~`scripts/mac/check-agent-runtime.py --fixtures` 与 `c_ai_agent_share` 表结构漂移~~ 已修（2026-11-06）：`c_ai_agent` 选 `id`、分享 SSE 用 raw POST、`count(*)` 断言按首行值判；修复后分享/访客夹具全过。
- 检索低分门（retrieval.minContextScore=0.6 绝对门）会误杀「隐性问答」：问元信息（如「引用的诗词是什么」）而块内只有引文无出处时，重排只给部分匹配分（实测 0.192，同块显式问法 0.999），第 1 名照样被杀。2026-11-08 线上实证（工作流检索 count=0 全杀；主问答同轮因模型没调 search_knowledge 工具、靠预训练知识答对造成「检索通了」假象）。当日处理=只补内容（01-产品简介 补诗句出处标注 c8076c14，同步后 0.992 过门、工作流 E2E 通）；机制层修法（保底 top-1 / 相对门）未拍板，动检索域时先评估。
