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
| 工作区完整版 | 🚧 | A 上下文边界 + B 专注 + C1 动态 ✅（2760e4b8、12d8d433）；**剩 C2 事项清单、C3 进入即推进、A4 记忆分域、D 模板** |
| 测试地基 | ❌ 结构性债 | 自动化测试近乎为零（RBAC 判定核 19 测是起点，且 src/test/ 约定不入库）；公共件改动目前靠人工回归——这正是「改烂旧功能」风险的主因 |
| bug 移交池 | 🚧 | 2026-11-04 第二波前后端猎捕移交 13 项（0-token 误标、编辑重发互斥、审批超时绕 finish 等），细节在 11-04 会话记忆 |
| 延迟线尾巴 | 🚧 | **2026-11-06 14:32 云端实测（新流水线）**：两条新会话 `1+1=?` 整轮 **1.44s / 1.58s**（开轮前准备 252/195ms，机器 1.19/1.39s），对比改前 5.66s（准备 3.48s）/12.46s（准备 10.4s）。prompt 降至 9.9k（system 4.66k + toolSchema 5.25k），cached 2048；本地两条新会话的最终 system prompt 指纹完全一致，工作区档也未捕获到 `AgentStateStore ID`/container id——剩余缓存上限不是前缀稳定性，待查供应商缓存粒度。历史记录：稳态首包 6s→1.2s（2026-11-06）；新会话首轮曾付「开轮前准备」~3.5s（HarnessAgent 冷构建 + 沙盒冷装配）；16.8k prompt 缓存仅中 2k。 |
| 问答流水线重做 | 🚧 | 参考方案见 `.zcode/plans/plan-chat-pipeline-rebuild.md`（**DSH 问答架构与矩阵**，DeepSeek Harness 开源实现）；问渠自有 A/B 方案已另存 `.zcode/plans/plan-chat-pipeline-wenqu.md`。**M1/M2 代码已完成并本地验证**：聊天档免沙盒（按意图分流）、宿主 scratch（可配 `chat.scratchDir`，多副本须共享卷）、跳过 run_code/MCP（`chat.mcpEnabled` 默认关，开时仍受 `mcp.turnWaitMs` 有界等待）、前缀稳定器、MCP 等待有界、`chat.profileAuto` 回滚开关、scratch 保留清理；tokens 落 `profile=chat/workspace`。本地实测：新会话 `1+1=?` 1.2~1.3s、同会话二轮 0.68~0.72s；聊天档 `write_file`/`presentArtifact` 均无沙盒；`profileAuto=false` 可回滚；挂死 MCP 只等 1.5s；scratch 过期目录 5s 清理；工作区沙盒 E2E 已本地验证（默认 dev token，冷态 4.76s / 热态 2.79s，`write_file` 214ms，文件在容器内）；隐式会话异步升级机制已验证（默认关；首轮 1.33s + 后台 2.1s 建沙盒，二轮 1.08s 切 workspace，粘性 0.48s；首轮按方案 1 隐藏文件/命令工具，mock 验到 `write_file=false`、无 scratch 文件，交接问题关闭）；静态+运行时+分享/访客夹具全过（2 项按参数 SKIP）。**云端真实模型已验证（2026-11-06 14:32 两条 `1+1=?`）**：整轮 1.44s/1.58s、准备 252/195ms、prompt 9.9k、cached 2048；本地新会话 system prompt 指纹一致，说明聊天/工作区档的前缀本身稳定，2048 缓存上限待查供应商侧。**M3 已做**：全局 cap=6、每用户 `maxPerUser=2` LRU（f4f47ab2）、`sandbox.userScope` 用户级热复用（8c423b90）、启动热池预热 `sandbox.poolWarmUsers`（d0fc4fcd；本地实测预热 1.7s，新会话直接复用，开轮前准备 493ms；真实模型下 1+1 整轮 2.65s）。**剩余**：多副本共享存储、核心工具面继续裁剪（第 1 档：内置 10 把已物理退役，4079f387；第 2 档安全子集：goal_* 按活跃目标、agent/task 按子智能体存在性，4b0a19e5；plan/skill 保留为功能入口）；跨用户通用预创建池在当前「按 uid 挂载用户卷」下不安全，等效实现即每用户预热（已做）。 |
| 智能体对外能力 | 🚧 | 2026-11-07 立项并完成第一批（ed4ebbb3=自定义 HTTP 工具 OpenAPI 导入 + cbffeec4=调用变量注入含分享级开关，8091 栈真模型 E2E 全过：导入/落库 JWE/工具触发+鉴权头/审批卡/变量双向/游客 fail-closed/版本回滚/三态闸门）。方案 `.zcode/plans/plan-agent-http-tools-and-variables.md`；后续批=工作流当工具/webhook 出站/agent 级记忆/管道层（计量 scope CORS/OpenAI 兼容端点）。注意与工作流第 4 期的 webhook 是两回事（那边=入站事件触发，这边=结果出站回调） |
| MCP 外部工具页升级 | 🚧 | 2026-11-07 立项（用户拍板）：UI/UX 打磨 + 工具试调用 Playground + 个人调用统计 + 定时健康巡检；方案 `.zcode/plans/plan-mcp-panel-upgrade.md`（含效果图制作输入 §1 与后端端点规格 §4）；**效果图由外部工具制作，定稿后实施** |
| 市场三期 | 🚧 立项 | MCP 目录已立项（2026-11-07，`plan-market-mcp-directory.md`，由 MCP 面板升级派生：模板走正式目录不做轻量预设），实施排期另议；二期（应用模板 fork/工作流导入导出/官方模板）2026-10-25 完毕 26 项 E2E，**已入库（58ba17e1，2026-11-07 git 考古核实）** |
| RBAC 批次 F | ⏸ 排 M7 | v1.0 已于 2026-11-03~04 全量落地（10 提交）；仅剩批次 F 排期 M7；D2 粒度是后续唯一阻塞项 |
| 工作流升级第 4 期 | ✅ 2026-11-07 | 第 0~3 期 ✅（ff637d20 / 5e7fef65 / 78f7053e / ebdea58c）；**第 4 期当日重建排期+落地（4 提交 61ec1025 / 3af31d43 / 4b146a89 / 8cec6c8d）+遗留债清偿（97252ccf / 30c5bf1b）**：事件触发（webhook 入站 + KB 解析完成事件 + 链深防环熔断 depth≥3）/ kb_write 知识写入节点（create/update 双模式）/ 并发可视化（node_start 实时事件+画布呼吸灯 / 运行历史甘特时间轴+并行分组标）/ trace 摘要三键可配 + eventTriggerEnabled 总闸 / 导入导出接线+过时文案修缮；UI 四处经用户拍板（自动化单弹窗分区/双模式表单/呼吸灯圆点/历史页甘特+分组标）；真机 E2E 全过（webhook 三守卫、事件闭环、防环 1→2→3 熔断、呼吸灯 DOM 实证、**审批挂起恢复与检查点续跑复验**（短路回放零重复执行）、历史页并行渲染+老数据降级、问答线 3.1s 回归）。**遗留债**：多订阅者共写同一文档时链深登记会被并发覆盖（真实链条各写各文档，不受影响）。**附带修缮（1c98ce4c）**：StateGraph「并行分支必须同点汇聚」的框架语义保留不动，但编译闸前置到 create/update/publish——「画得出、存得进、跑时才炸且报错晦涩」的问题改为保存/发布当场拦截+人话指引（复用引擎编译 dry-run，不自建第二套汇聚分析） |

