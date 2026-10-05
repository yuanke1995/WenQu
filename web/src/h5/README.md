# h5 —— 手机端（真 H5）

本目录只放**移动端专属**的模块。判断标准很简单：

> 这个文件在 PC 宽屏下有没有用？有 → 不放这里（留在 `views/` / `components/` / `chat/`）。

## 架构：一套引擎，两个壳

```
src/chat/useChatEngine.js   聊天引擎（会话/流式/选择器/附件/引用）—— PC 与移动共用
src/chat/projections.js     消息投影与格式化纯函数（时间线/工具卡/来源分组/文案）—— 共用
src/views/ChatPage.vue      PC 壳（三栏工作台，保留既有窄屏补丁给「鼠标用户拖窄窗口」）
src/h5/MobileChatPage.vue   移动壳（/m/chat，独立布局，不经 AppLayout）
```

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
- `AppLayout` + 窄屏补丁**没有退役**：管理页（引导卡）、`/profile`、`/help` 与拖窄的桌面窗口仍走它。

## 移动壳的交互约定（与 PC 的差异，都是有意为之）

| 事项 | PC | 移动壳 |
|---|---|---|
| 发送 | Enter 发送 / Shift+Enter 换行 | 按钮发送，回车是换行（软键盘习惯） |
| 停止 | ESC 两段式上膛 + 按钮 | 直点停止钮（无物理键盘） |
| @ / # / 技能 / 附件 | 输入框敲字符唤起 + 「+」菜单 | 全部收进「+」底部 sheet（触屏不敲符号） |
| 引用来源 | 右栏常驻列 | 顶栏标题/状态 sheet + 角标点按进来源 sheet |
| 消息操作 | hover 出操作行 | 点气泡选中 / 最新一条常显 |
| 会话管理 | 侧栏常驻 | 底部 sheet（搜索/分组/置顶/重命名/删除） |
| 新问题置顶 | 尾随留白（桌面语义） | 不做；贴底跟随 + 回到底部浮钮 |
| 会话内查找 | Ctrl/⌘+F | 不做（无键盘；浏览器原生查找可用） |
| 分享/导出/评测/调试 | 页头与「更多」菜单 | 未做（见「待补」） |

## 键盘与安全区

`keyboard.js`（main.js 顶层安装）把 visualViewport 写成 `--app-vh` / `--kb`：
- 移动壳满高容器用 `--app-vh`（已扣键盘），键盘弹起时**容器收缩**而非 fixed 定位被盖住；
- 底部 sheet / 输入区的底部内边距用 `max(0px, var(--sab) - var(--kb))`：键盘开着时不再叠加安全区。

## Service Worker（PWA）

`public/sw.js` 只做一件事：**缓存带内容 hash 的 `/assets/*`**。
- 明确排除：`/s/*`、`/shared/*`（免登录分享，token 在 URL 里）、API、HTML 与一切非 `/assets/` 请求
  —— 分享内容不会被落到设备磁盘（旧版「不引 SW」的隐私顾虑由此消解）。
- 只在生产构建注册（`import.meta.env.PROD`）；dev 不注册，避免缓存干扰 vite。
- 资源名带 hash ⇒ 新版本新 URL，天然无陈旧问题；SW 版本号变更时清理旧缓存。

## 明确**不放**在这里的东西

- `ChatPage.vue` / `AppLayout.vue` —— PC 壳；手机不再走它们（但桌面窄窗口仍走）。
- `api.js` / `md.css` / `app.css` / 主题 token —— 全端共用。
- 第二个 `index.html` 入口 —— 刻意不做：antd 整包会在两个 entry 里各打一份。

## 静态校验

- `node scripts/verify-sfc.mjs`：SFC 编译、模块解析、CSS 语法、项目约束、PWA 资源存在性。
- `node scripts/check-engine-imports.mjs`：projections 引用完整 + 从引擎 inject 解构的名字必须真实存在
  （两类漏接都只在真浏览器渲染时才炸，这里前移为静态失败）。
- `node scripts/check-mshell.cjs`：412×916 触屏跑 dist 产物，验重定向/结构/热区/sheet 开合/桌面弹回。
- `node scripts/check-browser.cjs`：PC 壳（含鼠标拖窄窗口）回归。
`npm run check` 串起静态三件套；`check:browser` / `check:mshell` 需 playwright-core，单独跑。

## 待补（v1 有意留白，按需再做）

- 会话分享（生成只读链接）与「导出 Markdown」未进移动壳；
- 检索调试 / 加入评测集 / 删除本轮等管理向操作未进移动壳；
- 多智能体胶囊：移动壳用「模型与思考」sheet 承载模型/思考/窗口，未做智能体切换入口
  （引擎已有 `pickAgent`，接一个 sheet 即可）。
