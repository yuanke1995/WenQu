# 权限界面 UI/UX / 功能升级方案（先去出方案与效果图，确认后再写代码）

## 0. 结论先行

| 项 | 结论 |
|---|---|
| **本次第一步** | 产出 `docs/permissions-upgrade/permissions-upgrade.html`：完整方案书 + 8 张高保真效果图（两套信息架构对比），**这一步不写业务代码** |
| **确认后再做** | 用户在效果图中选定方向（A 渐进增强 / B 角色中心），再按本文 §7 实施 |
| **范围** | 权限管理主页面（菜单 / 接口 / 角色 / 权限配置抽屉 / 有效权限视图）+ 并入的认证源 / 服务账号 / 内容安全三个 Tab 的视觉与交互统一 |
| **不做** | 不改鉴权判定链路、不改数据库表结构、不重写成员管理页（只做角色↔成员跳转与只读成员抽屉） |
| **后端** | 只补 3 个只读能力：概览统计、菜单→可见角色反查、授权点中文展示名；不新增写接口 |
| **默认推荐** | 方向 A「渐进增强」：保留现有三页签骨架，风险低、改动可控；方向 B 作为效果图对比与后续升级路径 |

> 本文是**执行计划**，不是最终方案书。方案书的正文、效果图、接口契约将按本文 §4～§6 生成到 HTML 文件中。

---

## 1. 现状诊断（基于代码与截图，非猜测）

### 1.1 现状截图

用户上传的截图已保存在 `.uploads`（只读）。执行时复制到 `docs/permissions-upgrade/assets/current-permissions.png`，作为方案书「现状」一节的对照图，**不修改原文件**。

### 1.2 代码事实

