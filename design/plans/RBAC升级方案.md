# 问渠 RBAC 升级与完善方案

> **版本：v1.0 方案定稿 · 2026-11-03**（决策点已全部拍板，可直接开工）
>
> 定位：本文是 **RBAC 授权面**的专项方案。
> 身份面（统一身份、多角色、SCIM/LDAP/SAML）已收官，本文只管**授权面**：
> 「谁（角色/用户）能对什么（菜单/接口/数据）做什么」。
>
> 原则沿用既定口径：**不兼容改造**。凡是阻碍企业级的能力一律结构性改造，不做兼容层、不保留遗留特例。
> 手册只写改造后的**现状**，迁移过程与退役史不进手册。
>
> **校准信息**：2026-11-03 按 `master@626b9ecd` 逐条复核全部代码证据并修订。
> 本轮修订修正了 4 处陈旧/错误的数字与 1 处与现行决策的方向冲突，新增 4 项遗漏工作面
> （见文末「修订记录」）。本文所有行号以该提交为准。

## 一页结论

| | 内容 |
|---|---|
| **v1 交付** | 批次 0（止血）+ A（授权点层，106 点/细粒度）+ B（管理员降级 + deny）+ D（反查与审计）+ E（测试地基），即 **接口授权维度做到企业级** |
| **v1 之后接着做** | 批次 C（有效期 / 部门数据隔离 / 服务账号）+ 批次 F（租户级角色），按 §8 里程碑推进 |
| **v1 明确不做** | 权限申请审批流、行级 ABAC、菜单租户化、IdP 反向同步、双人复核、态势大屏（6 项，见 §9.3，防止反复被提） |
| **总工期** | 单人约 **15~22 周**；两条并行线（A 主线 + C1/E 副线）约 **4.5~5 个月** |
| **第一步** | 批次 0：0.5~1 天，可独立发布。多副本下"解绑权限永不生效"是当前唯一静默越权漏洞 |

---

## 0. 现状盘点（结论先行）

问渠的 RBAC 已经是一套**可用的两级授权系统**，不是玩具：

| 已有 | 证据 |
|---|---|
| 角色 → 菜单 / 接口 两级绑定 | `c_ai_role_menu` / `c_ai_role_api`（`schema.sql:591-602`） |
| 接口级 fail-closed 鉴权 | `SecurityConfig.java:485-497`，未绑定一律 403 |
| 多角色并集判定 | `RoleService.canAccessAny`（`RoleService.java:143-149`） |
| 管理员级短路 | `RoleService.isAdminCode`（`RoleService.java:122-128`） |
| 接口清单启动自扫描（含 Controller→菜单默认归属） | `ApiEndpointScanner`（`ApiEndpointScanner.java:274` 幂等登记、`:301-316` 退役清理、`:349` 默认菜单） |
| 菜单↔接口多归属 | `c_ai_menu_api`（`schema.sql:603-608`，权限页分组与勾选联动，**不参与鉴权**） |
| 认证源授予角色（idp 档） | `c_ai_user_role.granted_by`（`schema.sql:1420-1428`） |
| 组 → 角色映射与 priority 裁决 | `IdentityMappingService.resolveBest`（`:143-158`） |
| 结构化审计事件（哈希链） | `AuditService` + `c_ai_audit_event`（`schema.sql:1503-1525`） |
| 资源归属隔离（谁建归谁） | `ResourceVisibilityService`（`:32-38`） |

**但它只是"两级授权"，不是"企业级 RBAC"**。差的是三样东西：

1. **授权点（permission）层缺失** —— 授权直接绑到 HTTP 端点，没有"读/写/管"这类可复用的业务语义；
2. **管理员级是二值开关** —— `admin_flag=1` 直接绕过全部绑定，做不出"只读管理员"；
3. **没有数据维度** —— 部门不参与任何判定，资源可见性只有"谁建归谁"。

### 0.1 先分清两个维度（本方案的边界）

讨论 RBAC 时必须把两条完全不同的判定链分开，混在一起会得出自相矛盾的结论：

| 维度 | 管什么 | 现行实现 | 本文档 |
|---|---|---|---|
| **接口授权** | 这个角色能不能调用这个端点 | `c_ai_role_api` + `SecurityConfig` 拦截器 | **本文主体**（批次 0/A/B/D/E） |
| **资源可见性** | 这条数据（库/文档/智能体/工作流）归谁 | `ResourceVisibilityService`：创建者 MANAGE，其余 NONE；管理员不短路 | **批次 C2**（已排期，D6 已拍板） |

2026-11-03 的 `f6a1f589` 已把资源共享范围（`share_config`：全局/部门/用户三档）**整体退役**，
口径明确收敛为「谁建归谁」，管理员与普通用户同口径、无全量短路。

**2026-11-03 晚已拍板：部门级数据隔离要做**，作为批次 C2 排期，但**形态必须与退役前不同**——
不恢复逐资源的共享范围面板，改为角色自带数据范围（见批次 C2 的三条设计约束）。
租户级角色（批次 F）与服务账号鉴权（批次 C3）同批排入后续。
执行优先级仍是 0 → A/B → D/E → C → F，不与主线混排。

---

## 1. 问题清单（按严重度，全部有代码证据）

### P0 — 安全语义错误

**G1. 多副本部署下，权限回收永不生效**（静默越权）
`RoleService.roleSnapshot` / `apiPatternSnapshot` 是 volatile 全量快照，**无 TTL**（`RoleService.java:71-80`），
失效只调本进程 `invalidateCaches()`（`:103-108`）。`RoleService.java:40` 的注释明写
"本进程单实例部署形态……无需跨节点失效"——但 Helm 已支持 `replicaCount`（`deploy/helm/wenqu/values.yaml:4`），
且有 `canary.replicaCount` 灰度槽（`templates/deployment.yaml:8`）。

后果：A 副本管理员解绑某角色的某接口后，B 副本**仍可访问该接口**，直到 B 重启。
项目已有 Redis 广播原语 `ReplicaCoordination.listen/broadcast`（`ReplicaCoordination.java:178`/`:196`），
RBAC 层未接入。原语本身在 Redis 不可用时**如实返回未命中、不抛异常**（类注释，"退回单副本语义"），
所以接入时无需自己处理降级，但 **TTL 是单机形态下的唯一保险**，两者都要有。

**G2. 管理员级 = 绕过一切绑定，无中间档**
`SecurityConfig.java:488` 管理员级直接 `pass`，`canAccess` 根本不参与；`MenuService.java:82`/`:288` 同理。
`admin_flag` 只有 0/1 两档（`Role.java:38-39`）。想做「只读管理员」「运维管理员」只能靠
白名单 + 控制器内散落的手写 `adminGuard.isAdmin()`——实测 **28 处调用点、10 个 Controller**
（`ConfigController`/`OpsController`/`WebPushController`/`QuotaController`/`ChatController`/`AuditController`/
`TenantController`/`McpController`/`UsageStatsController`/`ContentSafetyController`），
语义分散、无单一事实源。（原方案写"9 处"，低估约 3 倍。）

**G3. 白名单绕过 RBAC，新端点默认全员可访问**
`SecurityConfig.isPublicUserEndpoint`（`:72-232`）内共 **44 个 `return true` 分支**，
另有 3 个自服务辅助方法各自再展开多条前缀：`isKbOrDocEndpoint`（`:277`）、
`isAgentSelfEndpoint`（`:312`）、`isProviderSelfEndpoint`（`:379`）。
命中即**整体跳过 L3 全部 RBAC**（`:485`）。覆盖 skill / market / mcp / project / workflow / credential /
artifact / sandbox / scheduled / api-key / notification / knowledge / provider / user 等。
新增端点落进这些前缀 → 自动对全体登录用户放行，管理员在权限页勾不勾都无效。
P7 已在 `/api/ai/auth/` 前缀修过同型问题并留下警告（`:143-152`："看着像同一段就被漏掉自动放行"），
但只修了一处。（原方案写"24 处前缀"，实际是 44 个分支的混合形态，
且这些端点早已不止"问答最小闭环"，大量个人资产功能都在其中。）

