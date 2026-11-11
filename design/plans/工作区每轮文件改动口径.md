# 工作区每轮文件改动（DSH workspace-changes 口径）

## Context
截图里的「N 个文件已更改 +69 −30」卡来自 DSH 的 `workspace-changes` 机制（已通读其源码：轮前/轮后 git 快照 + 文件工具改动前整文件捕获 + 按需 diff）。问渠工作区现在只有「本机文件」静态列表，看不出每一轮助手改了什么。用户已拍板：做 DSH 式每轮改动；**不做撤销**（只读）。

**隐私红线（硬约束）**：
- 文件内容**永不出用户电脑**。快照对象与改动前捕获全部留在执行器本机临时目录（`os.tmpdir()/wenqu-turns-*`，0700）；服务端只收「相对路径 + 行数」的统计，只在用户点开某文件差异时由执行器现算 hunk 回传（通道量级与现有文件预览一致）。
- git 快照对用户仓库**绝对只读**：私有 index + 私有 object store（仓库 objects 作 alternate 只读挂载）、`GIT_OPTIONAL_LOCKS=0`、`GIT_TERMINAL_PROMPT=0`、`GIT_CONFIG_COUNT=0`、`LC_ALL=C`，绝不碰用户的 index/refs/worktree。
- 落库的 `c_ai_message.changes` 只含相对路径与行数；执行器临时数据按 TTL（24h）+ 轮收尾清理。
- 差异端点按消息归属 uid 校验；游客/分享回放路径不带 changes。

## 一、本机执行器 `scripts/local-agent/wenqu-agent.mjs`
1. **新状态**：`turnStates = Map<turnId, {scratch, repos: Map<alias,{root,env,tree}>, captures: Map<absPath,{kind,file,binary}>}>`；scratch 建在 `os.tmpdir()`，0700。poll 循环每轮顺手清 TTL>24h 的残态（服务端崩溃兜底）。
2. **op `turnStart {turnId}`**：对每个 root 跑 DSH 同款定位+快照——`git rev-parse --show-toplevel --absolute-git-dir --git-path objects`（128+not a repo → 该 root 标 `supported:false`）；临时 index（复制仓库 index，缺失则空）、`git add --all --ignore-errors` + `write-tree` → treeId。仓库路径校验必须落在该 root 内（防读到 root 外仓库）。macOS `/usr/bin/git` Xcode stub 探测照抄 DSH（`xcode-select -p`）。
3. **改动前捕获**：`write/writeBytes/writeChunk/move/remove` 处理器开头，若 `args.turnId` 命中 turnState 且该路径本轮未捕获过 → 整文件按 SHA-1 内容寻址存入 scratch/captures（帽 2MB，超帽记 oversized；前 8000 字节探 NUL 判 binary）。捕获写的是 scratch（执行器自有临时目录），**不走 resolveInRoot 围栏**。exec 不捕获（与 DSH 同口径）；agent 单队列串行，天然无竞态。
4. **op `turnEnd {turnId}`**：各 supported root 再快照一次 → `git diff-tree -r -M -z --numstat before after` 得每文件 added/deleted/binary/oldPath；捕获过但快照没覆盖的路径（非 git root / 被 ignore）补拍后照，内容相同跳过，不同则用自带小 LCS diff（3 行上下文，~100ms 超时降级整文件替换 hunk）算行数。汇总 `{files:[{path(相对root), display(alias/rel), added, deleted, binary?, oversized?}], total, added, deleted}`（帽 200 文件，路径一律相对、不带绝对路径出机器）。回传后 turnState 保留（供后续 turnDiff），起 TTL。
5. **op `turnDiff {turnId, path}`**：定位该文件的两侧——快照侧走 `ls-tree -z -l`（GIT_LITERAL_PATHSPECS=1）+ `cat-file blob`，捕获侧读 scratch 文件；返回 `{kind:'text', before, after, hunks:[{oldStart,oldLines,newStart,newLines,lines[]}]}` 或 `binary/oversized`。输出帽 8MB。
6. `HANDLERS` 注册三个新 op；老执行器返回 `E_UNSUPPORTED`，服务端给「请升级本机执行器」的可读提示。`node --check` 验语法。

## 二、服务端
1. **`ExecutorFiles`**（executor/ExecutorFiles.java）加三个包装：`turnStart/turnEnd/turnDiff`（`userInitiated=false`），复用现有 `call()` 的 roots/policy/错误映射。
2. **新 `ProjectTurnChangesService`**：
   - `start(sessionId, projectId, turnId)`：仅当 `projectService.isLocal(p)` 且 roots 非空且执行器在线（离线/未升级→静默跳过，不出卡不报错）；异步发起 turnStart，future 存 `ConcurrentHashMap<sessionId, Pending>`；同时暴露 `currentTurnId(sessionId)` 供 ExecutorFilesystem 读取。
   - `settle(sessionId, turnId)`：同步调 turnEnd（60s 帽），返回 summary JSON；失败记 warn 返回 null。finally 清 pending。
   - `fileDiff(messageId, index, uid)`：查消息行（归属校验）→ 取 changes 里的 path/turnId → 调执行器 turnDiff。
