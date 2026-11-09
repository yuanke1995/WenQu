# 问渠企业级升级与建设方案（先 B 后 A · 问题驱动 · 不兼容改造）

> 结论先行：问渠已具备企业级**基础能力**（RBAC fail-closed、OIDC 多认证源、密钥 RSA 加密、

> 资源按创建者+共享范围隔离、审计/用量/追踪、MCP 双向、会话历史恒存 Redis）。但当前架构在
> **高可用、身份抽象、租户隔离、授权粒度、密钥管理、合规**六个方向存在**结构性缺陷**，
> 达不到企业级标准。
>
> 本方案原则（用户拍板）：**以"现有体系问题清单"为驱动，凡是阻碍企业级的能力一律结构性改造，
> 不做兼容层、不保留遗留特例、不为"不破坏老逻辑"而让步。** 路线=先 B 后 A；首期=身份治理深化。
> 本文件为唯一口径；决策点（§7）已按推荐默认锁定，可按 §3.10 批次实施（仍先完成方案完善，待开工令再改代码）。

---

## 0. 现有体系问题清单（必须改，不兼容）

证据均来自当前代码/配置，定位到文件:行。

| # | 问题 | 证据 | 企业级要求 | 改造性质 |
|---|---|---|---|---|
| **P1** | **运行总线 `ChatRunBus` 纯内存** | `ChatRunBus.java:150/155` `runs`/`channels` 为 `ConcurrentHashMap`，`Run` 持有 `SseEmitter`；`:46` 注释"只在内存" | 断线重接连流、事件发布必须跨副本可用 → 迁入 Redis pub/sub | 结构性 |
| **P2** | **`FrameworkSessions` 全内存** | `:58` `held` 常驻 `AgentSession`、`:105` `followers`、`pendingOwner`、`:461` `reserveFollower` 用内存 `putIfAbsent` | 会话实例/跟随者占位/待办归属须跨副本一致、重启可恢复 → 外置 Redis（协调态）+ 实例可重建 | 结构性 |
| **P3** | **`AI_JWT_SECRET` 留空随机生成** | `AppProperties.java:175-176` "留空则开发期自动生成随机值，重启后原令牌失效" | 多副本共享固定签名密钥；prod 缺失即启动失败（或统一从 KMS 拉取） | 结构性 |
| **P4** | **RSA 密钥是本地文件** | `application-local.yml:12` `data/secret/config-rsa.key`；密钥不入库、不打构建产物 | 多副本共享同一私钥（或 KMS 托管），支持轮换且不中断解密 | 结构性 |
| **P5** | **单角色 `c_ai_user.role` VARCHAR** | `schema.sql` `c_ai_user.role VARCHAR(20)` 引用 `c_ai_role.code`，注释"单角色" | 多角色（一人兼具多职责）→ `c_ai_user_role(uid, role_code)` 多对多 | 结构性 |
| **P6** | **OIDC 绑定为专用两列，非统一身份抽象** | `schema.sql` `c_ai_user.oidc_sub` + `oidc_provider` 复合唯一 | 统一身份模型：`c_ai_identity_provider(type)` + `c_ai_user_identity`；**废弃专用列，迁移脚本一次性迁入** | 结构性 |
| **P7** | **`SecurityConfig` 前缀级 `permitAll`** | `SecurityConfig.java:212` `path.startsWith("/api/ai/auth/")` 整段放行 | 取消前缀级放行 → 精确端点白名单 + SCIM/SAML 独立令牌校验 | 结构性（安全红线） |
| **P8** | **限频 Redis 不可用 fail-open** | 手册§审计与防护 "Redis 不可用时自动放行，不阻塞业务" | fail-closed 或降级本地令牌桶，防止 Redis 抖动时限流全失效（DoS 面） | 结构性 |
| **P9** | **无租户维度** | 全库无 `tenant` 概念（grep 无 `tenant/namespace` 业务表） | SaaS 多客户强隔离：`tenant_id` 一等公民 | 结构性 |
| **P10** | **配置扁平 KV + JSON schema** | `c_ai_config` key-value + `config-schema.json`；覆盖层→DB→defaults | 分层（env/tenant/user）+ 强类型 + 配置中心；密钥不混 KV | 较大重构 |
| **P11** | **审计分散、无限留/导出** | `[AUDIT]` 日志 + `c_ai_mcp_call_log`/`c_ai_usage_log`/`c_ai_qa_log` 散表 | 统一审计事件总线 + 留存策略 + 合规导出/不可篡改 | 结构性 |
| **P12** | **生成内容无安全审查** | 无内容安全网关层 | 输入合规检查 + 输出内容安全过滤（生成式AI 合规刚需） | 新增 |
| P13 | 无行级 ABAC / 部门继承共享 | 共享范围仅 global/dept/user 三级；部门树无继承共享 | 策略引擎 + 子部门继承（可暂缓至 A 期） | 增强 |

