# RBAC 整改清单（照着做和改）

> 校准基线：**2026-11-04 工作树**（未提交 15 文件 / +541 行，含 `RoleService`、`ResourceVisibilityService`、`SecurityConfig`、`RoleController`、`schema.sql`、`application.yml`）。
> 定位：`RBAC升级方案.md` 是**方案与决策记录**（D1~D6 已拍板，不再重开）；本文是**执行账**——只列「代码现状与方案口径没对齐的地方」，每条给证据、动作、验收。
> 行号按当前工作树。这块正在被并行修改，动手前先按证据行号复核一次；行号漂了以符号名为准。

---

## 一、先对账：方案文档写着 11 个批次全绿，实际是这样

| 批次 | 文档声称 | 本次复核（代码事实） |
|---|---|---|
| 0 跨副本一致性 | ✅ | 角色缓存广播在（`RoleService:203-210`）；**数据范围快照不在这条广播里** → R-13（删角色那一处已随 A 档补上，部门树快照仍缺） |
| A 授权点层 | ✅ 389/396 标注 | 标注实测已 100%（408 处 `@Permission` / 110 个 code）；**判定链路已唯一化，legacy 整条删除**（第七节）；**可读名没做** → R-07，**分组口径乱** → R-08 |
| B 管理员降级 + deny | ✅ | 判定核支持 deny/exempt；**同点 allow+deny 已改为直接报错**（R-01 已修）；deny 的界面入口由你在并行改动中补（R-10 待验收） |
| C2 部门数据隔离 | ✅ | 「生效部门」是这次未提交改动里才真正接进判定的（`ResourceVisibilityService:210-215`，之前是死字段）；仍有两处资源族没接 → R-11 / R-12，快照失效不全 → R-13 |
| D 反查与审计 | ✅ | 端点在；**反查失败时前端把它显示成「未授权给任何角色」** → R-09 |
| E 测试地基 | ✅ 19/19 | `src/test` 不在 git 跟踪（既定口径），本文不重复计 |
| F 租户级角色 | ✅ | 五表 PK 已租户化；**`RoleDataScope` 实体没有 `tenantId` 字段** → R-15（待核），**两个 Runner `@Order(1)` 撞车** → R-16 |

一句话：**判定核是对的，乱在目录、界面、失效通路和化石**。你看到的「很乱很烂」有 60% 落在刀 4 和刀 5。

---

## 二、刀次与顺序

| 刀 | 内容 | 条目 | 是否碰鉴权 | 建议顺序 |
|---|---|---|---|---|
| 1 | 判定核止血 | R-01 R-02 ~~R-14~~ | 是 | R-01/R-02 已随 A 档收口；R-14 待做 |
| 2 | 双跑收口 | ~~R-04 R-05~~ 已整体退役 | 是（判定链路唯一化了） | **已完成**（见第七节） |
| 3 | 数据范围一致性 | R-11 R-12 R-13 R-15 | 是 | 第 3 批 |
| 4 | 目录与界面可读性 | R-06 R-07 R-08 R-19 | 否 | **第 1 批**（独立、见效最快） |
| 5 | 静默失败 | R-09 R-10 | 否（写路径守门） | 第 1 批 |
| 6 | 对账与化石清理 | R-03 R-16 R-17 R-18 | 否 | 第 4 批 |

刀 4 + 刀 5 完全不动判定链，可以先出、先验收、先发布。

---

## 三、清单

### 刀 1｜判定核止血

**R-01 同一点 allow + deny 时，deny 被静默丢弃 ✅ 已修（2026-11-04，随 A 档）**
- 症状：给某角色既勾了 `chat:use` 又对它下 deny，保存提示成功，deny 实际没落库，权限没收紧。
- 证据：`c_ai_role_permission` PK 不含 effect（`schema.sql:653`）→ `bindDeny` 是 `INSERT IGNORE`（`RolePermissionMapper.java:37`）；保存顺序是先 `bindAllow`（`RoleService.java:1000`）后 `writeDenies`（`:1021`→`:1046-1053`），后者撞 PK 返回 0，而 `n++` 不看返回值（`:1054`）→ 审计里 `denies=` 条数虚高。`RolePermissionMapper:32` 的注释写着「由服务层裁决，不在 SQL 层静默覆盖」，服务层没有这段裁决。
- 动作：`writeDenies` 里对「本次同时出现在 allow 与 deny 的 code」直接 `throw BizException`，报错文案点名是哪几个 code；`n` 改用 `bindDeny` 返回值。三态（允许/禁止/未置）在 UI 上就该互斥（见 R-10），这里是服务端兜底。
- 验收：提交同点冲突 → 界面报「`chat:use` 同时给了允许和禁止」，刷新后原绑定不变；正常 deny 保存 → `GET /role/{code}/permissions` 回显 `denies` 一条。

