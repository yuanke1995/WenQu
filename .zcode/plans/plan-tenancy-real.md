# 多租户体系完善方案——三刀把「CRUD 玩具」补成真体系

- 日期：2026-11-07
- 状态：方案待拍板（§4 三个拍板点）；**刀一 P0 不依赖拍板，可直接开工**
- 审计方式：三路并行代码审计（管理面 / 资源侧 / 前端），结论均带 file:line；按 2026-11-07 产物政策提交留痕

## 0 结论先行

「增删改查自己玩的体系」这个体感，审计后的定性是：**底座是真的，面是假的**。

底座已到位、无需重做：

- 行级租户过滤：62 张业务表带 `tenant_id`（`TenantLineInnerInterceptor` 按豁免名单自动拼谓词，`WenquTenantLineHandler.java:41-55`；`SchemaMigrator` 启动归一补列）
- 租户键控 RBAC：角色域四表按租户隔离（批次 F），每租户播种 superadmin/admin/user + 受限管理员预设；判定快照按租户分格、TTL 60s + 跨副本广播失效（`RoleService.java:86-177`）
- 数据范围/部门树快照同款租户键控（14ddab97 修过跨租户复用越权）
- 配额三档（tenant/department/user）独立叠加、规则缓存按租户分格（b3cd0c82 修过未绑定线程按全平台聚合的串号）
- 限流桶键「桶名:租户段:维度」（`RateLimitService.java:78,131-134`）
- 台账记账就地盖租户戳（`UsageLedgerService.java:92-95`）；费用报表平台级「按租户结算」按行归属归因（`UsageStatsService.java:342-348`）
- SSO/SCIM/LDAP 建号落**认证源自己的租户**（`ExternalUserService.inProviderTenant`）；认证源换归属四表 restamp 随迁
- 服务账号令牌校验**先绑归属租户再取角色**（`UserContextInterceptor.java:121-135`，顺序有注释背书）
- 停用租户挡登录四入口同判据（密码 `AuthService.java:144-146` / SSO / SCIM 403 / 目录同步）
- 按租户备份恢复脚本 + 落点清单端点 + Helm chart（8c2b7be7）
- 越权自查（本审计顺手核过）：租户管理员无法经角色域升到平台级——同租户内置 superadmin 占码（`create` 查重「角色编码已存在」）+ 内置角色不可删（`RoleService.java:556`）+ 内置角色 admin_flag/status 锁死；自建 admin_flag=1 角色只是本租户内的授权放大，属设计内能力

「玩具感」的真实来源（本方案要修的）：

1. **面上一堆点了必 403 的按钮 + 全站无「当前租户」指示**——租户管理员打开管理页像进了别人的驾驶舱
2. **资源侧三大件没有租户档**：供应商/模型（平台全局，注释与实际已漂移）、市场（平台共享，租户私有档代码自己预告了没做）、平台配置（全局）
3. **游客分享归属失真**：游客产生的会话/消息/qa_log 落 default 租户，按租户结算口径是错的
4. **生命周期只有「开通+停用」两端**：无邀请流、无清退、无租户感知登录/品牌

## 1 现状能力矩阵

| 能力 | 状态 | 证据 |
|---|---|---|
| 数据行级隔离（62 表） | ✅ | `WenquTenantLineHandler.java:41-55` + SchemaMigrator 归一 |
| 租户内成员自助管理（增删改/重置密码/强制下线） | ✅ 租户管理员今天就能用 | `OrgService.asPlatformIfSuper` 落本租户（`OrgService.java:274-375`） |
| 租户自定义角色 | ✅（批次 F） | `RoleController.java:83-169` 本租户盖戳，跨租户同名合法 |
| 配额本租户档自设 | ✅ | `QuotaController.java:63-91` |
| 本租户审计/费用/公告/会话治理 | ✅ | `AuditController` / `UsageStatsService:260-276` / `AnnouncementService` |
| 平台级动作收口（开停租户/划人/审批/落点清单） | ✅ 仅 superadmin | `TenantController.requirePlatform/requireSuper` |
| 租户管理员前端体验 | 🟡 半残 | 见 §2.1 |
| 供应商/模型归属 | ❌ 平台全局 + 注释漂移 | 见 §2.3 |
| 市场租户私有档 | ❌ 未做（代码已预告） | `WenquTenantLineHandler.java:26-28` |
| 游客分享归属 | ❌ 落 default | 见 §2.2 |
| 邀请流 | ❌ 只有管理员建号 | MembersPage 无邀请入口 |
| 登录/品牌租户感知 | ❌ | LoginPage 无租户分支 |
| 平台配置租户覆盖 | ❌ | `c_ai_config` 全局 |
| 清退/删除租户 | ❌（刻意不设删除） | `TenantController.java:31-33` |