**G4. 新库部署后 `user` 角色零接口绑定**
`RbacSeedRunner.seed()`（`:151-234`）只种角色、菜单、user 菜单，**不种 `c_ai_role_api`**；
`schema.sql` 也没有对应 INSERT。存量库里 `user` 的绑定来自历史 dump，不是种子。
新环境普通用户只能靠 G3 的白名单端点存活——一旦按 G3 收口白名单，普通用户立刻大面积 403。
**G3 与 G4 必须同批解决，否则收口即断网。**

### P1 — 表达能力不足

**G5. 无授权点层，授权粒度 = HTTP 端点**
角色绑的是 `method + path`（`RoleService.canAccess` 用 `AntPathMatcher`）。
同一个业务动作散落在 5 个端点就要勾 5 次；换个 URL 前缀就得重新配一次。
企业语义里的「只读」「导出」「管理」无处安放。
规模：**52 个 Controller、440 个端点映射**（原方案写"17 个 Controller"，严重低估，
A2 的标点工作量要按 52 个类重新估算）。

**G6. 无 deny 规则**
`canAccessAny` 纯 allow-union（`:143-149`），无 deny 表、无 deny 列。
某端点一旦被某角色勾上，无法对其他角色单独禁止；而对白名单端点连"不勾"都无效。

**G7. 部门不参与任何权限判定**
`ResourceVisibilityService.resolve`（`:32-38`）只比 `createdBy == uid`；
`Principal.departmentId` 携带但不使用。部门只服务成员筛选与配额统计。
后果：做不出部门级数据隔离。**注意这是资源可见性维度，是否要做见 D6。**

**G8. 组映射「唯一胜者」与角色「并集放大」语义冲突**
`IdentityMappingService.resolveBest` 返回唯一最优规则，非最优组的 idp 角色行被主动回收
（`DirectorySyncService.java:205-209`、`ScimService.java:495-499`）。
用户属 3 个组时只保留 1 个组角色——**表达不了「A 组给只读、B 组给写」的组合**。

**G9. `grant` 的 delete+insert 会吃掉 manual 行（D1 语义被破坏）**
`UserRoleService.grant`（`:148-154`）先 `delete(uid, role_code)`（**不限 granted_by**）再 insert。
同一 (uid, role_code) 已有 manual 行时，manual 行被删掉换成 idp 行——与 D1 决策
「手动角色不被 IdP 静默覆盖」直接矛盾。`setManualRoles` 的 `DuplicateKeyException` 兜底
（`:136-138`）也意味着手动行插不进去时**静默跳过**。

**G10. 无权限有效期 / 临时授权 / 服务账号**
`c_ai_user_role` 无 `expire_at`（对比 `c_ai_api_key.expire_at` 是有的）。
无 S2S 鉴权（内部调用靠 `RequestUser.snapshot()` ThreadLocal，不做鉴权）。

### P2 — 可观测性与审计

**G11. 「谁因为什么权限访问了这里」无法反查**
`RoleApiMapper.roleCodesOfApi`（`:23`）、`RoleMenuMapper.roleCodesOfMenu`（`:23`）**已定义但零调用方**。
`UserRoleService.countOfRole`（`:180`）同样是死代码。无 `/role/{code}/members` 端点。

**G12. 审计只记数量不记内容**
`RoleService.java:354-355` 记 `menus={} apis={}` 的**个数**——事后无法回答"当时到底授权了哪些接口"。
`grant` / `revokeIdpRole`（`:146-170`）**完全不写审计**——SCIM/LDAP 自动授权变更无留痕。
角色事件的 `target_type` 恒为 null（`AuditService.applyTarget` 的候选键里没有 `code`，`:248-256`）。

**G13. 角色列表不显示成员数**
`list()` 不带计数（`RoleService.java:242`），只有 `delete` 里现查一次占用（`:318-322`），
管理员只能靠删除报错才知道占用情况。

**G14. IdP 授予的角色在成员页可被误改，表现为静默无效**
前端多选不区分来源、IdP 角色无 disabled（`MembersPage.vue:184`）；
`/user/list` 不下发 `granted_by`（`OrgService.java:156-158`）。
管理员取消勾选一个 IdP 角色 → `setManualRoles` 只删 manual 行 → IdP 行原样不动 → 保存后角色又出现。
界面提示写着"组映射授予的角色在成员管理中不可改"（`:449`），**实际没实现**。

### P3 — 测试与工程

**G15. RBAC 测试覆盖为 0 —— 且全仓测试为空**
`src/test/java/com/wenqu/ai/` 下**已无任何测试文件**（只剩空目录）；
原方案提到的 `GoalFlowTest` / `GoalMarkerTest` 已在 `697703ad`、`87f900b9` 两批重构中删除。
并集判定、管理员级短路、AntPathMatcher 匹配、白名单边界、Key 三层夹死、组裁决、锁死保护——全无覆盖。
（结论不变，但基线比原方案描述的更差：不是"两个不相干测试"，是"一个测试都没有"。）

**G16. 租户维度与 RBAC 割裂**
`c_ai_role` / `c_ai_role_menu` / `c_ai_role_api` 在 `WenquTenantLineHandler.GLOBAL_TABLES`（`:41-47`），
而 `c_ai_user_role` 有 `tenant_id` 列（`schema.sql:1421`）却不在全局表名单里 → 被租户谓词过滤。
角色定义全局、用户角色绑定按租户，两层口径不一致。

### 新增问题（本轮复核发现）

**G17. `c_ai_menu_api` 与拟建的 `c_ai_permission_resource` 职责重叠，方案未提**
`schema.sql:603-608` 已有「菜单↔接口多归属」表，权限页分组与勾选联动就在用它
（`ApiEndpointService.java:29`、`MenuApiMapper`），`ApiEndpointScanner` 还维护了一份
Controller→菜单默认归属（`ApiEndpointScanner.java:349` 的 `DEFAULT_MENUS`）。
A1 若新建 `c_ai_permission_resource` 而不说清与它的关系，会平白多一张同义表；
A2 的标点也应**以现成的 menu_api 分组作为候选清单**，而不是从 52 个 Controller 从零归类。

**G18. 白名单与 API Key 拒绝名单是两份手工维护的清单，必然漂移**
`isKeyDeniedEndpoint`（`SecurityConfig.java:243-266`）按**前缀**再拦一层，
但 `isPublicUserEndpoint` 里新增的端点会**自动**对 Key 开放——这一点代码注释自己已经写出来了
（`:239-242`："将来问答白名单里新增端点会**自动**对 Key 开放"），却仍靠人工同步两份清单。
批次 A4 收口时应把两份清单合并为一份「端点 → 允许的身份类型」声明，而不是继续双份维护。

**G19. 权限页前端与 `c_ai_role_api` 强耦合，退役时的返工没排进批次**
`PermissionsPage.vue` 的「角色 → 接口权限」勾选直接读写 `c_ai_role_api`。
A6 退役该表后，这个页面要整体改为「角色 → 授权点」勾选，工作量与后端同量级，但原方案批次里没有它。
A2 还应补一条**启动期自检**：扫描到任何端点既不在公开端点集、也没有授权点归属时，
启动日志 WARN 并列入权限页「未标注」分组——否则收口后新增端点会静默变成"管理员能用、其他人全 403"。

---

## 2. 目标模型

从"角色 → HTTP 端点"两级，升级为**四层授权模型**（仅接口授权维度）：

```
用户 ──c_ai_user_role──> 角色 ──c_ai_role_permission──> 授权点 ──> 资源(菜单/接口/数据)
                                          │
                                          └── deny 规则（覆盖 allow）
```

**授权点（permission）**：业务语义的最小单位，如 `agent:read` / `agent:write` / `kb:publish` /
`tenant:manage`。每个授权点绑定一组端点（注解声明）与一组菜单（`c_ai_permission_resource`）。
好处：换 URL 不改权限、同一动作跨端点只配一次、deny 可以按语义粒度下。

这一层**不是**"再加一张表"那么简单——它需要给 **52 个 Controller、440 个端点**逐个标授权点，
是本方案最大的工作量，也是最值得做的部分。建议的落地形态：

- 端点归属用**代码注解**（如 `@Permission("agent:write")`）而非数据库手填，
  `ApiEndpointScanner` 注册时一并写入 `c_ai_api.permission_code`；
  review 时能看到、改 URL 时不会漏、新端点不标就是编译期可见的缺口。
- 授权点 → 端点 的展开关系由注解生成，`c_ai_permission_resource` 只存**授权点 → 菜单**的
  外加关系（菜单这一侧需要运行期可配），避免同一事实两处维护。