**R-02 管理员级角色的旧绑定残行会在关掉 adminFlag 后复活 ✅ 已修（2026-11-04，随 A 档）**
- 症状：一个 admin_flag=1 的角色，历史上用端点粒度（apiIds）存过绑定，后来改成授权点粒度保存；某天把管理员级关掉，那批旧端点绑定原样恢复权限。
- 证据：`savePermissions` 只在非管理员分支清 allow（`RoleService.java:948-949`），`savePermissionPoints` 只在非管理员分支清 `c_ai_role_api`（`:1004-1005`）；两条清理各漏一半，且都靠 `adminFlag` 跳过。
- 动作：把「清自己来源的行」提到分支外——`savePermissionPoints` 无条件 `roleApiMapper.unbindAllOfRole(code)`，`savePermissions` 无条件 `unbindAllowsOfRole(code)`；管理员级只是「不写行」，不是「不清行」。
- 验收：建 adminFlag=1 角色 → 分别走两条保存路径 → 关 adminFlag → 有效权限视图里该角色授权点为空（不是复活成历史端点集）。

**R-14 无请求线程时角色集合回落成「普通用户」**
- 症状：后台线程/工具线程里做鉴权，拿到的不是"没身份"而是"user 角色"，静默按普通用户放行或拒绝。这是同一个坑第四次现身。
- 证据：`RequestUser.roles()` 无上下文返回 `List.of("user")`（`util/RequestUser.java:71-74`）；而 `ResourceVisibilityService.rolesOf()` 无上下文返回**空表**（`:168-175`）→ 两处口径相反，一个像"当普通用户"，一个像"谁都不是"。
- 动作：`roles()` 无上下文改为返回空表（与判定侧一致），把「谁在跑」显式化；无请求线程的调用点必须自己装载身份——已知四处：`ChatService.java:906`、`McpServerService.java:716`、`MarketService.java:646-648`、`ServiceAccountService` 令牌装载。
- 验收：起一个不带登录态的后台任务调 `hasPermission` → 判为拒；四条装载路径回归（分享页免登录轮、MCP 服务侧、市场上架、服务账号调用）。
- 风险：这是本清单唯一可能改出大面积 403 的条目，**必须排在有测试之后**。

### 刀 2｜双跑收口 → 已整体退役（2026-11-04）

原 R-04（影子差异没有界面）与 R-05（标注自检开关没打开）不再存在：按「不留兼容」拍板，
双跑机制整条拆除，判定只剩授权点一条链路。拆除账见文末第七节。

### 刀 3｜数据范围一致性

**R-11 GraphRAG 的文档白名单造了 Principal 却不参与判定**
- 症状：图谱检索扩展出来的文档，不受数据范围约束——别人库里的文档可能进图谱结果。
- 证据：`GraphRagService.allowedDocIdsOf`（`:901-903`）new 了 `Principal` 后，循环体只判 `status`/`deleted`（`:905-909`），`p` 全程没被用。
- 动作：循环里补 `canRead(p, d.getCreatedBy())`；若图谱扩展刻意不受范围约束，就删掉死变量并把理由写进方法注释（现在两头都不是）。
- 验收：A 建库→B 用 `all` 档角色跑图谱问答，改前 B 能召回 A 的块、改后不能（own 档）。

