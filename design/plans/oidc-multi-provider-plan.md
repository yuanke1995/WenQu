# 问渠 OIDC 多认证源改造方案

> 结论先行：当前 OIDC 实现是**单认证源（单 IdP）**——配置层是 `c_ai_config` 里 20 个扁平键
> （`oidc.*`），服务层 `OidcService` 只持一套元数据缓存，账号绑定是 `c_ai_user.oidc_sub`
> 单列唯一索引。本文给出改造为**多认证源**的完整方案。

---

## 1. 现状证据（为什么说「只能配一个」）

| 层 | 位置 | 现状 |
|---|---|---|
| 配置 | `config-schema.json` L2252–2543、`ConfigService.defaults()` L582–601 | 20 个扁平键 `oidc.enabled / oidc.providerName / oidc.issuerUrl / oidc.clientId / oidc.clientSecret / …`，无列表结构 |
| 设置页 | `SettingsPage.vue`（纯 schema 驱动）+ `config-schema.json` panels L112–119 | 「单点登录」面板只渲染这一组字段 |
| 服务 | `OidcService.java` L96（`volatile Metadata metadata` 单缓存）、L490 `configured()` | 单 provider；`states`/`codes` 两个 Map 的 key 是随机 token，**不带 provider 维度** |
| 入口 | `OidcController` L33–37 `/config` 只回 `{enabled, providerName}`；`LoginPage.vue` L81–84 只渲染一个按钮 | 单按钮、单回调路径 `/api/ai/auth/oidc/callback` |
| 绑定 | `schema.sql` L418/426 `c_ai_user.oidc_sub` 单列 + `uk_oidc_sub` 唯一索引；`OidcService.findByOidcSub` 全局按 sub 查 | 一个 sub 只能绑一个账号；跨 IdP 的相同 sub 值会直接撞唯一索引 |

---

## 2. 方案选型：为什么搬到独立表

三个候选：

| 候选 | 结论 |
|---|---|
| A. `c_ai_config` 扁平键 + 序号前缀（`oidc.p1.clientId`） | ❌ `ConfigService.update()` 按 `schema.isEditable(fullKey)` 逐键校验、`snapshot()` 只遍历 `defaults()` 登记过的键——动态 key 进不了静态 schema，改了不回显、存不进去 |
| B. 单个 JSON 值（`oidc.providers = [...]`） | ❌ 敏感字段无法逐项 RSA 加密/掩码（`isSensitiveKey` 按 key 后缀判定，整块加密后快照掩码成 `****后4位`，设置页无法分字段展示）；schema 校验不可用；无行级并发保护 |
| C. **独立表 + 管理端点 + 管理页**（推荐） | ✅ 项目内已有完全同形的先例：**模型供应商** `c_ai_provider`（`schema.sql` L438–459）+ `ModelRegistryService`（api_key 逐行 `ConfigCryptoService` 加密、列表 `mask()` 脱敏）+ `ProviderController` + 前端管理页。MCP、工作流凭据同样是这个形状 |

改造后形态：**`c_ai_oidc_provider` 表（N 行认证源）+ `c_ai_user` 绑定列加 provider 维度 + 登录页 N 个按钮**。

---

## 3. 数据模型设计