- 现有 `c_ai_menu_api` 保留原职责（权限页分组展示），不升级为鉴权数据源；
  `c_ai_permission_resource` 的菜单半边可直接从它初始化。

**管理员级降级为「全量 allow 的角色集」**：不再是绕过一切的短路，而是
`admin_flag=1` ⇒ 该角色的授权点集合 = 全部 allow；deny 仍然对它生效（除非显式豁免）。
这样「只读管理员」= 全部 allow + deny 写类授权点。

**数据维度**：`c_ai_role_data_scope`（role_code + scope_type + scope_value），
scope_type ∈ {all, own, department, department_and_children}。与 `c_ai_user.department_id` 接上，
部门树沿用现有 `OrgService` 的部门模型。
已排入**批次 C2**，形态约束见该批次（角色集中授权，不恢复逐资源共享面板）。

---

## 3. 实施批次

**执行顺序**：0（止血）→ A（授权点层）→ B（管理员/deny）→ D（反查审计）→ E（测试）
→ C（数据权限；C1 可与 A 并行）→ F（租户级角色）。
字母是**稳定编号，不随执行顺序调整**（避免引用漂移），顺序以本行为准。

**粗估（单人，含自测，不含评审返工）**

| 批次 | 内容 | 粗估 | 依赖 |
|---|---|---|---|
| 0 | 跨副本一致性止血 | 0.5~1 天 | 无（可立刻做） |
| A | 授权点层 + 白名单收口 + 种子 | 4~6 周 | 0 |
| B | 管理员降级 + deny + 28 处收口 | 1.5~2 周 | A |
| D | 反查 / 成员数 / 审计 / 有效权限视图 | 1~1.5 周 | A（D-3 可提前） |
| E | 测试地基 + RBAC 单测 | 1.5~2 周 | 与 A 并行 |
| C1 | 临时授权与到期回收 | 0.5 周 | 无（可与 A 并行） |
| C2 | 部门级数据隔离 | 2~3 周 | A + B5 + D-3 |
| C3 | 服务账号与 S2S | 1~2 周 | A |
| F | 租户级角色体系 | 3~4 周 | A + B + E-2 |

合计约 **15~22 周（单人）**；按两条并行线（A 主线 + C1/E 副线）可压到 4.5~5 个月。
A 是全表的长杆，排期上先把它拆成"注解骨架 → 按模块分批标点"两段交付，
每标完一批模块就能跑一批回归，不必等 52 个 Controller 全标完才验收。

### 批次 0：跨副本一致性止血（G1）—— 先做，与 A 完全解耦

**成本最低、收益最大，且 G1 是唯一一条"静默越权"的 P0**。多副本部署下现在解绑权限永远不生效，
任何其它改造之前都该先把这个洞补上。

- 0-1. `RoleService` 快照改为带 TTL（与 `UserRoleService` 的 60s 同口径，
  见 `UserRoleService.java:34/:56`）+ 写操作后 `ReplicaCoordination.broadcast` 通知其它副本失效。
- 0-2. `UserRoleService.setManualRoles` / `grant` / `revokeIdpRole` 同样广播
  （当前 `invalidate` 只清本进程）。
- 0-3. 补 `UserContextInterceptor` 的用户档案缓存失效（当前 `setManualRoles` 不 evict 档案缓存，
  见 `OrgService.updateUserScoped` 的 evict 只在另一条路径）。
- 0-4. 单机/无 Redis 形态写明降级策略：`ReplicaCoordination` 广播是 no-op，
  **TTL 是唯一保险**（60s 内回收延迟可接受，且已在 `UserRoleService` 有先例）。

### 批次 A：授权点层落地（G3 + G4 + G5 + G17 + G18 + G19 的共同解；G6 的 effect 列在此建、语义在批次 B）

**这是整个升级的地基**——G3（白名单收口）与 G4（user 角色补接口绑定）必须靠它一起解，
否则先收窄白名单就会把普通用户打死。

- A1. 新建 `c_ai_permission`（授权点：code/name/module/data_scope 默认值）、
  `c_ai_role_permission`（role_code + permission_code + effect{allow,deny}）。
  端点↔授权点的展开由注解 + 扫描器写入 `c_ai_api.permission_code`，不单独建 `c_ai_permission_resource`
  ——与 `c_ai_menu_api` 的职责边界见 §2 / G17。
- A2. 为全部端点标授权点：以 `c_ai_menu_api` 的现成分组 + `DEFAULT_MENUS` 对照表作为候选，
  按模块分批给 52 个 Controller 的 440 个端点打注解；扫描器登记时一并写入。
  清单见**附录 A（授权点草案，细粒度）**——A2 的产出是把草案逐 Controller 复核成终稿。
  同时加启动期自检：未标注且不在公开端点集的端点 → WARN + 权限页「未标注」分组（G19）；
  一个端点归属多个授权点 → 启动失败（唯一归属）。
- A3. `RoleService.canAccess` 改为：请求 → 匹配授权点 → 查角色 allow/deny → 判定。
  **deny 优先于 allow**，跨角色并集时 deny 只在"所有相关角色都无 allow"时才生效？
  —— **这里要拍板**（见 §5 D3）。
- A4. `isPublicUserEndpoint` 全面收口：把 44 个分支 + 3 个辅助方法改为
  「授权点白名单 + 显式公开端点集」，并与 `isKeyDeniedEndpoint` 合并为一份
  「端点 → 允许身份类型」声明（G18）。**显式公开端点集必须逐条列名**（不许用前缀），至少包含：
  `isScimEndpoint`（`/api/ai/scim/**`）、`isAuthBootstrapEndpoint`、`/api/ai/auth/me`、
  `isShareGuestEndpoint`（`/api/ai/share/**`，游客通道）、`isTenantApplyEndpoint`（精确 POST）、
  `isSandboxCallbackEndpoint`（沙盒回调）、`GET /config/public`、`ProjectFileViewController`（签名 token 下载）。
  这些是设计上的公开通道，收口时漏掉会直接打断登录、分享与沙盒。
- A5. `RbacSeedRunner` 补种：`user` 角色的授权点集合（对齐今天白名单覆盖的能力）+
  管理员角色的全量 allow。新库开箱即用，不依赖历史 dump。
  种子要覆盖 G3 收口后普通用户实际需要的全部能力（会话、个人资产、知识库/文档自建自管等），
  **A4 与 A5 必须同一提交落地**。
- A6. 存量迁移：现有 `c_ai_role_api` 的绑定 → 折算成授权点绑定（同一授权点下的端点合并去重）。
  **细粒度在这里是优势**：每个端点恰好映射一个授权点，折算结果是确定性的、可对账的
  （粗粒度会把不同动作并桶，迁移时反而要靠猜）。迁移后 `c_ai_role_api` 退役，
  `RoleApiMapper` / `RoleMenuMapper` 一并退役。
- A7. 前端 `PermissionsPage.vue` 从「角色 → 接口」改为「角色 → 授权点」勾选（G19），
  与 A6 同批，否则退役即界面不可用。**必须做分组折叠 + 分组级全选**（D2 护栏 1），
  并同批提供「有效权限视图」的最小形态（给定 uid 列出授权点），作为 A 批次的验收工具。

### 批次 B：管理员级与 deny（G2 + G6）

- B1. `admin_flag` 语义改为"全部 allow"，删除 `SecurityConfig.java:488` 与 `MenuService.java:82/288`
  的三处理论短路。
- B2. deny 规则落地：`c_ai_role_permission.effect='deny'`，判定时 deny 优先。
- B3. 控制器内散落的 **28 处** `adminGuard.isAdmin()` 收口为授权点判定
  （10 个 Controller：Config/Ops/WebPush/Quota/Chat/Audit/Tenant/Mcp/UsageStats/ContentSafety）。
- B4. 受限管理员预设：内置「只读管理员」「运维管理员」两个角色（授权点集合是数据，不是代码）。
- B5. **deny 的自我保护要同批解决**：全局 deny 下，同一用户被多个角色覆盖时，
  一个角色的 deny 会压掉另一个角色给的 allow——「受限管理员」给某人加一个只读 deny，
  可能把别的角色给他的写权限也一起打死。B2 必须同时提供**按角色豁免**
  （deny 只在该角色内部生效的开关）或至少让 E3 的「有效权限视图」同批上线，
  否则这类降权事故只能靠猜。

