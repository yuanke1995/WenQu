# 产物出场方式优化 · 实施方案（A + B + C）

- 日期：2026-11-10
- 状态：已落地，待提交（效果图：`design/mockups/artifact-presence/artifact-presence.html`）
- 范围：对话内产物卡（PC / H5 / 分享页）；不含「我的产物」页与交付抽屉改版
- 结论：**A、B、C 全部在前端收敛，不需要新增后端接口**；C 纳入本轮后风险从低升为中，主要在 iframe / 文本拉取的用量控制；分享页因下载端点强制 attachment，整卡退回类型徽标

---

## 0. 结论摘要

| 项 | 内容 |
|---|---|
| 本轮做 | A 单件统一为交付卡；B 生成态→完成态；C 单件卡左侧首屏缩略；180ms 入场 |
| 后端 | **零改动**（复用现有 `preview` 字段、签名地址、`/artifact/{id}/content`、`preparing` 工具帧） |
| 前端 | 改 7 个文件、新增 5 个（2 组件 + 2 工具 + 1 纯投影）、新增 1 个测试文件 |
| 最大风险 | 网页缩略 iframe 与文本缩略拉取的并发/用量；分享页不能内联缩略（下载端点） |
| 验收核心 | 截图里的单件 `form.json`：有交付感、生成时有反馈、卡内能看出是表单 |

---

## 1. 现状与判据

### 1.1 两套视觉的断点