**R-12 工作流：详情吃范围，列表不吃，导致部门档"能读但找不到"**
- 症状：把角色调到「本部门可读」后，别人的工作流详情打得开，但列表里根本没有它——只能靠链接直达。
- 证据：单读走 `visibility.canRead`（`WorkflowService.java:735-742`、`:766`）；列表恒本人（`listVisible` 的 SQL `eq(Workflow::getUid)`，`:181-186`，注释写着「恒为本人创建的（mine）」；`listByProject` 同理 `:159-163`，那条注释说明工作区是单用户容器，属刻意）。
- 动作：见分岔 Q2。推荐 `listVisible` 加 `shared` 栏走 `canRead` 过滤，`listByProject` 保持本人。
- 验收：部门档账号在工作流页看到「本部门」栏；工作区页仍只见自己的。

**R-13 数据范围与部门树的快照失效通路不全**
- 症状：改完范围/部门树，本机最长 60s 才生效，其它副本只能等 TTL——角色缓存是有广播的，范围没有。
- 证据：`invalidateScopes()` 只 `scopeSnapshots.clear()`，不清 `deptParentSnapshots`（`ResourceVisibilityService.java:116-118`）；全仓只有 `RoleService.saveDataScope` 调它（`:1152`），`RoleService.delete` 删了角色和绑定却不调（`:901-915`）；`OrgService.updateDepartment`（`:99`）与硬删部门（`:127`）没有任何 invalidate；`RoleService` 那套是 Redis 广播（`:203-210`），范围快照没挂进去。
- 动作：`invalidateScopes` 同时清两张表；`delete` 与部门写路径都调它；把它挂进 `RoleService` 现有的失效广播（同一条频道，收到即 clear）。
- 验收：双副本下 A 改部门归属 → B 立即按新部门树判定（不等 60s）。

**R-15 `RoleDataScope` 实体与租户化后的 PK 不对齐（待核，别当结论用）**
- 症状：批次 F 把 PK 改成 `(tenant_id, role_code)`，实体只有 `@TableId roleCode`、没有 `tenantId` 字段。`selectById(code)` 靠租户插件拼谓词才没串租。
- 证据：`model/RoleDataScope.java:21-22`（无 tenantId）对 `schema.sql:621-628`（复合 PK）；写入靠拦截器补列（`config/WenquTenantLineHandler.java`）。
- 动作：先核实——两个租户各建同名 `role_code` 的角色、分别设不同 scopeType，然后各自改一次范围，看是否落到自己的行（`select * from c_ai_role_data_scope`）。确认没问题就只补注释；有问题就实体加 `tenantId` 并把查询改成显式 `(tenant_id, role_code)`。
- 验收：跨租户同名角色互不覆盖；`saveDataScope` 在新租户下不写进 default 行。

### 刀 4｜目录与界面可读性（你说的「看不懂」主要在这）

**R-06 分组名中英混杂：10 个 Controller 没汉化，还有 1 个幻影键**
- 证据：`MODULE_ZH`（`ApiEndpointScanner.java:86-130`）实测缺 `Goal`、`IdentityAdmin`、`Market`、`Ops`、`ServiceAccount`、`WebPush`（这 6 个会出现在权限抽屉）+ `ProjectFileView`、`Saml`、`SandboxToolCallback`、`Scim`（这 4 个只出现在接口页签）；表里另有幻影键 `OidcProvider`（无此类）。回落逻辑在 `:414-417`。
- 动作：补 10 条中文键、删幻影键。命名按本仓口径走业务功能名，不搬 Controller 名。
- 验收：权限抽屉与接口页签里不再出现任何英文组名；`SELECT DISTINCT module FROM c_ai_permission` 全中文。
- 注意：`module` 是启动时写进目录表的，补对照表后**存量行不会被 INSERT IGNORE 更新**——需要一次性 UPDATE 或在扫描器里补「展示字段回填」（对照表注释 `:83` 已声明改名无副作用，但没写回填动作）。

**R-07 110 个授权点全部只显示裸编码，因为可读名压根没做**
- 症状：`manual:sync`、`chat:tool-output`、`service-account:manage` 直入界面，这就是「看不懂」的直接来源。
- 证据：`permissionDisplayName(code)` 原样 `return code`（`ApiEndpointScanner.java:458-461`，注释自认「M1 先与编码同形；A2 逐 Controller 复核时换成可读名」——A2 复核没做这件事）；前端已支持 `code · 名称` 双段（`PermissionsPage.vue:872`），后端不给名字它就只显示编码。
- 动作：给 110 个 code 出一份 `code → 中文名` 表（按附录 A 的草案口径），写进扫描器 `register` 的 name 参数；同 R-06，存量行 name==code 的要回填（UPDATE 或扫描器内「name 等于 code 视为未定制，允许覆盖」）。
- 验收：抽屉每个叶子是「同步内置手册 `manual:sync`」这类双段；搜索框按中文也搜得到（`:880-888` 已匹配 name）。

