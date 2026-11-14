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
| 沙盒设置归位沙盒面板 | ✅ 2026-11-14 | 用户拍板「这是沙盒的功能的话就放沙盒」：工具调用面板「子工具」节三项沙盒相关设置（`tool.sandbox.enabled` 沙盒 API 与工作流节点 / `tool.runcode.enabled` 沙盒脚本 / `tool.runcode.maxSubCalls` 单次脚本子调用上限）挪进「沙盒」面板新增首节「能力开关」，原两节（工作区与执行限额/回收）顺延；键名/后端命名空间/vif 链（sandbox.enabled←tool.enabled←runcode 链）全不动，纯展示归位。tool 面板只剩「总开关」节，SettingsPage 页签「总开关与子工具」→「总开关」；README 工具调用条目、manual/09 两处指向、manual/11 两行同步。验证：ConfigContractTest 4 测绿、blocksOf 真实渲染函数对新 schema 算块正确、vite build 过；8090 启动期已载旧 schema，真机复验待重启。**未提交**（混并行 WIP，config-schema.json/SettingsPage.vue/README 均含他人 hunk） |
| HTTP 工具两设置退役 | ✅ 2026-11-14 | 用户拍板「这俩退役 不做开关控制」：`httpTool.enabled`（总开关）与 `httpTool.allowPrivateNetwork`（允许调用内网地址）全链退役——HttpApiToolProvider 删全局闸（只受 tool.enabled 总闸+智能体显式三态制约，同工作流/技能口径）、HttpToolService/HttpApiToolCallback/HttpToolExecutor 删 allowPrivate 参数链与 assertPublicHost 私网防护（内网地址恒允许调用；宿主 API 常在内网，自宿主默认本就开）、HttpToolService 去 ConfigService 依赖；schema 删 httpTool 面板+两字段+两 tips；AgentsPage toolHttp 能力行摘 path（globalText 恒「开启」）；README（httpTool.* 属性表只剩 timeoutMs、工具开关/HTTP 工具集/工具族口径）、manual/11（面板表删行、108 项/17 面板/13 导航按增量对齐）。契约测试 103/153 对齐并行计数器全绿、全量 compile 过。**未提交**（混并行 WIP）；注意：私网防护随开关一并删除，公网多租户部署若需防探测内网需另立机制；`httpTool.timeoutMs` 保留（隐藏键） |
| 联网搜索纳入执行审批退役 | ✅ 2026-11-14 | 用户拍板退役 `webSearch.requireApproval`（联网搜索面板「安全」节唯一一项，节随字段一并删）：审批恒开——联网是唯一向外部第三方发请求、可能计费的能力，让模型自主决定花谁的钱不该是默认态（原默认开，退役即固化）。`ToolDescriptor.approvalGateKey` 配置闸机制整体收编（record 11→10 参、10 参便捷构造 9 参化、`requiresApproval(configOn)` 谓词改无参静态），谓词链塌缩：ToolRegistry.requiresApproval 去 Predicate 参、ToolMountContext 游客闸/WenquAgentxTools 两处/ToolInventoryService sensitive 标记全部改直调；WebSearchToolProvider 声明改静态 requiresApproval=true；ToolFamily/ConfigService 注释同步。schema 删字段+`webSearchRequireApproval` tips+面板「安全」节；V14 迁移清 c_ai_config/c_ai_user_config 残留行（开发库仅平台层一行 true、个人层 0 行，零漂移）；README webSearch.* 属性表、manual/07（恒纳入执行审批无开关）/manual/11（联网搜索 6→5 项）。契约测试 103/153 对齐并行计数器全绿、test-compile 全量过、WebSearchPersonalLayerTest 4 测绿。**未提交**（混并行 WIP，WenquAgentxTools/ToolInventoryService/providers 含他人 hunk 需 hash-object 拆分）；游客不挂联网/ask 档拦/off 档不管联网三口径不变，部署后行为对存量环境等价（除非曾显式关过——无处可关即恒开） |
| 知识块填充上限下放个人 | ✅ 2026-11-14 | 用户拍板两步走：上午「平台不限制死、下放个人设置全局」（`context.maxContextHits` 加 `personal: true`，问答偏好面板+`GET/PUT /user/settings` 通道落地）；**下午追加拍板「平台设置直接退役、默认值直接放个人设置页」当日落地**——字段标 `personalOnly`（复用 rerank.model 建的既有机制：管理端 schema 不下发、resolveChain 无全局层直返 PERSONAL_ONLY_UNSET、管理端 PUT /config 拒写）、schema 删「上下文与长度控制」面板、editable 条目改口径（保留作管理端拒写提示文案）、ConfigService 删 d.put 种子+AppProperties.Context 类+application.yml `context:` 块（env 槽位退役；未设时读点 fallback 8 兜底）、契约测试 DYNAMIC_DEFAULTS 删键（104/154 计数不变——defaults 基座仍含 schema def）、SettingsPage 删「上下文」页签+ragChildren/PANEL_CHILD_TAB 清理、ProfilePage 占位符/清空 toast 改双口径（有全局层「跟随系统（X）」，personalOnly「默认（X）」/「已清除，用默认值」）、UserController 过期描述（「无 personal 字段」）修正、manual/06+11、README（计数、yml 样例、属性表、面板数 16）。真机 8091+5802：设置页 103 项/检索设置仅剩关键词引擎页签、个人页占位「默认（8）」、改 12 保存回读、清空「已清除，用默认值」回落、trace uid 维度 userOverride=12、管理端写全局拒。**未提交**（混并行 WIP，config-schema.json/api.js 含他人 hunk 需 hash-object 拆分）；存量 c_ai_config `context.maxContextHits` 全局行与库行成死数据不清理 |
| 权限档位三项设置退役 | 🚧 落地中 2026-11-14 | 用户拍板「这三个也是直接退役」：设置页工具调用「权限档位」节三项全退——`agent.permissionPreset.default`（智能体未选档恒 standard）、`agent.permissionPreset.max`（恒 autonomous，钳制分支/名册 available+unavailableReason/前端置灰与降级提示全成死码一并删）、`tool.deny`（平台停用名单恒空，mergeDeny 并集/checkPlatformDeny 启动核对/ToolInventoryService platformDenied 标记/SettingsPage denyOptions 全删）。PermissionPolicyResolver 去 ToolRegistry 依赖、resolve 系去 onClamp 参数（degradationSink 其余降级码不动）、catalog 只留 platformDefault 点名；ToolAssemblyService/WenquAgentxTools/AgentscopeChatEngine 签名同步；schema 删三字段+三 tips+「权限档位」节（15 面板/99 字段）；前端 permission.js 砍 ceiling 回落、AgentsPage/ChatPage 去 available 置灰、AgentsPage 黑名单候选去 platformDenied 标注；Agent/Session/ChatController/AgentController/PermissionPreset/SandboxMode 注释与 Swagger 描述同步、README 权限档位/tool.*/agent.* 三处口径。**智能体级档位/黑名单、会话级覆盖、游客只读闸不动**。编译+ConfigContractTest 4 测绿（99/149 对齐并行计数器）+vite build 过；真机验收待 8090 重启。**未提交**（混并行 WIP） |
| 工具说明精简设置退役 | ✅ 2026-11-14 | 用户拍板「取消参数设置，直接在代码默认开启」：`chat.toolDescriptionCompact` 开关退役、精简恒开——schema 删 chat 面板条目（108 字段，「回答行为与内容」节整节清空删除、超时五键 section 2→1 归位）；AgentscopeChatEngine 去 getBoolean 现读、AgentxToolDescriptionCompact 去 BooleanSupplier 构造参改无参恒压（类注释同步）；契约测试 108/158、ConfigSchemaStoredDefaultsTest 删该键断言、README 5 处计数（调优 41→40）、manual/11 对话模型 7→6 项、ConfigController PUT /config 示例改存活键。编译+ConfigContractTest/ConfigSchemaStoredDefaultsTest 5 测全绿；真机未跑（默认部署行为等价——默认本就开）。**未提交**（混并行 WIP） |
| 图片访问鉴权设置退役 | ✅ 2026-11-14 | 用户拍板「取消参数设置，直接在代码默认开启」：`images.authEnabled` 开关全链删除、鉴权恒开恒校验——schema 删 chunk 面板条目+两处 tips（109 字段，面板标题「解析并发」）、`parse.qaConcurrency` section 2→0 归位（删 sections 项后下标越界坑）；AppProperties 字段/application.yml env 映射/ConfigService 播种与回写/ImageUrlSigner isEnabled+signUrlRequired 并入 signUrl/ImageAuthInterceptor 放行分支全删；helm `imagesAuthEnabled`+`AI_IMAGES_AUTH_ENABLED` 退役；契约测试 109/159+DYNAMIC_DEFAULTS 删键、README 5 处计数（工程排障 67→66）、manual/11、SettingsPage 页签「解析与图片」→「解析并发」。编译+ConfigContractTest 4 测全绿；真机未跑（默认部署行为等价——默认本就开）。**未提交**（混并行 WIP）；存量 c_ai_config `images.authEnabled` 行成死数据不清理 |
| 开放 API 两设置项退役 | ✅ 2026-11-14 | 用户拍板「退役的只是两个参数配置，能力本体不动」：设置页「开放 API（外部项目嵌入）」面板整体退役——`apiOpen.enabled` 总开关连闸门逻辑一起删（SecurityConfig 总闸块+configService 字段，Key 通道恒开，各 Key 单独停用已够）、`apiOpen.allowedOrigins` 转运行时键（OpenApiCorsConfig 照读，默认空=不回跨域头，改库+重启生效；PUT /config 按 schema 校验已不认此键，与内部键同模式）；schema 删面板+两字段+两 tips（19 面板/137 字段）、ConfigService defaults 登记 hidden 键、契约测试 137/192、README/manual/contract README 口径同步。提交态验证法：`git commit-tree` 暂存树挂临时 worktree 跑契约测试全绿（工作区混并行退役线时验提交态的标准做法）。已提交 8d798214（7 文件全自己的 hunk，hash-object+update-index 造 blob）；真机 8091：设置页导航无「开放 API」、统计带 129 项对齐、白名单内 origin 回 ACAO/外不回、无效 Key 401 无总闸文案。**能力本体未动**：v1 契约/API Key/SDK/嵌入挂件/平台 MCP/分享全部保留 |
| 分支执行方式退役 | ✅ 2026-11-14 | 用户拍板「两层一起退役」（关键发现：P5 退役 SubAgentOrchestrator 后 digest 档运行时零消费点，派遣一律走完整问答轮，该档位只剩展示与校验残留）：平台参数 `agent.subagentMode`（schema 条目+tips）与智能体级 digest/full 选择一并退役——AgentService 版本字段/更新/校验/subAgents/configDefaults 全清、Agent.java 删字段、AgentController 描述同步、BrowserCapabilitySeeder 去 mode 写值（11-07 真修的是审批档 ask→auto，mode 是搭车）、BranchMode 枚举与测试段删除；前端 AgentsPage 选择器/摘要/委派候选后缀全清、api.js 注释同步；手册 07/11 与 README 计数对齐；V11 迁移（清 `c_ai_config` 键 + DROP 列，V1 建列+RETIRED_COLUMNS 不补回，与 V2 同口径）。编译+契约测试（131/185）+BranchReportAndModeTest 全绿+vite build 过；真机（临时 vite 5811 连 8090 旧后端）子智能体配置页运行方式区无选择器、保存链路过、委派候选纯名字。**未提交**（与并行 vision/重排退役线共用 config 契约面与 README/账本，按 hunk 拆分暂存）；V11 随后端重启生效 |
| 图片描述提示词固化 | ✅ 2026-11-14 | 用户拍板「固化进代码、设置页直接退役」：`vision.prompt` 设置项整体退役（vision 面板连同残留并发/缓存 tips 一并清出 schema，19 面板 / 131 字段），提示词固化 `ConfigDefaults.VISION_PROMPT`（文本与原默认一字不差，缓存键不受影响）；VisionService 去 ConfigService/AppProperties 依赖、运行时零配置读取；V12 迁移清 `c_ai_config` 残留行；SettingsPage 检索设置「图片描述」页签/深链/导航残留全清。模型本就归各知识库解析设置（visionRef），OCR 提示词本就是代码常量。编译+契约测试（ConfigContractTest/ConfigSchemaStoredDefaultsTest 5 测全绿）+`npm run build` 过；真机验收待 8090 重启（V12 随启动执行）**未提交**（并行会话共用 config 契约面与 README/账本，按 hunk 拆分暂存） |
| 启动对账开关退役 | ✅ 2026-11-14 | 用户拍板退役 `keyword.reconcileOnStartup`（启动时对账开关；周期对账是写路径 best-effort 的唯一兜底，保留不动）：ScheduleCenter 该任务启动首轮改恒 false、TASK_PARAMS 登记删除，schema 条目+`keywordReconcile` tips 删除（检索设置组现 129 字段），KeywordIndexService/package-info 注释同步；README 多副本第 7 条改口径（启动巡检只剩 `parse.recoverStuckOnStartup`）、`keyword.*` 设置项表去该键、手册 13/helm README 同步；契约测试计数对齐（129/183，与并行退役线共用同计数器按实际全量清点）。开发库 `c_ai_config` 残留行已删；编译+ConfigContractTest/ConfigSchemaStoredDefaultsTest/ConfigSchemaRelaxTest 全绿。**未提交**（并行退役线共用 config 契约面与 README/账本，按 hunk 拆分暂存）；线上残留行随下次部署清或无视（无读取点，纯死数据） |
| 深度执行预算设置退役 | ✅ 2026-11-14 | 用户拍板「深度执行的参数全部退役，只保留一个在代码区：自动推进轮数上限=20轮」：设置页「深度执行（长任务）」面板整体退役——schema 删 goal 面板+四字段（`chat.goal.maxRounds/maxTokens/maxMs/maxToolCalls`，18 面板/141 字段）；GoalSettings 重写：`MAX_ROUNDS=20` 代码常量、maxTokens/maxMs/maxToolCalls getter 与 UNLIMITED 哨兵/ConfigService 依赖全删（完成检查/核验失败两项的 0=不限制语义保留为私有哨兵）；GoalService 三项预算闸/四列快照写入/budgetView 三键全退，轮数闸与软阈值收敛（softBudgetRatio 0.8）改按轮数占比单轴计算；Goal.java 删 budget_* 四字段；V16 迁移清 c_ai_config/c_ai_user_config 四键+c_ai_goal 四列（V15 已被并行 contentSafety 占用）；前端零改动（GoalCard/GoalViewer/deepState 按 payload 条件渲染，max 缺省即纯用量展示）。契约测试 141/196、Flyway 断言对齐提交态 8 个迁移文件（V11~V15 随并行退役线入库）、GoalSettingsTest 重写、ConfigSchemaRelaxTest/GoalLifecycleLocalTest/GoalTurnGuardLocalTest 预算行清理；提交态（commit-tree+worktree）14 测全绿。**期间事故**：config-schema.json 曾被并行分支整体还原到旧态，goal 删除连并行 contentSafety 删除一并被卷回——已重做手术（删块脚本先跑只读 trace 验边界、落盘前后双 json.load）；contentSafety 的 schema 删除归并行线自行重做。**已提交 e1649c61 + 文档 259a4898（隔离 index+commit-tree，不碰共享暂存区）**；行为变化：轮数上限从默认不限制收紧为恒 20，进行中目标即时适用；token/时长/工具调用硬预算从「可配不限制」变「机制整体不存在」，用量展示保留 |
| 重排退役下放库级 | 🚧 落地中 2026-11-14 | 2026-11-14 当日拍板当日实施（方案 `design/plans/重排退役下放库级方案.md`）：平台「启用重排」开关/平台默认重排模型/个人默认重排模型/rerank.baseUrl 直连回落全退役，重排=库绑了模型才重排（归属论定=知识库）；minHits/maxHits 下放库级（跟收敛出模型的库走，缺省 6/15）；智能体级只留「强制关」（显式 false）；RerankService 改按路由隔离状态（原全局 client/冷却在多路由下跨轮串线）；轮级收敛登记供咨询/MCP 来源评分复用；V10 迁移清全局键与个人残留行（V7~V9 已被历史一次性迁移占用）；flyway 增 `ignore-migration-patterns: "*:missing"`（配合迁移用完即删策略，否则存量库 validate 卡死）。**未提交**（与并行 chat 退役线共享 config 契约面，按 hunk 拆分暂存），编译/契约测试/真机验证已完成 |
| KB 参数全局默认值退役 | 🚧 落地中 2026-11-14 | 用户拍板「知识库这些默认值直接写在弹窗、退役系统设置全局默认值」+「整个检索设置面板一并退役（门/召回上限下沉库级）」+「保持留空=跟随内置默认」：schema 删「检索设置」检索参数 9 字段与「文档解析默认模板」9 字段（129→110 字段，panel chunk 只剩图片访问鉴权+问答并发改题「解析并发与图片访问」）、eval.applyObserveHours 连带退役；**6 个仅全局键下沉库级**（vectorTopK/keywordLimit/minContextScore/minFusionScore/lowConfidencePolicy/top1Floor 进 KbParamCodec.KEYS）：召回量按轮「参与库取最大」（单库轮即该库值），分域门新增 RetrievalGate.applyPerKb 按命中块所属库逐块取门（全灭保底在合并层统一判），WebSearch/工作流节点无库归属读点回落 ConfigDefaults 常量（线程覆盖=评估扫参仍最优先）；ConfigService.defaults() 删 12 处种子、KbRetrievalParamService 去全局回落；**评估灰度应用整体退役**（全局槽位没了 apply 无处可写）：RetrievalEvalApplyService/三端点/体检与运行 observe 挂点/paramsDrift 参数指纹/前端应用按钮与横幅全删，扫参本体保留；param-defaults 端点改纯内置常量（DocumentService.parseParamDefaults + Params.defaults()）；DocxParser structural 与 DocumentService qa/child 三处单参 getBoolean 补默认值兜底（否则删行后静默变 false）；V13 迁移清 c_ai_config/c_ai_user_config 24 键。验证：全量 283 测绿、vite build 过、8091 真机（V13 迁移+启动对账 160 键三处一致、设置页 110 项/检索设置只剩关键词引擎、弹窗 12 检索参数占位=内置默认、debug/retrieval 回显逐库 12 参数且改库级门 0.6→0.1 dropped 13→0/还原、新键保存归一+清空回环、评估页无应用入口）。**未提交**（并行退役线共用 config 契约面/README/账本，按 hunk 拆分暂存）；遗留：卡片徽标「继承全局」文案已改「跟随内置默认」 |
| 对外集成能力补齐 | 🚧 落地中 2026-11-10 | 2026-11-10 立项（用户拍板「全面补齐、内外兼顾、JS+Python SDK」）：方案 `design/plans/对外集成能力补齐计划.md`。范围：Embeddings OpenAI 兼容端点 + 可用向量模型清单、知识库检索/管理对外 API（v1，kb.read/kb.write 分层）、API Key scope 机制（chat/embedding/kb.read/kb.write/workflow.run，默认集不含 kb.write）、ShareChatPage 嵌入参数化（no-tool/compact/color/placeholder/autoOpen）、`wenqu-embed.js` 浮动挂件（web 根静态）、对接文档四新章节、`sdk/js`（@wenqu/sdk，43 测全绿：v1 标准面 + `client.native` 原生问答深度集成模块——人在回路三张卡/artifact/resume/steer/stop，`nativeRoot`+`headers` 支持「Key 在业务代理侧」拓扑，dynamic-form 表单设计助手即此形态）与 `sdk/python`（wenqu-ai，40 测全绿）；`sdk/` 目录已在开发规范登记 |
| 图扩展上限下放库级 | ✅ 2026-11-14 | 用户拍板「知识图谱检索图扩展上限也下放库级」（KB 参数全局默认值退役线的收尾：设置页「知识图谱（库级开关）」节只剩这一项，节标题指的就是库级开关本体）：`graphrag.expandTopK` 全局槽位退役——schema 删字段+删节标题+maintenance 后续 section 重编号（104 字段），`editable` 文案保留作弹窗问号源（tipsByKey 对非设置页键回落 editable，与 rerank.minHits 同路径）；`KbParamCodec.KEYS` 加 `graphrag.expandTopK`（保存白名单自动收编）、`ConfigDefaults.GRAPH_EXPAND_TOP_K=5`、`GraphRagService.expand` 改按参与库取最大（与召回量两键同口径，空=内置默认、显式 0=不扩展）、param-defaults 下发内置默认。前端知识库弹窗「知识图谱」开关下新增「图扩展上限」输入（graphEnabled 才显示，不计进检索参数折叠组自定义数）；契约测试 104/154 对齐、README 6 处计数（调优 39/工程排障 64）。验证：编译+ConfigContractTest 4 测全绿；8091 真机——param-defaults 含键=5、schema 无字段且 maintenance section 无越界、KB 保存白名单收编落库回读、法律库临时开图谱跑 debug/retrieval 24 命中新分支无异常（验后还原）、弹窗开关开启即现「默认 5」占位输入、设置页无残留。**未提交**（并入今日 KB 参数退役批次 WIP） |
| 技能平台级设置退役 | ✅ 2026-11-14 | 用户拍板「整体退役：平台不禁用、也不限制安装域名」：`skill.disabled`（平台停用清单）与 `skill.remoteAllowedHosts`（URL 安装域名白名单）两键全链退役——WenquSkillRepository 去 ConfigService 依赖、SkillEntry 回单层（disabled=个人停用）、catalog 删平台层过滤；UserSkillStore 删 requireAllowedHost/allowedHosts/checkAllowedHost（SsrfGuard 公网逐跳校验保留，构造去 ConfigService 参）、SkillController 删 remoteInstall()/platformDisabled/userDisabled 载荷；schema 删 skills 面板+两字段（15 面板，与并行退役线对齐 99/149）；SettingsPage 删「技能」页签+平台停用名册+skillOptions 加载链（panelDefaultPayload 随之删）；SkillsPage URL 安装弹窗去白名单门改纯提示；SkillDrawer/SkillContentModal/MarketPublishModal 去 platformDisabled 分支；V1 基线退役名单加两键（1464 行「remoteAllowedHosts 永久禁入退役名单」注记作废——读取点已全删，当年顾虑=删行被空默认补回）；开发库 c_ai_config 两死行已删；manual 09/11/15、README（99 项/15 面板/工具调用 2 页签/两级停用改个人停用）。验证：编译+ConfigContractTest/UserSkillStoreVersionTest 全绿、vite build 过；8091+5802 真机——/skill/list 无 remoteInstall 无 platformDisabled、loopback URL 安装报 SSRF 拒（非白名单拒）、设置页工具调用 2 页签无技能导航、技能页 URL 弹窗无门可填。**未提交**（混并行 WIP） |
| 对外集成第四轮（契约治理 A+B） | ✅ 2026-11-11 a43a0284 | 用户拍板「先做 A+B」。**A 契约单一真源**：新增 `openapi/` 包（`@OpenApi` 注解 + 启动扫描 `OpenApiRegistry`，路径/方法直接读 Spring 的 `RequestMappingHandlerMapping`，v1 未标注路由启动期 fail-loud；`SecurityConfig` 的 v1 放行改查表、默认拒绝；**非 v1 的 client.native 问答白名单与 `isKeyDeniedEndpoint` 原样保留**——那是正当保护不是兼容层，删了会让 Key 能改密/自签 Key）；`GET /api/ai/v1/openapi.json` 实时产出 OpenAPI 3.1（只含 v1，生产默认可读）；CORS Allow-Methods、scope 名册（`OpenApiScopes` 下沉为唯一真源）、README / manual / `/docs` 口径同源。**B 接入健壮性**：`Bearer sk-…` 与 `X-Api-Key` 在身份装载与鉴权两处统一（官方 OpenAI SDK 零改造直连，修掉 README 承诺与实现不符的 401）；v1 错误体统一 `{error:{message,type,code,request_id}}` + `X-Wenqu-Request-Id` 全链路；API Key 轮换（旧明文即时作废、归属/scope/有效期/台账不变）；双 SDK 透出 code/request_id。验证：IDEA 内置 mvn 离线 compile 通过、`OpenApiRegistryTest` 5 测全绿、`npx vite build` 14.3s 通过、JS SDK tsc（补 DOM lib）0 错 + errors.ts 运行时验证、Python SDK 40/40（顺带修 `mock_server` 对绝对形式请求行的路由误判——keep-alive 第二次请求变成完整 URL 导致 404，用 `urlsplit` 取 path）。**遗留**：`Idempotency-Key` 未做（需动轮次状态机或引入结果缓存层，按「与架构差距大先给方案」口径不落普通版）；`/docs` 仍是手写页只加了契约入口、SDK 未接 codegen；补齐计划里三条真机验证（嵌入回归 / 真实后端联调 / LangChain 行为）仍未做。**下游继承验证 2026-11-11（wenqu 057ac61e / dynamic-form 1983531b）**：在 dynamic-form 与 dynamic-form-vue 上验证三仓联动对新契约的继承——① SDK 新错误字段（code/request_id）同步后解析通过（本机 `node_modules/@wenqu/sdk` 原本未安装，`sync-wenqu-sdk.mjs` 按设计静默跳过，用等价拷贝补齐后断言）；② 假上游身份链路模式协议级端到端通过（`/copilot/config`、`agent/available`、SSE `/q/chat` 含 form-json，17 帧）；③ 新增 `IdentityLinkInheritanceTest` 逐条断言 dtbd 代理的 18 条转发路径在 Key 白名单内、不在拒绝名单里，A+B 未打断身份链路；④ 发现代理不透传新请求号，`CopilotWenquProxyController` 现采信合法外来号/生成 UUID，随 `X-Api-Key` 转发并回写响应头（反射运行时断言通过）。真实后端端到端仍未做：本机 8099 无监听、dtbd Java 后端未启动。**实机端到端 2026-11-11 23:40（ba6c4922）**：`git archive` 干净快照打包起 8099 实测 7 项全绿（契约端点免登录 200、X-Api-Key 与 Bearer sk- 双凭据回显 admin、v1 400/401 OpenAI 形状且 request_id 与响应头一致、Key 硬边界 403 保持平台包络、客户端请求号原样采信）；实机抓到并修复 3 个 mock 抓不到的启动/门禁 bug（e2ab98b2 mapping 歧义炸启动、ff31000c 契约文档被登录门禁 401、ba6c4922 门禁 401 漏分流）；dtbd fat jar 起在随机端口且 logback 配置损坏致日志黑洞（jstack 实为已就绪），代理端点被其自身 Coos 认证挡 AUTH_REQUIRED（本地无门户登录态，正是设计器假上游存在的原因）；浏览器面板全链路仍未做 |
| max_tokens 下发治本 | ✅ 2026-11-07 | 当日立项当日落地（701183fa）：框架引擎 AgentBinding 烤入登记 max_output+主链下发、手写引擎全平台必经点按请求注入；辅助链刻意不下发（runaway 护栏）；登记空=不下发零变化。token-plan 真机验证：请求体带 max_tokens=384000、单次补全 12300 tokens 破 8192 旧帽、原 presentArtifact 空参案场景复验过。方案 `design/plans/max_tokens下发方案.md`（§6 落地记录）；**遗留**=其余供应商逐家复核（别烧 deepseek/方舟 key，有真实用量时看 output 是否破 8192 即判）；当日另修 related 孤儿闭标签泄漏（53f567a1） |
| 工作区完整版 | 🚧 | A 上下文边界 + B 专注 + C1 动态 ✅（2760e4b8、12d8d433）；**剩 C2 事项清单、C3 进入即推进、A4 记忆分域、D 模板** |
| 测试地基 | ❌ 结构性债 | 自动化测试近乎为零（RBAC 判定核 19 测是起点，且 src/test/ 约定不入库）；公共件改动目前靠人工回归——这正是「改烂旧功能」风险的主因 |
| bug 移交池 | 🚧 | 2026-11-04 第二波前后端猎捕移交 13 项（0-token 误标、编辑重发互斥、审批超时绕 finish 等），细节在 11-04 会话记忆 |
| 延迟线尾巴 | 🚧 | **2026-11-06 14:32 云端实测（新流水线）**：两条新会话 `1+1=?` 整轮 **1.44s / 1.58s**（开轮前准备 252/195ms，机器 1.19/1.39s），对比改前 5.66s（准备 3.48s）/12.46s（准备 10.4s）。prompt 降至 9.9k（system 4.66k + toolSchema 5.25k），cached 2048；本地两条新会话的最终 system prompt 指纹完全一致，工作区档也未捕获到 `AgentStateStore ID`/container id——剩余缓存上限不是前缀稳定性，待查供应商缓存粒度。历史记录：稳态首包 6s→1.2s（2026-11-06）；新会话首轮曾付「开轮前准备」~3.5s（HarnessAgent 冷构建 + 沙盒冷装配）；16.8k prompt 缓存仅中 2k。**2026-11-14 前缀稳定器退役（用户拍板）**：后续实测证明本地新会话 system prompt 指纹本就一致、工作区档已不注入那两个 id，cached 停在 2048 是供应商缓存粒度问题——本刀收益未兑现，`PrefixStabilizerMiddleware` + `chat.prefixStabilizer` 配置键 + 设置页条目全删（契约测试 144→142 同步）。 |
| 问答流水线重做 | 🚧 | 参考方案见 `design/plans/plan-chat-pipeline-rebuild.md`（**DSH 问答架构与矩阵**，DeepSeek Harness 开源实现）；问渠自有 A/B 方案已另存 `design/plans/plan-chat-pipeline-wenqu.md`。**M1/M2 代码已完成并本地验证**：聊天档免沙盒（按意图分流）、宿主 scratch（可配 `chat.scratchDir`，多副本须共享卷）、跳过 run_code/MCP（MCP 只在工作区档挂载，仍受 `mcp.turnWaitMs` 有界等待；原 `chat.mcpEnabled` 调优开关 2026-11-14 拍板退役：不进设置页、按默认关收口）、前缀稳定器、MCP 等待有界、分流常开（原 `chat.profileAuto` 回滚开关已拍板退役：不进设置页、代码内恒自动）、scratch 保留清理；tokens 落 `profile=chat/workspace`。本地实测：新会话 `1+1=?` 1.2~1.3s、同会话二轮 0.68~0.72s；聊天档 `write_file`/`presentArtifact` 均无沙盒；`profileAuto=false` 可回滚；挂死 MCP 只等 1.5s；scratch 过期目录 5s 清理；工作区沙盒 E2E 已本地验证（默认 dev token，冷态 4.76s / 热态 2.79s，`write_file` 214ms，文件在容器内）；隐式会话异步升级机制已验证后于 2026-11-14 拍板退役（默认关从未开启；`chat.asyncWorkspaceUpgrade` 键 + 引擎异步分支 + SandboxService `isReady`/`provisionAsync` + 槽位 asyncPending + 工具面文件隐藏分支全删，命中文件/执行意图直接同步走工作区档；历史实测：首轮 1.33s + 后台 2.1s 建沙盒，二轮 1.08s 切 workspace，粘性 0.48s）；静态+运行时+分享/访客夹具全过（2 项按参数 SKIP）。**云端真实模型已验证（2026-11-06 14:32 两条 `1+1=?`）**：整轮 1.44s/1.58s、准备 252/195ms、prompt 9.9k、cached 2048；本地新会话 system prompt 指纹一致，说明聊天/工作区档的前缀本身稳定，2048 缓存上限待查供应商侧。**M3 已做**：全局 cap=6、每用户 `maxPerUser=2` LRU（f4f47ab2）、`sandbox.userScope` 用户级热复用（8c423b90）、启动热池预热 `sandbox.poolWarmUsers`（d0fc4fcd；本地实测预热 1.7s，新会话直接复用，开轮前准备 493ms；真实模型下 1+1 整轮 2.65s）。**剩余**：多副本共享存储、核心工具面继续裁剪（第 1 档：内置 10 把已物理退役，4079f387；第 2 档安全子集：goal_* 按活跃目标、agent/task 按子智能体存在性，4b0a19e5；plan/skill 保留为功能入口）；跨用户通用预创建池在当前「按 uid 挂载用户卷」下不安全，等效实现即每用户预热（已做）。 |
| 智能体对外能力 | ✅ 第二批 2026-11-08 | 第一批（2026-11-07，ed4ebbb3=自定义 HTTP 工具 OpenAPI 导入 + cbffeec4=调用变量注入含分享级开关）方案 `design/plans/智能体对外能力第一批方案.md`。**第二批当日立项当日全量落地（方案 `design/plans/智能体对外能力第二批方案.md`）**：2a 工作流当工具（bbcd2ca7，防环编译闸+wfDepth 链深贯穿、审批 fail-closed）+ 顺手修复工具说明精简中间件丢工具（cc4972d4，单句说明的工具被静默丢出发模型——存量 bug，E2E 抓到）+ 2b 轮终 webhook 出站（4670cf3e，收编 OutboundWebhook、c_ai_webhook_delivery 至少一次重试、耗尽通知、总闸）+ 2c 智能体记忆三态（80473bf7，专属分域/全局只读叠加、记忆面板下拉；引擎部分随并行 19c21942 入库已注明）+ 2d OpenAI 兼容端点（91113c7b，`/api/ai/v1/chat/completions`、计量落 c_ai_usage_log.api_key_id、CORS Filter 热更白名单、ApiKey 列表 30 天用量）。真机 E2E 全过（worktree 栈）。遗留（后排）：OpenAI 端点逐 token 真增量流式/responses API/function calling；工作流当工具游客放行（等分享安全 P3/P4 拍板）；webhook 事件面扩展（v1 只有轮终一类）。注意与工作流第 4 期的 webhook 是两回事（那边=入站事件触发，这边=结果出站回调） |
| 智能体评测与发布门禁 | ✅ 2026-11-07 P1+P1.5 | 当日立项当日落地（方案 `design/plans/智能体评测立项方案.md` + 效果图 `design/mockups/agent-eval/`，后端 e9c3ee49 / 前端 4b4e0de6）：工作台第 7 个「评测」Tab（菜单种子 menu-agents-tab-eval，全员可见）+ 4 张评测表 + 真实流水线回归（source=eval 只读、需审批工具自动拒、temp 会话跑完即删）+ 引用召回/忠实度/相关性打分（评判模型 agentEval.judgeModel 空回落 eval.judgeModel）+ 门禁五态（none/unevaluated/changed/running/pass/below）+ 保存前检查弹窗（hint 默认/block 可配，无套件静默直存存量行为零变化）+ 两次运行对比/设基线 + 会话采样（差评优先）+ 用量 kind=eval 单列「智能体评测」+ 通知 agentEval.done/failed + 重启残留清理。真机四场景 E2E + 回归面（无套件保存//evaluation 页/temp 泄漏）全过。**坑账**：① `trigger` 是 MySQL 保留字（MP 生成 SELECT 直接语法错）→ 列名 trigger_kind；② CollectingSseEmitter 的 done 载荷是「字符串套 JSON」双重序列化，直接 parseObject 静默丢 sources/tokens；③ 评判是辅助调用，默认开思考的模型会把 max_tokens 花在推理上正文本空——复用 reasoningOffBody（恒思考族放宽 maxTokens）；④ 评判 maxTokens 200 会被长 issue 截成半截 JSON；⑤ Math.rint 是 Java API，进 Vue 模板整页渲染炸。**遗留**：P2=历史版本重测（config override 收口）/工具期望评分/多轮/定时回归/评测集市场模板；存量库每个租户要在权限页给普通角色勾一次 agentEval:read/write（新库由 RbacUserRoleSeeder 自动推导）；门禁 block 只在前端拦（API 层可绕，P2 配草稿态时收口到服务端） |
| MCP 外部工具页升级 | 🚧 | 2026-11-07 立项（用户拍板）：UI/UX 打磨 + 工具试调用 Playground + 个人调用统计 + 定时健康巡检；方案 `design/plans/MCP面板升级方案.md`（含效果图制作输入 §1 与后端端点规格 §4）；**效果图由外部工具制作，定稿后实施** |
| 市场三期 | ✅ 2026-11-07 | MCP 目录当日立项当日落地（a0ad0a5a，方案 `市场MCP目录立项.md`）：市场第 4 页签 + 管理员目录维护（无 ref 概念、条目统一挂平台官方、普通用户 403）+ 安装落 c_ai_user_mcp（同名同址拒重装/同名异址拒覆盖/每人 10 个配额沿用）+ 列表四态后端算（已装>同名>超限>可装）+ 名称/地址/传输校验收编 util/McpServerRules（McpController 委托同源）+ 测试连接复用 /mcp/probe + 前端 MCP 卡片/详情弹窗（认证提醒常驻）/管理员新增编辑弹窗照效果图；curl API 链 + admin/yuanke 双用户真机 UI 全过（四态、安装即时翻转、去 MCP 页跳转、技能页签回归）；dev 库留 4 条目录样例（DeepWiki/GitHub/天气/高德，可在 UI 下架）；二期（应用模板 fork/工作流导入导出/官方模板）2026-10-25 完毕 26 项 E2E，**已入库（58ba17e1，2026-11-07 git 考古核实）** |
| RBAC 批次 F | ⏸ 排 M7 | v1.0 已于 2026-11-03~04 全量落地（10 提交）；仅剩批次 F 排期 M7；D2 粒度是后续唯一阻塞项 |
| 工作流升级第 4 期 | ✅ 2026-11-07 | 第 0~3 期 ✅（ff637d20 / 5e7fef65 / 78f7053e / ebdea58c）；**第 4 期当日重建排期+落地（4 提交 61ec1025 / 3af31d43 / 4b146a89 / 8cec6c8d）+遗留债清偿（97252ccf / 30c5bf1b）**：事件触发（webhook 入站 + KB 解析完成事件 + 链深防环熔断 depth≥3）/ kb_write 知识写入节点（create/update 双模式）/ 并发可视化（node_start 实时事件+画布呼吸灯 / 运行历史甘特时间轴+并行分组标）/ trace 摘要三键可配 + eventTriggerEnabled 总闸 / 导入导出接线+过时文案修缮；UI 四处经用户拍板（自动化单弹窗分区/双模式表单/呼吸灯圆点/历史页甘特+分组标）；真机 E2E 全过（webhook 三守卫、事件闭环、防环 1→2→3 熔断、呼吸灯 DOM 实证、**审批挂起恢复与检查点续跑复验**（短路回放零重复执行）、历史页并行渲染+老数据降级、问答线 3.1s 回归）。**遗留债**：多订阅者共写同一文档时链深登记会被并发覆盖（真实链条各写各文档，不受影响）。**附带修缮（1c98ce4c）**：StateGraph「并行分支必须同点汇聚」的框架语义保留不动，但编译闸前置到 create/update/publish——「画得出、存得进、跑时才炸且报错晦涩」的问题改为保存/发布当场拦截+人话指引（复用引擎编译 dry-run，不自建第二套汇聚分析） |