- 单件走旧灰条：[ChatPage.vue](../../web/src/views/ChatPage.vue#L500-L520)、[MobileMsgRow.vue](../../web/src/h5/MobileMsgRow.vue#L351-L365)。
- 2 件及以上才成卡：`deliveryCardOfMessage` 在 `fileCount < 2` 时返回 `null`，见 [useChatEngine.js](../../web/src/chat/useChatEngine.js#L1062-L1073)。
- 多件卡的视觉与动作在 [DeliveryCard.vue](../../web/src/components/DeliveryCard.vue)。

### 1.2 现有可复用能力（C 的关键）

| 能力 | 现状 | 位置 |
|---|---|---|
| 产物类型判据 | 服务端下发 `preview`（page/image/pdf/md/table/text/none） | [PreviewTypes.java](../../src/main/java/com/wenqu/ai/util/PreviewTypes.java)、[artifactView.js](../../web/src/utils/artifactView.js) |
| 真实首屏缩略 | 「我的产物」网格已用 iframe（375 逻辑宽、缩放、`sandbox="allow-scripts"`） | [ArtifactsPage.vue](../../web/src/views/ArtifactsPage.vue#L293-L312)、[缩略逻辑](../../web/src/views/ArtifactsPage.vue#L460-L604) |
| 文本原文 | 登录态按产物 id 取前一段（带 truncated） | [api.js](../../web/src/api.js#L94)、`ArtifactController#content` |
| 图片 | 登录态签名地址直接 `<img>` | `resolveImg` |
| 分享产物地址 | token 下载端点，**强制 `attachment` + `octet-stream`**，只能下载、不能内联 | [ShareController.java](../../src/main/java/com/wenqu/ai/controller/ShareController.java#L183-L201) |
| 历史数据 | 历史消息与分享快照都带 `preview` | `ArtifactService` 出参、[SessionShareService.java](../../src/main/java/com/wenqu/ai/service/SessionShareService.java#L318-L339) |
| 生成中信号 | 模型生成工具入参时的早产帧 `preparing/chars/brief`（≥500 字或 ≥600ms 才发，1.5s 节流） | [AgentscopeChatEngine.java](../../src/main/java/com/wenqu/ai/agentx/AgentscopeChatEngine.java#L114-L126)、[早产帧](../../src/main/java/com/wenqu/ai/agentx/AgentscopeChatEngine.java#L2103-L2152) |

### 1.3 效果图与真实数据的偏差（已修正）

| 效果图原稿 | 真实数据 | 修正 |
|---|---|---|
| 生成进度「13 / 21 个字段」、62% 进度条 | 只有入参**字符数**与截断摘要，没有字段级进度 | 去掉百分比，改不定长条 + 「已接收 N 字」 |
| 失败态给「重试」按钮 | 失败原因已由工具卡承载，前端没有重试入口 | 失败时撤掉占位，不加第二个失败卡 |
| 单件卡标题是「录入申请人审批表」 | 产物只有 `filename` / `description`，没有独立标题字段 | 主标题 = `description`（无则 `filename`），文件名作为副信息 |
| C 用整宽大缩略 | 对话宽度 640，整宽缩略会把单件卡撑到 ~240px | 缩略落在卡左侧 112×84，与多件 hero 同尺度 |
| 分享页也给图片/网页缩略 | 分享产物地址是 `attachment` 下载端点，`img/iframe` 不会内联渲染 | 分享页整卡退回类型徽标；内联需改后端出流口径，不在本轮 |

---

## 2. 设计定稿

### 2.1 方案 A · 统一轻量交付卡

单件与多件共用 `DeliveryCard`，按 `fileCount` / `scope` 分支密度：

| 元素 | 单件 | 多件（现状保留） |
|---|---|---|
| 头部 | 无独立包名，直接文件信息 | 包名 + 版本 + 状态徽标 |
| 主体 | 左缩略 112×84 + 右侧标题/说明/元信息 | 现有 hero + 清单 |
| 主按钮 | `打开预览`（不可预览时为 `下载`） | `打开入口` |
| 动作位 | 标题行右侧（卡片右上角）：眼睛图标 + 下载图标 | 标题行右侧（卡片右上角）：眼睛图标 + ZIP 图标 |
| 状态 | 元信息里的绿色「已交付」；目标场景沿用包级状态 | 同现状 |

- 目标（goal）交付即使只有 1 件也保留**包形态**（版本、轮次、验收状态是目标语义，不降级成单件卡）。
- 主标题映射：`description || filename`；副行固定展示 `filename`；元信息 `类型 · 大小 · 已交付`。
- 缩略 112×84；动作并入元信息行（按钮 24px 高），单件整卡约 104px，比多件包卡更轻，不做整宽大图。

### 2.2 方案 B · 生成态 → 完成态

**数据来源**：现有 `onToolStatus` 的 `preparing` 帧，不新增后端事件。

显示条件（全部满足）：

1. 消息仍在生成（`m.loading`）；
2. 本轮尚无产物（`m.artifacts` 为空）；
3. 存在 `presentArtifact` / `deliver_artifact` 的工具卡，且状态为 `start`（含 `preparing` 早产帧）。
   占位组件自身延迟 180ms 才显示，短任务不会闪一下。

文案：

- presentArtifact：`正在生成交付文件` + `已接收 {chars} 字`（`chars` 是入参字符数，**不换算成 KB**）
- deliver_artifact：`正在交付 {filename}`（从 `args` 解析 `filePath` 末段）
- 有 `brief` 时优先展示 `brief`（服务端已截断到 40 字）

表现：

- 与单件卡同宽、同最小高度；占位 + 不定长进度条 + 「生成中」胶囊；
- 产物事件到达后，同一位置换成完成卡（不新增第二张）；
- 工具失败 / 用户停止 / 本轮结束仍无产物 → 直接撤掉占位，失败原因留在工具卡里；
- 只在「本轮第一个产物」之前出现；同一轮后续产物不再显示占位，避免卡片来回跳。

### 2.3 方案 C · 首屏所见即所得

新增 `ArtifactThumb.vue`，按 `previewKindOf(file)` 分档：

| 类型 | 缩略内容 | 取数 | 失败/降级 |
|---|---|---|---|
| image | `<img>`，`object-fit: cover` | 登录态 `resolveImg(url)` | `@error` → 类型徽标 |
| page | iframe，375 逻辑宽后缩放，只露首屏 | 登录态签名地址 | 3s 未触发 `load` → 类型徽标 |
| md / table / text | 原文前 3–4 行（保留标题重字） | 登录态 `artifactContent(id)`；**分享页不取** | 空/失败 → 类型徽标 |
| 表单形 JSON | 迷你表格骨架（判断 `components` 数组 + `BasicTable` 结构，只画示意） | `artifactContent(id)` 后 `JSON.parse`（失败即退回文本行） | 结构不识别 → 文本行 |
| pdf / office / zip / 其他 | 类型徽标 + 扩展名 | 不取数 | — |

用量与安全：

- **网页 iframe**：模块级并发上限 2；`IntersectionObserver` 可见才挂载，滚出视口卸载；`sandbox="allow-scripts"`（与「我的产物」页一致，不给同源）；`pointer-events: none`，点击交给外层锚点；`aria-hidden`。
- **文本拉取**：并发上限 3，按产物 id 缓存；只取可见卡片；不做失败重试（避免长会话反复打接口）。
- **H5**：只做 image + 文本缩略，不跑网页 iframe。
- **分享页**：全部退回类型徽标。分享产物地址是 token 下载端点（`Content-Disposition: attachment` + `application/octet-stream`），`img/iframe` 只会触发下载；要让分享页内联缩略必须先改后端出流口径，不在本轮零后端改动范围。
- 点击缩略图与「打开预览」走同一入口：登录态进预览台（`data-pv` 委托），分享页新标签打开。

### 2.4 入场动效

- 仅完成卡首次到达时播放：`opacity 0→1`、`translateY(7px)→0`，180ms，`cubic-bezier(.2,.7,.3,1)`。
- **只在实时产物事件后播放**：`onArtifact` 给消息记 `freshArtifactId`，卡片消费后 900ms 清除；刷新历史、切换会话、分享页不播。
- 尊重 `prefers-reduced-motion`。

---

## 3. 改动面清单

### 3.1 前端（7 改 + 5 新增 + 1 测试）

| 文件 | 改动 | 风险 |
|---|---|---|
| `web/src/components/DeliveryCard.vue` | 单件布局分支；缩略接入；`fresh` 入场；`mobile/shared` 分支 | 中：分享页、H5、目标包共用 |
| `web/src/components/ArtifactThumb.vue`（新增） | 图片/网页/文本/表单/降级；懒加载与并发上限 | 中：iframe 与网络 |
| `web/src/components/ArtifactPendingCard.vue`（新增） | 生成态占位；180ms 延迟显示；不定长进度 | 低 |
| `web/src/utils/artifactThumb.js`（新增） | iframe 槽位（并发 2）、文本拉取（并发 3 + 缓存） | 低 |
| `web/src/utils/artifactPreview.js`（新增） | `pickPreviewLines`、`looksLikeFormJson` 纯函数（可单测） | 低 |
| `web/src/chat/artifactPending.js`（新增） | `artifactPendingOf(m)` 纯投影（可单测） | 低 |
| `web/src/chat/projections.js` | 新增 `deliveryCardOf(d)`；转发 `artifactPendingOf` | 低 |
| `web/src/chat/useChatEngine.js` | 单件也成卡；`onArtifact` 记 `freshArtifactId`；`freshDeliveryOf` | 中：所有对话入口共用 |
| `web/src/views/ChatPage.vue` | 生成态占位；`fresh` 透传；产物只以交付卡呈现 | 中 |
| `web/src/h5/MobileMsgRow.vue` | 生成态占位；`mobile` 缩略模式 | 低 |
| `web/src/h5/MobileChatPage.vue` | 传 `pending` / `fresh` | 低 |
| `web/src/views/ArtifactsPage.vue` | 本地 `pickLines` 换成共享纯函数（无行为变化） | 低 |

> `SharedSessionPage` 不需要改：它已经把 `shared` 传给 `DeliveryCard`，缩略组件按 `shared` 自动退回徽标。
>
> `ArtifactsPage` 这一处是「同一逻辑收编成一处」，不重写网格缩略。

### 3.2 后端

无。已核实：

- 产物事件与历史出参都带 `preview`，分享快照也带（`publicArtifacts`）；
- 文本原文端点按 id + `artifact:read` 鉴权，登录态可直接用；
- 签名地址与 `sandbox` 拦截器已支撑「我的产物」页的 iframe 缩略，链路已被验证。

### 3.3 数据流

```
tool preparing/start ──> artifactPendingOf(m) ──> 占位卡
artifact 事件 ──> m.artifacts ──> deliveryOf ──> DeliveryCard(单件/多件)
                                      └──> ArtifactThumb(image/page/text/fallback)
```

---

## 4. 边界矩阵

| 场景 | A 单件卡 | B 生成态 | C 缩略 | 备注 |
|---|---|---|---|---|
| PC 普通回答 1 件 | ✅ | ✅ | ✅ image/page/text | 截图主场景 |
| PC 普通回答多件 | ✅ 包形态 | 仅首个产物前 | ✅ 入口缩略 + 清单 | 清单动作保持 hover/触屏常驻 |
| 目标交付（任意件数） | ✅ 包形态（不降级） | ✅ | ✅ 入口缩略 | 版本/轮次/验收不丢 |
| 分享页 | ✅ | ❌ 无实时流 | ❌ 类型徽标（下载端点不内联） | 无登录态、无 ZIP |
| H5 | ✅ | ✅ | image/text；page 降级 | 不在手机上跑 iframe |
| 刷新/切会话 | ✅ | ❌ | ✅ 懒加载 | 不播入场 |
| 流式中的气泡（无 `turnId/messageId`） | ✅ | 撤占位 | ✅ | 交付包按消息对象锚定，第一件产物到达即成卡 |
| 产物无 `url/id` | 包内剔除 | — | 降级 | 不伪造可点链接 |
| 工具失败/停止 | — | 撤占位 | — | 失败原因在工具卡 |

---

## 5. 风险与防护

| 风险 | 防护 |
|---|---|
| iframe 过多拖慢长会话 | 并发 2 + 可见才挂载 + 滚出卸载 + 3s 超时降级 |
| 文本拉取打爆接口 | 并发 3 + 按 id 缓存 + 仅可见 + 不重试 |
| 单件卡比灰条高，连续多轮交付显拥挤 | 缩略 112×84、整卡约 146px；不做整宽大图 |
| 入场在刷新时全屏乱动 | 只认实时 `freshArtifactId`；历史恢复不播 |
| 分享页缩略不可用 | 下载端点强制 attachment，整卡退回类型徽标；内联需后端出流改造，明确不在本轮 |
| 分享页 401 / 死链 | 缩略地址一律走 `hrefOf`；文本不取；图片失败降级 |
| 网页缩略安全 | 复用现有 `sandbox="allow-scripts"`（无同源），不新增权限 |
| 产物出现两种形态 | 交付卡是聊天气泡里产物的唯一形态；交付包按消息对象锚定，流式与落库后同一张卡 |
| 目标包被拆成单件卡 | `scope === 'goal'` 强制包形态 |
| 表单 JSON 被误读成真实渲染 | 只画结构示意，不执行、不渲染真实字段；点击进预览台看真内容 |

---

## 6. 验证清单

### 自动化

- `cd web && npm test`：新增 `web/test/artifact-presence.test.mjs`
  - `artifactPendingOf`：preparing 有字符、deliver 路径取文件名、非交付工具/完成/无产物返回 null
  - `pickPreviewLines`、`looksLikeFormJson` 正反例与截断 JSON
- `cd web && npm run build`

### 真机（8090 + 5800）

1. 截图场景：`form.json` 单件 → 生成态 → 完成卡 → 缩略为表单骨架 → 预览台打开真实表单。
2. 网页单件：缩略显示真实首屏；同屏第二张网页卡时仅 2 帧在跑。
3. 图片单件：缩略为真图；坏地址降级不出现裂图。
4. Markdown / CSV：前几行缩略；分享页同产物降级为徽标。
5. 多件站点交付：包形态、ZIP、查看全部不受影响。
6. 目标交付：1 件仍为包形态，版本/轮次/验收可见。
7. 分享页：单件卡 + 类型徽标（图片/网页不内联）；无 ZIP。
8. H5：单件卡 + 图片/文本缩略；网页不跑 iframe。
9. 刷新历史：卡片正常、缩略懒加载、无入场动画。
10. 停止 / 工具失败：占位撤掉、无残留卡、失败原因在工具卡。
11. 深色模式与窄屏（≤768）布局。

---

## 7. 实施顺序

1. **A**：`deliveryCardOfMessage` 放开单件 → DeliveryCard 单件分支 → PC/H5/分享页回归。
2. **B**：`artifactPendingOf` + 占位卡 → 接入 PC/H5 → 停止/失败回归。
3. **C**：共享缩略工具 + `ArtifactThumb` → 单件/多件接入 → 限量与降级 → 分享/H5 回归。
4. **收尾**：入场、深色、测试、真机清单。

每步独立可回归；C 若出现性能问题，可只保留 image/page 两档先上线。

---

## 8. 不在本轮

- PDF 首页渲染、Office 在线预览（需要 pdf.js / 转换服务）
- 「我的产物」页与交付抽屉改版、右栏产物列表缩略
- 表单 JSON 的**真实**表单渲染（本轮只做结构示意，避免第二套表单渲染口径）
- 后端产物缩略图生成与缓存（当前全部前端现取，后续量大再考虑）
- 分享页产物内联缩略（需后端把下载端点改为可安全内联的出流口径）

---

## 9. 实施记录（2026-11-10）

### 已落地

- A：`deliveryCardOfMessage` 单件也成卡；`DeliveryCard` 增加单件密度（左缩略 + 标题/文件名/元信息 + 打开预览/下载）；目标包保持包形态。按用户反馈二次压缩：去掉独立动作条，动作统一到**卡片右上角**（单件在标题右侧、多件在标题行右端），预览=眼睛图标、下载/ZIP=图标按钮，均 24px 高（H5 28px），单件整卡约 104px；多件清单不再重复列入口文件，行内补大小。
- B：`chat/artifactPending.js` 纯投影 + `ArtifactPendingCard` 占位卡；PC/H5 接线；只报已接收字数，180ms 延迟显示，失败/停止自动撤掉。
- C：`ArtifactThumb` + `utils/artifactThumb`；图片真图、网页首屏 iframe、文本前几行、表单 JSON 骨架；iframe 并发 2、文本并发 3、按 id 缓存、失败不缓存。
- 入场：`freshArtifactId` 实时标记，完成卡 180ms 淡入上移；历史恢复不播。
- `ArtifactsPage` 的缩略行洗法收编到 `utils/artifactPreview`，行为不变。

### 与方案的偏差

| 项 | 方案 | 落地 | 原因 |
|---|---|---|---|
| C 分享页 | 图片/网页缩略 | 全部类型徽标 | token 下载端点强制 attachment + octet-stream，img/iframe 不会内联 |
| B 触发阈值 | 工具 start 持续 ≥300ms | 占位组件延迟 180ms 显示 | 不依赖服务端时钟，短任务同样不闪 |
| 入场时长 | 120ms | 180ms | 实际观感更稳，仍不抢戏 |

### 验证

- `npm test`：14 项全过（新增 6 项）。
- `npm run build`：通过（仅有既有的 chunk 体积提示）。
- 真机（本地 vite 5800，代理到云端接口）：
  - 单件 `form.json`：卡 + 表单骨架缩略 + 打开预览 → 通过；
  - 单件 HTML：左侧缩略渲染真实首屏 + 预览台 → 通过；
  - H5：单件/多件交付卡正常，网页不跑 iframe → 通过；
  - 分享页按设计退回类型徽标（未单独建分享链接验证）。
- 未验证：B 的真机生成态（需要真实模型长入参；逻辑由单测覆盖）、多件卡的逐行动作（模板未改，H5 已见 3 件包形态）。
- 未提交 git（用户未要求）。