**R-08 分组按 Controller，不按授权点语义**
- 症状：`chat:goal` 被分到「Goal」组而不是「对话」组；`kb:` 与 `knowledge:` 同域双前缀；`schedule:admin` 与 `scheduled:*` 近似；同前缀散在 2~4 组。
- 证据：分组取 `module` 字段（`PermissionsPage.vue:854-874`），而 `module` 来自 Controller 类名（`ApiEndpointScanner.java:414-417`）；跨 Controller 复用同 code 的实测有 `chat:use`（Chat+Qa）、`stats:read`（UsageStats+Qa）、`knowledge:read`（Knowledge+Chat）、`self:preference`（User+Menu）。
- 动作：见分岔 Q1。推荐改按 code 前缀分组（前端一处 `permGrouped` 的 key 换成前缀），同时归一 `kb:`/`knowledge:` 与 `schedule:`/`scheduled:` 两对前缀。
- 验收：`chat:*` 全在一组；组数下降；组级全选语义变成「全选该业务域」。

**R-19 界面直露内部标识与黑话**
- 证据：判定模式英文原样（`PermissionsPage.vue:324` 显示 `legacy`）；`admin:all-allow` 当分组名（`:315`、`:789`）；「批次 C2」写进用户提示（`:369`）；提示里解释「全局 deny」（`:315`）而界面上根本没有 deny（R-10）；「可见」一词三用——菜单可见开关（`:82`）、范围可读（`:368-369`）、已退役的共享（`:369`）。
- 动作：mode 汉化（旧链路/双跑对照/按授权点）；`admin:all-allow` 显示成「管理员级：全部动作」；删掉「批次 C2」这类编号；deny 文案等 R-10 落地后再出现；「共享」字样按本仓一词口径清干净。
- 验收：权限页整页截图里不出现 `legacy`、`admin:all-allow`、「批次」、`deny`（未落地前）。

### 刀 5｜静默失败（有两条会真删权限）

**R-09 五处吞错，其中两处能把角色权限清空或降级**
- a) 抽屉先开后拉数据，失败只 toast，勾选项留空（`PermissionsPage.vue:938-948`）→ 此时点「保存权限」= 全量替换提交空集，**该角色全部授权点被清**（`:958-968` 无拦截）。
- b) 目录加载失败留空且不报错（`:952-956`，`r.success=false` 时 `data` 为空）→ 回显时把已有勾选全滤掉（`:945-947`）→ 同 a) 的清空路径。
- c) 编辑角色时范围回显失败静默按 `own`（`:716-720`）→ 直接保存 = 把 `department` 悄悄改回 `own`。
- d) `saveRoleDataScope` 的空 catch 后照弹「已保存」（`:734-735`）。
- e) 反查失败显示成真实数据：成员数 0（`:747-750`）、接口列「未授权给任何角色」（`:133-136`）。
- 动作：抽屉与角色弹窗加 `loadFailed` 态——失败时保存按钮禁用 + 就地「重试」；c) 的默认值改成「未加载」而非 `own`；d) 失败必须报错并保留弹窗；e) 失败态显示「取数失败 · 重试」而不是 0。
- 验收：把后端停掉再开抽屉 → 保存键不可点；网络恢复点重试 → 勾选完整回显；对 department 角色重复 c) 场景保存 → DB 里 scope_type 不变。

**R-10 deny 有后端、有判定、有预设语义，就是没有界面**
- 证据：`GET /role/{code}/permissions` 已回显 `denies`（`RoleController.java:118`）、PUT 已支持同事务带 denies（`:137` → `RoleService.java:984-1021`），但前端回显丢弃（`PermissionsPage.vue:941-947` 只取 menuIds/permissions）、保存不带（`:963`）。注：PUT 时 denies 为 null 表示「本次不动 deny」（`RoleService.java:1044`），所以现在不会误删，只是配不了。
- 动作：抽屉叶子做三态（允许 / 禁止 / 未置）+ 一个「仅本角色内生效」勾选（对应 exempt，D3）；保存时一次提交 permissions + denies。做完这条，R-19 里那句 deny 文案才该出现。
- 验收：配「只读管理员」= admin_flag=1 + 对 `*:write` 类点下 deny，该账号能读不能写，且有效权限视图里能看到被禁的点。