| P1 必补能力（五项） | ✅ 四项落地 + function calling 未做 2026-11-08 | 方案 `design/plans/P1必补能力方案.md`；效果图 `design/mockups/tenancy-lifecycle-ux/`（6 景）、`design/mockups/notification-outbound-ux/`（4 景）、`design/mockups/mcp-panel-upgrade/` 补 04/05（真机渲染验证）。**落地**：① 多租户生命周期——邀请流（`c_ai_tenant_invite`，Token 只存 SHA-256、名额 CAS 占用/失败归还、`/join/{token}` 匿名预览与注册、MembersPage 邀请弹窗与在途列表）、登录品牌（tenant 四列 + `/auth/brand` + 登录页租户品牌 + 租户品牌弹窗）、归档（status=2 + archive/restore + 列表按钮）、首登引导（`/tenant/onboarding` 五项按真实数据 + 顶部 checklist）；3E 当前租户指示此前并行已落地（auth.js tenantStateSync + 侧栏 pill + 必 403 控件收口）。② 邮件外发——`spring-boot-starter-mail` + `c_ai_mail_delivery` 台账（至少一次重试/聚合窗口/skipped 留痕）+ 个人邮箱 `PUT /user/email` 与通知偏好 mailTypes + 设置页「通知外发」面板（258 字段）+ 测试发送与投递记录；总闸默认关，站内/WebPush 零变化。③ OpenAI 端点——`/v1/chat/completions` 真增量流式（CollectingSseEmitter.forwardTokens 转发正文 token、usage 尾帧、[DONE]）+ `/v1/responses`（非流式对象与 response.created/output_text.delta/completed 流事件）+ API Key 白名单；**function calling 已实现**（tools ≤32 / schema ≤64KB、副作用工具 fail-closed、只读交集映射 tool_calls（非流式 finish_reason=tool_calls、流式 tool_calls 尾帧）、role=tool 回传恢复历史；映射与过滤 4 个单测全绿；本机 E2E 受模型工具轮挂起限制，待链路正常时线上复验）。④ MCP——补健康巡检（`mcp.patrolIntervalMs`，非空闲池 connected 服务 listTools 失败翻红接退避）+ 编辑弹窗 URL 自动识别/名称查重 + 批量重连；后端 schema/invoke/stats 三件与前端试调用/统计/失败卡三景此前并行已落地。⑤ 工作流游客放行 B 档——只读节点集（start/end/llm/retrieval/condition/loop/template）+ 工具装配与执行双闸 + chatflow 游客闸。**验证**：后端编译/打包/启动通过（81 表、515 端点、SchemaMigrator 补 5 列）；邀请注册端到端通过（一次性、落 default、发 JWT）；匿名 `/join` 与 `/auth/brand` 冒烟通过；前端 build 通过；OpenAI chat/completions 真增量流式已实测（role 帧 + 多段 content + finish + usage）；function calling 映射与副作用过滤 4 个单测全绿（真实模型已触发 tool_call，本机工具轮收尾受环境链路限制）。**遗留**：邮件模板自定义/附件；品牌 logo 只支持填路径不提供上传；邀请未接邮件发送（复用邮件通道待接线）。 |
| P0 必补收敛 | 🚧 2026-11-08 | 方案 `design/plans/P0必补收敛方案.md`。**已落地**：多租户刀一（必 403 控件收口 + 当前租户 pill + 游客行/台账按发布者租户）；检索门统一 `RetrievalGate`（分域门 + 全灭保底 top-1 + 低置信显形，四调用点）；分享 P3 `ShareBudgetService`（分享/访客日轮次 + 分享日 token，shareId 贯穿台账）；P4 通道复核并补「附件正文信封」；测试地基 T1/T2/T3 起步/T4（`src/test` 白名单入库、5 存量测试修好、新增 3 后端测试类 + 前端 Node 纯模块测试并接入 CI）；工作区事项 C2/C3/D（`c_ai_project_item` + 去聊预填/会话关联 + 模板复制 + 删工作区清理事项）。**验证**：`mvn test`、`npm test`、`npm run build` 通过；本地 8091 真机冒烟事项增删改查、模板复制（目录+事项 open）、删工作区事项清零全过。**评估回放（硅基流动 bge-reranker-v2-m3，25 条）**：top1 与 off 两组的 recall@5/10/20、MRR、hitRate 完全相同（0.722/0.875/0.889/0.722/1.0），保底策略不降召回；A/B 后配置已还原 top1。**bug 池按用户口径关闭**（11-04 原 13 项清单已找不到，不再追）。**剩余**：T3 契约层仍只覆盖事项接口（分享游客/配额/RBAC 契约后续补）、本批改动未提交 git。 |
| 多租户完善 | 🚧 立项 | 2026-11-07 三路并行审计立项：**底座已真**（62 表行级过滤 / 租户键控 RBAC 快照 / 配额限流审计租户维度 / SSO 建号落认证源租户 / 按租户备份），「玩具感」来自面 + 资源侧 + 生命周期——面上必 403 控件×2（TenantsPage 落点清单按钮、MembersPage 租户管理弹窗）、全站无「当前租户」指示（/auth/me 下发 tenant 前端不消费）、游客分享产生的行落 default（结算口径失真）、供应商/模型全局且注释与「谁建归谁」漂移、市场租户私有档未做（代码已预告）、无邀请流/清退/登录品牌。方案 `design/plans/多租户体系完善方案.md` 三刀：**刀一 P0 面修真**（约 1 天，不依赖拍板可开工）→ 刀二 P1 资源归属（条件刀，供应商口径待拍板）→ 刀三 P2 生命周期与门面。**补登记：本线 a7877128 起 25+ 提交此前从未回写账本（并行会话遗漏）** |
| 产物出场方式优化 | ✅ 2026-11-10（已提交） | 用户拍板 A+B+C 本轮一起做：效果图 `design/mockups/artifact-presence/产物出场方式.html`、方案 `design/plans/产物出场方式实施方案.md`。改动面=前端 7 改 + 5 新增（DeliveryCard 单件分支与缩略、ArtifactThumb、ArtifactPendingCard、artifactThumb 并发闸、artifactPreview/artifactPending 纯函数），**后端零改动**；C 复用现有 preview 字段/签名地址/artifact content 接口与产物页沙箱缩略能力，边界=H5 不跑 iframe、分享页整卡退回徽标（token 下载端点强制 attachment + octet-stream，内联需后端出流改造）、iframe 并发 2、文本并发 3。B 只报已接收字数，不画假百分比。单件动作按用户反馈二次压缩并统一位置：操作按钮固定在卡片右上角（单件在标题右侧、多件在标题行右端），预览=眼睛图标、下载/ZIP=图标按钮，均 24px 高（H5 28px），单件整卡约 104px；多件清单不再重复列入口文件，行内补大小。已过 npm test（14 项）+ npm run build；真机（vite 5800→云端接口）单件 form.json 卡/表单骨架缩略/预览、单件网页真实首屏缩略、H5 单件与多件包形态均通过；B 生成态未真机触发（逻辑单测覆盖）；分享页按设计退回徽标；已提交 |
| H5 整体优化 | 🚧 P0+P1 已落地；P2 H5-12 白名单窄屏复核 ✅（2026-11-12） | 2026-11-09 立项并出效果图 `design/mockups/h5-optimization/H5体验升级.html`（主屏三态/横屏/状态与来源/图片查看器/附件与会话/暗色）；方案 `design/plans/H5整体优化方案.md`。**已落地**：登录页窄屏居中缺陷修复（390×844 / 390×420 / 1280×800 三档实测）；输入草稿本机持久化（按用户+会话、300ms 防抖、发送/清空/删会话/退出清除，与引擎未发插话恢复不串会话）；图片查看器手势（双指 1–4x、双击缩放、放大拖动、左右滑切换、下滑关闭、返回键先复位）；附件页拆「拍照 / 从相册选择」；PWA 新版本就绪提示（不自动刷新、每版本一次）；首屏分块改造（H5 首屏 JS 686.3→540.0KB gz，index.html 只预加载 vue-vendor）。**2026-11-11 P1 按效果图方案 A 落地**：顶栏 6→5 入口（过程回放收进状态 sheet 的「看完整过程」）；状态与来源加 6 段位锚点 + 4 折叠摘要（本轮/引用默认展开）；横屏 844×390 顶栏收到 40、输入卡单行、模型名并入 +（命中区仍 44）；会话 sheet 首访展开账户入口并记忆。Playwright 实测 18/18：375/390/412 三档竖屏均 5 入口、无横向溢出（390 标题 198px）；状态 sheet 锚点 6 个/折叠 4 个/过程回放单层；会话 sheet 首访展开、再开沿用；844×390 无横向溢出、输入区 63px、顶栏 45px、命中区 44；PC /chat 未挂移动壳。`npm run build`、`npm test` 20/20 通过。**H5-10 ✅（2026-11-12）**：ChatInput 附件/发送/停止、ChatPage 分享/任务清单等动态 `:title` 全部镜像 `:aria-label`；审计脚本加 `A11Y=1` 无名按钮检查，390px `/chat` 与五页白名单（knowledge/artifacts/project/profile/help）实测均 **0 个无名按钮**；顺带补了知识库卡片「更多」与 antd 搜索按钮的可及名称。**P2 审计 2026-11-12**：新增 `scripts/mac/check-h5-pages.mjs`（CDP，5 页 × 375/390/412/768/1024，横向溢出 + 视口内 <44px 热区）。实测：五页均**无横向溢出**；但 <768 仍是桌面布局——`/artifacts` 13–16 个、`/knowledge` 5 个、`/project` 6 个、`/profile` 11–38 个、`/help` 17 个热区 <44px（help 导航宽 892 说明未切移动壳）。修复：`app.css`/AppLayout 统一 ≤1024 壳层热区，HelpCenter/Profile/Artifacts/Project/ChatInput 补页内 44px 规则；复验 **5 页 × 5 宽度 25/25 全过**（无横向溢出、无视口内 <44px 主操作；输入框清除图标不计主操作）。**H5-13 ✅（本地矩阵）**：登录页 / 通知中心 / 分享页均 5 档全过（登录按钮/链接、通知页签、分享品牌链接补 44px；审计支持 `ANON=1` 匿名页；装饰轮播圆点、开关与输入清除图标列为紧凑/装饰控件不计主操作）。唯一不可本地替代：微信内置浏览器真机复核。**H5-15 部分 ✅**：新增 `scripts/mac/check-h5-budget.mjs` 并挂到 `postbuild`——入口静态闭包 gzip 预算 300KB（当前 248.2KB，超了构建失败）；antd 当前仍在主入口静态依赖（P3 已知缺口，默认告警、`STRICT_ANTD=1` 失败，待路由级懒加载收口）；LCP/TBT 仍需运行时采样。**H5-11 ✅（布局）**：`KnowledgeGraphModal` ≤1024 全屏工作台，三栏纵向堆叠（图在上、实体与详情在下）；新增 `scripts/mac/check-graph-mobile.mjs`（隔离库种子图谱 + CDP 打开菜单），390/1024 实测全屏、无横向溢出、纵向堆叠；画布沿用 ECharts `roam:true`（拖拽/滚轮/双指缩放），触屏双指未做真机模拟。**H5-14 部分 ✅（自动化矩阵）**：审计脚本支持 `SIZES=WxH` 与 `SCHEME=dark`；iPhone SE 375×667 / iPhone 15 393×852 / Pixel 412×915 / iPad 1024×768 横屏 × 登录/知识库 × 暗色 **8/8 全过**（无溢出、主操作 ≥44px）。仍需真机/人工签核：微信内置浏览器、PWA standalone、200% 字体、离线/弱网。剩余 H5-16 视觉 token。Playwright 390×844 全项通过、生产构建 SW 更新提示实测、PC 1280×800 无变化 |
| 市场体验升级（四期） | 🚧 四期全部落地待验收 2026-11-10 | 2026-11-10 重写方案与效果图（用户要求重出）：方案 `design/plans/市场升级方案.md`，效果图 `design/mockups/market-upgrade/市场升级.html`（11 屏，Chromium 实机逐屏渲染校验）。范围：默认页签/全局搜索/tag 过滤/排序分页/卡片重构/「我安装的」/更新聚合/批量更新/版本说明与历史/详情结构化/编辑信息；不改安装即复制与指纹判更新。本版按代码修正初版三处前提：安装趋势无时间序列（install 记录删后重插）、MCP 无更新语义、新版本表必须进 GLOBAL_TABLES。**2026-11-10 拍板**：①安装趋势/最近安装人要做（P2 加 `c_ai_market_install_log`）；②批量更新允许含技能、默认不勾选；③标签 chips 用当前类型已加载条目推导；④发布说明强制必填；⑤侧栏常驻更新计数。**一期 P0 已落地（未提交）**：后端 list 增 sort/page/pageSize/total，前端默认落点、全局搜索分组、tag 过滤、排序分页、卡片重构、详情收敛为介绍/内容、文案清理；8091 jar + 5820 前端真机回归通过（默认落点/空态跳转/MCP 四态/标签/搜索钻取/详情/我的上架/暗色/1280 两列/安装与清理）。**二期-四期同日落地（未提交）**：installed/updates/versions/meta/mine-stats 端点 + `c_ai_market_version`/`c_ai_market_install_log`（已进 GLOBAL_TABLES，版本表加 `released_at_ms` 修同秒排序）+ 发布说明必填与版本历史 + 「我安装的」/批量更新/侧栏红点 + 详情结构化（技能正文+附件清单/应用模板能力摘要/工作流只读画布/MCP 参数表）+ 编辑信息 + 安装趋势与最近安装人；顺带修复官方模板种子指纹每次启动漂移（`Map.of`→有序 Map）与下架后装过的人无法看详情。验证：`mvn package`、`npm run build` 通过；API E2E 全链路通过；Playwright 真机通过（更新说明、批量更新技能默认不勾选且提交禁用、版本区间、只读流程图、编辑信息、统计、发布说明必填），无 console error；测试数据经 API 清理。**补充审计**：能力摘要/引用明细、MCP 参数表、下架后已装详情可看但不可安装/下载、`/updates` 排除下架、非作者 meta 与普通用户 MCP 目录 403、MCP 已装以真实配置为准（孤记录已过滤）；默认落点在无技能时落工作流，26 条已装首屏 24→加载更多 26 无重复，1600 宽三列。**遗留**：存量条目无历史版本行；开发库唯一技能条目快照 zip 丢失（存量数据）。 |
| 架构与功能加固 | 🚧 S0 ✅ → S1 ✅（S1-1 ✅ 本地 5/5 / S1-2 ✅ / S1-3 ✅ / S1-4 ✅）→ S2 🚧（S2-1 ✅ / S2-2 ✅ / 前端分层 ✅（只留布局））→ S3 🚧（两档 profile ✅ / S3-3 ✅ / S3-4 合并 ✅ + 门禁服务端 ✅ / S3-2 ✅ / S3-5 待做）2026-11-12 | 2026-11-12 架构/引擎/前端/产品四路评审后排定，见下方《加固排期（2026-11-12）》；S0 止血已本地完成并过全量测试与双启动烟测 → S1 地基（S1-1 本地 5/5 关键路径 ✅ / S1-2 事件契约单源 ✅ / S1-3 配置基座/三层契约/隐形键/类型化常量 ✅ / S1-4 关键写路径事务 ✅（Goal/Market/Document/Session 本地 MySQL 集成测试全齐））→ S2 结构（S2-1 ✅ / S2-2 ✅ / 前端分层 ✅（只留布局））→ S3 收敛（两档 profile：企业面可见性/路由/后端 404 已完成，首启默认与引导待做 / 深度执行 / 市场与产物收口 ✅ / 质量页合并 ✅、门禁服务端 ✅）。**退役口径：重构直接删旧逻辑，不做兼容/迁移/兜底** |

