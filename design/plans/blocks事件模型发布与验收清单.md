# blocks 事件模型 · 发布与现网验收清单

> 配套设计稿：`design/plans/问答事件模型与引擎拆分.md`。
> 本清单里的**每一步都只做一件事、都有一条能核对的输出**；带【需授权】的步骤必须在当轮对话里点名目标与动作后才执行。

## 0. 本地已绿的基线（发布前复核）

| 项 | 命令 | 期望 |
|---|---|---|
| 后端单测 | `mv src/test/java/com/wenqu/ai/service/GoalFlowTest.java /tmp/ && mvn test; mv /tmp/GoalFlowTest.java src/test/java/com/wenqu/ai/service/` | `Tests run: 118, Failures: 0` |
| 前端构建 | `cd web && npm run build` | `✓ built in …` |
| 夹具离线回放 | `node scripts/loadtest/check-fixtures.mjs` | `✓ 正文 / 工具卡 / 产物 / notice 四处全部一致`（13 份；不需要起服务） |
| 本地真跑回放 | 起 8091 + mock 网关后 `CASES=plain,ask,stop,resume,artifact,plan PLAN_CASE=approve node scripts/loadtest/blocks-e2e.mjs` | `6/6 个用例一致` |
| 其余可选档 | `stall`（默认会跑，约 10s）、`budget`（长回答网关）、`detach`、`workflow`、`asktimeout`、`approval`（`APPROVAL_CASE=timeout` 切超时）、`plan` 的 `reject/supersede` | 各自 `1/1` |

> `GoalFlowTest` 是租约驱动之前的旧目标引擎测试，挡住测试编译；不属于本改造，跑全量时临时移开、跑完放回。

## 1. 部署（后端 + 前端各一次）【需授权】

```bash
# 1) 服务器取最新代码并核对是不是你以为的那一版
ssh wenqu 'cd ~/WenQu && GIT_TERMINAL_PROMPT=0 git pull && git log -1 --oneline'
# 2) 构建并重启后端容器
ssh wenqu 'cd ~/WenQu && docker compose build app && docker compose up -d app'
# 3) 前端构建与静态发布
ssh wenqu 'cd ~/WenQu/web && npm install && npm run build && sudo rm -rf /var/www/wenqu/* && sudo cp -r dist/. /var/www/wenqu/'
```

核对：

```bash
ssh wenqu 'docker ps --format "{{.Names}}\t{{.Status}}" | grep wen-qu'
ssh wenqu 'docker exec wen-qu ls -l --time-style=long-iso /app/app.jar'
curl -s -o /dev/null -w '%{http_code}\n' https://<域名>/ai/actuator/health   # 期望 200
```

**blocks 列迁移**（启动时 `SchemaMigrator` 自动补，不需要手工 SQL）：

```sql
-- 期望 1 行：blocks mediumtext YES NULL
SHOW COLUMNS FROM c_ai_message LIKE 'blocks';
-- 启动日志里应有这一行
-- [SchemaMigrator] 自动补列完成: c_ai_message.blocks = MEDIUMTEXT ...
```
【需授权】只读查库：`ssh wenqu "docker exec wenqu-mysql bash -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot \"$MYSQL_DATABASE\" -N -B -e \"…\"'"`

## 2. 现网真轮采帧（三条场景，够覆盖显示面）

用真实账号在浏览器发三条问：

1. **工具→正文轮**：让它查一次知识库再回答（产生 `tool_status` + 工具卡 + 终稿正文）。
2. **停止轮**：发一个长任务，2–3 秒后点「停止本轮」（产生 `notice#stopped` + 正文尾标）。
3. **工作流轮**：用一个绑定工作流的智能体问一句（单 answer 块 + `workflowRun`）。

每轮结束后这样把这一轮的 SSE 存下来（**不用自签 token**）：
1. DevTools → Network → 过滤 `chat` → 选中这一轮的 `chat` 请求 → 右键 **Copy → Copy as cURL**；
2. 在本地把这行 cURL 的 `--compressed` 去掉、`-i` 加上，重放到文件：`… > fixtures/chat-events/prod-<场景>.txt`；
3. 脱敏后再入库：把 `sessionId`/`messageId`/`userMessageId`/`Authorization` 全部替换掉，**不要带 token**。

> `scripts/loadtest/blocks-e2e.mjs` 是本地 mock 栈用的（它会自签本地 token），**不要拿它打生产**。