| 编号 | 现状 | 证据 |
|---|---|---|
| S1 | 页面本质是三张管理表，页头只有标题，没有权限域概览与健康度 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L2-L24) |
| S2 | 六个页签平铺：菜单 32 / 接口 466 / 角色 5 / 认证源 / 服务账号 / 内容安全，主任务与治理任务混排 | 同上；[router.js](file:///Users/yuki/IdeaProjects/wenqu/web/src/router.js#L63-L63) |
| S3 | 接口页签 466 行一次性渲染，无分页、无模块导航、无批量操作，定位一个接口只能靠搜索 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L108-L163) |
| S4 | 接口页「授权点 / 已授权角色」一列混装两种信息；反查失败时显示「未授权给任何角色」，会把故障读成事实 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L129-L140)；[rbac-fix-checklist.md](file:///Users/yuki/IdeaProjects/wenqu/docs/rbac-fix-checklist.md#L121-L128) |
| S5 | 角色页签只有 5 行，列里没有权限数 / 菜单数 / 最近变更；成员数只是数字，不可点进成员清单（后端已有 `GET /role/{code}/members`） | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L166-L221)；[RoleController.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/controller/RoleController.java#L178-L185) |
| S6 | 菜单页签「图标」列与菜单名左侧图标重复；「排序」是只读文本；没有「被哪些角色可见」反查 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L50-L101) |
| S7 | 权限抽屉：菜单树与授权点树两段分离，110 个授权点全是裸编码（`manual:sync`、`chat:tool-output`），分组按 Controller 中英混杂 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L382-L441)；[ApiEndpointScanner.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/startup/ApiEndpointScanner.java#L446-L448)；[rbac-fix-checklist.md](file:///Users/yuki/IdeaProjects/wenqu/docs/rbac-fix-checklist.md#L94-L117) |
| S8 | deny 用行尾文字按钮「排除 / 只压本角色」表达，没有三态（允许 / 禁止 / 未置），语义靠提示文字解释 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L405-L430) |
| S9 | 抽屉没有脏状态与离开确认；加载失败时勾选为空但保存仍可点，存在「全量替换清空权限」风险 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L970-L1015)；[rbac-fix-checklist.md](file:///Users/yuki/IdeaProjects/wenqu/docs/rbac-fix-checklist.md#L121-L133) |
| S10 | 有效权限视图要手输 uid，不展示 `globalDenies`，也没有「这个点是谁给的」来源信息 | [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue#L313-L335)；[RoleController.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/controller/RoleController.java#L194-L205) |
| S11 | 三个并入 Tab 的页头 / 工具栏 / 按钮 / 空态各写一套（`head-count`、`sr-only title`、`a-button type=link` vs `app-link-btn`） | [ServiceAccountsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/ServiceAccountsPage.vue#L1-L12)；[IdentityProvidersPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/IdentityProvidersPage.vue#L1-L11)；[ContentSafetyPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/ContentSafetyPage.vue#L1-L20) |

### 1.3 管理员真正回答不了的四个问题

1. **谁能进什么？** 当前只能逐个角色开抽屉看，或手输 uid 查有效权限。
2. **哪些角色是空壳？** 成员数为 0、权限数为 0、停用角色，没有聚合视图。
3. **哪些接口没人管？** 466 行里未授权、未挂菜单的接口没有筛选入口。
4. **这次改动会影响谁？** 保存权限前没有差异摘要，菜单隐藏前只靠弹窗警告。

---

## 2. 设计目标与原则

1. **首屏先给结论，再给表格**：进入页面 3 秒内看到角色 / 授权点 / 接口 / 菜单的规模和异常项。
2. **每个数字可下钻**：角色成员数、授权点数、菜单可见角色数、接口未授权数都能点进对应清单。
3. **配置权限时看得懂**：授权点用「业务域 + 中文名 + code」，三态（允许 / 禁止 / 未置）互斥可见。
4. **不丢改动、不静默失败**：脏状态、离开确认、加载失败禁用保存、保存结果摘要。
5. **沿用现有设计语言**：只使用 `web/src/views/app.css` 的令牌与 `.app-list-bar / .app-card / .app-btn / .app-pill` 等既有类，不引入新视觉体系。
6. **两套方向同图对比**：A 渐进增强 vs B 角色中心，按效果图拍板后再写代码。

---

## 3. 两套信息架构（方案书 §04 的核心）

### 方向 A：渐进增强（默认推荐）

```
权限管理  [有效权限查询] [帮助]
├─ 概览条：角色 5 · 授权点 109 · 接口 466 · 菜单 32 · 待处理 2
├─ 页签组 1：菜单 | 接口 | 角色
├─ 页签组 2（分隔线 + 小标题「接入与治理」）：认证源 | 服务账号 | 内容安全
└─ 各页签内容（保留现有列表骨架，上加筛选/批量/反查，行内下钻）
```

- 角色页签：表格增强 + 成员抽屉 + 权限配置抽屉 2.0 + 克隆角色。
- 接口页签：左侧模块导航 + 分页 + 状态筛选 + 批量归属菜单。
- 菜单页签：渲染位置筛选 + 可见角色反查 + 拖拽排序。
- 优点：改动局部化、上线风险低、与现有路由/页签机制完全兼容。
- 代价：角色视角仍需要「进页签 → 找角色 → 开抽屉」。

### 方向 B：角色中心工作台

```
权限管理  [有效权限查询] [新建角色]
├─ 二级导航：角色（默认） | 菜单 | 接口 | 接入治理
├─ 左栏：角色列表（搜索 / 状态筛选 / 角色卡片：名称·code·成员数·权限数·状态）
└─ 右栏：选中角色详情
   ├─ 头部：角色名 + 内置/管理员级/停用 pill + 成员·菜单·授权点·数据范围 + 操作
   ├─ 分段：权限配置 | 成员 (N) | 数据范围 | 变更记录
   └─ 权限配置：左「可见菜单」右「功能授权点」，底部常驻保存条
```

- 优点：以「角色」为中心回答「谁能做什么」，配置路径最短，适合管理员日常巡检。
- 代价：主页面需要重写，菜单/接口管理变成二级导航；改动量大，需要更多回归验证。
- 建议：如果本期只能选一个，选 A；如果团队更常按角色巡检，选 B，并把 A 的增强组件直接复用进右栏。

### 对比维度（方案书内表格）

学习成本 / 首屏信息密度 / 多角色对比效率 / 权限配置步数 / 菜单与接口管理可达性 / 改动文件数 / 回归风险 / 适配现有路由机制 / 推荐场景。

---

## 4. 页面与组件规格（决定实现细节）

### 4.1 全局与页头

- 页头：`权限管理` + 说明「角色 · 菜单 · 接口 · 接入治理」；右侧常驻 `有效权限查询`（ghost）+ `新建角色`（仅角色页签显示，或方向 B 常驻）。
- 概览条（方向 A 与 B 共用）：
  - 5 个紧凑指标卡，高 64px，一排在 ≥1100px；<1100px 两行；<768px 横向滚动。
  - 指标：角色（启用/停用）、授权点、接口（未授权数）、菜单（隐藏数）、待处理（空成员角色 + 7 天内临期授权）。
  - 每卡可点击：角色卡 → 角色页签；接口卡 → 接口页签并预置「未授权」筛选；菜单卡 → 菜单页签并预置「隐藏」；待处理卡 → 展开待处理清单。
  - 数字为 0 时弱化显示，不制造噪音。
- 页签：保留 antd Tabs；`认证源 / 服务账号 / 内容安全` 前加 12px 分隔与「接入与治理」小标签；badge 仅在有数时显示。

### 4.2 角色页签（方向 A）

- 工具栏：搜索、状态筛选（全部/启用/停用）、类型筛选（全部/管理员级/业务角色）、`新建角色`。
- 列：
  1. **角色**：名称 + code + 「内置」「管理员级」pill。
  2. **成员**：头像堆叠（最多 4）+ 数字；0 人显示 warn 色；点击打开成员抽屉。
  3. **授权**：菜单数 + 授权点数（允许/禁止分开计数）；用 12px 迷你条表示「已授予 / 全部」。
  4. **数据范围**：pill（本人 / 本部门 / 本部门及下级 / 全部只读）。
  5. **状态**：启用 / 停用。
  6. **最近更新**：`updateTime` 相对时间（hover 显示绝对时间）。
  7. **操作**：`权限配置`（主）· `更多`（编辑 / 复制 / 成员 / 有效权限 / 删除）。
- 行内「权限配置」打开抽屉；`更多` 用 `a-dropdown`，删除仍走 `a-popconfirm`。
- 克隆角色：前端预填「原名称 + 副本」与 `code-copy`，先 `createRole`，再 `saveRolePermissionPoints` 复制原角色权限；失败时明确提示「角色已建、权限未复制」。
- 成员抽屉（新组件 `RoleMembersDrawer.vue`）：
  - 标题「角色成员 · 角色名」；列：账号、昵称、授予来源（手动 / 认证源）、状态、操作「有效权限」。
  - 数据源 `getRoleMembers(code)`；空态「该角色还没有成员」+ 「去成员管理」链接。
  - 授权来源为 IdP 的行只读并标注（与 D-5 口径一致）。

### 4.3 权限配置抽屉 2.0（方向 A）／右栏权限面板（方向 B）

两者共用同一个权限编辑器组件（`PermissionEditor.vue`）与同一套数据模型：

- **头部摘要**：角色名 + 内置/管理员级/停用 pill；`成员 x · 菜单 y · 允许 z · 禁止 w · 数据范围`。
- **可见菜单区**：紧凑树，支持搜索、展开/收起、全选/清空；勾选联动子树；显示「已选 n / 共 m」。
- **功能授权点区**：
  - 分组从前端按 **code 前缀业务域** 聚合（替代 Controller 分组，落实 R-08）：`chat` 对话、`kb/knowledge` 知识库、`manual` 手册、`agent` 智能体、`user` 成员、`role` 角色与权限、`menu` 菜单、`api` 接口、`skill` 技能、`workflow` 工作流、`stats` 统计、`tenant` 租户、`ops` 运维、`service-account` 服务账号、`webpush` 推送等；未知前缀归「其他」。
  - 组头：业务域名 + `已允许/总数` + 禁止数；组操作菜单「全选允许 / 全部禁止 / 清空」。
  - 行：`中文名 + code` 双段；右侧三态分段控件 `允许 | 禁止 | 未置`；选「禁止」时出现「仅本角色内生效」开关（对应 exempt）。
  - 三态互斥：允许与禁止不会同时存在（呼应 R-01）。
  - 搜索与筛选：全部 / 已允许 / 已禁止 / 未授予。
- **底部常驻栏**：
  - 左：`N 项未保存` + `撤销`；右：`取消` / `保存权限`。
  - 保存成功提示带摘要：`已保存：菜单 12 · 允许 34 · 禁止 2`。
  - `loadFailed` 时显示错误横幅 + `重试`，保存禁用（落实 R-09 a/b）。
  - 脏状态下关闭抽屉/切角色弹确认。
- **管理员级**：顶部说明「管理员级默认拥有全部动作，只需配置禁止项」；允许区置灰但保留可见，禁止区可操作。
- 尺寸：方向 A 抽屉宽 760px；方向 B 右栏自适应 `minmax(0,1fr)`。

### 4.4 接口页签

- 布局：左侧 200px 模块导航（全部 466 / 未授权 12 / 未挂菜单 8 / 扫描登记 464 / 手工登记 2 + 各业务模块计数），右侧表格。
- 表格列：方法、路径（点击复制）、名称、授权点（中文名 + code chip）、已授权角色（头像/缩写 chips，最多 3 + `+N`）、所属菜单（最多 2 + `+N`）、来源（扫描/手工）、操作。
- 分页：默认 50 条/页；保留搜索与模块筛选；分页器显示总数。
- 批量：勾选行后浮出操作条 `已选 N 项 · 批量归属菜单 · 清除`；批量归属逐条调用现有 `updateApi`。
- 反查失败：列显示「取数失败 · 重试」，不再显示「未授权给任何角色」。
- 空态区分：无数据 / 无搜索结果 / 筛选无结果。

### 4.5 菜单页签

- 工具栏：搜索、渲染位置筛选（全部 / 侧栏 / 页内 Tab / 分组 / 仅权限）、`展开全部` / `收起全部`、`新建菜单`。
- 列：菜单（图标 + 名称 + 内置 + 渲染位置 pill）、路径（可复制）、渲染位置、排序（拖拽手柄）、**可见角色**（最多 3 个角色 chip + `+N`，数据来自新接口）、可见开关、操作。
- 拖拽排序：同父级内拖拽，乐观更新 `sortOrder`（10/20/30 序列）并调用 `updateMenu`；失败回滚并提示。
- 图标列删除（信息已在菜单名左侧展示）；图标编辑保留在弹窗。
- 隐藏父级的副作用提示保留，并补充「会影响的页内 Tab」清单。

### 4.6 有效权限视图 2.0

- 入口：页头常驻 + 角色行 + 成员抽屉行。
- 表单：账号输入支持从成员搜索选择（`getRoleMembers` / 成员列表按需）；也允许手输 uid。
- 结果：
  - 顶部：账号 + 角色 chips + 管理员级横幅。
  - 授权点按业务域分组；每行标注来源角色（需要前端对每个角色 `getRolePermissions` 做一次聚合，5 个角色可接受）。
  - `globalDenies` 单独红色分组「被禁止」，与允许项视觉区分。
  - 底部：`导出 JSON` / `复制清单`。
- 空态与失败态明确区分。

### 4.7 接入与治理三个 Tab 统一

统一为同一套壳：

- 页头：标题（可见）+ 计数 + 说明 + 主操作按钮（右对齐）。
- 工具栏：`.app-list-bar.in-card`（搜索 240px + 筛选 + 右侧操作）。
- 列表：`.app-card` 内表格，`size="middle"`，操作列统一 `.app-link-btn` / `.app-link-btn.danger`。
- 空态 / 加载 / 失败：统一组件与文案；失败给「重试」。
- 认证源、服务账号、内容安全各自的业务弹窗只改外壳间距与按钮，不改业务字段。
- 方向 B 下，这三个 Tab 收进「接入治理」二级导航，与审计事件并列。

### 4.8 响应式与无障碍

- ≥1240px：完整布局；1100～1240：概览条两行、接口左栏收窄；768～1100：表格横向滚动、工具栏换行；<768：概览卡横滚，抽屉全屏。
- 表格操作列按钮保留 `.app-link-btn` 的键盘焦点环；拖拽提供键盘替代（排序数字输入仍在弹窗内）。
- 所有图标按钮补 `aria-label` / `title`；开关补 `aria-checked`。
- 深色主题只依赖 `app.css` 令牌，不新增硬编码颜色。

---

## 5. 数据与接口方案

### 5.1 新增只读接口（后端）

1. **`GET /api/ai/role/overview`**（`@Permission("role:read")`）
   - 响应：
     ```json
     {
       "summary": {
         "roles": 5, "activeRoles": 4, "adminRoles": 2, "rolesWithoutMembers": 1,
         "menus": 32, "hiddenMenus": 3,
         "apis": 466, "apisWithoutPermission": 12, "apisWithoutMenu": 8,
         "permissions": 109, "expiringGrants": 0
       },
       "roles": [
         { "code": "admin", "memberCount": 2, "menuCount": 12, "permissionCount": 0, "lastUpdated": "2026-11-07T10:00:00" }
       ]
     }
     ```
   - 实现：`RoleService.overview()`，复用 `roleMapper`、`memberCountsByRole()`、`menuMapper`、`apiEndpointMapper`、`permissionCatalogMapper`、`userRoleService.expiringWithinDays(7)`。
   - 只读聚合，不改鉴权缓存。
2. **`GET /api/ai/role/menu-roles`**（`@Permission("role:read")`）
   - 响应：`{ "menu-chat": ["admin","user"], "...": [...] }`。
   - 实现：`RoleMenuMapper` 增加 `SELECT role_code AS roleCode, menu_id AS menuId FROM c_ai_role_menu` 批量查询；`RoleService.rolesByMenu()` 组装。
3. **授权点中文展示名**
   - `ApiEndpointScanner` 增加 `PERMISSION_ZH`（覆盖当前 109 个 `@Permission` code），`permissionDisplayName()` 返回中文名。
   - 目录表回填：新增 `PermissionCatalogMapper.backfillNameIfSame(code, name)`，仅在存量 `name == code` 时更新，避免覆盖管理员手工命名。
   - 前端仍按 `code` 作为唯一键，展示层用 `name`；搜索同时匹配中文名与 code。

### 5.2 可选小补（若时间允许）

- `MODULE_ZH` 补齐 R-06 缺失的 10 个 Controller 中文名（`Goal/IdentityAdmin/Market/Ops/ServiceAccount/WebPush/ProjectFileView/Saml/SandboxToolCallback/Scim`），删除幻影键 `OidcProvider`。
- 接口页「所属菜单」批量修改在现有 `PUT /api/{id}` 循环完成，不新增批量接口。

### 5.3 前端复用（不新增）

- `getRoleMembers`、`getExpiringRoles`、`getRoleDataScope`、`saveRoleDataScope`、`getEffectivePermissions`、`getPermissionCatalog`、`getPermissionRoles`、`getRoleMemberCounts`、`listAuditEvents`。
- 角色「变更记录」用 `listAuditEvents` + `keyword=角色 code`，不新增审计接口。

---

## 6. 交付物：方案书 + 效果图

### 6.1 文件

- `docs/permissions-upgrade/permissions-upgrade.html`：自包含 HTML（内联 CSS/JS），沿用 `docs/artifacts-upgrade/artifacts-upgrade.html` 的版式与 `app.css` 令牌。
- `docs/permissions-upgrade/assets/current-permissions.png`：现状截图（复制自 `.uploads`，只读原件不动）。
- 不新增其他文档；Markdown 方案只保留本计划文件。

### 6.2 文档章节

| 章节 | 内容 |
|---|---|
| 封面 | 标题、范围、状态「待选方向」、日期、指标带 |
| 01 一页结论 | 4 张摘要卡：现状问题数、两套方向、建议、后端改动 |
| 02 现状诊断 | 现状截图 + §1.2 问题清单（按严重度 P1/P2/P3） |
| 03 设计目标与原则 | §2 六条 |
| 04 信息架构对比 | A/B 结构图 + 对比表 + 推荐 |
| 05 效果图 · 方向 A | A1 角色页签总览（含概览条）、A2 权限配置抽屉、A3 接口页签、A4 菜单页签 |
| 06 效果图 · 方向 B | B1 角色中心工作台、B2 权限配置右栏、B3 成员/变更记录 |
| 07 效果图 · 接入与治理 | C1 认证源列表统一壳（服务账号/内容安全同壳说明） |
| 08 组件与交互规格 | 三态控件、脏状态、筛选、抽屉、加载/空/错误态、键盘 |
| 09 数据与接口 | §5 的 3 个新增能力 + 复用清单 |
| 10 实施计划 | §7 阶段表 |
| 11 验收标准 | §9 |
| 12 待确认决策 | 方向 A/B、中文名是否本期做、拖拽排序是否本期做、审计是否进权限域 |

### 6.3 效果图制作要求

- 尺寸：内容宽度 1120px；外壳为应用桌面布局（左侧栏 + 内容区），与现状截图一致。
- 所有颜色、圆角、字号取自 `app.css` 令牌；方法 pill、状态 pill、角色 pill 复用现有样式语义。
- 每张图配 3～5 条标注，说明「新增了什么、为什么」。
- 图中示例数据明确标注为「示例数据」；不写成真实统计承诺。
- 方向 A 与方向 B 使用同一批组件视觉，确保对比只体现信息架构差异。

---

## 7. 实施步骤

### 阶段 0：产出方案书与效果图（本次执行的第一段）

1. 创建 `docs/permissions-upgrade/assets/`，复制现状截图。
2. 按 §3～§6 编写 `permissions-upgrade.html`，完成 8 张效果图。
3. 用浏览器打开 HTML 自检：无横向溢出、深色模式正常（文档可自带明暗跟随或仅浅色）、锚点导航可用。
4. 调用 `NotifyUser` 通知评审；**不开始任何代码改动**，等用户选定方向。

### 阶段 1：后端只读能力（选定方向后）

- [RoleController.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/controller/RoleController.java)：新增 `overview`、`menu-roles` 两个 GET。
- [RoleService.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/service/RoleService.java)：新增 `overview()`、`rolesByMenu()`。
- [RoleMenuMapper.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/mapper/RoleMenuMapper.java)：新增全量绑定查询。
- [PermissionCatalogMapper.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/mapper/PermissionCatalogMapper.java)：新增「name==code 才回填」方法。
- [ApiEndpointScanner.java](file:///Users/yuki/IdeaProjects/wenqu/src/main/java/com/wenqu/ai/startup/ApiEndpointScanner.java)：`PERMISSION_ZH`、`permissionDisplayName` 实现、回填调用；可选补 `MODULE_ZH`。

### 阶段 2：前端公共层

- [api.js](file:///Users/yuki/IdeaProjects/wenqu/web/src/api.js)：新增 `getRoleOverview`、`getMenuRoles`。
- 新建 `web/src/utils/permissions.js`：业务域分组、域中文名、`code → 中文名` 回退、三态常量、计数工具。
- 新建 `web/src/components/permissions/`：
  - `OverviewStrip.vue`（概览指标卡）
  - `PermissionTristate.vue`（允许/禁止/未置 + exempt）
  - `PermissionEditor.vue`（菜单树 + 授权点分组 + 脏状态 + 保存条）
  - `RoleMembersDrawer.vue`
  - `EffectivePermissionsModal.vue`
- 全部组件只用 `app.css` 令牌与既有通用类。

### 阶段 3：按选定方向实现主页面

**方向 A（默认）**

- 改造 [PermissionsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/PermissionsPage.vue)：
  - 页头加概览条与有效权限入口；页签加「接入与治理」分隔。
  - 角色页签：新列、筛选、成员抽屉、克隆、权限数/菜单数（来自 overview）。
  - 接口页签：模块导航、分页、批量、反查失败态。
  - 菜单页签：渲染位置筛选、可见角色列、拖拽排序、删除图标列。
  - 抽屉替换为 `PermissionEditor`；有效权限弹窗替换为 `EffectivePermissionsModal`。
- 保持现有 URL `?tab=` 与懒加载机制不变。

**方向 B**

- 新建 `web/src/components/permissions/RoleWorkbench.vue`（左角色栏 + 右详情分段）。
- 改造 PermissionsPage 为「角色 / 菜单 / 接口 / 接入治理」二级导航；复用 A 的接口页、菜单页、编辑器与成员抽屉。
- 现有三个固有页签的内容迁移到二级导航，URL 采用 `?view=roles&code=admin&tab=permissions` 形式（兼容旧 `?tab=menus|apis|roles` 重定向）。

### 阶段 4：三个并入 Tab 统一

- [IdentityProvidersPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/IdentityProvidersPage.vue)、[ServiceAccountsPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/ServiceAccountsPage.vue)、[ContentSafetyPage.vue](file:///Users/yuki/IdeaProjects/wenqu/web/src/views/ContentSafetyPage.vue)：
  - 统一页头结构、工具栏、按钮类、表格操作列、空/错/加载态。
  - 不改业务字段与接口调用。
- 认证源 / 服务账号的角色列统一使用角色 pill 与角色名映射。

### 阶段 5：验证与收尾

- `cd web && npm run build`
- `mvn -q -DskipTests compile`
- 浏览器逐项验收（见 §9）
- 清理临时截图与调试文件。

---

## 8. 假设与决策

| 编号 | 决策 | 依据 |
|---|---|---|
| D1 | 范围包含认证源 / 服务账号 / 内容安全三 Tab | 用户确认「另外 3 个是这一块的内容」 |
| D2 | 允许小幅后端补充，但仅只读聚合与展示字段 | 用户选择「允许小幅新增」 |
| D3 | 两套方向都出效果图，等用户选定后再写代码 | 用户选择「两种都出效果图对比」 |
| D4 | 不改数据库表结构、不改 `@Permission` 与鉴权判定 | 方案约束，避免把 UI 升级变成 RBAC 重构 |
| D5 | 授权点分组改为前端按 code 前缀业务域聚合 | 落实 R-08，后端 `module` 保持兼容接口页展示 |
| D6 | 授权点中文名由后端扫描器维护并回填 | 落实 R-07；前端保留 code 回退 |
| D7 | 成员抽屉只读，不重写成员管理页 | 控制范围；需要编辑时给「去成员管理」链接 |
| D8 | 方向 A 为默认推荐；若用户选 B，A 的组件全部复用 | 降低返工 |
| D9 | 效果图示例数据明确标注，不当作真实统计 | 避免误导评审 |
| D10 | 深色主题只靠现有令牌，不新增硬编码色 | 与 app.css 一致 |

---

## 9. 验收标准

### 9.1 方案书

- [ ] HTML 可在浏览器直接打开，8 张效果图完整，无横向溢出。
- [ ] 两套方向各有一张「同场景」对照图，差异一眼可辨。
- [ ] 现状诊断每条都有代码/截图证据，不写空泛评价。
- [ ] 新增接口契约、实施阶段、验收标准齐全。

### 9.2 功能（选定方向后）

- [ ] 概览条数字与列表数据一致；点击指标能落到对应页签/筛选。
- [ ] 角色成员数可点开抽屉，成员清单与 `GET /role/{code}/members` 一致；IdP 授予行只读。
- [ ] 接口页可分页、按模块筛选、按「未授权 / 未挂菜单」筛选；批量归属成功与失败都有明确反馈。
- [ ] 菜单页可见角色反查正确；拖拽排序落库并在刷新后保持；隐藏父级的 Tab 副作用提示准确。
- [ ] 权限编辑器三态互斥；允许 + 禁止同点无法同时提交；exempt 开关保存后回显。
- [ ] 抽屉加载失败时保存禁用并可就地重试；网络恢复后勾选完整回显（R-09）。
- [ ] 脏状态离开有确认；保存成功提示包含菜单/允许/禁止数量。
- [ ] 有效权限视图展示角色来源、`globalDenies`，管理员级横幅正确。
- [ ] 认证源 / 服务账号 / 内容安全三个 Tab 的页头、工具栏、操作列、空/错态视觉一致。
- [ ] 1240 / 1100 / 768 / 375 四档无横向溢出（表格内部横向滚动属于预期）。
- [ ] 浅色与深色主题下无对比度异常、无硬编码颜色穿帮。
- [ ] `npm run build` 与后端编译通过。

### 9.3 不回归

- [ ] 现有 `?tab=menus|apis|roles` 深链仍然有效（方向 B 下提供重定向）。
- [ ] 非管理员访问 `/permissions` 的拦截行为不变（路由与后端 fail-closed）。
- [ ] 角色权限保存仍走唯一写路径 `PUT /role/{code}/permissions`，菜单/授权点/deny 一次提交。
- [ ] 侧栏菜单下发（`/auth/me`）与菜单可见性逻辑不变。

---

## 10. 风险与回滚

| 风险 | 处理 |
|---|---|
| 改动面大导致权限配置回归 | 编辑器逻辑抽成独立组件并保留旧组件到验证通过；权限保存仍用现有接口 |
| 授权点中文名回填覆盖人工命名 | 只回填 `name == code` 的行；人工命名永不被覆盖 |
| 概览接口增加首屏请求 | 与现有列表并行请求；失败降级为「—」并保留列表可用 |
| 拖拽排序误操作 | 同父级限制 + 乐观更新 + 失败回滚 + 排序数字仍可编辑 |
| 方向 B 改动过大 | 先交付方向 A；B 仅在用户明确选择后实施，并复用 A 的组件 |

---

## 11. 下一步（本次执行）

1. 生成 `docs/permissions-upgrade/permissions-upgrade.html`（方案书 + 8 张效果图）。
2. 通知用户评审：在「方向 A」与「方向 B」中选定一个，并确认 §12 的 4 个决策。
3. 收到选择后，从阶段 1 开始写代码。