## 待拍板（用户未决）

- KB 导出导入通用化两路线：纯文档 zip vs Dify 格式
- 游客审批卡 fail-closed + 数据字典注入
- 分享安全：P3 费用面 / P4 注入面（P1 ClientIp、P2 share-read 桶已修）
- 工作区上下文装配 0.4s（延迟线尾巴）
- FormCopilot「copilot 切 API Key」——已初评语义倒退，待终裁

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
| §10 | 智能体评估集（EvaluationPage 无智能体 Tab，评测集是单份全局 JSON） | ❌ 未做 |
| §11 | 深度研究预设（§1 已解锁，纯组合零新引擎，高性价比候选） | ❌ 未做 |
| §12 | 通知中心 | ✅ c82beb9 |
| §13 | Token 成本报表（模型单价+台账×单价聚合） | ✅ 14380d7c |
| §14 | 移动端 H5（M0~M3 分包/引擎/移动壳/SW + M4~P6 收官） | ✅ |

### 其他已完成流

- **子智能体运行视角**（2026-11-07，41142f14）：实时直播+事后回放落地——收集通道转发白名单加宽（retrieved/artifact/warn/ask_user）、分支收束统一写一条 `type=subagent` 账本事件（挂父会话+父轮，关键帧 ≤240×2000 字，`appendLarge` 200k 上限、payload 列升 MEDIUMTEXT 已 ALTER）、前端 `SubAgentRunFrames.vue` 共用渲染（派遣卡「完整过程」抽屉直播吃 `_frames`/终态账本懒取 + 事件面板 subagent 聚合块）。四轮真机 E2E 全过（直播帧/刷新回放/账本聚合/老消息优雅空态）；**顺带修复**：DSH 平铺行路径原本不挂 SubAgentChatCard，subChat（问它/它的回答）在新口径下不可见——已在平铺行详情挂载，两条路径同构。遗留债：BranchMode(digest/full) 在框架通路未按档分流。

- **市场一期技能**：✅ c92db191。
- **RBAC 升级 v1.0**（2026-11-03~04）：10 提交、判定核 19 测全绿、三态双跑/enforce 全验证；方案在 docs/rbac-upgrade-plan.md（gitignored）。坑账：新 RBAC 表必须进 GLOBAL_TABLES（三踩）、种子须反向折算 role_api。
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

- 多数 deepseek 模型上下文登记值仍 65536（SQL 改值后须重启后端——供应商/模型是启动期内存缓存）。
- AgentScope 2.1.0-BETA1 ingestion 在 ~2 万字处丢字+尾部重投（seq 幂等闸已兜；chunk 聚合是上游待办）。
- GraphRAG 抽取模型退役后，存量 c_ai_user_config 行未清（库已迁 wenqu_ai）。
- 沙盒双重加密修复（68d8bb6b）后，DB 内旧 token 需设置页重填。
- 检索两缺口：元数据过滤（c_ai_knowledge 无 metadata/tags 字段）、检索内容注入防护与来源可信度。
- 「后端有前端没接」5 处（2026-10-07 盘点：单轮删除撤销、清空全部会话、沙盒文件浏览三接口、关键词索引 stats/reindex 无 UI、运维端点无入口）——现状待核，动相关域时顺手核销。
- 配置默认值三层口径：schema def 只给前端、defaults() 是运行时权威、getDouble fallback 纯防御；多读点 key 默认值进 ConfigDefaults 常量类。
- 显式本机工作区（ExecutorFilesystem）的服务器沙盒白建已修：`frameworkSandboxContext` 对显式工作区直接返回 null，不再创建无用的服务器容器；待本机执行器配对后真机验证（2026-11-06 代码审计/修复）。
- ~~`scripts/mac/check-agent-runtime.py --fixtures` 与 `c_ai_agent_share` 表结构漂移~~ 已修（2026-11-06）：`c_ai_agent` 选 `id`、分享 SSE 用 raw POST、`count(*)` 断言按首行值判；修复后分享/访客夹具全过。