### 刀 6｜对账与化石

**R-03 注释声称的防线不存在**
- 证据：`SecurityConfig.java:483-484` 写「绑定侧已在 `ApiKeyService` 禁掉管理员级代表账号」，但 `ApiKeyService.java`、`ApiKeyController.java` 里没有任何 admin 判定（grep 无 `isAdmin`/`adminFlag`/`superadmin`）。真正兜住的是 Key 硬边界（`:489-495`），行为目前是安全的。
- 动作：见分岔 Q4——要么把注释改成「唯一防线在此」，要么在绑定侧真加校验。推荐加校验：否则后人按注释删硬边界不会有任何报警。
- 验收：注释与代码一致；若加校验，则给管理员级账号绑 Key 应被拒并点名原因。

**R-16 启动钩子顺序：文档漂移 + 两个 Runner 撞 `@Order(1)`**
- 证据：`startup/package-info.java:46-47` 写 RbacSeedRunner 是 `@Order(1)`，实际 `@Order(2)`（`RbacSeedRunner.java:49`）；表里 ApiEndpointScanner 没标 Order（实际 10）；`RbacTenantKeyRetrofitRunner`（`@Order(1)`）与 `RbacPermissionMigrationRunner`（`@Order(11)`）**完全不在清单里**（grep 计数 0）；`TenantSeedRunner` 同为 `@Order(1)`，与改造 Runner 的相对顺序未定义。
- 动作：`RbacTenantKeyRetrofitRunner` 单独占一个更早的序（它必须先于每租户种子，否则种子撞旧 PK）；`TenantSeedRunner` 后移一位；package-info 表补两行、Order 值全部对齐代码。
- 验收：启动日志按序打印五个钩子且与 package-info 一致；空库首启 + 存量库升级两条路都过。

**R-17 菜单归属表还剩什么用**
- 证据：抽屉里接口叶子已撤（`PermissionsPage.vue:840` 注释「接口能力走授权点」），但 `ApiEndpointScanner.DEFAULT_MENUS`（`:138-`）与 `c_ai_menu_api` 仍每次启动幂等补齐，且 `DEFAULT_MENUS` 实测漏 18 个 Controller（含上述 10 个 + Auth/DescCache/Manual/Memory/Notification/Oidc/Sandbox/ServiceAccount/WebPush/Ops/Market）。
- 动作：确认归属现在只服务「接口页签分组」；若接口页签也不需要按菜单分组，就把归属表与补齐逻辑整条退役（方案 §4 退役清单口径：不留兼容）。
- 验收：要么接口页签仍在用分组并补齐 18 个键，要么表、Mapper、扫描器写入三处一起消失，不留半截。

**R-18 死代码与双端化石**
- `api.js:1530` `saveRolePermissions`（apiIds 变体）被 `PermissionsPage.vue:441` 导入但从未调用；`api.js:1542` `getRoleMembers` 零消费。
- `RoleController.java:138-140` PUT 的 apiIds 兼容分支：前端已全走授权点，切 enforce 后按退役清单删除。
- `MarketPublishModal.vue:136-137` 仍读 `data.shared`，而 `WorkflowService.listVisible` 只返回 `mine`（`:181-190`）；`WorkflowController.java:38` 的 `@Operation` 描述仍写「{mine, shared}」——接口文档与响应体两头撒谎。
- `ResourceVisibilityService.Principal` 的 `role` 字段只被 `superadmin()` 读，而 `superadmin()` 全仓零调用（其余都走 `RequestUser.superadmin()`）→ 死参数，且判定用的是 ThreadLocal 全角色并集，与该字段口径脱节。
- 动作：删死导入/死导出、`@Operation` 与响应体对齐、Principal 去掉 role 字段（或让它真参与判定）。
- 验收：`grep -rn "shared\|saveRolePermissions\|getRoleMembers" web/src` 无化石；Swagger 里工作流列表描述与实际返回一致。