## 加固执行快照（2026-11-12；已入库）

> 已完成的代码与验证；下一轮从这里继续，不要重做已完成项。提交边界见下方「未提交清单」。

**已完成（均有本地验证）**：

- **S0-1** Flyway V1 基线 + V2 删 `agent.workflow_id`；存量库 baseline，SchemaMigrator 退役列防回填；双启动实测。
- **S0-2** 租户/异步身份 fail-closed：`TenantResolutionException`、拦截器 403、线程池透传、分享 fail-closed。
- **S0-3** 单副本声明 + Redis 轮锁 + 启动清理遗留锁。
- **S0-4** 消息只写 MySQL、失败 503；事件失败计数；`SessionConsistencyReporter` 只报不修。
- **S1-1** 本地 5/5 关键路径：会话写入、删除、租户隔离、事件账本、审批恢复；另有 Flyway/Redis IT。
- **S1-2** 事件契约单源：`RunEventType` 元数据 + 生成前端表 + 契约测试。
- **S1-3 ✅**：schema 基座 + 键/值/读取点三层契约 + 20 个隐形键登记与 `ConfigKeys` 类型化常量 + 删 110 条冗余字面量；读取键契约同时识别字面量与常量。
- **S1-4 ✅**：Goal 生命周期事务、Market 发布收窄、Document purge 核心 DB 同事务、Session 删除同事务。集成测试全齐（真实 MySQL）：`GoalLifecycleLocalTest`（pause/resume/edit/cancel/delete 五步落库回读）、`MarketPublishLocalTest`（条目+版本同事务，版本写失败条目回滚）、`DocumentPurgeLocalTest`（知识块/版本/文档同事务，版本删失败整体回滚）、`SessionWriteLocalTest.deleteRollsBackSessionWhenMessageWriteFails`（消息写失败会话软删回滚）。
- **S2-1 退役 chatflow**：桥/旁路/字段/UI 全删，V2 迁移；单一问答路径。
- **S2-2** 计划/压缩瞬时态 Redis；`pendingOwner` DB 兜底；孤儿轮 `OrphanTurnRunner`；实例态按设计不持久化。
- **S3-1 可见面+门禁**：`wenqu.deployment.profile`、`/auth/me` 下发、标准档侧栏/路由隐藏、`@EnterpriseOnly` 企业端点 404；标准/企业双档实测。

