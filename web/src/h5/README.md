# h5 —— 移动端窄屏适配

本目录只放**移动端专属**的模块。判断标准很简单：

> 这个文件在 PC 宽屏下有没有用？有 → 不放这里（留在 `views/` / `components/` / `utils/`）。

## 目录内容

| 文件 | 作用 | 为什么是移动端专属 |
|---|---|---|
| `mobile.js` | `isNarrow` / `isCoarse` / `isTouch` 三条能力判据 | 宽屏下三者恒为 false，消费方全部走 PC 分支 |
| `keyboard.js` | visualViewport → 写 `--kb` / `--app-vh` 到 `:root` | 宽屏不写变量（`--kb` 恒 0），PC 行为零变化 |
| `MobileTopBar.vue` | 窄屏顶栏 | 宽屏由侧栏承担导航，顶栏不渲染 |
| `DesktopOnlyGuard.vue` | 窄屏下管理类页面的引导卡 | 宽屏不渲染 |

## 明确**不放**在这里的东西

- **`ChatPage.vue` / `AppLayout.vue`** —— 它们是 PC 与移动端**共用**的同一份组件。
  窄屏适配走 CSS 媒体查询 + `isNarrow` 分支，不 fork 文件。
  理由：`ChatPage.vue` 有 4861 行、100+ 个 computed 全部作用在同一个 `messages` 数组上，
  fork 一份必然与 PC 版漂移（改一个 bug 要改两处，忘一处就是线上不一致）。
- **`api.js` / `md.css` / `app.css` / 主题 token** —— 全端共用。
  窄屏差异写在各自的 `@media (max-width: 768px)` 块里，不另起一份。
- **第二个 `index.html` 入口 / 第二套 router** —— 刻意不做。
  同一份构建产物、同一套路由，窄屏只是布局形态不同。
  多入口会让 antd 整包在两个 entry 里各打一份，产物体积翻倍。

## 三条纪律

1. **布局 100% 走 CSS `@media (max-width: 768px)`**，JS 只在必须改行为/模板处用 `isNarrow`。
   antd 的 modal/drawer/select 虽 teleport 到 body，但媒体查询匹配的是**视口宽度**、
   与 DOM 层级无关 —— 写在 `app.css` 的 768 规则对浮层照样命中。
2. **禁用 UA 判断**。`userAgent` 分不出 iPad，更分不出「桌面窗口缩窄」——
   后者必须留在 PC 行为里，一旦误判就违反「PC 端零改动」。
3. **宽度 ≠ 能力**。`isNarrow` 管布局形态，`isCoarse` 管 hover 降级与热区，
   `isTouch` 管桌面专属能力降级，三者不可混用。

## 布局壳约定

左侧栏 `AppLayout.vue` 的 `<aside class="side">` 在窄屏下由 CSS 整体变左侧抽屉，
**DOM 结构一个字节都不改**（`.side` 加 `position:fixed` + `translateX`，`.side.open` 归零）。
另建抽屉组件会导致会话搜索防抖、分组折叠、批量模式、游标分页、置顶/重命名/删除
约 200 行逻辑要么复制一遍、要么抽 composable —— 两份实现必然漂移。

## 静态校验

`node scripts/verify-sfc.mjs`（在 `web/` 下执行）：SFC 编译、模块解析、CSS 语法、
项目约束（断点值 / 裸色值 / 禁用词，只查相对 HEAD 的增量）、PWA 资源存在性。