> 说明：P1–P4 是**当前无法真正水平扩展**的根因——"会话历史在 Redis"常被误读为"可水平扩展"，
> 但**运行态（在跑的轮、流式 bus、框架会话实例）仍在单进程内存**，app 实质是有状态、不可横向扩。

---

## 1. 企业级架构原则（取代"沿用既有"）

1. **运行态外置**：`ChatRunBus` 事件与 `FrameworkSessions` 的协调态（follow/待办归属/实例租约）迁入
   Redis（pub/sub + 分布式锁/租约）。app 进程成为**真正无状态**，可任意横向扩缩。
2. **tenant 一等公民**：自 Phase 0 起定义 `tenant_id` 规范；Phase 4 对**全表**（含存量表）retrofit 加列，
   **不靠 `NULL` 占位偷懒**，启动迁移脚本一次性补齐。
3. **统一身份模型**：`c_ai_identity_provider(type: oidc/saml/ldap/scim)` + `c_ai_user_identity(uid, provider_id, external_id)`；
   **删除 `oidc_sub` / `oidc_provider` 两列**，迁移脚本将存量绑定迁入统一表（无兼容双写）。
4. **多角色**：`c_ai_user_role(uid, role_code)` 取代单 `role` 列；RBAC 判定改为**角色并集**。
   管理员判定 = 任一角色 `admin_flag=1` 或角色 `superadmin`。
5. **授权精确化**：取消 `/api/ai/auth/` 前缀 blanket；改为精确公开端点白名单（OIDC/SAML 回调、登录页配置）
   + SCIM 端点独立 Bearer 校验（置于前缀放行之前）。
6. **密钥集中**：`AI_JWT_SECRET` 与 RSA 私钥由 Secret Manager / KMS 统一托管，支持轮换、多副本一致。
7. **限频 fail-closed**：Redis 不可用时降级本地令牌桶并**拒绝超额**（或按策略可配），不再无脑放行。
8. **统一审计 + 内容安全**：审计事件总线 + 留存/导出；问答链路加装内容安全网关。

---

## 2. 总体分期（先 B 后 A，问题驱动）

```
B 期（单租户企业底座）
  Phase 0  运行态外置 + HA + 边缘安全加固   P1 P2 P3 P4 P8（★ 横向扩展前提，建议最先；P8 限频 fail-closed 随地基走）
  Phase 1  身份治理深化（统一模型，废弃旧列）  P5 P6 P7   ★首期
  Phase 2  计量计费与配额
  Phase 3  合规与内容安全        P11 P12
  Phase 3b 配置分层             P10
A 期（多租户隔离层，最后加）
  Phase 4  tenant_id 全表 retrofit + TenantLine 拦截 + 计量按租户结算 + 运营交付(Helm/k8s/灰度/DR)
  （P13 行级 ABAC / 部门继承共享：增强项，随 Phase 4 多租户一并评估，不单列）
```

> 顺序逻辑：P1–P4（运行态外置）是其余一切的前提——身份/计量的多副本一致性都建立在"app 无状态"上。
> 但首期按用户拍板仍为**身份治理**（P5/P6/P7），可与 Phase 0 并行。