**验证基线（2026-11-12 最终）**：后端 `mvn -o -DexcludedGroups=container test` **311 通过 / 0 失败 / 0 跳过**（含 V3 菜单企业面迁移）；前端 `npm test` 393/393、`npm run build` 12.1s；JS SDK 43/43；标准档隔离库启动烟测通过（596 端点、`/tenant/list` 404、`/auth/me` 401）；postbuild 首屏预算通过（入口闭包 248.1KB gz / 300KB，antd 隔离为已知告警）；H5 窄屏矩阵 25/25、登录/通知/分享 15/15、设备矩阵+暗色 8/8；H5-10 主聊天页与五页白名单无名按钮 0；最终启动烟测 596 端点、`/tenant/list` 404、`/chat/deep` 401。**本轮追加验证**：前端 `npm test` 419/419（原 393 + 新增 26）、`npm run build` 10.3s、首屏预算 248.2KB gz / 300KB 通过；`@vue/compiler-sfc` 静态核对 67 个接线变量全部在 `<script setup>` 绑定中，`fetchToDataUrl` / `resolveEffectivePermission` / `permissionLabelOf` 无残留引用。**「只留布局」本轮验证**：前端 `npm test` **535/535**、`npm run build` 10.6s、首屏预算 248.2KB gz / 300KB；524 个模板绑定零缺失，`<script setup>` 内 watch/computed/onMounted 计数为 0（2230→570 行）；scope 与 ctx 键静态核对无真实缺口；setup 级模拟执行成功（825 绑定、0 Vue 警告），抓修 1 个 TDZ（router/route 被删段带走却在共享上下文提前引用）；ChatPage 分包 174.7KB（gzip 65.8KB）。**实机冒烟（2026-11-12 晚）**：隔离库 + 本地后端 + Chrome headless 实测 PC/H5 渲染、命令面板、加号面板、发送链路，零 console 错误（详见「未完成」第 1 项）。