### 批次 C：数据权限与身份扩展（G7 + G10）—— 已拍板，排在主线之后

**拍板结论（2026-11-03）**：三项都做。字母 C 保持不动，执行顺序在 0/A/B/D/E 之后（C1 可提前与 A 并行）。

**内部顺序与依赖**
- C1 最先：成本最低（一列 + 一个定时任务），不依赖授权点层，可与 A 并行。
- C3 其次：服务账号本质是给"非人类身份"配授权点，复用 A 的判定主干。
- C2 最后：需要 D-3 的「有效权限视图」来验收，否则范围配错了看不出来。

- C1. 临时授权与到期回收：`c_ai_user_role` 加 `expire_at`，到期自动回收
  （定时任务对齐既有 `@Scheduled` 基建，与 `c_ai_api_key.expire_at` 同口径）。
  同时补到期提醒与「即将过期」列表。
- C2. 部门级数据隔离：`c_ai_role_data_scope` + `ResourceVisibilityService` 接部门树。
  **这是对 `f6a1f589` 的有意反转，但形态必须不同**，三条约束：
  1. 旧模型是**逐资源配共享范围**（每张库/文档/智能体各挂一个 `share_config` 面板，退役时砍掉 263 行）；
     新模型是**角色自带数据范围**——资源上不再有"共享给谁"的入口，用户能看什么由角色集合推导。
  2. 判定 = 角色数据范围 ∪ 创建者（创建者恒 MANAGE）；管理员不短路，与 `f6a1f589` 同口径。
  3. 验收必须含"资源页无共享入口"一条，防止旧 UI 被顺手带回来。
  步骤：建表 → `ResourceVisibilityService.resolve` 加部门分支 → 各控制器把调用方 scope 传进去
  → 权限页加「数据范围」列 → 六类资源逐个走查（库/文档/智能体/工作流/API Key/产物）。
- C3. 服务账号与 S2S 鉴权：`c_ai_service_account`（绑授权点、可设有效期、可审计、**不可登录网页**）
  + 拦截器第一层新增身份类型 + `X-Service-Token`。
  **范围要说清**：`/api/ai/v1/workflows` 与 `/ai/mcp` 已有 API Key 方案、沙盒回调有一次性运行凭据，
  这些**不动**；C3 补的是"其它系统长期调用管理面 / 内部服务互调"这个缺口。
  产物：服务账号 CRUD + 授权点绑定 + 用量与审计归属。

### 批次 D：反查与审计（G11-G14）

- D-1. 启用已有的 `roleCodesOfApi` / `roleCodesOfMenu`，前端接口表加「已授权角色」列、
  菜单表加「已授权角色」列。
- D-2. `/role/{code}/members` 端点 + 角色列表带成员数（`countOfRole` 已有，接到出参上，
  并在 `list()` 一并返回）。
- D-3. 「有效权限视图」：给定 uid，算出其实际可访问端点集合（权限排查与验收用，B5 依赖它）。
- D-4. 审计留痕补齐：授权点 id 列表入 detail（不只记个数）、`grant`/`revokeIdpRole` 补审计、
  角色事件补 `target_type=role`。
- D-5. 成员页按来源分组展示角色，IdP 授予项标为只读不可改（兑现 `MembersPage.vue:449` 那句提示），
  `/user/list` 下发 `granted_by`。

### 批次 E：测试与工程（G15 + G16 + G9）

- E-1. 先立测试地基（全仓测试为空，不是修两个文件）：JUnit5 + Testcontainers/嵌入式 H2 选型，
  建立最小可跑的 `mvn test` 链路。原方案 F3「修掉 GoalFlowTest」作废。
- E-2. RBAC 单测：并集判定、deny 优先、admin 降级后行为、白名单边界、组裁决、锁死保护、
  跨副本失效。目标覆盖率：授权判定主干 100%。
- E-3. `grant` 的 delete 放宽为只删同来源行（或按 D1 语义拆分 manual/idp 两条写路径），
  与 `setManualRoles` 的 DuplicateKey 静默跳过一并收口（G9）。
- E-4. 租户口径统一：`c_ai_user_role` 是否进 GLOBAL_TABLES 一并拍板，落地见批次 F1
  （D4 已拍板走"按租户隔离"，此项从"待拍板"变为"随 F 执行"）。

### 批次 F：租户级角色体系（D4 反转）—— 后续最后一项，风险最高

- F1. `c_ai_role` / `c_ai_role_menu` / `c_ai_role_api` retrofit `tenant_id`，从 `GLOBAL_TABLES` 移出；
  菜单是否也租户化单独拍板——**建议菜单继续全局**，否则内置菜单要被复制 N 份、前端布局同步失效。
- F2. `RoleService` 快照改为**按租户键控**（`tenantId → snapshot`），并与批次 0 的 TTL/广播一起设计：
  现在的单 Map 全局快照在租户化后就是直接越权源（A 租户能读到 B 租户的角色）。
- F3. `RbacSeedRunner` 改为**按租户种子**：新租户开通时复制内置角色模板，内置角色在租户内不可删。
- F4. 权限页 / 成员页加租户维度；跨租户角色不可绑定。
- 依赖与前置：必须排在 A/B 之后（角色形状稳定再动租户维度），且必须有 E-2 的测试兜底——
  动的是判定热路径、启动种子与前端权限页三处。

---

## 4. 退役清单（不做兼容层）

| 退役对象 | 替代 | 时机 |
|---|---|---|
| `c_ai_role_api`（角色-接口绑定） | `c_ai_role_permission` + `c_ai_api.permission_code`（注解生成） | 批次 A6 |
| `isPublicUserEndpoint` 44 个分支 + 3 个辅助方法 | 授权点白名单 + 显式公开端点集 | 批次 A4 |
| `isKeyDeniedEndpoint` 独立名单 | 并入「端点 → 允许身份类型」单一声明（G18） | 批次 A4 |
| 管理员级三处理论短路 | 「全量 allow 角色」语义 | 批次 B1 |
| 控制器内 28 处 adminGuard 判定 | 授权点判定 | 批次 B3 |
| `RoleApiMapper` / `RoleMenuMapper` | 授权点版本的反查 Mapper | 批次 A6 |
| `c_ai_menu_api` | **不退役**——保留为权限页分组展示（不参与鉴权） | — |

---

## 5. 决策记录（D1~D6 全部已拍板，2026-11-03）

**D1 · 是否做授权点层？** —— **已拍板：做**（2026-11-03）
- 备查：做 → 批次 A 4~6 周（52 个 Controller / 440 个端点逐个标点）；不做 → 只能端点粒度打补丁，
  deny 与受限管理员都做不出来，跨端点的语义复用无解。
- 结论：做。这是唯一能让 RBAC 从"能用的工具"变成"企业级体系"的一步。

**D2 · 授权点的粒度** —— **已拍板：细粒度（动作级）**（2026-11-03）
- 选项原样保留备查：粗（`kb:read`/`kb:write`/`kb:admin`，30~40 个）vs 细（`kb:publish`/`kb:export`，实点 **106 个**，草案见附录 A）。
- **拍板结论**：细粒度。要求能表达"能编辑但不能删除""能看但不能导出"这类同模块内切分。
- **必须同批的护栏**（细粒度会让"配角色"变贵，不配套就会退化成没人敢配）：
  1. 权限页按模块分组 + 分组级全选/反选，不呈现成 70 个平铺勾选框（A7）；
  2. 角色克隆 + 内置角色模板（B4），常见岗位不必从零勾；
  3. 有效权限视图（D-3）**提前到批次 A 的验收工具**，而不是等批次 D——细粒度下"这个角色到底能干什么"人脑算不出来。
- 经验判据：授权点按**会被独立授权的边界**切，不是按 verb 机械切。机械套 verb 会膨胀到 100+ 个点
  （附录 A 已收敛）；若两个点从未被分开授权过，就应合并。

**D3 · deny 的跨角色语义** —— **已拍板：全局 deny + 按角色豁免开关**（2026-11-03）
- 备查：全局 deny（任一角色 deny 即 deny）vs 仅当无任何角色 allow 时才生效。
  后者实际等于重复一遍 fail-closed（默认就是拒），不形成有效表达。