---

## 3. 首期详细方案：身份治理深化（统一模型，废弃旧列）

### 3.1 统一身份模型（核心改动，不兼容）

**删除** `c_ai_user.oidc_sub` 与 `c_ai_user.oidc_provider` 两列（迁移脚本将其数据迁入下表的 `oidc` 类型行）。

```sql
CREATE TABLE IF NOT EXISTS `c_ai_identity_provider` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键 (UUID)',
    `tenant_id`     VARCHAR(50)  DEFAULT NULL COMMENT '租户ID（Phase4 启用，当前 NULL）',
    `type`          VARCHAR(20)  NOT NULL COMMENT 'oidc | saml | ldap | scim',
    `name`          VARCHAR(100) NOT NULL COMMENT '认证源名称（登录按钮/同步源名）',
    `enabled`       INT          DEFAULT 1,
    `config`        TEXT         NOT NULL COMMENT '协议参数 JSON（按 type 不同；敏感字段整块 RSA 加密，RSA: 前缀）',
    `default_role`  VARCHAR(20)  DEFAULT 'user' COMMENT '须是 c_ai_role.code 已启用值',
    `default_department` VARCHAR(50) DEFAULT NULL,
    `auto_create_user`  INT      DEFAULT 1,
    `sort_order`    INT          DEFAULT 0,
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一身份认证源（OIDC/SAML/LDAP/SCIM）';

CREATE TABLE IF NOT EXISTS `c_ai_user_identity` (
    `id`            VARCHAR(50)  NOT NULL,
    `tenant_id`     VARCHAR(50)  DEFAULT NULL,
    `uid`           VARCHAR(64)  NOT NULL COMMENT '本地账号 (c_ai_user.uid)',
    `provider_id`   VARCHAR(50)  NOT NULL COMMENT 'c_ai_identity_provider.id',
    `external_id`   VARCHAR(255) NOT NULL COMMENT 'IdP 侧唯一标识（OIDC sub / SAML NameID / SCIM id / LDAP dn 摘要）',
    `external_attrs` TEXT         DEFAULT NULL COMMENT '最近同步的映射属性快照 JSON',
    `last_synced`   DATETIME     DEFAULT NULL,
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ident` (`provider_id`, `external_id`),
    KEY `idx_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-外部身份关联（取代 oidc_sub/oidc_provider）';
```

- `config` 用 JSON 收纳各协议参数（OIDC 的 20 字段、SAML 的元数据、LDAP 的连接、SCIM 的 token），
  敏感字段（client_secret / x509 / bind_password / scim token）整块 RSA 加密，沿用 `ConfigCryptoService` 口径。
- **迁移脚本**（`SchemaMigrator` 之后跑）：将 `c_ai_user` 中 `oidc_sub` 非空的行，按 `oidc_provider`
  找到对应 provider（旧 `c_ai_oidc_provider` 数据迁入 `c_ai_identity_provider` type=oidc），写入 `c_ai_user_identity`；
  随后 `DROP COLUMN oidc_sub, oidc_provider`。旧 `c_ai_oidc_provider` 表保留为历史归档或一并下线（不双写）。
- `OidcService` 重构：绑定/解析改读写 `c_ai_user_identity`，不再碰 `c_ai_user` 旧列。

### 3.2 多角色（P5）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_user_role` (
    `uid`        VARCHAR(64) NOT NULL,
    `role_code`  VARCHAR(20) NOT NULL COMMENT 'c_ai_role.code',
    `granted_by` VARCHAR(64) DEFAULT NULL COMMENT '授权来源: idp | manual',
    `create_time` DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`uid`, `role_code`),
    KEY `idx_role` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色多对多（取代 c_ai_user.role 单值）';