**未完成（按优先级，2026-11-12 回写）**：

1. S2-3 收尾：**「ChatPage 只留布局」已落地**——`<script setup>` 2230→570 行，watch/computed/onMounted 全部归零，页面只剩 imports、组件注册、共享 DOM ref 与 8 个 composable 的解构接线；模板 1748 行与样式 1368 行保留为布局。新增 5 组纯函数 + composable（`chatChrome.js`/`useChatChrome.js`、`commandPanels.js`/`useCommandPanels.js`、`chatActions.js`/`useChatActions.js`、`planPanels.js`/`usePlanPanels.js`、`deepAsk.js`/`useDeepAsk.js`）与 5 组 Node 测试；引擎装配提前 + `page` 惰性容器解决跨域 TDZ，`composerRef`/`editingRef` 由布局层共享。**实机已验（2026-11-12 晚）**：隔离库 `wenqu_e2e_20261112` + 后端 8090 + 前端 5801 + Chrome headless——PC 1280×860 与 H5 390×844 渲染正常、侧栏与输入区完整、无横向溢出（scrollW=innerW）、命令面板按 `/` 列出 `/compact` `/deep`、加号面板「添加到本轮」四条目齐全（上传文件/引用资料/引用历史问答/快捷命令）、发送链路进引擎并提示「未指定模型」、**零 console 错误 / 零 unhandled rejection**。**仍缺**：带真实模型的流式回答与历史回放（隔离库无模型配置）。
2. H5-16 视觉 token（只在效果图确认界面内收敛）。
3. H5 真机签收：微信内置浏览器、PWA standalone、200% 字体、离线/弱网。
4. S3-1 首启 profile 默认值/引导（依赖供应商 key 与执行器，需产品拍板）。
5. S3-5 权限两层化（等 RBAC 批次 F / M7）。

**本轮提交（2026-11-12）**：`c082f212`「只留布局」17 个代码文件（ChatPage 1 改 -2033/+374、`useEditResend.js`/`useChatChrome.js` 共享 ref 小改、`chat/` 10 新增、`test/` 5 新增）加本文件回写；未部署。此前已入库 `39549f20` / `d56beeaa`。

## 待拍板 / 已拍板记录

> 带 ✅ 的是本轮已定，未标注的仍待拍板。

- 线 C 机器身份：把已有 `c_ai_service_account` 升为对外一等主体（先核查 `svc:` uid 在会话归属/配额/租户过滤/沙盒/记忆链路的假设，再给迁移清单）；**不推翻**「API Key 恒绑签发人」的 11-02 拍板，也不上 OAuth2 授权服务器
- KB 导出导入通用化两路线：纯文档 zip vs Dify 格式
- 游客审批卡 fail-closed + 数据字典注入
- 分享安全：P3 费用面 / P4 注入面（P1 ClientIp、P2 share-read 桶已修）
- 工作区上下文装配 0.4s（延迟线尾巴）
- FormCopilot「copilot 切 API Key」——已初评语义倒退，待终裁
- 多租户刀二前置：供应商口径（租户自备 key BYOK vs 平台统一供 key）+ 租户定位（外部客户 vs 内部分域）——见 `design/plans/多租户体系完善方案.md` §4
- P1 邮件通道是否平台必备（定位纯内部工具则可只保留邀请链接复制、不做 SMTP）；见 `P1必补能力方案.md` §7
- P1 OpenAI function calling：**已按只读交集实现**（副作用工具不暴露、未声明工具不返回）；本机 E2E 受模型/工具轮挂起限制，待线上复验；见 `P1必补能力方案.md` §3.4
- P1 工作流游客放行：**已按 B 档落地**（只读节点集 + 装配/执行双闸 + chatflow 游客闸）；C 档仍等分享安全 P3/P4
- **加固 D1 部署形态（✅ 2026-11-12 拍板）**：单副本部署；但代码不得把轮状态硬写成内存态——统一走可持久化/可恢复接口（Redis 为运行态唯一存储，Redis 不可用 fail-loud；MySQL 只存业务记录，不做故障兜底切换），重启可恢复；不为多副本做分布式协调。S0-3 取单副本口径，S2-2 改为「重启恢复」而非「多副本共享」
- **加固 D2 产品档位（✅ 2026-11-12 拍板）**：只做两档——**标准档**（个人 + 小团队，邀请/共享/RBAC 用开关）与**企业档**（多租户/SSO/SCIM/审计/配额/成本）。不是三套代码，是同一套代码的「可见面 + 默认值」；存量库按企业档兜底，新装默认标准档
- **加固 D3 概念合并（✅ 2026-11-12 拍板）**：三刀全做——① `/plan` + `/goal` 合并为 `/deep`（深度执行），旧命令/旧端点/旧事件直接删除，不做别名/双发；② 智能体取消「运行方式」三选一，`isSubagent` 降为「可被委派」开关、`workflowId` 运行分支删除、新建只走「工作流工具」；③ 市场只做发现/安装/更新/发布/下架，技能/MCP/工作流管理收口 `/agents`。开发阶段不迁移旧数据

## 加固排期（2026-11-12 评审后排定）