### 3.1 新表 `c_ai_oidc_provider`（schema.sql 新增，字段平移现有 20 个键）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_oidc_provider` (
    `id`                   VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `name`                 VARCHAR(100) NOT NULL COMMENT '认证源名称（登录按钮文案）',
    `enabled`              INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用',
    `issuer_url`           VARCHAR(512) DEFAULT NULL COMMENT 'IdP issuer（discovery 源）',
    `client_id`            VARCHAR(255) DEFAULT NULL COMMENT '客户端 ID',
    `client_secret`        TEXT         DEFAULT NULL COMMENT '客户端密钥（RSA 加密存储，RSA: 前缀）',
    `redirect_uri`         VARCHAR(512) DEFAULT NULL COMMENT '回调地址（空=按访问来源推导）',
    `frontend_base_url`    VARCHAR(512) DEFAULT NULL COMMENT '登录后前端跳转基地址（空=同源相对路径）',
    `authorization_endpoint` VARCHAR(512) DEFAULT NULL COMMENT '授权端点（空=discovery）',
    `token_endpoint`       VARCHAR(512) DEFAULT NULL COMMENT '令牌端点（空=discovery）',
    `userinfo_endpoint`    VARCHAR(512) DEFAULT NULL COMMENT '用户信息端点（空=discovery）',
    `scopes`               VARCHAR(255) DEFAULT 'openid profile email' COMMENT '请求 scope（空格分隔）',
    `force_prompt_login`   INT          DEFAULT 0 COMMENT '授权请求带 prompt=login',
    `username_claim`       VARCHAR(100) DEFAULT 'preferred_username' COMMENT '用户名字段',
    `name_claim`           VARCHAR(100) DEFAULT 'name' COMMENT '显示名字段',
    `use_raw_username`     INT          DEFAULT 0 COMMENT '用 IdP 用户名当账号标识',
    `auto_create_user`     INT          DEFAULT 1 COMMENT '首次登录自动建号',
    `default_role`         VARCHAR(64)  DEFAULT 'user' COMMENT '自动建档默认角色',
    `default_department`   VARCHAR(50)  DEFAULT NULL COMMENT '自动建档默认部门',
    `fetch_department_info` INT         DEFAULT 0 COMMENT '从 userinfo 同步部门',
    `department_claim`     VARCHAR(100) DEFAULT 'department' COMMENT '部门字段',
    `sort_order`           INT          DEFAULT 0 COMMENT '排序（小在前，决定登录页按钮顺序）',
    `create_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OIDC 认证源表（多 IdP）';
```

`SchemaMigrator`（`@Order(0)`，启动最早跑）会自动为存量库建新表、补列——**新表零手工 DDL**。

### 3.2 账号绑定：`c_ai_user` 加 `oidc_provider` 列（推荐方案 A1）

- `schema.sql` `c_ai_user` 加列 `oidc_provider VARCHAR(50) DEFAULT NULL COMMENT 'OIDC 认证源（c_ai_oidc_provider.id；空=未绑定）'`；
- 唯一索引由 `uk_oidc_sub(oidc_sub)` 改为 **`uk_oidc_provider_sub(oidc_provider, oidc_sub)`**——「一个 sub 在一个认证源下只能绑一个账号」的防冒用语义按 provider 维度保留；两个不同 IdP 签发相同 sub 值不再冲突（它们本就是不同身份）；
- 存量行回填 `oidc_provider = 'default'`（见 §4 迁移）；
- 语义边界：一个本地账号同时只能绑一个认证源（换绑覆盖旧绑定）。若将来出现「同一账号要同时绑多个 IdP」的需求，再演进为独立绑定表 `c_ai_user_oidc_binding(user_uid, provider_id, sub)`，UK(provider_id, sub)——届时 `OidcService` 的绑定读写面很小（集中在 `resolveUser`/`findByOidcSub`/`createUser` 三处），机械迁移即可。

### 3.3 索引更换的存量处理

`SchemaMigator` 的边界是「只补列/补索引，不删不重建」（见其类注释）。旧 `uk_oidc_sub` 单列唯一索引若保留，极端场景（两个 IdP 发出相同 sub）仍会撞索引。处理：启动迁移 runner 里**先回填 `oidc_provider='default'`，再 `DROP INDEX uk_oidc_sub`、`ADD UNIQUE KEY uk_oidc_provider_sub(...)`**（先查 information_schema 判断存在性，幂等；失败仅告警并保留旧索引——功能按复合键判断，冲突时 `resolveUser` 已有的 `DuplicateKeyException` 分支给出明确错误，不静默）。

---

## 4. 存量迁移与兼容（回滚零成本）

启动迁移 runner（`@Order` 排在 `SchemaMigrator` 之后，参照 `RbacSeedRunner` 的形态），三步全部幂等：