- 结论：全局 deny，语义 = 任一角色 deny 即 deny；同时在 `c_ai_role_permission` 上提供
  **按角色豁免**（`exempt` 标记，deny 只在该角色内部生效），避免"加个受限角色＝给所有人降权"。
- 配套：界面上明示"deny 会压过其它角色的 allow"；有效权限视图必须能解释每一条判定（B5 / D-3）。

**D4 · 角色/菜单是否按租户隔离** —— **已拍板：按租户隔离**（2026-11-03）
- 选项原样保留备查：保持全局（改动小）vs 按租户隔离（真正多租户 RBAC，角色表 retrofit tenant_id）。
- **拍板结论**：各租户自定角色，落地见批次 F。菜单仍建议保持全局（内置菜单复制 N 份不划算）。
- 与 `c_ai_user_role` 的口径统一随之落地：F1 里角色与绑定两侧都按租户走，不再一全局一过滤。

**D5 · `admin_flag` 的未来** —— **已拍板：保留但降级**（2026-11-03）
- 备查：保留（全量 allow 快捷标记）vs 废弃（内置超管角色 + 显式授权点全选）。
- 结论：保留。`admin_flag=1` 蕴含"这个角色全 allow"，但**不再是绕过 deny 的万能钥匙**；
  紧急救场能力保留，隐形全权问题由有效权限视图（D-3）化解——随时能算出他实际能干什么。
- 落地：B1 删除三处理论短路；A6 迁移时 `admin_flag=1` 的自定义角色**不写 106 行绑定**，
  只记标记，判定时展开，避免数据膨胀与新增授权点漏配。

**D6 · 资源可见性要不要重开数据维度？** —— **已拍板：做，按角色数据范围**（2026-11-03）
- 选项原样保留备查：维持现状 / 按部门重建 / 折中（只做标注）。
- **拍板结论**：做部门级数据隔离，落地见批次 C2。
- **关键约束**：重建的是"能力"不是"形态"——不允许恢复逐资源的 `share_config` 共享面板
  （那正是 `f6a1f589` 砍掉的 263 行）。新形态是角色自带数据范围，资源侧无共享入口。
- 验收以 D-3「有效权限视图」为准：配完范围要能算出来、能比对实际 403。

---

## 6. 与既有方案的关系

- 本文承接企业级升级的**授权面**。总纲 `企业级升级与建设方案.md` 当前**不在仓库内**
  （`docs/` 已被清理，仅剩本文），其原 §0 P13（行级 ABAC / 部门继承共享）的原文已散失。
  本文按 P13 的已知口径展开为批次 B/C/D/F；**若总纲需要保留，应先把原文补回仓库**，
  否则"承总纲 / P13 的展开"是悬空引用。
- **不受本文影响的部分**：统一身份、多角色并集、SCIM/LDAP/SAML、审计事件总线、内容安全——均已收官，不动。
- **本文不解决的**：接口鉴权的公开通道（`/api/ai/auth/**`、SCIM 独立通道、游客分享 `/api/ai/share/**`、
  沙盒回调、租户开通申请）——它们是设计上的公开口，A4 只负责把它们**显式列名**，不改变其语义。

---

## 7. 验收口径（每批次完成时逐条过）

| 批次 | 验什么 | 怎么验 |
|---|---|---|
| 0 | 双副本下解绑权限 60s 内两边同时 403 | 起两个副本 + Redis，A 副本解绑 → 立刻打 B 副本 |
| 0 | 单机无 Redis 不报错、TTL 仍生效 | 停 Redis 起单副本，解绑后 ≤60s 生效 |
| A | 普通用户白名单能力不掉 | 用 `user` 角色账号走查：会话/知识库/文档/工作流/技能/MCP/产物/工作区/定时任务 |
| A | 授权点覆盖完整：已标注 + 公开 = 全部 440 个映射，且无冲突 | 启动自检报告 + 权限页「未标注」为空 |
| A | 细粒度语义成立（D2 的核心验收） | 建"文档可编辑不可删""知识库可读不可导出"两个角色，各打一组端点 |
| A | 有效权限视图与实际 403 一致（细粒度下唯一可靠验收手段） | 给定 3 个角色算出可访问端点集，随机抽 20 个端点比对 |
| A | 新端点不标授权点 → 启动 WARN + 权限页「未标注」 | 临时加一个无注解端点，重启看日志与页面 |
| A | 收口后游客分享、沙盒回调、登录、SCIM 全通 | 公开端点集逐条打一遍 |
| A | `c_ai_role_api` 折算无丢失 | 迁移前后各角色授权点集合 diff 为空 |
| B | 只读管理员对写端点 403、读端点 200 | 建受限管理员角色实测 |
| B | deny 压过 allow、豁免开关生效 | 三角色交叉用例 |
| C | 到期自动回收、临时授权不越期 | 造过期行，跑定时任务 |
| C2 | 部门 A 的用户看不到部门 B 的资源、同部门可见、创建者恒可管 | 造两部门各一个用户 + 三类资源交叉验证 |
| C2 | **资源页没有共享入口**（防止旧形态回潮） | 逐页走查库/文档/智能体/工作流/API Key/产物 |
| C3 | 服务账号能调授权点内端点、调不了授权点外、不能登录网页 | 用 `X-Service-Token` 正反打 |
| D | 反查数据与判定同源 | 接口页「已授权角色」与 canAccess 实际结果比对 |
| F | 租户 A 的角色在租户 B 不可见、不可绑；新租户开箱带内置角色 | 双租户交叉 + 新建租户走查 |

---

## 8. 交付节奏与里程碑

| 里程碑 | 周次 | 内容 | 可发布状态 |
|---|---|---|---|
| **M0 止血** | 第 1 周（0.5~1 天） | 批次 0 全部 | ✅ 独立发布，无行为变化，只是权限回收开始生效 |
| **M1 地基（不切流）** | 第 2~4 周 | A1 建表 + A2 注解骨架 + 扫描器写 `permission_code` + 启动自检告警 + 附录 A 逐 Controller 复核 | ✅ 外网可发布：判定仍走旧链路，零风险 |
| **M2 切流（高风险窗口）** | 第 5~7 周 | A3 判定改授权点 + A4 白名单收口 + A5 种子 + A6 迁移 + A7 权限页 | ⚠️ 唯一高风险窗口，见下方双跑策略 |
| **M3 管理层** | 第 8~9 周 | B1~B5（管理员降级、deny、28 处收口、受限管理员、豁免） | ✅ 只作用于非 admin 角色时风险可控 |
| **M4 可观测** | 第 10~12 周 | D-1~D-5 + E-1 测试地基 | ✅ 纯增量 |
| **M5 数据与身份** | 第 13~15 周 | C1 有效期 + C3 服务账号 | ✅ 纯增量 |
| **M6 数据隔离** | 第 16~18 周 | C2 部门级数据范围 | ⚠️ 改变资源可见性，需灰度 + 用户公告 |
| **M7 租户化** | 第 19~22 周 | F 租户级角色 | ⚠️ 动判定热路径与启动种子 |

**M2 的双跑策略（不是兼容层，是切流机制，M2 后两周删除）**

`rbac.permission-mode` 三档：
- `legacy`：仍用 `c_ai_role_api` 判定（M1 全程）；
- `shadow`：新旧两套都算、**仍按旧结果放行**，只记录判定差异（日志 + 差异看板）。跑满一周、差异清零再切；
- `enforce`：新判定生效，旧表只读保留一个版本周期后删。

这样 M2 的风险从"一次性切换"变成"先看差异再切"，且不违反"不做兼容层"——它是临时的发布手段，
不是长期并存的语义。

---

## 9. 后续路线（v1 之后）

### 9.1 上线后 1~3 个月的优化（不改模型，只改体验与性能）

| 项 | 内容 | 触发条件 / 判据 |
|---|---|---|
| **授权点合并评估** | 复核附录 A.3 的 4 个合并组，砍掉从未被分开授权的点 | 上线 1 个月后看授权数据：某组内两个点从未被分开勾选过 → 合并 |
| **判定性能** | 授权点→端点映射预编译（前缀树/集合），替代每请求 `AntPathMatcher` 全扫 | 压测 P99 超过旧链路 20% 时启动；先测后做，不提前优化 |
| **权限页体验** | 搜索、角色 diff（两角色授权点对比）、角色导入导出、键盘操作 | 管理员反馈"配一个角色超过 10 分钟"即启动 |
| **403 可读性** | 返回确切缺失的授权点（生产可开关，避免给攻击者暴露内部结构） | 上线首月 403 排障工单占比较高时 |
| **审计报表** | 授权变更月报、**异常授权检测**（长期未用的授权点提示回收、超范围 admin） | 有合规审计需求时 |
| **有效权限视图增强** | 按角色/按端点双向反查、导出 CSV、与真实 403 的定时对账 | D-3 上线后即有价值，随用随加 |
| **错误文案收口** | 统一"无权访问"的三种成因（未登录 / 未授授权点 / 被 deny），避免"我明明有权限"的误报 | 与 B5 豁免同批 |