> 来源：2026-11-12 架构 / 引擎 / 前端 / 产品四路评审。只排影响「数据正确性、越权、可部署性、改动成本」的项；样式、命名、单点体验进「顺手核销」，不占迭代。
> 假设：1 名主程 + AI 并行，2 周一个迭代；P0 不夹带重构；每项必须有可执行验收，不写「优化完成」。
> **退役口径（2026-11-12 用户拍板）**：本次所有重构直接退役旧逻辑——不写兼容层、不做双读双写、不留隐藏别名/回退开关/「旧配置」运行时分支。开发阶段旧数据不迁移、不归档；dev 库直接重建/清空，云端执行破坏性变更前先备份并单独确认。失败一律 fail-loud，不静默兜底。

### 总览

| 迭代 | 窗口 | 主线 | 出口标准 |
|---|---|---|---|
| S0 止血 | 2026-11-12 ~ 11-25 | 启动迁移、租户 fail-closed、单副本声明、三存储一致性 | 重启零 DML；DB 抖动不越权；多副本配置 fail-fast；重启可恢复；故障不产生 Redis-only 消息 |
| S1 地基 | 2026-11-26 ~ 12-09 | 集成测试、事件契约单一表、配置单一源、事务边界 | 5 条关键路径进 CI；新增事件只改一处；schema/defaults 无漂移 |
| S2 结构 | 2026-12-10 ~ 12-23 | TurnCloseout + 退役 chatflow、轮状态恢复、前端聊天域分层 | 框架引擎是唯一路径；重启可恢复；useChatEngine 按四层拆出 |
| S3 收敛 | 2026-12-24 ~ 2027-01-13 | 两档 profile、深度执行、市场与产物收口、质量闭环 | 首启 10 分钟内可用；同一任务只有一个推荐入口；门禁服务端可拦 |

### S0 止血（P0，不夹带重构）

| 编号 | 事项 | 归属 | 估时 | 验收（可执行） |
|---|---|---|---|---|
| S0-1 ✅ | `schema.sql` 迁移治理：摘掉启动期 20 条破坏性 DML/DDL，只留 DDL 基线；引入 Flyway baseline，旧表/旧列直接删（dev 库重建），不写历史迁移兼容 | 新（架构债） | 3–4d | 连续重启两次 SQL 日志零 DML；新库/重建库都能起；`flyway_schema_history` 有基线 |
| S0-2 ✅ | 租户与异步身份 fail-closed：归属查询异常不缓存 MISS、不落 null；无身份不再注入空谓词；线程池统一透传 `RequestUser`/`TenantContext` | 多租户完善（刀零） | 2d | 模拟 DB 异常返回 403/空集而非全库；异步写落对租户；3 个回归测试 |
| S0-3 ✅ | 单副本声明 + 状态存储契约：启动校验副本数/共享卷并 fail-fast；新增轮状态必须走 `TurnStateStore`（Redis 为运行态唯一存储，不可用即 fail-loud，不写 MySQL 兜底分支），禁止新增无界 static map；SSE/emitter 等传输态可进程内，业务态必须可恢复 | 问答流水线重做 | 2d | 多副本配置启动 fail-fast；重启后未完成轮/深度执行卡/审批可恢复或明确失败；代码评审无新增无界内存态 |
| S0-4 ✅ | 三存储一致性止血：删除「MySQL 失败降级写 Redis 消息」分支且不设替代兜底；消息/事件写失败 fail-loud + 计数告警；加只报不修的对账任务 | 问答流水线重做 | 2–3d | 模拟 MySQL 故障时请求明确失败、不产生 Redis-only 消息；对账能列出差异；告警可观测 |

小计 9–11 人日（D1 已定单副本，不含多副本协调）。

**S0 完成记录（2026-11-12，本地未提交）**：

- **S0-1**：`schema.sql` → `db/migration/V1__baseline.sql`；接入 Flyway 11.7.2（`flyway-core` + `flyway-mysql`），`baseline-on-migrate=true`；`spring.sql.init` 关闭。实测两次启动：首次存量库 baseline 到 V1、二次 `No migration necessary`，启动 5.96s / 7.27s；`SchemaMigrator` 改读 V1 基线，只补列/索引。
- **S0-2**：新增 `TenantResolutionException`；拦截器对「真实身份但无租户」fail-closed（403），解析期内部查询放行防递归；`TenantService.tenantOf` DB 异常不再缓存/返回 null；`ThreadPoolManager` 捕获并恢复 `RequestUser` + 已绑定租户；分享归属解析失败改 404。新增 13 个测试。
- **S0-3**：新增 `DeploymentGuard`（`wenqu.deployment.mode=single`，multi 启动 fail-fast）；新增 `TurnStateStore` + `RedisTurnStateStore`（Redis 租约 + 单副本启动清理遗留锁），`ChatRunRuntime` 去掉 static `TURN_IN_FLIGHT`；`ReplicaCoordination.acquireOrThrow` 让存储故障 fail-loud。新增 11 个测试。
- **S0-4**：`SessionService.appendMessage` 删除 Redis 降级写，失败重试一次后 503；删除 Redis 读侧合并/补写与 `backfillInsert` 死代码；历史读取失败改 503（不再返回空历史），`SessionShareService` 删除 null 兜底；事件账本失败改 ERROR + 累计计数；新增 `SessionConsistencyReporter` 每小时只报不修。新增 3 个测试。
- **验证**：`mvn -o test` 全量 266 通过；本机 8099 双启动烟测通过；README 多副本口径改为单副本。

### S1 地基（P1，先有测试再动结构）

| 编号 | 事项 | 归属 | 估时 | 验收（可执行） |
|---|---|---|---|---|
| S1-1 ✅ | 集成测试骨架：Testcontainers（MySQL+Redis）+ 5 条关键路径。**本地 5/5 已实跑**：会话写入、会话删除、租户隔离、事件账本、审批恢复；另有 Flyway 空库、Redis 轮锁两条基础设施 IT。容器版/CI 待验证 | 测试地基 | 4–5d | `mvn test` 在 CI 必跑；5 条路径任一破坏即红 |
| S1-2 ✅ | 事件契约单一真源：`RunEventType` 携带 ledger/mirror/前端文案属性，前端表由一份常量生成；未知事件 WARN + 埋点 | 对外集成（契约治理 C） | 2–3d | 新增事件只改一处；契约测试自动覆盖三张表；未知事件不再静默 |
| S1-3 ✅ | 配置单一源：由 `config-schema.json` 生成 `defaults()` 与类型化常量；消灭不可见键与魔法默认；README 260 / schema 148 口径对齐。**已完成**键契约（148 ↔ 202，54 个隐藏项白名单）+ 值契约（126 条字面量零差异，38 条动态默认白名单）+ `ConfigSchemaService.storedDefaults()` 基座 + 20 个历史隐形读键登记并收成 `ConfigKeys` 常量 + 读取键契约同时识别字面量/常量 + 删除 110 条 schema 冗余字面量 + README 口径 | 遗留债 | 2–3d | 设置页可见键 = defaults 键 = schema 键；启动自检不一致即 fail |
| S1-4 ✅ | 关键写路径事务边界：GoalService、MarketService 发布、Session/Document 删除与回填。GoalService 5 条生命周期事务、MarketService.publish 收窄、DocumentService.purge 核心 DB 删除同事务、SessionService.deleteSession 会话+消息软删同事务；四类真实 MySQL 集成测试覆盖成功写与失败回滚 | 问答流水线重做 | 2–3d | 多表写失败可回滚 ✅；打 zip 等慢操作移出事务 ✅；集成测试覆盖 ✅ |

小计 10–14 人日；紧张时 S1-4 只做 Goal/Session 两条。

**S1 完成记录（2026-11-12，本地未提交）**：

- **S1-1 ✅（本地 5/5）**：接入 Testcontainers 1.21.4（`container` 标签，本地用 `-DexcludedGroups=container` 排除）；本地 MySQL/Redis 集成测试已覆盖全部 5 条关键路径：`SessionWriteLocalTest`（会话写入 + 软删）、`TenantIsolationLocalTest`（真实租户拦截器：隔离/系统跨租户/fail-closed）、`SessionEventLedgerLocalTest`（事件账本真实落库 + 开关关闭不写）、`ApprovalRecoveryLocalTest`（重启后 PENDING 卡恢复、live=false、本人裁决落库、越权拒绝）；另有 `FlywayLocalDatabaseTest`（空库 V1+V2、V2 删列）与 `RedisTurnStateStoreLocalTest`（DB 15 轮锁）。全量 277 通过、0 跳过。**容器版/CI 仍待验证**。
- **S1-2 ✅**：`RunEventType` 增加 callback/label/tone/sseLedger/mirror 元数据；`ChatRunRuntime` 的 ledger 名单与 `ChatRunBus` 的 mirror 名单改为从枚举派生；新增生成器 `RunEventContractGenerator`，产出 `web/src/chat/eventContract.generated.js`；`api.js` / `processEvents.js` 改用生成表；契约测试 5 项 + 生成器校验。后端 270、前端 20、`npm run build` 全绿。
- **S1-3 ✅**：`ConfigContractTest` 覆盖键、值、读取点三层：schema 148 ↔ defaults 202、54 个隐藏/部署项白名单；126 条字面量零差异，38 条 env/properties 动态默认进 `DYNAMIC_DEFAULTS`；`ConfigSchemaService.storedDefaults()` 作为 `defaults()` 基座；20 个历史隐形读键登记为隐藏默认值并收成 `ConfigKeys` 类型化常量，读取键契约同时识别字面量与常量；删除 110 条 schema 冗余字面量（88 条行尾说明保留为注释，16 条隐藏字面量保留）。全量 288 通过；启动烟测 5.06s。
- **S1-4 🚧**：GoalService 的 pause/resume/edit/cancel/deleteRecord 加 `@Transactional`（多表写原子化，新增契约测试防回退）；MarketService.publish 去掉方法级事务，改用 `TransactionTemplate` 只包住多表写，zip/快照文件 I/O 留在事务外。**待做**：DocumentService 删除/回收站补偿、其余长事务；`mvn -o test` 273 通过、2 跳过。

### S2 结构（P1/P2）