1. **组装 default 行**：`c_ai_oidc_provider` 为空 且 `c_ai_config` 存在任一 `oidc.*` 行时，把 20 个键（直接从 `configMapper` 读，**不经过 `defaults()`**）组装成一行 `id='default'`、`name` 取 `oidc.providerName`（空则「默认认证源」）。**旧 `oidc.*` 行原样保留、不删**——代码回滚后老实现直接可读，回滚零成本（与项目里退役键「留作孤儿数据、无读取方」的口径一致，但这里连孤儿数据都不产生）。
2. **回填绑定**：`oidc_sub` 非空的行 → `oidc_provider='default'`。
3. **换唯一索引**：见 §3.3。

迁移完成后：
- `ConfigService.defaults()` 删除 20 个 `oidc.*` 键；`config-schema.json` 删除 oidc panel、20 个字段定义、20 条 tips；
- 设置页「单点登录」面板随之消失（纯 schema 驱动，删定义即消失），管理入口迁到新页面（§6.2）；
- `ConfigService.update()` 对 `oidc.*` 会整笔拒绝（unknownKeys）——前端迁移后不再提交这些键，无影响。

---

## 5. 安全设计（验收硬项）

1. **管理端点绝不免登录**。现状 `SecurityConfig.isAuthBootstrapEndpoint` 是 `path.startsWith("/api/ai/auth/oidc/")` 整段豁免；多 provider 后 provider CRUD 挂在 `/api/ai/oidc/**`（不走 auth 前缀），同时把豁免条件**收窄为四个公开路径的精确匹配**（`GET /config`、`GET /login-url`、`GET /callback`、`POST /exchange-code`）——否则以后往 `/auth/oidc/` 下加任何管理端点都会变成免登录。
2. **clientSecret 逐行加密**：存 `ConfigCryptoService.encrypt`（`RSA:` 前缀），读 `decrypt`，列表回显 `****后4位`（照抄 `ModelRegistryService.mask` L1312 与 save L1003 的掩码保护：未修改提交掩码值不覆盖原值）。
3. **state / 一次性 code 机制不变**：`StateEntry` / `CodeEntry` 增加 `providerId` 字段；state 仍是随机不透明 token，TTL 5min / 60s 不变。
4. **回调寻址用 state 带 provider，URL 不变**：回调路径保持 `/api/ai/auth/oidc/callback`，多个 client 在各自 IdP 登记**同一**回调地址即可（redirect_uri 按 client 登记，同 URL 可被多 client 共用）。避免 `/callback/{providerId}` 把内部 id 露在 URL、以及老 IdP 只接受固定回调形态的兼容问题。
5. **删除守门**：provider 上有账号绑定（按 `oidc_provider` 计数 > 0）时拒绝删除，提示先解绑——防止用户登录入口静默消失、账号成孤儿（参照供应商删除守门 `references` 的思路）。
6. **保存期校验前移**：`defaultRole` 必须 `roleService.existsActive`（现状是首次登录才抛错，管理员后知后觉）；启用的 provider 必须 `clientId` 非空且 (`issuerUrl` 或 `authorizationEndpoint`) 非空（照搬现状 `configured()` 口径）。
7. **RBAC**：`/api/ai/oidc/**` 不在普通用户白名单 → 走管理员判定（fail-closed），权限页接口树自动收录，可授给自定义角色。`ApiEndpointScanner.MODULE_ZH` 加 `"OidcProvider" → "单点登录"`（L89 已有 `"Oidc"` 可复用或拆分），`DEFAULT_MENUS` 归属设置菜单。
8. **回调时 provider 失效**：state 带出的 provider 在回调时已删除/停用 → 明确错误「认证源已下架或停用，请联系管理员」，不静默失败。

---

## 6. 改造清单（文件级）

### 6.1 后端