## 2 缺口明细

### 2.1 前端：三处必 403 控件 + 一处失明（P0）

- TenantsPage「落点清单」按钮**无 superadmin 门禁**，后端 requirePlatform → 租户管理员点了必报错（`web/src/views/TenantsPage.vue:41` vs `TenantController.java:184-187`）
- MembersPage「租户管理」弹窗对租户管理员展示一排必 403 控件（启停/直开）（`web/src/views/MembersPage.vue:51,264-271,316-321`）
- MembersPage 租户列/所属租户下拉用 `multiTenant`=「拉到的租户数>1」**数据量判据**而非身份判据 → 租户管理员永远看不到自己的租户列（`MembersPage.vue:84-88,232-235`）
- `/auth/me` 专门下发了 `tenant/tenantName`（`ChatController.java:150-153`），前端 `auth.js:55-63` 不缓存不消费 → **全站无「当前租户」指示**
- 附带：审计后端支持 `tenant=` 跨租户参数、非 superadmin 传参显式报错（`AuditController.java:139-146`），前端没接——superadmin 也用不上跨租户审阅

### 2.2 游客分享归属失真（P0）

游客请求落 SYSTEM 且不绑租户（`UserContextInterceptor.java:166-170`，设计如此：访客看的是发布者租户里的资源）；但游客自己**产生的行**——createSession（`ShareController.java:300`）、消息、qa_log——发生在未绑定线程，插入不带 tenant_id 落 DB 默认 `'default'`。执行侧按发布者身份跑（`ChatService.loadIdentity` 装载发布者租户），**记账侧却落 default**：按租户结算/配额聚合口径失真。

修法：分享解析拿到发布者后 `TenantContext.as(发布者租户)` 包住建会话/落消息/qa_log 段（`AgentShareService.resolveGuest` 与 `SessionShareService.resolvePublic` 两入口）。

### 2.3 供应商/模型归属悬空（P1，先拍板）

- `WenquTenantLineHandler.java:22-24` 注释称「供应商、模型与平台参数由平台运营统一登记，各租户共用同一份网关与价格表」
- 但 Provider 2026-10 起实为「**谁建归谁**」按 owner_uid（`Provider.java:60-63`、`ProviderController.java:45-51` 按 uid 过滤），表仍在全局名单、无行级租户防线，可见性全靠应用层判据（ProviderController denyUnlessManageable）
- 口径漂移 = 注释说 A 做的是 B。真拿去服务外部客户时这是最大的一块：**现在别家租户的用户聊天，烧的是平台（或建号人）的 key**

### 2.4 市场两档可见性未做（P1）

代码已预告做法与原因：「租户内市场要的是本租户私有 ∪ 平台公共两档可见性，而 TenantLineHandler 只能表达 tenant_id=? 一个谓词——留到运营交付刀用显式 SQL 做」（`WenquTenantLineHandler.java:26-28`）。市场物品（智能体模板/技能/MCP 目录）全平台共享，租户私有模板发不出来。

### 2.5 生命周期只有两端（P2）

- 无邀请流：成员只能管理员建号+初始密码（MembersPage:177-195），没有「邀请链接自助注册落本租户」
- 无租户感知登录/品牌：`<租户>-admin` 与平台管理员同页登录、同款门面（LoginPage 无租户分支、无 logo/名称差异）
- 无清退：只有停用 + 落点清单（备份取数），没有「导出→冻结→清除」流程
- 新租户开通后无引导：批准即完事，`<租户>-admin` 首登落普通工作台