3. **`ExecutorFilesystem`**：构造时注入该 service（或经 SandboxService 传入），`write/uploadFiles/execute` 等变更型调用把 `currentTurnId(sessionId)` 塞进 payload 的 `turnId` 键（实例可能跨轮缓存，必须调用期读取，不能烘焙进构造器）。
4. **引擎挂点** `AgentscopeChatEngine`：
   - 轮开始（turnId 就绪、框架 run 发起前）：`turnChanges.start(...)` 异步不阻塞。
   - 收尾：finalize（正常/停止/超时共用，`persist` 标志处，约 4084 前）与半程 closeout（DONE emit ~1886）都调 `settle`；summary 有内容才：`sessionService.appendMessage(...)` 增一个 `changes` 参数落库（跟随现有 toolCalls/tokens 同款传法）、done JSONObject `done.put("changes", ...)`。
   - 消息链路：`Message` 模型 + `c_ai_message` 加 `changes TEXT`（SchemaMigrator addColumns 表 + schema.sql，照现有模式）；历史回放/SessionService 消息组装带出该字段；分享序列化过滤掉。
5. **端点**：ChatController 加 `GET /message/{messageId}/changes/{index}/diff`（uid 归属校验；400=已过期/越权，503=执行器离线，老执行器/scratch 已清→「改动详情已过期」）。
6. 新增 `E_UNSUPPORTED` 映射为可读的「本机执行器版本过旧，请升级」。

## 三、前端
1. **`web/src/api.js`**：`turnChangesDiff(messageId, index)`。
2. **`web/src/chat/useChatEngine.js`**：msg 形状（~998/2399/2088 三处 fresh 对象）加 `changes:null`；done 合并（~2860）`msg.changes = p.changes ?? msg.changes`；历史映射（~1184）读 `m.changes`。
3. **新组件 `web/src/components/TurnChangesCard.vue`**（仿截图）：头行「N 个文件已更改 +X −Y」（+/− 用 `--app-ok`/`--app-danger`），>4 行折叠；每行文件名 + 行数，点击行经预览台开差异。插入点：ChatPage.vue 助手气泡内工具卡之后、产物卡之前（工具卡渲染块 ~340-480 区域的尾部）；MobileMsgRow.vue 挂同一组件（或其简化版），差异浮层走 document 级委托两端通吃。
4. **预览台 diff 档**：`artifactView.js` PV 加 `DIFF`；`previewStage.js` 委托读 `data-pv-changes`（messageId+index）强制 kind=DIFF，`showTextBody` 加分支拉 turnChangesDiff 渲染统一 hunk（+行 `--app-ok-weak` 底、−行 `--app-danger-weak` 底、hunk 头灰、上限 5000 行截断提示）；`previewStage.css` 加 `.ps-diff-*`；顶栏隐藏「新标签/下载」（diff 无真实文件地址）。

## 四、文档与测试
- `manual/04-工作区.md` 加「每轮文件改动」节 + FAQ（非 git 目录只有文件工具改动会被记 / 需本机装 git / 执行器离线时该轮不出卡）；`03-对话与问答.md` 一句带过。
- 本地单测：ProjectTurnChangesService（mock ExecutorFiles/ProjectService，start 静默跳过矩阵、settle 失败返回 null、diff 归属校验）——按仓库约定 src/test 不入库。

## 验证
1. `mvn -c compile`（IDEA 内置 maven 路径，-pl 带 -am）+ `vite build` + `node --check wenqu-agent.mjs`。
2. 8091 jar + 临时 vite 栈联调（按惯例核验代理指向、验提交态）：绑定一个 git 仓库目录的工作区 → 让助手改 3 个文件 → 气泡尾出卡，行数与 `git diff --numstat` 对账；点行开差异、增删行配色正确；刷新后卡片仍在。
3. 停止轮（ESC）中途停 → 已改文件仍出卡；非 git 目录 → 仅文件工具写入的文件出卡；执行器离线轮 → 无卡无报错；老 agent → 503+「请升级」；`git status` 确认用户仓库 index/refs 全程未动；scratch 目录 24h 后被清。
4. 分享回放页确认不出现 changes 卡。

## 提交
分三笔中文 conventional commit：后端（feat(agent)）、执行器脚本（feat(executor)）、前端（feat(web)）；执行器脚本改动须提醒用户重启本机 wenqu-agent。