# 表单设计助手（FormCopilot）UI/UX 整体重做

## Context

报表系统（dynamic-form-vue）的 AI 助手 = 表单设计器的 FormCopilot 抽屉（头部「AI助手」按钮打开）。功能已完备（对话、schema 校验回喂、改动卡、撤销、图片/附件/截图问诊），但呈现层是测试版观感：消息是裸 `<pre>`、系统消息全是一行灰字刷屏、工具入口是裸 `<a>` 链接、无流式动画、流式期间强制滚底（用户上翻会被拽回）。本次只重做呈现层，**所有业务逻辑不动**（compose/runTurn/applyAnswer/undo/stop/token/上传/粘贴全保留），`api/formCopilot.js` 不碰。

环境约束：Vue 2.6.14 + antdv 1.7.8（图标用全局 `<a-icon type="...">`，已确认所需图标都有）、less 3、主题主色 #3C88FF（`src/styles/index.less:12` 的 `--primary-color`）、border-radius-base 2px 方正风。

## 改动文件

1. `dynamic-form-vue/src/views/form/copilot/FormCopilot.vue` — 主体重做模板+样式，逻辑保留
2. `dynamic-form-vue/src/views/form/copilot/mdRender.js`（新增）— markdown 渲染封装
3. `dynamic-form-vue/package.json` — 新增依赖 `marked@^4.3.0` + `dompurify@^3`（均带 UMD，兼容老 webpack）

## 具体条目

**① md 渲染**（用户已拍板引入 marked + DOMPurify）：`mdRender.js` 导出 `renderMd(text)`：先过现有 `displayText()`（JSON 块过滤逻辑复用不动），再 `marked.parse({ breaks: true })` → `DOMPurify.sanitize`。气泡内正文换 `.cp-md` 渲染，配套 less（p/ul/ol/li/code/pre/table/blockquote 间距配色，pre 深底白字圆角，超长横向滚动）。

**② 面板头**：替换现有 cp-bar——左侧圆形头像（`<a-icon type="robot">` 主色底白字）+「表单设计助手」+ 连接状态小圆点（绿=已连接/灰=未连接，替代 a-tag）；右侧两个 icon 按钮：新会话（`reload`，popconfirm 确认后走现有 resetSession）、设置（`setting`，开合 token 卡）。

**③ 未连接引导卡**：token 配置从顶部条移到会话区中央引导卡（图标+「填入问渠分享 token 后即可对话」+输入框+保存+hint），连接后消失。交互/存储逻辑不变（getToken/setToken/editing 流程保留）。

**④ 消息气泡体系**：
- 用户：右对齐、主色底白字圆角气泡；图片缩略图/附件 chips 保留在气泡上方
- 助手：左对齐白底描边气泡 + 头像；正文走 ①
- 系统：不再平铺灰字——居中细分隔条，按 tone 分色（neutral 灰 / success 绿 / warning 橙 / error 红），带小图标（check-circle/exclamation-circle）。`push()` 加 `tone` 参数，~15 个调用点补语义：告警→warning、失败→error、自动补齐/绑定成功→success、其余 neutral
- 流式状态：busy 时 assistant 气泡内 `loading` icon 旋转 + tool_status 文字直接呈现；正文尾部打字光标（CSS 闪烁 ▍）
- 每条消息底部小号时间戳（HH:mm）

**⑤ 改动卡精致化**：白底卡片+主色左竖条，头部「本轮改动」+summary；`+` 绿 / `−` 红 / `~` 蓝等宽行；底部「撤销这一轮」按钮化（a-button size=small，走现有 undo）或「已应用到画布」绿徽标。

**⑥ 输入区卡片化**：圆角卡片容器（聚焦主色描边）内含：待发送预览行 + 无边框 textarea + 工具行（icon 按钮：`picture`/`paper-clip`/`camera` + a-tooltip 说明，说明窗逻辑保留）+ 发送按钮（`send` icon，busy 时切换为 danger「停止」）。canSend/busy/uploading 判定全部复用现有 computed。

**⑦ 空状态引导**：居中大图标 +「描述你想要的表单，我来搭」+ 4 个建议 chips（点击填入输入框，不自动发送）：学生信息表弹窗录成员 / 手机号必填校验 / 字段级联 / 对照截图挑毛病；下方小字「支持直接粘贴截图、上传 Excel 样本」。

**⑧ 智能滚动跟随**：现在每条 chunk 强制 `scrollTop = scrollHeight`。改为贴底判据（`scrollTop + clientHeight >= scrollHeight - 80`）才跟随；用户上滚即停跟随并显示右下「回到底部」悬浮 pill，点击回底恢复跟随（判据与 wenqu ChatPage 踩坑一致，留白/flush 坑不适用此场景，纯判据即可）。

**⑨ 视觉规格**：抽屉 460→480px；消息区底 #f7f8fa 与白气泡分层；气泡圆角 8px、卡片 4px；硬编码 `#1890ff` 全部换 `var(--primary-color)`。

## 验证

1. `cd dynamic-form-vue && npm i marked dompurify` 装包成功
2. `npm run serve`（8000 栈）打开表单设计器 → AI助手，走查：未连接引导卡/token 保存 → 建议词 chip 填入 → 一轮真实对话（气泡、md 渲染、流式光标与状态行、贴底跟随/上滚出 pill）→ 生成表单后改动卡+撤销 → 粘贴截图/工具 tooltip → 系统消息三色分型
3. `npm run lint` 过
4. 该仓有他人未提交 WIP：只动上述 3 个文件，提交前 `git diff --stat` 核对不卷入别人的 hunk；用户要求时再按中文 conventional commit 提交