```

- **删除** `c_ai_user.role` 列（迁移：存量 `role` 值写入 `c_ai_user_role`，`granted_by='manual'`）。
- 判定改造：`RoleService/AuthService` 与权限拦截器改为"角色并集"；管理员 = 任一 `admin_flag=1` 或 `superadmin`。
  影响面：`UserController` 成员管理、`RbacSeedRunner` 内置角色、`ApiEndpointScanner` 接口归属。
- 单角色查询（如 "isAdmin"）改为 `userRoleService.hasAnyRole(uid, Set<code>)`。

### 3.3 SAML 2.0 认证源（type=saml）

- `config` 收纳：`idpEntityId/idpSsoUrl/idpX509(RSA)/spEntityId/acsUrl/attribute*`（平移旧 OIDC 20 字段形态）。
- `SamlService` 用 Spring Security SAML2 Service Provider，按 provider 配 Relying Party。
- 回调 `/api/ai/auth/saml/{id}/acs`：**从 L212 前缀放行中剔除**，仅 ACS 这类真公开端点精确放行。

### 3.4 SCIM 2.0 入站（type=scim，问渠为 Resource Server）

- `config` 收纳：`authType/ token(RSA)/ deprovisionAction`。
- 端点 `/api/ai/scim/v2/Users|Groups`（CRUD + ServiceProviderConfig/Schemas 发现）。
- **挂 `/api/ai/scim/**`，不在 `/api/ai/auth/` 前缀下**；`SecurityConfig` 在其前单独校验 `Bearer <scim.token>`，
  无效 401；管理端点走管理员 fail-closed。

### 3.5 LDAP / AD 目录拉取（type=ldap）

- `config` 收纳：`host/port/useSsl/baseDn/bindDn/bindPassword(RSA)/userFilter/groupFilter/syncCron`。
- `DirectorySyncService` `@Scheduled` 拉取 → 与 `c_ai_user_identity(type=ldap)` diff → 创建/更新/禁用。

### 3.6 组 → 角色 / 部门 自动映射（治理核心）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_group_role_mapping` (
    `id`             VARCHAR(50) NOT NULL,
    `tenant_id`      VARCHAR(50) DEFAULT NULL,
    `provider_id`    VARCHAR(50) NOT NULL,
    `external_group` VARCHAR(255) NOT NULL,
    `role_code`      VARCHAR(20) DEFAULT NULL,
    `department_id`  VARCHAR(50) DEFAULT NULL,
    `priority`       INT DEFAULT 0 COMMENT '多组命中裁决用，大优先',
    `create_time`    DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_grp` (`provider_id`, `external_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组→角色/部门 自动映射';
```

- `IdentityMappingService.apply(providerId, groups)`：取 `priority` 最大映射 → 写 `c_ai_user_role` + `c_ai_user.department_id`。
- 调用点统一收敛到 OIDC/SAML 回调、SCIM User 落地、LDAP 同步四处，一处实现四路复用。
- **D1（§7）**：映射语义——推荐 IdP 权威（企业期望 IdP 是角色真相源），但 `granted_by='manual'` 的管理员手动角色不被静默覆盖。

### 3.7 销户与停用治理

- SCIM `DELETE` / LDAP 同步后不存在 → 按 `deprovisionAction`：`disable`（置 `status=0`，可恢复）或 `delete`（逻辑删 `deleted=1`）。
- 全量写入 `c_ai_sync_job_log`（新增：source_type/source_id/users_created/updated/disabled/status）。

### 3.8 安全改造（P7）

- `SecurityConfig`：删除 L212 `path.startsWith("/api/ai/auth/")` 整段放行；改为精确公开端点集合
  （OIDC/SAML 回调、`/config`、`/login-url`、`/exchange-code`）+ SCIM 独立 Bearer 校验（置于此前）。
- `ApiEndpointScanner.MODULE_ZH` 收录 SAML/SCIM/LDAP 控制器，fail-closed 可授权。

### 3.9 改造清单（文件级，首期）

**后端**：`schema.sql`（删 `oidc_sub`/`oidc_provider`/`c_ai_user.role`；新增 5 表）；
`model/` 新实体（IdentityProvider/UserIdentity/UserRole/GroupRoleMapping/SyncJobLog）；
`mapper/*`；`service/IdentityProviderService`、`SamlService`、`ScimService`、`DirectorySyncService`、
`IdentityMappingService`（新）+ `OidcService`（重构绑定读写）+ `RoleService`/`AuthService`（多角色并集）+
`UserController`（成员管理解绑/多角色展示）；`controller/SamlController`、`ScimController`；
`config/SecurityConfig`（P7 精确化）；`startup/ApiEndpointScanner`+`RbacSeedRunner`（菜单种子）；
迁移 Runner（OIDCAuthSourceMigration + UserRoleMigration + DropLegacyColumns）。

**前端**：`api.js`（统一 provider CRUD）；`LoginPage.vue`（按 provider 类型渲染按钮）；
`SamlProvidersPage`/`ScimEndpointsPage`/`LdapSourcesPage`/`GroupMappingsPage`（新，照 `OidcProvidersPage`）；
`router.js` + `RbacSeedRunner` 菜单；`MembersPage.vue`（多角色展示 + 身份来源 + 解绑）；
`config-schema.json`（OIDC 旧 panel 删除，认证源走独立页）。

### 3.10 实施批次

1. **数据层+迁移**：新表 + 删旧列 + 三个迁移 Runner（OIDC→统一、单角色→user_role、删旧列）。验证：存量登录/绑定无感，旧列消失。
2. **统一身份 + 多角色**：`IdentityProviderService` + `OidcService` 重构 + `RoleService/AuthService` 并集判定。验证：OIDC 登录、成员管理多角色。
3. **SAML**：`SamlService/Controller` + SecurityConfig 收窄。验证：Keycloak/ADFS 对接。
4. **SCIM**：`ScimController` + 独立 Bearer + 同步日志。验证：Entra/Okta 推送建号/禁用。
5. **LDAP + 组映射 + 销户 + 前端**：目录同步、映射、回收、管理页。验证：跨源解绑、离职回收。
6. **收尾**：手册同步（§5 口径）+ 端到端清单。

### 3.11 端到端验收

- [ ] 存量 OIDC 用户升级后登录/绑定无感；`oidc_sub`/`oidc_provider`/`c_ai_user.role` 三列已从库删除
- [ ] 多角色：用户兼具多角色，权限取并集；管理员判定含 `admin_flag`
- [ ] SAML 对接真实 IdP 登录成功；SCIM 推送自动建号、`DELETE` 按策略禁用
- [ ] SCIM 端点无 Bearer → 401；管理端点未登录 401、非管理员 403
- [ ] LDAP 定时同步建号/禁用正确；组映射生效；同步日志可查
- [ ] `SecurityConfig` 无 `/api/ai/auth/` 前缀级放行（grep 验证）
- [ ] 成员管理可见身份来源/外部标识/多角色，可解绑各源

### 3.12 风险与回滚

| 风险 | 缓解 |
|---|---|
| 删旧列导致回滚困难 | 迁移 Runner 幂等；旧 `c_ai_oidc_provider` 先归档不删；回滚需先恢复列（一次性脚本） |
| 多角色判定改造面大、易漏 | `hasAnyRole` 单一入口；接口扫描覆盖；回归内置角色判定 |
| SCIM 端点误挂前缀变公开 | SecurityConfig 独立校验置于前缀放行前；PR grep 校验 |
| 大目录 LDAP 拉取拖垮 | 分页 + 增量 diff；失败仅记日志 |

---

## 4. 后续期要点

### 4.1 Phase 0 运行态外置 + HA（P1–P4，横向扩展前提）
- `ChatRunBus` → Redis pub/sub：事件发布进 Redis 频道，各副本订阅扇出到本地 `SseEmitter`；接流快照仍取本地运行时缓冲。
- `FrameworkSessions` → 协调态（`followers`/`pendingOwner`/实例租约）迁入 Redis（分布式锁 + TTL 租约）；
  常驻 `AgentSession` 实例仍绑定持有副本，但跟随者互斥、待办归属跨副本一致；实例丢失可由Redis 协调重建。
- `AI_JWT_SECRET`：prod 缺失即启动失败；统一从 Secret Manager 注入。
- RSA 密钥：KMS 托管，支持版本化轮换（双密钥过渡期）。
- **限频 fail-closed（P8）**：Redis 不可用时降级本地令牌桶并拒绝超额，不再无脑放行（对应 §1 原则#7）。

### 4.2 Phase 2 计量计费与配额
`c_ai_usage_log` 升级为配额上限 / 成本归因（模型+人+部门）/ 预算封顶 / 超额熔断；网关层强制。

### 4.3 Phase 3 合规与内容安全（P11/P12）
统一审计事件总线 + 留存策略 + 合规导出；问答链路加装内容安全网关（输入合规 + 输出过滤）。

### 4.4 Phase 3b 配置分层（P10）
env/tenant/user 三层 + 强类型配置元模型；密钥移出 KV，交由 KMS。

### 4.5 Phase 4 多租户隔离（A 期，最后加，P9）
全表 retrofit `tenant_id`（含存量，启动迁移一次性补齐）；MyBatis-Plus `TenantLineInnerInterceptor`；
Redis 键按 `tenant:` 前缀、沙盒按租户命名空间；计量按租户结算；运营交付 Helm/k8s、灰度、租户自助开通、DR。

---

## 5. 文档口径（项目既定）

- 手册只描述改造后**现状**：统一身份如何配置、多角色如何生效、组映射如何设、离职回收策略。
- 迁移过程、事故、退役史不进手册（既定口径）。手册改动随各期收尾同步。

---

## 6. 分期交付节奏

- 首期（身份治理）独立成线，约 3–5 周（标准协议，库成熟）；
- Phase 0（运行态外置）是横向扩展硬前提，**强烈建议与首期并行启动**（不互相阻塞）；
- Phase 2/3/3b 各自独立排期；Phase 4 放最后（结构性改造，宜底座稳定后一次性加层）。

---

## 7. 已决策点（按推荐默认锁定 · 2026-10-26）

1. **D1 组映射语义**：✅ IdP 权威；手动 `granted_by='manual'` 角色不被静默覆盖。
2. **D2 SAML 库**：✅ Spring Security SAML2。
3. **D3 SCIM 范围**：✅ 仅 Users + Groups。
4. **D4 销户默认**：✅ `disable`（可恢复），逻辑删 `deleted=1` 留痕。
5. **D5 目录源优先级**：✅ SCIM 与 LDAP 齐发（SCIM 先落地，LDAP 跟进）。
6. **D6 多角色迁移**：✅ 删除 `c_ai_user.role` 列，彻底多角色化（角色并集判定）。
7. **D7 Phase 0 与首期并行**：✅ 并行；首刀落身份治理（本地可验证），HA 待多副本环境。
8. **D8 前端归属**：✅ SAML/SCIM/LDAP/映射管理页统一挂「权限管理」下。

> 决策已锁定，可按 §3.10 批次实施。本文件为唯一口径，改动以文中表/文件清单为准，不做兼容层。

## 8. 执行就绪：首刀切口（依据 D7）

- **首刀 = Phase 1 身份治理（P5/P6/P7）**：本地单实例可全程验证，按 §3.10 批次 1→2→3→4→5→6 推进。
- **Phase 0（HA / 运行态外置）并行线**：依赖多副本环境，本地 IDE 单实例难验证；待测试集群就绪后按 §4.1 落地，不与首刀互锁。
- **开工起手 = §3.10 批次 1（数据层 + 迁移）**：新增 5 表 + 删除旧列（`oidc_sub` / `oidc_provider` / `c_ai_user.role`）+ 三个迁移 Runner；验收＝存量登录/绑定无感、旧列从库消失。
- 全部改造不兼容、无双写、无兼容层；回滚与风险见 §3.12。