**期望的帧序**（照本地 11 份夹具的实际顺序，连续 token 已压缩）：
- 工具轮（以提问卡为例）：`turn_start → turn_start → tool_status → ask_user → tool_status → token×N → usage → done`；`done.blocks` 含 `tool` + `text:answer`
- 停止轮：`turn_start → turn_start → token×N → usage → done`——**没有单独的 warn 帧**，终止原因只落在 `done.blocks` 的 `notice#stopped` 与正文尾标；**done 必须到**（2026-11-06 修的就是这条）
- 工作流轮：`turn_start → plan → turn_start → token×N → done`；`done.blocks` 恰一块 `text:answer`，`done` 里带 `workflowRun`

### 3.0 只读核对（不写、不重启、不建会话）

```bash
VERIFY=1 \
WENQU_TOKEN='<浏览器里那颗 token>' \
SESSION='<会话 id>' \
SHARE_TOKEN='<可选：现成的分享 token>' \
node scripts/loadtest/blocks-e2e.mjs
```

只发三个 GET（会话历史 / 分享信息 / 公开历史），对账口径与本地真跑一致；输出 `[verify] … → 一致` 即通过。
没有 blocks 的旧行会明确报「历史行没有 blocks」——这正是判断新 jar 有没有生效的第一步。

## 3. 落库逐行对比（同一轮，四个数必须自洽）

```sql
-- 取最近一轮助手消息（把 sid 换成你刚才那个会话）
SELECT id, turn_id,
       CHAR_LENGTH(content)                       AS content_len,
       CHAR_LENGTH(IFNULL(thinking,''))           AS thinking_len,
       CHAR_LENGTH(IFNULL(process_text,''))       AS process_len,
       CHAR_LENGTH(IFNULL(tool_calls,''))         AS tools_len,
       CHAR_LENGTH(IFNULL(artifacts,''))          AS artifacts_len,
       CHAR_LENGTH(blocks)                        AS blocks_len,
       JSON_VALID(blocks)                         AS blocks_json_ok
FROM c_ai_message
WHERE session_id = '<sid>' AND role = 'assistant'
ORDER BY sequence DESC LIMIT 3;
```

判据：
- `blocks_json_ok = 1`；
- `blocks_len` 落在「其余各列之和」的 **1.1 倍以内**（本地实测 1.006–1.066，现网真模型 1.02）；
- 停止轮 `content` 以 `⏹ 已停止本轮` 结尾，且 `blocks` 里 `"tone":"stopped"` 出现 **1 次**；
- 工作流轮 `tokens` 里有 `turnWallMs`/`turnPrepMs`，且与当轮 `done.turnWallMs` 一致。

### 3.1 批量行对账（一段只读 SQL + 一个脚本，可反复跑）

`scripts/loadtest/check-prod-rows.mjs` 用前端同一个 `applyBlocks` 重建，再与旧列逐字段对账：
正文逐字符、工具卡键序无关、产物文件名、notice 枚举、answer 块边界、双写是否齐全。

```bash
# 导出（只读）。--default-character-set=utf8mb4 不能省：默认字符集下中文会变 ?，
# 双方都被替换成 ? 时等式仍成立，等于白核对、夹具也失真。
ssh wenqu "docker exec -i wenqu-mysql bash -c 'MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" \
  mysql -uroot \"\$MYSQL_DATABASE\" -N -B --default-character-set=utf8mb4'" \
  < /tmp/prod-rows.sql > /tmp/prod-rows.tsv
node scripts/loadtest/check-prod-rows.mjs /tmp/prod-rows.tsv
```

`/tmp/prod-rows.sql` 就是上面那段 SELECT 的全列版（列顺序固定，脚本文件底部有同样一份；**必须带 `deleted = 0`**——
删除是软删，不滤会把重答前的旧版本当成现网轮次）。
**2026-11-06 实跑**：部署后 3 个有效真模型轮全部六项过——19:26/19:27 两轮 `search_knowledge` ×5/×4 + 技能加载
（11/8 块，答案 1783/1106 字，思考 3834/7031 字，1.02×）；19:48 一轮 23 块、9 次工具、2 个产物、8113 字思考（1.01×）。
短轮倍数会到 3×（旧列仅几十字，JSON 信封占大头），脚本会标注「不作体积判据」。

### 3.2 现网真模型夹具（脱敏后进仓库）

```bash
node scripts/loadtest/harvest-prod-fixtures.mjs /tmp/prod-rows.tsv   # 写 fixtures/chat-events/prod-*.json
node scripts/loadtest/check-fixtures.mjs                            # 与 mock 夹具同一套判据
```

脱敏是**逐字等长**替换（CJK→「文」、内部路径/URL/邮箱→`redacted`）+ 丢弃 sessionId/messageId/model，
因此「正文 = 文本块拼接」这条不变量在脱敏后仍然成立；每行脱敏后先自检，对不上就跳过不写。
现网 7 份已入库（含带产物与旁白的长轮、停止轮；其中 20:17/20:18/20:19 三份 **events 与落库行同源**），离线回放门 24 份带 blocks 全过（14 本地 mock + 7 现网 + 3 真模型）。
**events 为 null**：夹具只含落库行，帧序见 3.4。