| 文件 | 改动 |
|---|---|
| `schema.sql` | 新增 `c_ai_oidc_provider` 表；`c_ai_user` 加 `oidc_provider` 列；唯一索引换复合（§3） |
| `model/OidcProvider.java`（新） | 表实体（照 `model/Provider.java`） |
| `mapper/OidcProviderMapper.java`（新） | MyBatis-Plus mapper |
| `service/OidcProviderService.java`（新） | CRUD + 启用停用 + 删除守门 + 密文加脱敏；`listPublic()` 只回 `{id, name}`（enabled 且 configured） |
| `controller/OidcProviderController.java`（新） | `/api/ai/oidc/**`：列表/新建/改/删/启停（管理员判定）；可选 `POST /{id}/test` 探测 discovery 可达性（照 `ProviderController.test`） |
| `service/OidcService.java` | **重构为 provider 维度**：`volatile Metadata metadata` → `Map<providerId, Metadata>`（缓存键仍含全部配置值，改行即失效）；所有 `cfg("oidc.x")` → provider getter；`loginUrl(providerId, …)` / `handleCallback(…由 state 带出 providerId…)`；`findByOidcSub` → 按 `(providerId, sub)` 查；`resolveUser`/`createUser` 绑定写 `oidc_provider`；`publicConfig()` 回 `{providers:[{id,name}…]}`；`extract`/`normalizeRole`/`resolveDepartmentId` 逻辑不变只换配置来源；类头 javadoc 同步改写 |
| `controller/OidcController.java` | `/config` 回 providers 数组；`/login-url` 增 `providerId` 参数；`/callback`、`/exchange-code` 签名内部调整（对外协议不变） |
| `model/User.java` | 加 `oidcProvider` 字段（L63 `oidcSub` 旁） |
| `config/SecurityConfig.java` | `isAuthBootstrapEndpoint` 的 oidc 分支收窄为四个精确路径（§5.1） |
| `startup/ApiEndpointScanner.java` | `MODULE_ZH` / `DEFAULT_MENUS` 收录新控制器（§5.7） |
| `startup/OidcMigrationRunner.java`（新） | §4 三步幂等迁移 |
| `service/ConfigService.java` | `defaults()` 删 20 个 `oidc.*` 键（L578–601 整段） |
| `resources/config-schema.json` | 删 oidc panel（L112–119）、20 个字段（L2251–2545）、20 条 tips（L3919–3938） |
| `controller/UserController.java` | 成员管理解绑入口：`DELETE /api/ai/user/{uid}/oidc-binding`（或在 `PUT /{uid}` payload 加解绑标志，二选一） |
| `service/AuthService.java` | `userInfo()` 按需带出 `oidcProvider` / `oidcSub`（供成员管理展示，当前未带出） |

### 6.2 前端

| 文件 | 改动 |
|---|---|
| `api.js` | `getOidcConfig` 消费 providers 数组；`getOidcLoginUrl(providerId, redirectPath)`；新增 provider CRUD 五个封装 |
| `LoginPage.vue` | L114 `oidc` ref → `oidcProviders` 数组；L81–84 单按钮 → `v-for` 多按钮（`sort_order` 序，按钮文案 `使用 {name} 登录`）；`submitOidc(providerId)`。单 provider 时 UI 与现状逐字一致 |
| `views/OidcProvidersPage.vue`（新） | 管理页：表格（名称/状态/Issuer/ClientID 后4位/排序/启用停用）+ 新建编辑抽屉（20 个字段分区照搬现 schema 三节：「接入（客户端凭据）」「端点与授权参数」「身份映射与建号」）+ 删除守门提示 + 可选测试连接。形态照 `ProvidersPage.vue` |
| `router.js` | 新增 `/oidc-providers`（`AppLayout` 子路由，`meta: { requiresAdmin: true }`，照 `/members` L46） |
| `startup/RbacSeedRunner.java`（后端种子） | `BUILTIN_MENUS` 加 `menu-oidc-providers`（归属设置分组，sort 贴近 `menu-settings` L72） |
| `SettingsPage.vue` | 无需改动——oidc panel 从 schema 删除后导航项自动消失（L689 的 `oidc: '单点登录'` 短名映射随之成为死代码，可一并清） |
| `MembersPage.vue` | 成员列表/详情展示「认证源」列（provider 名称 + sub）；提供解绑按钮（调 §6.1 的解绑端点） |
| `OidcCallbackPage.vue` | **不改**（一次性 code 兑换与 provider 无关） |

### 6.3 文档（项目口径：只描述改造后现状）