| 编号 | 事项 | 归属 | 估时 | 验收（可执行） |
|---|---|---|---|---|
| S2-1 ✅ | 退役 chatflow 绑定 ✅ + TurnCloseout 抽离 ✅：已删 `ChatService` 旁路、`workflowBoundOf`/`runWorkflowBoundTurn`、`ChatflowBridge`、`Agent.workflowId`（V2 迁移删列）、编辑页「工作流」运行方式；存量绑定不迁移。**TurnCloseout 纯逻辑 ✅（同日）**：中断尾标优先级、正文/notice 拼装、是否落库抽成 `agent/chat/TurnCloseout` + 5 项单测。**I/O 首刀 ✅**：消息落库 + 问答台账收成 `TurnPersister`（同一份定稿快照、同一道 persist/realAnswer 判据，3 项单测）；**webhook 口径 ✅**：轮终状态映射收进 `TurnCloseout.webhookStatus` + 单测；**通知原因 ✅**：提前结束原因级联收进 `TurnCloseout.earlyExitReason` + 单测；**引用自检闸 ✅**：`citationsApplicable` 收进 `TurnCloseout` + 单测；**done 发射**：载荷由 `TurnDonePayload` 单源组装，`finish` 只做一次 `channel.emit`——收尾 I/O 已全部有单一归属 | 问答流水线重做 | 1d ✅ + 4–5d | 框架引擎是唯一问答路径；代码中无 workflowId 运行分支；无「工作流」运行方式入口；分享/通知/webhook 回归 |
| S2-2 ✅ | 轮状态恢复：followers / pendingOwner / planOutcomes / compactRequests 落 Redis 并设 TTL（不写 DB 兜底分支）；启动时用 OrphanTurnRunner 对账；重启后深度执行卡终态与审批可恢复。**已完成**：planOutcomes / planRevising / planFeedbacks / compactRequests / rebuildAfterCompact → `TransientSessionState`（Redis, 2h TTL）；`pendingOwner` 在 `ownerOf` 已有 `hitl.findApproval` DB 兜底；孤儿轮由 `OrphanTurnRunner` 接续；`followers/stepBudgets/repeatGuards/turnSlots` 是实例绑定运行时态，按设计不持久化（持久化会制造假在线） | 问答流水线重做 | 2–3d | 重启后未完成轮可恢复或明确失败；无无界内存表；不引入分布式协调 |
| S2-3 ✅ | 前端聊天域分层（**首批已落地 2026-11-12**；**四层第一刀 2026-11-12**：新增 `chatTransport.js` 收口引擎全部 `api.js` 调用；**重连状态**抽成 `streamLifecycle.js`（探测去重/重试计数/已收场轮）；**接流快照 reducer** 抽成 `streamReducer.js`（引擎经 ctx 注入状态）；接流入口 `attachRunningTurn` 抽成 `streamAttach.js`（依赖全注入，5 组测试）；早产卡/接流气泡收场并入 `streamReducer.js`（2 组测试）；**actions 层开工**：目标动作 `goalAct` 抽成 `goalActions.js`（依赖注入，4 组测试）；提问卡答题口径并入 `askUser.js`；提问卡提交/忽略抽成 `askActions.js`；工具审批裁决抽成 `approvalActions.js`；提问卡勾选/自定义/提示并入 `askUser.js`；提问卡构建/排队/撤卡抽成 `askReducer.js`；计划卡批准/拒绝/退回重出/批准后调整（replan/插话二选一，含停止并重开轮控制流）抽成 `planActions.js`（共 26 组测试）；思考档位状态（按模型记忆/可选项/下发参数）抽成 `thinkingLevels.js`（9 组测试）；技能清单/点名/头像底色抽成 `skillPicker.js`（5 组测试）；智能体清单/会话待选/当前作答方/徽标去重抽成 `agentPicker.js`（7 组测试）；模型选择状态与生效模型口径抽成 `modelSelection.js`（6 组测试）；模型切换/上下文压缩分隔线判据抽成 `turnDividers.js`（5 组测试）；重新生成/本地版本切换/持久分支切换抽成 `versionActions.js`（11 组测试）；工具卡中断定格/停止标注抽成 `turnCloseout.js`（5 组测试）；运行中插话队列（入队/立即发送/编辑/排序）抽成 `steerQueue.js`（9 组测试）；停止本轮动作抽成 `stopTurn.js`（4 组测试）；历史回显映射（过滤/字段归一/计划卡与时间线重建/插话行退场）抽成 `historyRows.js`（7 组测试）；会话生命周期（新建复用空会话/删除落点/自动落点）抽成 `sessionActions.js`（9 组测试）；发送主流程（载荷组装/上传门禁/插话分支/清输入/开流）抽成 `sendFlow.js`（7 组测试）；计划卡中断收尾（退回重出恢复原稿/落已停止）抽成 `planAbort.js`（5 组测试）；流失败收尾（保留半程内容/错误卡/卡片收场/toast 抑制）抽成 `turnFail.js`（5 组测试）；会话切换编排（历史加载/在途复用/失败回退/接流与卡片恢复）抽成 `sessionSwitch.js`（9 组测试）；done 收尾（卡片收场/收束语/来源用量/侧栏归位/多版本）抽成 `turnDoneCloseout.js`（6 组测试）；done 载荷合并（计划终态/插话结算/编辑回填/工具终态/时间线与显示块）抽成 `applyDonePayload.js`（10 组测试）；工具状态帧（早产卡/原地升级/终态认领/实时输出）抽成 `streamToolFrames.js`（9 组测试）；低频信息帧（任务清单/用量预览/检索/技能命中/审批卡/脚本进度/@ 归属）抽成 `streamInfoFrames.js`（7 组测试）；计划卡实时帧（草稿增量/批准终稿/被拒取代）抽成 `streamPlanFrames.js`，`planText` 下沉 `planText.js`（7 组测试）；流式文本/过程缓冲（节流落屏/接流去重/过程段累积）抽成 `streamBuffer.js`（7 组测试）；思考帧（成段/接段/终稿校正/断段）抽成 `streamThoughtFrames.js`（7 组测试）；快照运行期增量回放抽入 `streamReducer.js`（`replaySnapshotExtras`，2 组测试）；发送参数装配抽成 `turnRequest.js`（4 组测试）；子智能体步骤帧（完整过程抽屉/当前步骤/迷你时间线）抽成 `streamSubagentFrames.js`（7 组测试）；压缩/执行计划/目标态/跨轮续跑小帧抽成 `streamStateFrames.js`（5 组测试）；思考面板/胶囊文案（强度行/底部提示/胶囊标记与说明/档位豆）下沉 `thinking.js`（ChatPage 与移动壳共用，5 组测试）；思考面板交互（触屏 sheet/悬浮延迟/定位收口/开合联动）抽成 `useThinkPanel.js`（8 组测试）；滚动贴底/尾随留白/右轨锚点抽成 `useChatScroll.js`，锚点数据源抽成 `chatAnchors.js`（9 组测试）；事件抽屉/来源折叠/容量浮层抽成 `useChatPanels.js`（6 组测试）；右栏计划列表投影抽成 `planPanel.js`（4 组测试）；权限档位解析（会话>智能体>平台默认、上限回落）抽成 `permission.js`（5 组测试）；引用角标联动/行跳转抽成 `useSourceFocus.js`（4 组测试）；另做一次引擎工厂装配的初始化期读序审计（TDZ），全部实参为惰性包装或声明在前。后续：SSE 建流/接流主流程与 actions 继续迁出；深度执行状态/文案从 `projections.js` 抽成 `deepState.js`，命令解析抽成 `deepCommand.js`，交付包聚合抽成 `delivery.js`（体积格式化下沉 `utils/format.js`），引用来源口径抽成 `sources.js`、窗口/用量抽成 `usage.js`、附件卡片口径抽成 `attachmentCard.js`，消息文本/时间/错误文案抽成 `messageText.js`、重新生成多版本抽成 `versions.js`、工具卡展示口径抽成 `tools.js`、提问卡投影抽成 `askUser.js`、交付包会话聚合抽成 `deliveries.js`、终态标注/降级口径抽成 `turnStatus.js`/`degradation.js`、委派/咨询口径抽成 `consult.js`、本轮计时抽成 `turnTiming.js`（`durZh` 下沉 `utils/duration.js`）、正文/插话分段抽成 `turnBody.js`、过程聚簇抽成 `processCluster.js`、过程块投影抽成 `turnProcess.js`、折叠/可见性判据抽成 `turnFold.js`、状态行标题抽成 `turnSummary.js`、工具摘要/实时输出抽成 `toolBrief.js`、扇出看板/运行水位抽成 `runBoard.js`、工具终态合并抽成 `toolCalls.js`、时间线写入口/恢复抽成 `timelineWrite.js`、思考/过程段折叠抽成 `segments.js`、分组条/行尾 note 抽成 `notes.js`、工具卡开合/定位抽成 `toolCard.js`、检索词/检索行抽成 `retrieval.js`、秒针生命周期抽成 `chatTick.js`、归属徽标/思考档位抽成 `agentBadge.js`/`thinking.js`、交付包下载抽成 `deliveryDownload.js`，并补前端事件契约测试；新增 373 组 Node 测试；后续按四层继续拆）：useChatEngine 拆 transport/reducer/selectors/actions；深度执行、沙盒目录、交付各自 composable；事件表统一生成 | 新（前端结构） | 6–8d | ChatPage 只留布局；新增事件只改一处；流式与历史回放回归。**四层第二刀（39549f20 / 2026-11-12，ChatPage 视图逻辑）**：编辑重发（`editResend.js` / `useEditResend.js`，13 组测试）、目标与交付面板（`goalPanels.js` / `useGoalPanels.js`，6 组测试）、工作区摘要与会话权限档位（`workspaceInfo.js` / `useSessionWorkspace.js`，7 组测试）三族下沉；ChatPage 5757→5348 行（脚本 2639→2230），三处只留依赖注入式解构接线。顺带修正编辑卡异步抓图的回填判据（ref 数组元素是响应式代理，按对象身份比较永远不成立）。**四层第三刀（c082f212 / 2026-11-12，验收「只留布局」）**：上述剩余视图逻辑连同页面壳层、chip 同步、分享/反馈/固化/调试、深度执行与提问面板、初始化 watch 全部下沉为 5 组纯函数 + composable；`ChatPage.vue` 5348→3689 行，`<script setup>` 2230→570 行且只含装配；引擎装配提前、`page` 惰性容器与共享 DOM ref 解决跨域顺序；新增 116 组 Node 测试，全量 535/535。剩余仅真机回归 |

小计 13–17 人日；S2-3 允许跨迭代。

**S2 退役记录（2026-11-12，本地未提交）**：

- **S2-2 ✅**：新增 `TransientSessionState`（Redis，2h TTL），把 `FrameworkSessions` 里的 `planOutcomes / planRevising / planFeedbacks / compactRequests / rebuildAfterCompact` 五簇瞬时状态从进程内 Map/Set 移出；`take*` 用 `getAndDelete` 原子取走，销毁时整簇清理。`pendingOwner` 已由 `ownerOf` 的 `hitl.findApproval` DB 兜底；孤儿轮由 `OrphanTurnRunner` 接续；`followers/stepBudgets/repeatGuards/turnSlots` 是实例绑定运行时态，按设计不持久化。单测 4 项；全量 288 通过；本机 8099 启动烟测 5.05s，Spring 注入正常。

- **S2-1 退役 chatflow ✅**：后端删 `ChatflowBridge` / `ChatService` 旁路 / `workflowBoundOf` / `runWorkflowBoundTurn` / `workflowMapper` / `Agent.workflowId`；`AgentService` 与 `MarketService` 的绑定与模板 DSL 路径一并删除；新增 `V2__drop_agent_workflow_id.sql`（V1 保持基线不动），`SchemaMigrator` 加 retired-columns 防回填；前端 AgentsPage 运行方式收为「问答助手 / 可被委派」，绑定下拉与全部 `workflowId` 载荷删除。验证：后端 273、前端 20、`npm run build` 全绿；本机 8099 启动烟测 Flyway V2 执行、4.7s 启动、SchemaMigrator 补列 0。

### S3 收敛（P2，D2/D3 已拍板）

| 编号 | 事项 | 归属 | 估时 | 验收（可执行） |
|---|---|---|---|---|
| S3-1 🚧 | 两档 profile：**标准档**（个人+小团队；邀请/共享/RBAC 用开关，默认关）与**企业档**（多租户/SSO/SCIM/审计/配额/成本）；dev 新装默认标准档，企业档只由启动配置决定，不做存量判定与运行中切换。**已做**：`wenqu.deployment.profile` 启动配置、`DeploymentGuard` 校验与下发、`/auth/me` 返回 profile、标准档侧栏隐藏 `/tenants` `/security`、路由守卫拦截直链、**菜单库 `enterprise_only`（V3）与 MenuService 按 profile 过滤**（企业面菜单不进 standard 侧栏与菜单管理列表）、`@EnterpriseOnly` + 拦截器让企业端点直接 404（实测 `/tenant`、SCIM 404，`/tenant/availability` 200）、标准档配置引导新增本机执行器可选步骤；**待做** 首启 profile 写入默认（沙盒/联网/向量模型） | 新（产品收敛） | 3–4d | 新实例 10 分钟内完成一次带工具的问答；标准档看不到 `/tenants`、SCIM、审计；切换档位只改启动配置 |
| S3-2 ✅ | 深度执行入口（第一刀）：`/plan` + `/goal` 合并为 `/deep`——复杂任务先出计划、长任务自动续跑；过程里合并为一张「深度执行」卡；对外只暴露 `/deep*` 端点，删除 `/plan-approval/*` 与 `/goal*` 旧端点；事件词汇收敛为 `deep_*`，旧 `plan_*`/`goal_*` 删除、不双发；`goalEnabled` 改名「允许深度执行」（默认开）；旧命令、旧卡片、旧事件处理分支全删。**已落地（2026-11-12）**：前端唯一 `/deep`（别名 `/深度`）命令与 H5 唯一入口，`/plan`、`/goal`、计划 chip、移动端计划开关全删；后端 `DeepController` 暴露 `/chat/deep*`，计划裁决收进 `/deep/plan-approval*`，旧 `/chat/goal*`、裸 `/plan-approval*` 映射删除（`DeepEntryContractTest` 防回潮）；SSE 词表改 `deep_plan_delta/deep_plan_approval/deep_plan_cancelled/deep_plan_superseded/deep_state/deep_turn`，目标账本改 `deep_verify/deep_note/deep_outcome`；智能体开关、执行条、H5、通知文案统一「深度执行」；JS SDK 路径/事件、README、应用内手册（02/03/14）、压测脚本与 Java 注释口径同批更新，设置页分组改名「深度执行（长任务）」，`ai_plan_mode` 旧 localStorage 键不再读取。**首轮计划先行 ✅（同日）**：`AgentscopeChatEngine.openForContinuation` 为目标第 1 轮注入 `planMode=true`，与手动计划模式共用同一道 plan 门禁，批准后同一轮继续执行；后续轮不再重复出计划。**全部收口（同日）** | 新（产品收敛） | 6–8d | 输入 `/` 只有一个深度执行入口 ✅；旧 `/plan`、`/goal`、`/goal*` 端点直接 404 ✅（路由契约测试）；SDK/文档同批改 ✅；计划批准、跨轮续跑、中断、验收四条回归 ✅（mock 网关门禁全链路真机）。**真机入口验证 2026-11-12**：隔离库 `wenqu_e2e_browser` + 8099 + vite 5801，初始化管理员登录后打开 `/` 快捷命令，「添加」面板仅列 `/compact`、`/deep`（深度执行）、导出、新建，旧 `/plan`、`/goal` 不在列表；选中 `/deep` 后输入框出现「深度执行 ×」chip；console 无报错（顺带修掉标准档侧栏仍轮询 `tenant/requests/pending-count` 的企业端点 404）。**真机链路 2026-11-12（隔离库 + mock 网关）**：`CASES=plan` 三档全过——批准（deep_plan_approval → 批准 → done.planRecord=approved）、拒绝（deep_plan_cancelled）、继续对话（deep_plan_superseded 且正文为空）；`CASES=stop,resume` 全过（停止尾标与落库/分享一致、断流快照可重放）。**四回归全过（同日）**：新增 `scripts/loadtest/goal-e2e.mjs` 与 mock 网关 `[[GOAL]]` 两轮分支——`/chat/deep` 建目标 → 首轮计划批准 → `goal_note(remaining)` 触发第 2 轮 → `goal_submit(text evidence)` 通过 Verifier，最终 `ACHIEVED / endReason=achieved / verifier=wenqu-goal-verifier-v1`。**单卡收口 ✅（同日）**：`PlanCard` 增加 `deep` 上下文，深度执行中的计划卡标题改为「深度执行」，它就是本次执行唯一的过程卡（目标条只是状态条）；真机会话页确认显示「深度执行 v1 已批准 2 步」且无第二张过程卡。**真机抓修**：新会话直接 `/deep` 时会话占位落到 `system`，用户打开即「无权访问」——`GoalService.start` 现在先 `ensureOwnedOrCreate`——新会话建到发起人名下，已存在的他人会话直接 403；`SessionWriteLocalTest` 与 `GoalLifecycleLocalTest` 均有回归断言 |
| S3-3 ✅ | 智能体形态收口 ✅（运行方式=工作流删除，`isSubagent` 保留为「可被委派」）+ 市场侧入口收口 ✅（详情页作者「管理原资产」跳 `/agents` 对应 Tab，`编辑信息` 改名 `编辑上架信息`）+ 产物统一预览台 ✅（`previewStage.js` 全局挂载，交付卡/交付抽屉/产物页/变更卡/H5 共用一套 `data-pv` 舞台，同一产物只有一个规范预览） | 市场升级 / H5 | 3–4d | 同一资产只有一处管理入口；同一产物只有一个规范详情页 |
| S3-4 ✅ | 质量闭环：`/evaluation`、智能体评测、看板合成「质量」页三 Tab ✅；门禁 block 下沉服务端 ✅（`agentEval.gateMode`=hint/block，`AgentController` 保存/发布前校验，`AgentEvalGatePolicyTest` 3 项） | 智能体评测 | 4–5d | 指标可下钻到会话；API 层绕过被服务端拒绝（门禁已验，下钻待验） |
| S3-5 | 权限两层化：平台上限 + 会话档；删除智能体档与工具确认三态 | RBAC 批次 F | 3–5d（依赖 M7） | 用户只做一次权限选择；越权由平台上限兜底 |