---

## 四、要你拍板的四个分岔

**Q1 授权点分组口径（R-08）**
按 code 前缀（推荐）：组数从 42 降到约 30，`chat:*` 归一，代价是「组级全选」不再等于「全选某个 Controller 的端点」。
保持按 Controller：改动最小，但 `chat:goal` 永远在「Goal」组里，用户学不会这套分组逻辑。

**Q2 工作流列表吃不吃范围（R-12）**
推荐「列表加一栏本部门可读、写仍只创建者」。另一档是把单读的 `canRead` 也收成创建者，让工作流彻底回到"个人资产"语义——那要同时改 `WorkflowService:735-742`/`:766` 两处，且和「部门可读」这个卖点冲突。

**Q3 `roles()` 无上下文回落（R-14）**
推荐改空集 + 显式装载。保守档是只统一两处口径（把 `roles()` 也改成空）但先不动四个装载点，风险是那四条后台链路立刻全判 NONE——所以这条实际上没有便宜的走法，只能连装载一起做。

**Q4 API Key 代表账号（R-03）**
推荐在绑定侧真加校验（管理员级账号不允许绑 Key），注释随之成立。另一档只改注释，承认硬边界是唯一防线——但那样 `c_ai_api_key` 界面里能选出管理员级代表账号，靠拦截器兜着。

---

## 五、明确不做

- 不恢复 `share_config` 式逐资源共享面板（D6 已拍板，形态换成角色自带数据范围）。
- 不做行级 ABAC、权限申请审批流、双人复核、态势大屏（方案 §9.3）。
- 不给 legacy 双写留开关或兼容窗口：切 enforce 后 `c_ai_role_api`、`savePermissions`、PUT 的 apiIds 分支整条删（方案 §4 退役清单）。
- 不新增授权点粒度以外的第二套勾选面；`c_ai_menu_api` 若退役就退役，不并存。

---

## 六、工作量

| 刀 | 条目 | 估时 | 风险 |
|---|---|---|---|
| 4 可读性 | R-06 R-07 R-08 R-19 | 2~3 天（含 110 个 code 起名与存量回填） | 低，纯展示 + 目录字段 |
| 5 静默失败 | R-09 R-10 | 2 天 | 中：三态勾选是新交互，要出真机对照 |
| 1 判定核 | R-14 | 2~3 天 | 高：会牵动四条后台线程链路 |
| 3 数据范围 | R-11 R-12 R-13 R-15 | 2~3 天 | 中：跨副本失效要双实例验 |
| 6 对账化石 | R-03 R-16 R-17 R-18 | 1.5 天 | 低 |

合计约 8~10 人天（A 档拆除已完成，从原估里扣掉双跑那 1 天与判定核两条）。

---

## 七、A 档拆除账（2026-11-04 已落地）

按「不留兼容」拍板执行。判定链路从此只有一条：**端点 →（`@Permission` 扫描出的）授权点 →（`c_ai_role_permission` 的 allow/deny）→ 角色**。

### 删掉了什么

| 项 | 位置 |
|---|---|
| `permission-mode` 三态与 `require-annotation` 开关 | `application.yml` 的 `ai-app.rbac` 整块（两个键连同 env 变量一起消失） |
| legacy 判定链 | `RoleService`：`canAccessAnyLegacy`、`anyRoleBindsEndpoint`、`canAccess`、`apiPatternsOf`、`ApiPattern`、`isDenyOf`、`hasDenyRows`、`denyRowCount`、`Snap` 的 `apiPatterns`/`apiIds` 两条分量 |
| 端点粒度写入路径 | `RoleService.savePermissions(menuIds, apiIds)`、PUT 的 apiIds 兼容分支、`apiIdsOf`、`apiIdSet`、`GET permissions` 的 `apiIds` 字段 |
| 旧绑定表 | `c_ai_role_api`（建表语句删除 + 退役段显式 `DROP TABLE IF EXISTS`）、`RoleApiMapper`、种子与扫描器里的反向折算段 |
| 影子双跑台账 | `c_ai_rbac_shadow_diff` 表、`RbacShadowDiffMapper`、`Diff`/`shadowDiffs`/`recordShadowDiff`/`flushShadowDiffs`/`shadowDiffRows`/`shadowPending`/`clearShadowDiffs`/`stableId`、`rbacStatus`、`GET/DELETE /role/shadow-diff`、`GET /role/rbac-status`、`RoleController.effective` 的 `mode` 字段、`countDenies` |
| 前端双跑界面 | 角色页签的「判定模式」横幅、影子差异弹窗及其 script/CSS、有效权限视图里的「判定模式」一行 |
| 一次性折算钩子 | `RbacPermissionMigrationRunner`（整类删除；它的 user 种子职责另立，见下） |