### 2.6 流程债

本线 a7877128 起 25+ 提交从未回写 roadmap 账本（并行会话遗漏）——本方案入库时补登记。

## 3 实施排期（三刀）

### 刀一（P0，约 1 天）：面修真——bug 修复 + 租户管理员体验补完

1. 全站「当前租户」指示：auth.js 缓存 `tenant/tenantName`，顶栏用户区展示（一处组件，别做第二真相源）
2. TenantsPage 落点清单按钮加 superadmin 门禁
3. MembersPage：租户列/归属下拉/租户管理弹窗的判据从「数据量 multiTenant」换成 superadmin **身份判据**，必 403 控件按 superadmin 显隐
4. 审计页接 `tenant=` 跨租户下拉（仅 superadmin 渲染）
5. 游客分享归属修正（§2.2 机制，两个入口）
6. `WenquTenantLineHandler` provider/model 注释漂移修正（改完口径必须与 §4-A 拍板结果一致）

验收判据：租户管理员身份真机走查全部管理页，零「看得见、点了炸」控件；游客轮 DB 断言落发布者租户；**平台管理员视角零变化**（默认必须兼容）。

### 刀二（P1，2~3 天，条件刀——拍板点 A/B 后动）：资源归属补齐

- **2A 供应商/模型租户化（BYOK 路线，若拍板「租户自备 key」）**：provider/model 移出全局名单 → 行加 tenant_id（SchemaMigrator 归一自动补列）+ 平台公共行（visibility=public，归属 default）→ 可见性 = 本租户私有 ∪ 平台公共（复用市场两档模式，别发明第二套）→ 模型路由按两档取。与 ProviderController 应用层 owner 判据**收编成一处**，别造双重真相源。
  若拍板「平台统一供 key」：2A 退役，仅保留注释修正 + 模型面板标注「平台统一供模型」。
- **2B 市场两档可见性**：`c_ai_market_item` 加归属 + visibility，MarketService 显式 SQL 两段谓词（代码预告的既定做法）。
- **2C（P2 可选）租户级功能开关**：仅当拍板租户=外部客户才有意义；内部域不值得为它加一档配置真相源。

### 刀三（P2）：生命周期与门面

- 3A 邀请流：管理员生成邀请链接（带租户+角色预选，短时效）→ 自助注册落本租户；邮件通道可后置
- 3B 登录租户感知与品牌：登录页可配租户标识/子域（有则免输入），logo/名称/登录文案按租户出
- 3C 清退流程：停用 → inventory 导出 → 清除脚本（保留期可配），账实一致
- 3D 新租户首登引导：改密 → 建部门 → 邀请成员 checklist

## 4 待拍板

- **A（阻塞刀二 2A）供应商口径**：租户自备 key（BYOK）vs 平台统一供 key。判据：租户打算给外部客户用 → BYOK；只是内部部门/团队分域 → 平台统一，2A 退役。
- **B（决定 2C 与刀三分量）租户定位**：外部客户 vs 内部分域。
- **C 分享 token 语义**：维持全局公开凭据（仅修 §2.2 归属记账），还是加租户边界？建议维持——分享本就是「发布到互联网」语义，加边界会破坏「持有链接即可看」的产品直觉。

## 5 防回归纪律

- 每刀按 wenqu-dev 三道闸走；受影响面回归清单：RBAC 判定核 19 测 + 跨租户泄漏三案例（3630397f 快照失效 / 14ddab97 数据范围快照 / b3cd0c82 配额串号）逐条复验
- 动 GLOBAL_TABLES（2A）须同步 SchemaMigrator 租户列归一——G16 三踩教训：租户化表漏登记 = 谓词不注入 = 静默跨租户
- 刀一第 5 项动分享入口前，先核 `guestMode(true)` 执行链的身份装载（`ChatService.loadIdentity`），别把「访客看发布者资源」的既有语义改破
- 默认兼容：单租户部署（现状 default 一家）所有行为零变化

## 6 账本

本线补登记 roadmap「多租户完善」活跃线（随本方案提交）；后续每刀完工回写。