- `manual/11-系统配置说明.md` L22：OIDC 一行改为「多认证源管理（设置 → 单点登录页 CRUD）」描述；
- `manual/02-快速上手.md`、`manual/12-团队与权限.md`、`manual/14-API速查.md`：涉及 OIDC 的段落同步为改造后形态；
- 迁移过程、事故不进手册（既定口径）。

---

## 7. 实施批次（建议按序，每批可独立验证）

1. **数据层**：schema.sql（新表 + User 加列 + 复合唯一）+ `OidcMigrationRunner`（default 行 / 回填 / 换索引）。验证：老库启动后 `c_ai_oidc_provider` 出现 default 行、存量绑定用户 `oidc_provider='default'`。
2. **后端**：`OidcProviderService` + `OidcProviderController` + `OidcService` 重构 + `SecurityConfig` 豁免收窄 + `ApiEndpointScanner` 收录。验证：老登录链路逐字不变（default provider）；`/config` 回数组；管理端点未登录 401。
3. **前端**：api.js + 登录页多按钮 + `OidcProvidersPage` + 路由/菜单 + schema 清理 + 成员管理绑定展示/解绑。验证：两个按钮、两个 IdP 分别回调登录成功、成员管理见绑定、解绑后可重新绑。
4. **收尾**：手册同步 + 端到端清单（§8）。

## 8. 端到端验收清单

- [ ] 存量单 provider 部署升级后：登录页按钮、已绑定用户登录、自动建号行为与改造前一致
- [ ] 新建第二个认证源后：登录页出现两个按钮（顺序 = sort_order）；各自走完授权 → 回调 → 建号/绑定
- [ ] 两个 IdP 签发相同 `sub` 值时不再撞唯一索引（复合唯一生效）
- [ ] 同一本地账号换绑到另一个认证源：旧绑定被覆盖、旧 IdP 再登录时按未绑定处理（或按 `useRawUsername` 规则重建绑定）
- [ ] 管理端点（`/api/ai/oidc/**`）未登录 401、非管理员 403；四个公开端点免登录可达
- [ ] clientSecret 列表页只显后 4 位；编辑时未修改提交掩码不覆盖；DB 中为 `RSA:` 密文
- [ ] 删除有绑定的认证源被拒绝并提示先解绑；解绑后删除成功
- [ ] 认证源停用后：登录页按钮消失；进行中的回调得到「认证源已停用」明确错误
- [ ] 改任一 provider 配置（如换 Issuer）：下次登录即用新值（元数据缓存键含配置值）
- [ ] 代码回滚（保留迁移后的表与行）：老 `oidc.*` 配置行未被删，老实现直接恢复工作

## 9. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 旧唯一索引未换，跨 provider 相同 sub 撞索引 | 迁移 runner 先回填再换索引；失败仅告警，`resolveUser` 的 `DuplicateKeyException` 分支给明确错误 |
| 迁移 runner 部分失败 | 三步各自幂等，重启重跑；旧 `oidc.*` 行不删，最坏情况手工补一行 default |
| 设置页 oidc 面板删除后用户找不到入口 | 新页面加菜单种子（`menu-oidc-providers`），手册同步 |
| `state` 带 providerId 后的旧 state 兼容 | state 是一次性内存态，重启即失效，无跨版本兼容问题 |

## 10. 待拍板决策点

1. **绑定模型**：推荐 §3.2 方案 A1（`c_ai_user` 加 `oidc_provider` 列，一账号同时绑一个认证源）。若要求「一个账号同时绑多个 IdP」，改独立绑定表（§3.2 末段）。
2. **回调寻址**：推荐 state 带 provider、回调 URL 不变（§5.4）。备选 `/callback/{providerId}` 路径参数（id 露在 URL，部分 IdP 回调白名单校验更严时不适用）。
3. **管理入口**：推荐独立页 `/oidc-providers` + 设置分组菜单。备选：内嵌设置页自定义面板（要改 `SettingsPage` 纯 schema 驱动逻辑，不推荐）。
4. **旧 `oidc.*` 配置行**：推荐保留不删（回滚零成本，成为无读取方的孤儿数据）。备选：迁移成功后由 schema.sql 幂等 DELETE 清理。