### 保留了什么

- `@Permission` 注解、`c_ai_permission` 目录、`c_ai_api.permission_code`、`permissionOfEndpoint` 的精确+pattern 匹配。
- `c_ai_role_permission` 的 allow/deny/exempt 三态语义（D3）与管理员级展开（D5）——`judgePermission` 是现在唯一的判定核。
- 拦截器的 Key 硬边界、SCIM/分享/沙盒回调等公开通道；`isPublicUserEndpoint` 继续服务两处：API Key 的问答面，以及 user 角色种子的推导输入。
- Redis 失效广播（`rbac:invalidate`，scope 仍分 role/api）与 60s TTL 兜底。

### 补位了什么

1. **白名单收口**：`SecurityConfig` 第 3 层不再按模式二选一，恒用 `isPublicChannelEndpoint`（逐条列名的公开通道）。新端点没标注也没在白名单里 → 只有管理员级可达，其余 403。
2. **user 角色种子**：新建 `RbacUserRoleSeeder`（`@Order(12)`，必须晚于扫描器，因为推导依赖 `permission_code`）。规则不变——授权点入选 ⇔ 名下全部端点都在问答白名单内；该租户 user 已有任何授权点行则整租户跳过（不覆盖管理员的调整）。
3. **标注自检恒开**：`ApiEndpointScanner` 未标注受控端点改为无条件逐条 WARN（不再有开关，也不再"标完才打开"）。
4. **R-01**：`writeDenies` 现在先查同点冲突，冲突直接 `BizException` 点名是哪几个 code；写入条数改用 `bindDeny` 返回值，审计不再虚高。
5. **R-02**：`savePermissionPoints` 清 allow 行不再按 adminFlag 分支；反向折算段整体删除，`c_ai_role_api` 无从复活权限。
6. **R-13 的一半**：`RoleService.delete` 现在调 `invalidateScopes()`，删角色不再留 60s 脏范围。

### 上线前置（必须先跑，别直接部署）

删表的前提是「旧表覆盖的端点已被授权点 100% 覆盖」。本地库已复算通过：

```sql
-- 三条都必须是 0
SELECT COUNT(*) FROM c_ai_role_api ra JOIN c_ai_api a ON a.id=ra.api_id
  WHERE a.permission_code IS NULL OR a.permission_code='';                       -- 指向未标注端点
SELECT COUNT(*) FROM c_ai_role_api ra JOIN c_ai_api a ON a.id=ra.api_id
  LEFT JOIN c_ai_role_permission rp ON rp.tenant_id=ra.tenant_id
    AND rp.role_code=ra.role_code AND rp.permission_code=a.permission_code
    AND rp.effect='allow' WHERE rp.permission_code IS NULL;                      -- 未被授权点覆盖
```

实测（本地 `wenqu_ai`，2026-11-04）：旧表 1013 行、授权点 452 行（user/readonly-admin/ops-admin × 4 租户），两条未覆盖计数均为 **0**。**线上部署前要用同样两条复算，非 0 就先补授权点再发版**——发版即删表，没有回退开关。

### 验收（静态已过，真机待跑）

- `mvn -o compile` BUILD SUCCESS、`npm run build` 通过、全仓 grep 无 `permission-mode`/`c_ai_role_api`/`RoleApiMapper`/`shadow-diff` 残留。
- 待真机：① 普通用户（仅 user 角色）登录后问答链路全通、管理面 403；② 勾掉某角色一个授权点 → 该角色账号立刻 403，且**关掉再打开管理员级开关不会复活历史端点权限**；③ 同点同时提交 allow+deny → 报「同一授权点不能同时允许和禁止」；④ 新加一个无 `@Permission` 的端点 → 启动日志逐条 WARN 且非管理员 403。