**真模型跑 e2e 用例**（2026-11-06 本机实测，真实供应商、不启 mock 网关）：

```bash
WORKFLOW_CASE=1 CASES=workflow \
MODEL_REF='<providerId>/<modelId>' FIXTURE_PREFIX='realmodel-' \
node scripts/loadtest/blocks-e2e.mjs
```

`MODEL_REF` 给了就跳过 mock 供应商登记、直接用现成模型引用（须是该 uid 可用的）；`FIXTURE_PREFIX` 防止覆盖 mock 夹具。
结果：`done.blocks` 恰 `text:answer`，`workflowRun.status=success`、`durationMs=4674`，`turnWallMs = tokens.turnWallMs = 4892`；
夹具 `fixtures/chat-events/realmodel-workflow.json` **events 与落库行同源**（12 帧：`turn_start/plan/token/done`），已等长脱敏后入库。
同法还跑了 `stop`（`text:answer, notice#stopped, text:answer`）与 `plain`（61 帧），现共 7 个真轮（4 现网 + 3 真模型）。
默认（不给 `MODEL_REF`）的 mock 路径另跑 `CASES=plain` 冒烟，未回归。

**额度坑**：2026-11-06 20:15 再跑 `resume` 时模型侧返回「模型服务额度不足」，本轮只有用户消息、没有助手行
（`failMsg` 记在用户消息上），脚本随后在 `historyRow` 处报 `Cannot read properties of undefined`。
真模型批量跑之前先确认额度；失败轮别当代码问题查。

### 3.4 现网真轮采帧（在已登录页面里跑，token 不出浏览器）

无头浏览器拿不到现网登录态，但可以在**已登录的页面上下文**里直接 fetch 流式接口：`localStorage.ai_token` 由页面自己读用，
不经过终端、不落盘。2026-11-06 实测（新建一次性会话，核对完即删）：

- 帧序：`turn_start → turn_start → token×5 → usage → done`；
- `done.turnWallMs = tokens.turnWallMs = 1790`；`done.blocks` 恰一块 `text:answer`（`1+1=2`）；
- 落库行同轮六项对账通过。接口前缀是 `/proxy/api/ai`（不是 `/ai/api/ai`），模型引用取
  `GET /proxy/api/ai/provider/available` 里的 `providerId/modelId`。
- **停止轮**（同一做法：第 3 个 token 后 POST `/proxy/api/ai/chat/stop`）：帧序
  `turn_start×2 → tool_status×4 → image×2 → retrieved×2 → token×80 → usage → retrieved → done`（暂缓关闭时 retrieved 会晚到）；
  `done.blocks` 为 `tool, tool, text:answer, notice#stopped, text:answer`，`turnWallMs = tokens.turnWallMs = 7816`，
  末段正文以 `⏹ 已停止本轮，以上为已生成的部分` 收尾；落库行六项通过（1.10×）。
  注意 **notice 落在最后那段尾标正文之前**：双写期内尾标自成一块，P6 停写尾标后这块才会消失，
  届时 `notice` 独占提示——不要在 P6 之前把 notice 渲染上去，否则会和尾标重复。

一次性会话都在核对完用 `DELETE /proxy/api/ai/session/{sid}` 删掉（软删，导出时靠 `deleted = 0` 过滤）。

**把 events 与落库行绑成夹具**（2026-11-06 20:17–20:19 实做，3 轮：检索轮 116 帧、产物轮 316 帧、纯答轮 41 帧）：
页面里跑完一轮后先按 §3.2 同口径逐字脱敏 events，再 `localStorage` 暂存、`fetch('http://127.0.0.1:8123/capture')` 发给本机小服务落盘
（`node /tmp/capture-server.mjs <out>`，只 allow CORS + POST 一次）；随后用同口径脱敏该轮落库行，`applyBlocks` 自检
正文/工具卡/产物一致后拼成 `fixtures/chat-events/prod-*.json`。这样夹具里既有帧序也有落库行，**token 始终没出浏览器**。
文本增量的 `token` 帧很占体积（316 帧约 124KB），一般在拿帧序证据时够用即可。

### 3.3 现网真轮在本地渲染（不用登录态）

无头浏览器是全新 profile，拿不到现网登录态；把现网那一轮的**未脱敏**落库行导入本地库即可用同一份前端构建渲染。
2026-11-06 实测：线上 `index-BZCmEtda.js` 与本地 `web/dist` 的 sha256 相同，整套静态 282 个文件逐字节一致
（线上多出一批 macOS `._*` 附属文件，无影响）——所以本地渲染结论对线上成立。

