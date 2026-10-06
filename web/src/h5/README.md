# h5 —— 手机端（真 H5）

本目录只放**移动端专属**的模块。判断标准很简单：

> 这个文件在 PC 宽屏下有没有用？有 → 不放这里（留在 `views/` / `components/` / `chat/`）。

## 架构：一套引擎，两个壳

```
src/chat/useChatEngine.js   聊天引擎（会话/流式/选择器/附件/引用）—— PC 与移动共用
src/chat/projections.js     消息投影与格式化纯函数（时间线/工具卡/来源分组/文案）—— 共用
src/chat/useChatSearch.js   会话内查找（Range + CSS Custom Highlight API）—— 共用
src/views/shareSession.js   会话分享的状态机与链接拼装 —— 共用
src/views/exportMd.js       Markdown 导出（拼装 / 落盘分开，给移动端留第二条出口）—— 共用
src/utils/clipboard.js      复制文本（clipboard API + execCommand 双路径）—— 共用
src/chat/useNotifications.js     站内通知取数（未读轮询/列表/乐观置已读）—— 共用，不含路由
src/chat/useApprovalRecovery.js  工具审批恢复状态机（?approval= 深链 → 重建/裁决）—— 共用
src/views/ChatPage.vue      PC 壳（三栏工作台，保留既有窄屏补丁给「鼠标用户拖窄窗口」）
src/h5/MobileChatPage.vue   移动壳（/m/chat，独立布局，不经 AppLayout）
```

共用单元的进入门槛：只有「两端行为必须一致」的逻辑才抽（消息查找的行契约、分享的换链失效语义、
剪贴板的兼容路径）。纯呈现差异不要抽 —— 那会把两端都拖住。

引擎经 `useChatEngine(hooks)` 单实例化，移动壳再把实例 `provide('wqChat')` 给 sheet 族
（`MobileMsgRow` / `MobileSessionSheet` / `MobileModelSheet` / `MobileAttachSheet` / `MobileRefSheet`）。
**禁止任何消费方各自调用 useChatEngine**——那会造出第二份会话/流式状态。

接缝只有 5 个 hooks + 落点：`scrollFollow / scrollForce / scrollSoft / closePanels / focusInput / chatPath`。
PC 壳传与原实现逐字等价的回调；移动壳传自己的滚动与 `/m/chat` 落点。

## 路由与设备判据

- `/m/chat` 是手机的正门：守卫在登录态之后做双向重定向（`/chat` ↔ `/m/chat`，查询串透传）。
- 判据是 `preferMobileShell()`（`mobile.js`）= **触屏**（`isCoarse`）且宽度 ≤1024：
  - 手机竖屏/横屏（横屏约 900px）与平板竖屏 → 移动壳；
  - 桌面（含把窗口拖窄的鼠标用户）`isCoarse` 恒 false → 永远留在 PC 布局。
- 用触屏而非纯宽度：手机横屏 >768px 就不是「窄屏」了，但仍是手指操作，该进移动壳。
- `AppLayout` + 窄屏补丁**没有退役**：管理页（引导卡）、`/profile`、`/help`、`/artifacts`（我的产物，
  已做窄屏适配：卡片列表 + 44px 热区 + Modal.confirm 删除确认）、`/knowledge`（含 `/knowledge/:id/docs`，
  只读浏览：库卡片自适配 + 文档表 ≤560px 行重排——首行文件名、次行状态+操作、表头隐藏、多选退出）
  与拖窄的桌面窗口仍走它。

## 移动壳的交互约定（与 PC 的差异，都是有意为之）