### 9.2 接着做（v2，已排期在 §3 批次 C / F）

- 批次 C1：临时授权与到期回收（含到期提醒、即将过期列表）
- 批次 C2：部门级数据隔离（按角色数据范围，不恢复逐资源面板）
- 批次 C3：服务账号与 S2S 鉴权；可扩：密钥轮换提醒、服务账号用量配额、按服务账号维度的审计视图
- 批次 F：租户级角色体系（菜单是否租户化届时再拍）
- 批次 E 扩展：E-2 的 RBAC 单测接进 CI 做 PR 门禁；覆盖率从"判定主干 100%"扩到授权面全量

### 9.3 明确不做（要重估必须有新依据，防止反复被提）

| 不做 | 原因 | 重估条件 |
|---|---|---|
| 权限申请审批流（自助申请—审批—临时授权） | C1 有效期已覆盖"临时授权"的 80%；审批流是独立产品形态 | 出现"非管理员需要自助申请权限"的真实诉求 |
| 行级 ABAC 策略引擎（原 P13 的完整形态） | 角色级数据范围（C2）已覆盖部门隔离；行级策略的表达与调试成本远高于收益 | 出现"同部门内按字段/行再切分"的需求 |
| 菜单租户化 | 内置菜单复制 N 份、前端布局同步失效，收益极低 | 出现"租户自定义侧边栏结构"的需求（目前 my-layout 已覆盖排序/隐藏） |
| IdP 反向同步（wenqu → IdP 写回） | 权限事实源必须在 wenqu，写回造成双写冲突 | IdP 成为唯一权限源的产品决策 |
| 双人复核 / 变更单 | 企业版合规能力，当前规模用不上 | 有等保/内控条款明确要求 |
| 权限态势大屏 | 报表能覆盖；大屏是展示层投入 | 有对外演示需求 |

---

## 附录 A：授权点草案（细粒度，v0）

> A2 的产出 = 把本草案逐 Controller 复核成终稿。清单基于 `master@626b9ecd` 的 52 个 Controller / 440 个端点映射机械清点。

### A.1 命名与判定规则

- 命名：`{domain}:{action}`，action ∈ {read, write, delete, publish, export, import, run, approve, manage, admin, self}。
- 默认映射（仅作起点，终稿以"会被独立授权的边界"为准，见 D2）：
  `GET 列表/详情 → :read`、`POST/PUT 新增修改 → :write`、`DELETE → :delete`、
  发布/下线 → `:publish`、导出/下载 → `:export`、执行/触发/探活 → `:run`、审批裁决 → `:approve`。
- **唯一归属**：一个端点恰好属于一个授权点或一个公开标记，不允许"既是 A 又是 B"。
- "首批"列 = 建议第一批交付（约 75 个）；其余为候选，按需再拆。授权点是数据，加细是加行，不重构。

### A.2 覆盖清单