```bash
# 1) 本地栈：8091 后端 + 5801 vite（代理指向 8091）
DB_PASSWORD= SERVER_PORT=8091 java -jar target/wenqu.jar &
cd web && VITE_PROXY_TARGET=http://localhost:8091/ai npm run dev -- --port 5801 --strictPort &
# 2) 把 /tmp/prod-rows.tsv 里该会话的行导入本地 wenqu_ai（字符串走 UNHEX，不碰转义）
node scripts/loadtest/replay-prod-row.mjs /tmp/prod-rows.tsv <现网 sid>
# 3) 桌面 / 移动 / 分享页（--expect 给答案里的一句话，--dump 落全文便于核对）
WENQU_TOKEN=$(cat /tmp/local-token) node --experimental-websocket scripts/mac/check-mobile-layout.mjs \
  --session replay-<sid 前 12 位> --expect '动态子表' --width 1440 --height 900 --mobile 0 \
  --shot /tmp/replay-desktop.png --dump /tmp/replay-desktop.txt
```

2026-11-06 结果：桌面 1440 与移动 390 均 `innerWidth = scrollWidth = bodyWidth`、两轮答案与
「检索知识库」工具卡、引用角标都渲染；分享页（`POST /api/ai/session/{sid}/share` 拿 token）只有答案+引用，
无工具卡与过程，符合 `answerOnly`。注意 Node 20 需 `--experimental-websocket`，Node 24 免。以上是本地回放。

**现网域名下也直接看过**（2026-11-06 20:00，用 `yuanke` 登录 `localhost:5800`——该 vite 代理到线上后端）：
- 19:25/19:26 两轮：引用角标、图片（带签名 URL）、「引用一致性自检未收到可用判定，本轮仅完成机械预筛」
  「已剔除 N 处与引用内容不匹配的引用标注」「搜索 4 个关键词，参考 5 段资料」、每轮用时条（21.5s / 19.4s）都在；
- 19:48 轮：产物卡 `form.json` / `.md` 预览下载、2 字段/双产物、旁白段、「接下来可以」三枚建议；
- 分享页 390（`--share-only`，免登录）：`innerWidth=scrollWidth=bodyWidth=390`，只出答案+引用+产物，无工具卡/过程。

`--share-only` 只查分享页，`WENQU_TOKEN` 给占位值即可（分享页免登录）：
`WEB_BASE=http://localhost:5800 WENQU_TOKEN=x node --experimental-websocket scripts/mac/check-mobile-layout.mjs --share <token> --expect '<答案里的一句话>' --share-only --shot /tmp/share-mobile.png`

## 4. 观测（发布后 30 分钟）

```bash
ssh wenqu 'docker logs --since 30m wen-qu 2>&1 | grep -E "本轮收口|FAIL-LOUD|SchemaMigrator" | tail -50'
```
要看到的：
- 每轮一条 `[TURN] 本轮收口 … 整轮墙钟=…`；
- 没有成批的 `[FAIL-LOUD]`；
- 前端控制台没有 `CHAT_EVENT_CB` 未匹配事件（新增/改名事件没同步会在这里现形）。

## 5. 回滚

- 后端：保留上一版 jar，覆盖回去重启容器即可（`docs` 里的部署 runbook 同一套）。
- 前端：静态目录回滚到上一版 dist。
- 旧客户端/旧行不受影响：`blocks` 为 NULL 的存量消息继续走 `timeline` 兜底，这是双写的意义。

## 6. P6 旧机制退役的前置条件（本周期不做）

同时满足才动：
1. 上面第 2–4 节全部过完，且**没有需要回滚的 blocks 问题**；
2. 双写窗口跑满一个发布周期（不是固定天数）；
3. 决定「分享页是否继续显示产物卡」（现在走公开历史的 `artifacts` 字段，不走 blocks）；
4. 移动端宽度与移动端历史回显验过（本地无真机，需现网）。

退役顺序（两步，不要合并）：
1. 先停写 `timeline` / `process_text`，观察一个周期；
2. 再停写正文尾标、前端改渲染 `notice` 块；
3. 最后等会话保留期把旧行淘汰后删列/删兜底。

## 7. 授权边界

- 【需授权】`ssh` 覆盖容器、重启服务、发布静态资源、改库；
- 只读查库/看日志可以按需进行，但**一次授权只覆盖点名的那次动作**；
- 不要在生产用 `AI_JWT_SECRET` 自签 token 代替登录；采帧用真实登录会话。