| 事项 | PC | 移动壳 |
|---|---|---|
| 发送 | Enter 发送 / Shift+Enter 换行 | 按钮发送，回车是换行（软键盘习惯） |
| 停止 | ESC 两段式上膛 + 按钮 | 直点停止钮（无物理键盘） |
| @ / # / 技能 / 附件 | 输入框敲字符唤起 + 「+」菜单 | 全部收进「+」底部 sheet（触屏不敲符号） |
| 引用来源 | 右栏常驻列 | 顶栏标题/状态 sheet + 角标点按进来源 sheet |
| 消息操作 | hover 出操作行 | 点气泡选中 / 最新一条常显 |
| 会话管理 | 侧栏常驻 | 底部 sheet（搜索/分组/置顶/重命名/删除） |
| 新问题置顶 | 尾随留白（桌面语义） | 用户消息出现即送上容器顶（不加留白，见下） |
| 会话内查找 | Ctrl/⌘+F 热键 | 顶栏按钮 → 消息流上方查找条 |
| 站内通知 | 侧栏 foot 铃铛 popover（未读 Badge，30s 轮询） | 顶栏铃铛 → 通知 sheet（角标与列表同源，同一份 useNotifications.js） |
| 个人设置 / 帮助 / 主题 / 退出 | 侧栏 foot | 会话 sheet 底部四项（壳外入口；通知不在其中，铃铛是高频动作单列顶栏） |
| 工具审批恢复 | 消息区顶部横幅（`?approval=` 深链重建） | 消息区顶部横幅（同一份 useApprovalRecovery.js） |
| 上下文容量 | 工具栏圆环 + 悬浮容量卡（十类构成/缓存命中） | 「状态与来源」sheet 的容量段（同算法，分段与标签同 PC） |
| 空态配置引导 | 欢迎区换 `SetupGuide`（`loaded && !chatDone` 门控） | 同左（同一组件、同一门控；移动壳自己触发 refreshSetupGuide） |
| 会话分享（只读链接） | 页头动作区的 `a-modal` | 「状态与来源」sheet 内展开（同一份 shareSession.js） |
| 导出 Markdown | 页头「更多」菜单 / 侧栏菜单 | 会话级：「状态与来源」sheet 保存 + 复制双通道；单轮：操作行「⋯」→ sheet（同 PC 的 buildAnswerMd/buildSessionMd） |
| 「更多」菜单（评测 / 检索调试 / 删除本轮） | 消息操作行的 a-dropdown | 操作行「⋯」→ 底部 sheet（MobileRoundSheet，同四项） |

### 三处「既然要做，怎么做得不像桌面版」

- **新问题置顶**：PC 是靠尾随留白把"贴底"这个动作的落点抬到问题顶部（`updateTailSpacer`）。
  移动端刻意不搬：一屏只放得下 1-2 条，留白常常要近一屏，用户上翻回看时会在内容尾拖一大块空白，
  观感像"消息丢了"；那套算法还要求每次重渲染都 `getBoundingClientRect` 反推一次，手机上更容易掉帧。
  等价做法是用户消息出现的那一刻直接 `scrollIntoView({ block:'start' })`，并顺势把 `stickToBottom`
  置 true（发新问题即宣告要看这一轮）。行本身带 `scroll-margin-top: 8px`，不会压在上边框上。
- **会话内查找**：只是入口不同。Range 遍历、命中分组、CSS Custom Highlight 全是同一份
  `useChatSearch.js`——两端消息行都带 `data-row-index`，高亮还共用全局 `app.css` 的 `::highlight(chat-search)`。
- **导出**：`<a download>` 在微信内置浏览器里基本不落盘，在 iOS Safari 上也不是总能能用。所以
  「复制全文」不是降级方案，而是这条路上必须有的一条出口——两个按钮都显式给出，不静默切换。

## 键盘与安全区

`keyboard.js`（main.js 顶层安装）把 visualViewport 写成 `--app-vh` / `--kb`：
- 移动壳满高容器用 `--app-vh`（已扣键盘），键盘弹起时**容器收缩**而非 fixed 定位被盖住；
- 去重同时看 `--kb` 与 `vv.height`：浏览器全屏/地址栏收放不改键盘占用但改可视高度，
  只按键盘去重会让 `--app-vh` 停在旧值（全屏后输入区悬空不贴底）；
- 底部 sheet / 输入区的底部内边距用 `max(0px, var(--sab) - var(--kb))`：键盘开着时不再叠加安全区。

安全区变量（`--sat` 等）默认恒为 0，只在 `display-mode: standalone / fullscreen`（PWA 真正贴边）才取
`env(safe-area-inset-*)`：夸克等 Chromium 内核浏览器在普通标签页会把屏幕切洞 inset 泄漏给页面
（浏览器 UI 已避开刘海/手势条，页面再补一次就多出留白），见 app.css 的媒体查询。

## Service Worker（PWA）