小计 19–26 人日；D2/D3 已拍板，S3-1/S3-2/S3-3 可直接开工；单主程三周放不下，需并行或顺延到 2027-01-20；S3-5 仍等 RBAC 批次 F（M7）。

**S3 完成记录（2026-11-12，本地未提交）**：

- **S3-4 质量页合并 ✅ + 门禁服务端 ✅**：新增 `QualityPage.vue` 三 Tab（检索评估/智能体评测/数据看板），按管理员身份显隐；`menu-evaluation` 原地复用为「质量」`/quality`，旧 `menu-dashboard` 与 `menu-agents-tab-eval` 保留行但隐藏（避免删行丢 API 归属），user 角色补绑 `menu-evaluation`；旧 `/evaluation`、`/dashboard` 路由删除，看板通知深链改 `/quality?view=dashboard`。验证：后端 288、前端 20、`npm run build` 通过；启动日志确认菜单同步/隐藏/user 补绑生效；启动 5.02s。**门禁下沉**：`config-schema.json` 新增 `agentEval.gateMode`（hint/block，默认 hint），`ConfigKeys.AGENT_EVAL_GATE_MODE` 类型化读取，`AgentEvalService.assertSaveAllowed` 在 `AgentController.update/publish` 内按 below/changed 拒绝（409），`AgentEvalGatePolicyTest` 3 项；后端全量 **291 通过**。

- **S3-2 ✅（2026-11-12）**：`/deep` 合并第一刀落地，详见 S3-2 行；隔离库真机确认命令面板只有 `/deep`、选中出现「深度执行 ×」chip、console 干净（`AppLayout` 标准档跳过租户角标轮询）；mock 网关真机跑通计划批准/拒绝/作废、停止与断流恢复、目标两轮续跑与 goal_submit 验收（`goal-e2e.mjs`）；会话页确认「深度执行 v1 已批准 2 步」单卡与「深度 · 已完成」回声；验证后端 307、前端 171、`npm run build` 通过、JS SDK 43/43；8099 启动烟测通过（596 端点、无 mapping 冲突）。

- **S3-3 ✅**：市场侧作者详情新增「管理原资产」，按 itemType 跳 `/agents?tab=skills|agents|workflow|mcp`；「编辑信息」改为「编辑上架信息」，明确市场只维护分发信息。产物侧核对确认 `previewStage.js` 已在 `main.js` 全局挂载，交付卡、交付抽屉、产物页、变更卡与 H5 全部用同一套 `data-pv` 舞台——同一产物只有一个规范预览，不需要再建第二套详情页。前端 20/20、`npm run build` 通过。

- **S3-1 🚧（可见面+门禁+执行器引导 ✅）**：`wenqu.deployment.profile`（standard/enterprise）启动配置；`DeploymentGuard` 校验并记录档位；`/auth/me` 下发 `profile`；标准档侧栏隐藏 `/tenants` `/security`，直链由路由守卫拦回对话页；新增 `@EnterpriseOnly` + `EnterpriseOnlyInterceptor`，标准档企业端点直接 404（多租户管理、SCIM、服务账号、审计）；标准档配置引导新增「启动本机执行器」可选步骤（`/executor/devices` 的 online 对账、查看说明、就地刷新），不计入聊天进度。验证：后端 286、前端 20、`npm run build` 通过；标准档启动 5.39s，`/api/ai/tenant` 与 SCIM 404、`/api/ai/tenant/availability` 200；企业档启动 5.23s，`/api/ai/tenant` 与 SCIM 均 401（非标准 404）；README/.env.example 已补部署形态与 profile 口径。**待做**：首启 profile 写入默认（沙盒/联网/向量模型）。

**退役与破坏性变更清单（开发阶段，2026-11-12 定）**：

- **不迁移、不归档**：旧计划/目标会话、旧 goal 记录、旧 chatflow 绑定一律不迁移；dev 库直接重建/清空。云端执行破坏性变更前先备份并单独确认。
- **命令/入口**：`/plan`、`/goal` 删除，不留别名；`/plan-approval/*`、`/goal*` 端点删除并直接 404；新入口只有 `/deep`。
- **公开参数**：`planMode` 从 `ChatRequest` 与 OpenAI 兼容文档删除，不保留旧语义；SDK/文档同批改。
- **事件**：`plan_*`、`goal_*` 删除，收敛为 `deep_*`；前端、SDK（js/python）、对接文档同批改，不双发。
- **智能体**：`workflowId` 运行分支与编辑页「工作流」运行方式删除（S2-1 落地）；`isSubagent` 保留为「可被委派」开关，内部字段名暂不改，避免无收益的机械重命名；工作流只保留「工作流工具」。
- **市场/产物**：旧管理入口直接删除，不做重定向；「我的上架」仍在市场；资产编辑/测试/统计回 `/agents`；产物只保留一个规范详情页。
- **profile**：dev 新装默认标准档；企业档只由启动配置决定，不做运行中切换与双读分支。
- **兜底**：所有失败路径 fail-loud；不保留「先试新再回落旧」的分支。
- **内部命名**：`GoalService` / `c_ai_goal*` 作为 `/deep` 的唯一实现保留，不再有 plan-vs-goal 双路径；如需连表名/类名一起改，另加机械重命名项（约 1–2d）。

### 顺手核销（不占迭代，动到相关域时一起做）

- 12 个 controller 直连 mapper：改到相关控制器时顺手下沉。
- `c_ai_scheduled_run` / `c_ai_schedule_run` 职责重叠、全库无外键：只在新表加约束，不做存量重构。
- 1086 处 `catch (Exception)`：只修写路径与资金/配额路径，不做全量。
- 前端 PC/H5 双实现：合并时直接删旧实现，不保留双份；S3-3 之后按 MessageRow/ChatInput 收敛。
- 文档漂移、命名（智能体/助手/应用模板）、产物入口文案：随对应功能改动核销。

### 不排期（明确不做）

- 全局 Pinia / TypeScript / ESLint 一次性重构：风险大于收益，随 S2-3 分域引入。
- 90 表合并 / 全量加外键：只在新表约束。
- 260 项配置全量重命名/重分组：只做单一源与分级，键名不动。
- 真多副本分布式协调：D1 已定单副本，不排；「状态可恢复」仍要做（S0-3/S2-2），两者不是一回事。

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
- **RBAC 升级 v1.0**（2026-11-03~04）：10 提交、判定核 19 测全绿、三态双跑/enforce 全验证；方案在 design/plans/RBAC升级方案.md。坑账：新 RBAC 表必须进 GLOBAL_TABLES（三踩）、种子须反向折算 role_api。
- **每轮文件改动卡**（DSH workspace-changes 口径，2026-11-05）：执行器快照+捕获+比对、c_ai_message.changes、改动卡+预览台 diff 档。
- **问答延迟线**（2026-11-06）：五笔根因修四笔半（沙盒往返 / journal 回读 / 技能扫盘 / 方舟冷连），近五笔提交 b6825773 / 93705109 / a91272ac / d2c91185 等。
- **问答流水线重做 M1/M2/M3**（2026-11-06）：聊天/工作区双档、宿主 scratch、工具面过滤、前缀稳定器、MCP 有界、异步升级、每用户租约、userScope 热复用、启动热池预热。提交 164d3097 / a873bb16 / 22e8380e / f742997b / f4f47ab2 / 8c423b90 / d0fc4fcd；细节与实测见「活跃线·问答流水线重做」。
- **内置工具退役**（2026-11-06）：10 把纯计算工具（calculate/currentDateTime/daysBetween/addDays/randomNumber/uuid/unitConvert/textStats/base64/hash）已物理退役——`@Tool` 实现、描述符、注册入口全部删除，内置族只剩 `askUser`；`BuiltinToolProvider` 改名 `AskUserToolProvider`。前端 `projections.js` 只保留历史消息的工具卡展示名，不影响老会话回显。提交 93d5d6df（先默认收窄）→ 4079f387（物理退役+改名）→ f1d18e1c（族名改 ASK_USER，code=ask_user、显示名「提问工具」）。
- **编排工具第 2 档安全子集**（2026-11-06）：`AgentxToolSurfaceFilter` 按本轮能力收窄工具表——无活跃目标隐藏 goal_note/goal_submit，无子智能体隐藏 agent_*/task_*/wait_async_results；目标续跑轮按 turnId=goal: 兜底。plan_*/skill_* 保留为功能入口（隐藏等于关功能，且 plan 支持模型中途 plan_enter）。本地 1+1 隐藏 goal 2 把（当前用户有子智能体，agent/task 保留），workspace 文件链路回归通过。提交 4b0a19e5；续跑轮补标 042b8f2e。
- **journal 读放大**（2026-11-01）：纸表轮收尾空窗 57s 根因修（逐 token 落账回放），chunk 聚合是上游待办。
- **定时任务**：容量评估完成、dispatch 队列满泄漏（bf434f3b）与 cron 错峰两 bug 已修。
- **联网搜索 custom 服务商**（2026-11-14）：模型厂商自带搜索 API 各家格式不一，不做预设——新增 `CustomSearchProvider`（id=custom）万能适配器：请求体模板（{{query}}/{{count}}/{{model}} 占位；GET 模式服务地址即 URL 模板、{{query}} 自动 URL 编码）+ 响应点路径取数（customResultsPath/标题/链接/摘要/发布时间五路径），7 个 `webSearch.custom*` 键进 schema（99→106 字段，defaults 经 storedDefaults 基座自动跟到 156），tips 带智谱/百炼/OpenAI Responses 三家示例模板照抄改 key 即用；apiKey 非空即 Bearer、缺省不拦（与 generic 同款宽松）；探测 `custom` 分支委托 provider 真打 limit=1。fake 端点三厂商形状 6 测全绿、契约测试 106/156 对齐。坑：GET 模板占位符必须 URL 编码（首版漏了，假端点 IllegalArgument 测出）。

- **工作流工具总开关退役**（2026-11-14）：`workflowTool.enabled` 平台设置全链删——参数设置「工作流工具」面板+字段、`WorkflowToolProvider` 全局闸判断（智能体不显式关即挂）、智能体页能力行摘 `path`（同技能/MCP 口径：无独立全局开关，只受「工具调用」总闸制约）、README 配置行。限制收口在智能体选工作流处（≤8 个/嵌套 2 层/游客不暴露原样不变）。契约计数 106/156 对齐、ConfigContractTest 4 测全绿。未提交。

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