| 模块 | 授权点 | 覆盖（归纳） | 首批 |
|---|---|---|---|
| 问答 | `chat:use` | POST /chat、/chat/attachment(+/live)、/chat/stop、/chat/steer、/chat/run、/chat/resume、/feedback | ✓ |
| 问答 | `chat:goal` | /chat/goal(+/ {id})、/chat/session/{id}/goal(+/events)、goal/pending | ✓ |
| 问答 | `chat:approve` | tool-approval(GET/POST)、ask-user(GET/POST)、plan-approval(GET/POST) | ✓ |
| 问答 | `chat:tool-output` | GET /tool-output/{id}(+/search) | ✓ |
| 会话 | `session:read` | GET /sessions、/session/{id}、/events、/model、/workspace、/todos、/history-summary | ✓ |
| 会话 | `session:write` | /session/new、pin、favorite、rename、project、model(PUT)、/compact、message-group/switch、message-group/undo | ✓ |
| 会话 | `session:delete` | DELETE /session/{id}、/sessions、/sessions/batch-delete、DELETE /message-group/{id} | ✓ |
| 会话 | `session:share` | /session/{id}/share(3)、/session-shares(3) | ✓ |
| 智能体 | `agent:read` | /list、/{id}/versions、config-defaults、tool-catalog、permission-presets、sub、consultable | ✓ |
| 智能体 | `agent:write` | PUT /{id}、/{id}/rollback、/{id}/default | ✓ |
| 智能体 | `agent:delete` | DELETE /{id}、POST /batch-delete | ✓ |
| 智能体 | `agent:publish` | GET/POST/DELETE /{id}/publish | ✓ |
| 知识库 | `kb:read` | /kb/list、/{id}、param-defaults、param-tips | ✓ |
| 知识库 | `kb:write` | PUT /{id}、PUT /kb/doc/{docId} | ✓ |
| 知识库 | `kb:delete` | DELETE /{id} | ✓ |
| 知识库 | `kb:export` | GET /{id}/export | ✓ |
| 知识库 | `kb:import` | POST /{id}/import | ✓ |
| 文档 | `document:read` | /list、/{id}/source、/queue/stats、/{id}/versions | ✓ |
| 文档 | `document:write` | upload(+/batch)、import-url、PUT status/tags/refresh-config、reparse(+/batch)、batch/status、backfill-descriptions | ✓ |
| 文档 | `document:delete` | DELETE /{id}、batch/delete、/{id}/purge、recycle/purge(+/batch) | ✓ |
| 文档 | `document:restore` | GET /recycle、/{id}/restore、recycle/batch/restore | ✓ |
| 文档 | `document:admin` | /stats、/health、/vector-diagnose、/rebuild-kb、/{id}/rollback | ✓ |
| 知识块 | `knowledge:read` | /knowledge/list、/search、GET /knowledge/{id}（在 ChatController） | ✓ |
| 知识块 | `knowledge:write` | POST /knowledge、PUT /{id}、PUT /{id}/status | ✓ |
| 知识块 | `knowledge:delete` | DELETE /knowledge/{id} | ✓ |
| 知识块 | `knowledge:admin` | GET /knowledge/unmatched | ✓ |
| 图谱 | `graph:read` | /status、triples、view、entity、neighbor、search、chunk | ✓ |
| 图谱 | `graph:build` | POST /{kbId}/build | ✓ |
| 图谱 | `graph:delete` | DELETE /{kbId} | ✓ |
| 工作流 | `workflow:read` | /list、/{id}、versions、templates、config-defaults、automation(GET)、run/list、run/{runId} | ✓ |
| 工作流 | `workflow:write` | PUT /{id}、import、validate、from-agent、automation(PUT)、rollback/{version} | ✓ |
| 工作流 | `workflow:delete` | DELETE /{id}、/batch-delete | ✓ |
| 工作流 | `workflow:publish` | publish、unpublish、batch-publish、batch-unpublish | ✓ |
| 工作流 | `workflow:run` | POST /{id}/run、schedule/run-now、cancel、resume、run stream | ✓ |
| 工作流 | `workflow:export` | GET /{id}/export | ✓ |
| 工作流 | `workflow:approve` | GET/POST /run/{runId}/approval | ✓ |
| 工作流 | `workflow:api` | /v1/workflows/{id}/run(+/runId)（对外触发，身份类型=API Key/服务账号） | ✓ |
| 评测 | `eval:read` | /set、last-report、answer/report | ✓ |
| 评测 | `eval:write` | /case、/generate、/run、/run-auto、answer/run | ✓ |
| 模型 | `provider:read` | /{id}/models、/{id}/references | ✓ |
| 模型 | `provider:write` | PUT /{id}、enabled、models、clear-session-models、batch-enabled、models/fetch、test | ✓ |
| 模型 | `provider:delete` | DELETE /{id}、/batch-delete | ✓ |
| 配置 | `config:read` | /schema、/trace、rerank/check、keyword/check | ✓ |
| 配置 | `config:manage` | /probe、/reset | ✓ |
| MCP | `mcp:read` | /status、audit/logs、audit/summary | ✓ |
| MCP | `mcp:write` | servers(POST/PUT)、enabled、tool-label、batch-enabled、reload、probe、import | ✓ |
| MCP | `mcp:delete` | DELETE /servers/{id}、/batch-delete | ✓ |
| 技能 | `skill:read` | /available、list、detail、content、file、download | ✓ |
| 技能 | `skill:write` | install(+/zip)、PUT /{name}、/{name}/disabled | ✓ |
| 技能 | `skill:delete` | DELETE /{name} | ✓ |
| 市场 | `market:read` | /list、/{id}、/{id}/download | ✓ |
| 市场 | `market:publish` | /publish、PUT status、DELETE /{id} | ✓ |
| 市场 | `market:install` | POST /{id}/install | ✓ |
| 沙盒 | `sandbox:read` | /state、tree、files、download、files/download、procs、procs/live | ✓ |
| 沙盒 | `sandbox:run` | POST /run、procs/kill、files/artifact | ✓ |
| 沙盒 | `sandbox:callback` | POST /tool-call（公开通道，一次性运行凭据守卫） | ✓ |
| 工作区 | `project:read` | /list、/{id}、files、file、file/content、workflows、runs、instruction-history、todos(GET)、turn-guard | ✓ |
| 工作区 | `project:write` | PUT /{id}、status、file、files、dir、todos(POST/PUT) | ✓ |
| 工作区 | `project:delete` | DELETE /{id}、todos/{id} | ✓ |
| 工作区 | `project:export` | GET /{id}/export | ✓ |
| 产物 | `artifact:read` | /list、/{id}/content | ✓ |
| 产物 | `artifact:write` | /refresh-sign、PUT /{id}/project | ✓ |
| 产物 | `artifact:delete` | DELETE /{id}、/batch-delete | ✓ |
| 定时 | `scheduled:read` | /list、/{id}、/{id}/runs | ✓ |
| 定时 | `scheduled:write` | PUT /{id}、enabled、batch-enabled | ✓ |
| 定时 | `scheduled:delete` | DELETE /{id}、/batch-delete | ✓ |
| 定时 | `scheduled:run` | POST /{id}/run | ✓ |
| 定时 | `schedule:admin` | /schedule/tasks、runs、trigger、DELETE runs（调度器总控） | ✓ |
| 个人 | `self:memory` | /memory/content、compact、daily | ✓ |
| 个人 | `self:notification` | /notification/**（按 uid） | ✓ |
| 个人 | `self:preference` | /user/preference、/settings、/profile、/avatar、/menu/my-layout(+/layout PUT) | ✓ |
| 个人 | `credential:read` | GET /credential/list | ✓ |
| 个人 | `credential:write` | PUT /credential/{id} | ✓ |
| 个人 | `credential:delete` | DELETE /credential/{id} | ✓ |
| 个人 | `apikey:read` | GET /api-key/list、/{id}/reveal | ✓ |
| 个人 | `apikey:write` | PUT /{id}、disabled、mcp | ✓ |
| 个人 | `apikey:delete` | DELETE /{id} | ✓ |
| 管理 | `user:read` | GET /user/list、/user/page | ✓ |
| 管理 | `user:write` | PUT /user/{uid}、/{uid}/password | ✓ |
| 管理 | `user:delete` | DELETE /user/{uid}、oidc-binding、identity-binding/{pid} | ✓ |
| 管理 | `role:read` | /role/list、options、{code}/permissions(GET) | ✓ |
| 管理 | `role:manage` | PUT /{code}、DELETE、{code}/permissions(PUT) | ✓ |
| 管理 | `menu:read` | GET /menu/list | ✓ |
| 管理 | `menu:manage` | PUT /menu/{id}、DELETE | ✓ |
| 管理 | `api-endpoint:read` | GET /api/list | ✓ |
| 管理 | `api-endpoint:manage` | PUT /api/{id}、DELETE | ✓ |
| 管理 | `department:read` | GET /department/list | ✓ |
| 管理 | `department:manage` | PUT /department/{id}、DELETE | ✓ |
| 管理 | `tenant:read` | GET /tenant/requests、/{id}/inventory | ✓ |
| 管理 | `tenant:manage` | /{id}/status、/member、requests/{id}/approve、reject | ✓ |
| 管理 | `identity:read` | providers(GET)、mappings(GET)、sync/logs | ✓ |
| 管理 | `identity:manage` | providers(POST/PUT/DELETE/test)、mappings(POST/PUT/DELETE)、sync | ✓ |
| 管理 | `quota:manage` | DELETE /quota/{id} | ✓ |
| 运维 | `audit:read` | GET /audit/export | ✓ |
| 运维 | `audit:verify` | GET /audit/verify | ✓ |
| 运维 | `trace:read` | /trace/list、chat、workflow、pool、stats | ✓ |
| 运维 | `trace:manage` | pool/{id}/label、dismiss、sample/run | ✓ |
| 运维 | `stats:read` | /stats/usage、quota、cost | ✓ |
| 运维 | `content-safety:manage` | /status、dry-run、DELETE /{id} | ✓ |
| 运维 | `ops:read` | checkup/last、announcement(GET)、announcement/active | ✓ |
| 运维 | `ops:manage` | /checkup、force-logout、announcement(POST/DELETE/purge) | ✓ |
| 运维 | `webpush:manage` | /webpush/**（config/keys/test 管理侧） | ✓ |
| 运维 | `manual:sync` | POST /manual/sync | ✓ |
| 运维 | `retrieval-debug:run` | POST /debug/retrieval | ✓ |
| 运维 | `search-index:manage` | /search-index/stats、/reindex | ✓ |
| 运维 | `desc-cache:manage` | /desc-cache/stats、/prune | ✓ |

合计 **106 个**（全部列为首批；采纳 A.3 的合并组可降到约 95 个）。
机械清点比原估的 60~80 多得多，
这本身是细粒度的真实成本——**权限页会多出 90+ 行**，所以 A7 的分组折叠与 B4 的角色模板不是可选优化，是必需项。

### A.2b 公开通道（无授权点，A4 必须逐条列名）

| 通道 | 端点 | 守卫 |
|---|---|---|
| 登录引导 | `/auth/first-run`、`initialize`、`login`、`logout`、`me`、OIDC 4 个、SAML 2 个 | 登录门禁 / 一次性初始化 |
| SCIM | `/Users`、`/Groups`（15 个，无 `/api/ai` 前缀） | SCIM Bearer（`ScimService` 逐入口校验） |
| 游客分享 | `/api/ai/share/**`（16 个） | 分享 token |
| 租户开通申请 | `POST /api/ai/tenant/apply` | IP 限流 + 字段校验 |
| 沙盒回调 | `POST /api/ai/sandbox/tool-call` | 一次性运行凭据 |
| 签名下载 | `GET /project-files/{token}` | 签名 token |
| 公开只读 | `GET /config/public`、`GET /agent/available` | 只读、脱敏 |
| 帮助中心 | `GET /manual/documents`、`/manual/documents/{id}/content` | 内置库只读 |

这些进「公开端点集」，不进授权点表；`self:*`（改密、偏好、头像、记忆、通知）走授权点，**不算公开**。

### A.3 需要在 A2 复核时拍板的合并组

| 候选合并 | 理由 | 影响 |
|---|---|---|
| `graph:read` + `graph:build` | 图谱构建通常在"能看图谱"的人手里就该能做 | 少 1 个点，代价是"只读图谱"做不出来 |
| `*-delete` 并入 `*-write`（仅个人资产：credential/apikey/artifact） | 个人资产"能改就能删"，拆开无实际授权边界 | 少 3 个点；**管理面绝不合并**（"能编辑不能删"正是要的） |
| `eval:write` + `eval:read` | 评测是专家工具，读写不分开授权 | 少 1 个点 |
| 运维 8 个 `*:manage` 合并为 `ops:admin` + 子模块 | 运维角色通常整包授权 | 少 5~6 个点，代价是"只能看 trace 不能重建索引"做不出来 |

建议：**合并组默认不合并**，先按 106 个交付；上线后观察权限页实际使用，若某组从未被分开授权过，下一个版本再合。

### A.4 例外与陷阱（清点时实测发现）

1. **不能按 Controller 归属，只能按端点**：`ChatController` 里混着 `GET /auth/me` 与
   `GET /knowledge/{id}`；`GoalController` 的路径前缀也是 `/api/ai/chat`，与 `ChatController` 重叠。
   按类名给授权点必然错。
2. **SCIM 无 `/api/ai` 前缀**（`/Users`、`/Groups`），且整条链不走 RBAC——必须进公开端点集，
   否则 A4 收口时会被当"未标注端点"拦掉。
3. **`ShareController` 全部是游客通道**（`/api/ai/share/**`），整类公开、不需要授权点，
   但要防被误标——它和"会话分享管理"`session:share` 是两回事。
4. **`ProjectFileViewController` 不在 `/api` 前缀下**（`/project-files/{token}`），
   Spring 拦截器不覆盖，属于签名下载通道，登记为公开。
5. **`ToolInventoryController` 当前 0 个端点**（空壳），扫描器无行可登记，标点时登记为"待补"。
6. **多路径注解与带 charset 的怪 mapping**：`WorkflowController` 有
   `/api/ai/workflow/text/event-stream;charset=UTF-8` 这类路径，且存在一个注解多 path 的情况——
   "端点↔授权点唯一归属"的校验要按**展开后的实际路径**算，不能按注解行数算。
7. **`chat:approve` 必须独立**：只读管理员最该被禁的就是"批准工具执行/批准出网"。合并进 `chat:use`
   会让"能聊天"等于"能批准一切"。

### A.5 校验规则（A2 的验收）

- 每个端点**恰好**一个授权点或一个公开标记；缺失 → 启动 WARN + 权限页「未标注」分组。
- 同一端点标注冲突（注解重复/多 path 落到不同点）→ 启动**失败**（不允许带着歧义上线）。
- 公开标记必须带理由（字段 `publicReason`），权限页可见——防止"顺手加个公开"。
- 每个授权点至少覆盖 1 个端点；空点（0 端点）→ 启动 WARN。
- 覆盖率报告：`已标注 + 公开 = 全部映射数`，否则不通过。

---

## 执行状态（2026-11-04 更新，v1.0 方案的落地进度）

| 批次 | 状态 | 提交 | 真机验证 |
|---|---|---|---|
| 0 跨副本一致性 | ✅ 完成 | `3630397f` | 双副本 8091/8092：A 解绑 → B 即时 403；广播日志双端确认 |
| A 授权点层（M1+M2+A7） | ✅ 完成 | `02c89b38`+`db389bd6`+`6b81e537` | 389/396 端点标注 108 点；legacy/shadow/enforce 三态真机切换全过；G4 种子 56 点；UI 抽屉勾选→反向折算全闭环 |
| B 管理员降级 + deny | ✅ 完成 | `6bf1f6f5` | admin_flag 展开、全局 deny 压过 allow、exempt 豁免、readonly-admin/ops-admin 预设隔离验证 |
| D 反查与审计 | ✅ 完成 | `68e193b0` | members-counts/permission-roles/审计明细/target_type=role 全就位 |
| E 测试与 G9 | ✅ 完成 | `7a9cade5` | 判定核单测 19/19 绿（含 C2 的 8 例）；grant 只删同来源行 |
| C1 临时授权 | ✅ 完成 | `ea00275d` | 授予带到期→读侧即滤→定时清扫留痕→提醒清单 |
| C3 服务账号 | ✅ 完成 | `9aca6d75` | X-Service-Token 边界/轮换旧令牌即失效/过期门/删除级联 |
| C2 部门数据隔离 | ✅ 完成 | `6eb24196` | scope=all 可见库 1→6、回 own 复原；范围只授读；单测 8 例 |
| F 租户级角色 | ✅ 完成 | `4fb76504` | 四租户各 5 角色全种子；跨租户同名共存；huazhang 管理员只见本租户角色；新租户开通即种；19/19 回归绿 |

**批次 F 落地纪要（2026-11-04）**：五张角色域表 PK 变更为 (tenant_id, ...)，存量库由
`RbacTenantKeyRetrofitRunner`（@Order(1)，一次性）改造——它必须先于每租户种子（@Order(2)），
否则种子撞旧 PK 炸启动；`c_ai_role_permission`/`c_ai_role_data_scope` 随角色租户化
（`c_ai_permission` 目录与 `c_ai_service_account` 保持全局）；RoleService 六张快照收进
按租户键控的 Snap（platform/未绑定线程共用不过滤键），TTL 重建连绑定表一起作废；
开通动作跑在平台视图，种租户角色的方法必须自绑目标租户并显式 (tenant_id, code) 查缺。

执行中修正的方案外事实：
- G16 同型坑三次现身（c_ai_role_permission / c_ai_service_account / c_ai_role_data_scope
  都需进 GLOBAL_TABLES，否则租户插件拼 tenant_id 即炸）——新 RBAC 表一律进全局名单。
- 种子/预设角色必须反向折算回 c_ai_role_api（A7 保存路径同口径），
  否则 legacy 双跑模式下纯授权点角色等于零绑定。
- 双跑模式下 deny 行的清理必须按 effect 分列（unbindAllows/Denies），
  全行清空会误删 deny 配置。

## 修订记录（2026-11-03，对齐 `master@626b9ecd`）

1. **G2 数字修正**：`adminGuard.isAdmin()` 9 处 → **28 处 / 10 个 Controller**。
2. **G3 范围修正**：24 处前缀白名单 → **44 个 `return true` 分支 + 3 个自服务辅助方法**；
   补全公开通道枚举（SCIM/auth/分享/沙盒回调/租户申请），A4 要求逐条列名。
3. **G5 / D1 工作量修正**：17 个 Controller → **52 个 Controller / 440 个端点映射**；
   标点方案改为「注解 + 扫描器写入」；原估 3~5 周，D2 定为细粒度后修正为 4~6 周。
4. **G15 事实修正**：`GoalFlowTest`/`GoalMarkerTest` 已被 `697703ad`/`87f900b9` 删除，
   `src/test` 现为空；原 F3 作废，改为「先立测试地基」。
5. **方向冲突显式化**：新增 §0.1「两个维度」与 D6，把数据权限批次与
   `f6a1f589`（资源共享范围退役、谁建归谁）的冲突摆到台面上。
   **当日晚拍板：三项都做**——D6 改判"按部门重建但换形态"、D4 改判"按租户隔离"，
   新增批次 F（租户级角色），批次 C 从「待拍板」转为正式排期（含 C1 有效期 / C2 数据隔离 / C3 服务账号）。
6. **批次重排**：原批次 C（跨副本一致性）提前为**批次 0**——成本最低、且是唯一静默越权的 P0。
7. **新增 G17**：`c_ai_menu_api` 与拟建授权点资源的职责重叠，A1/A2 改为复用它作为候选分组。
8. **新增 G18**：`isPublicUserEndpoint` 与 `isKeyDeniedEndpoint` 双名单必然漂移，A4 合并为单一声明。
9. **新增 G19**：`PermissionsPage.vue` 与 `c_ai_role_api` 强耦合，A6 退役必须带前端改造（A7），
   并补启动期「未标注端点」自检。
10. **新增 B5**：deny 跨角色的自我保护 + 与有效权限视图（批次 D-3）的同批要求。
11. **§6 悬空引用修正**：总纲 `企业级升级与建设方案.md` 不在仓库内，已标注需补回或删除引用。
12. **D2 拍板为细粒度**（2026-11-03）：新增**附录 A**（106 个授权点草案 + 合并组 + 7 条清点陷阱 + 5 条校验规则）；
    A1/A2/A6/A7 相应调整（唯一归属校验、迁移确定性、权限页分组折叠、有效权限视图提前到 A 批次）；
    A 批次工期 3~5 周 → 4~6 周，总工期 12~19 周 → 15~22 周。
13. **端点计数修正**：438 → 440（按 handler 注解实数）。
14. **v1.0 定稿**（2026-11-03）：D1/D3/D5 按建议拍板（做授权点层 / 全局 deny + 按角色豁免 / admin_flag 保留但降级），
    至此 D1~D6 全部落定；新增「一页结论」、§8 交付节奏与里程碑（M0~M7 + M2 双跑策略）、
    §9 后续路线（优化 7 项 + 接着做 5 项 + 明确不做 6 项）。