`public/sw.js` 只做一件事：**缓存带内容 hash 的 `/assets/*`**。
- 明确排除：`/s/*`、`/shared/*`（免登录分享，token 在 URL 里）、API、HTML 与一切非 `/assets/` 请求
  —— 分享内容不会被落到设备磁盘（旧版「不引 SW」的隐私顾虑由此消解）。
- 只在生产构建注册（`import.meta.env.PROD`）；dev 不注册，避免缓存干扰 vite。
- 资源名带 hash ⇒ 新版本新 URL，天然无陈旧问题；SW 版本号变更时清理旧缓存。

安装入口（`h5/pwa.js`，main.js 顶层 `registerPwa()`）：manifest + SW 只让应用「可安装」，入口要自己给。
- Android/Chromium：接住 `beforeinstallprompt` 并 `preventDefault()`（浏览器自带的迷你提示条位置不可控），
  会话 sheet 底部出现「安装到桌面」，点击触发原生安装提示；
- iOS Safari：没有该事件，给「分享 → 添加到主屏幕」图文步骤；
- 应用内浏览器（微信/QQ/微博/支付宝）与已在 standalone 运行时不显示入口。

## 明确**不放**在这里的东西

- `ChatPage.vue` / `AppLayout.vue` —— PC 壳；手机不再走它们（但桌面窄窗口仍走）。
- `api.js` / `md.css` / `app.css` / 主题 token —— 全端共用。
- 第二个 `index.html` 入口 —— 刻意不做：antd 整包会在两个 entry 里各打一份。

## 静态校验

- `node scripts/verify-sfc.mjs`：SFC 编译、模块解析、CSS 语法、项目约束、PWA 资源存在性。
- `node scripts/check-engine-imports.mjs`：projections 引用完整 + 从引擎 inject 解构的名字必须真实存在
  （两类漏接都只在真浏览器渲染时才炸，这里前移为静态失败）。
- `node scripts/check-mshell.cjs`：412×916 触屏跑 dist 产物，验重定向/结构/热区/sheet 开合/
  通知角标与通知 sheet 深链/壳外入口/单轮操作 sheet（导出·调试·删除）/桌面弹回
  （通知、审批、历史、调试接口带 mock，断言的是真渲染）。
- `node scripts/check-browser.cjs`：PC 壳（含鼠标拖窄窗口）回归，同样覆盖铃铛弹层与审批恢复横幅
  ——通知/审批抽成共用单元后，两端各有一条真点开的断言守着绑定没漏。
- `node scripts/check-share.cjs`：分享阅读侧（`/shared/:token` 只读 + `/s/:token` 智能体对话）在
  412×916 触屏 + 桌面双上下文回归——免登录页不走 AppLayout，前两个脚本都不经过它们；
  断言 markdown 排版 / 产物与来源渲染 / 触摸热区 / `--app-vh` 消费 / `enterkeyhint` / 无横向溢出，
  并回归桌面形态未被窄屏补丁外溢（820px 居中栏、14px 输入框）。
`npm run check` 串起静态三件套；`check:browser` / `check:mshell` / `check:share` 需 playwright-core，单独跑。

## 待补（v1 有意留白，按需再做）

- 分享链接在阅读侧**刻意不做**移动专属页：`/shared/{token}` 复用 PC 那张 `SharedSessionPage.vue`
  （自带 @media ≤768px 适配，且 `meta.pageFlow` 已绕开 AppLayout 的 100vh 溢出陷阱）。
  另起 `/m/shared/{token}` 等于要求分享时先猜对方设备在哪一端——做不到，也没收益。
- 通知 sheet 只做「有什么新事 + 直达」：筛选/清理/静音偏好留 PC 通知中心整页
  （管理向；`/notifications` 不在窄屏白名单，这是有意的）。
- 管理页（智能体/看板/评估…）在手机上仍是引导卡：按需再挑页做移动化
  ——「我的产物 /artifacts」与「知识库只读浏览 /knowledge（含 :id/docs）」已完成；下一候选=图谱弹窗移动化。
- 已销账（曾被列为待补，现已落地）：多智能体切换入口在「模型与思考」sheet 内（M3），
  会话分享 / 导出 Markdown / 会话内查找（5d0ff1b），通知与壳外入口、审批恢复横幅（36ce7d2），
  单轮导出 / 删除本轮 / 加入评测集 / 检索调试（MobileRoundSheet），
  我的产物移动化（51201b7）、知识库只读浏览（本批）。
