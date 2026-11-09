# 问渠 P1 必补能力方案（五项）

- 日期：2026-11-08
- 状态：方案待拍板；其中「当前租户指示」「邀请流基础版」「邮件通道」不依赖拍板可直接开工
- 范围：上一轮功能盘点给出的 P1 五项。MCP 面板升级已有独立方案（`plan-mcp-panel-upgrade.md`），本文只补效果图缺口与收口口径，不重复设计。
- 产出物：本文 + 三份效果图（`design/mockups/tenancy-lifecycle-ux/`、`design/mockups/notification-outbound-ux/`、`design/mockups/mcp-panel-upgrade/` 补两场景）

## 0 总览

| # | 项 | 类型 | 是否涉及 UI | 前置依赖 | 建议批次 |
|---|---|---|---|---|---|
| 1 | 多租户生命周期 | 功能 + UI | 是 | 无（3C 清除脚本除外） | P1-a |
| 2 | 通知外发通道（邮件） | 功能 + UI | 是 | SMTP 可用 | P1-a |
| 3 | OpenAI 兼容端点补齐 | 后端契约 | 否（仅 API 文档页补说明） | 引擎 token 通道 | P1-b |
| 4 | MCP 面板升级 | UI 为主 | 是（方案已定，效果图缺 2 景） | 无 | P1-a |
| 5 | 工作流游客放行 | 策略 + 后端 | 少量（分享页提示） | 分享安全 P3/P4 拍板 | P1-c |

建议顺序：**4（效果图已八成）→ 1（当前租户指示 + 邀请流）→ 2（邮件）→ 3（OpenAI 补齐）→ 5（等拍板）**。

---

## 1 多租户生命周期

### 1.1 现状

- 底座已真：62 表行级过滤、租户键控 RBAC、配额三档、SSO 建号落认证源租户、按租户备份清单（`plan-tenancy-real.md` §0）。
- 生命周期只有「开通 + 停用」两端：无邀请流、无清退、无租户感知登录/品牌、无首登引导。
- 全站无「当前租户」指示：`/auth/me` 已下发 `tenant/tenantName`（`ChatController.java:150-153`），前端 `auth.js` 不缓存不消费。

### 1.2 3E 当前租户指示（P0，独立可做）

**规格**

- `auth.js` 登录/`/auth/me` 时缓存 `tenant`、`tenantName`，提供 `tenantState()` 只读口径（单一真相源，禁止各页自取）。
- 顶栏用户区（头像左侧）展示租户名 pill：`--app-panel-2` 底、`--app-border` 描边、12px `--app-text2`；仅当 `tenant !== 'default'` 或平台 superadmin 时显示，单租户部署零变化。
- 平台 superadmin 额外在 pill 上给「平台」标识（不可点），避免误以为自己身处某租户。
- TenantsPage「落点清单」按钮按 superadmin 身份显隐；MembersPage 租户列/归属下拉判据从「数据量 multiTenant」换成 superadmin 身份（修 `plan-tenancy-real.md` §2.1 的必 403 控件）。
- 审计页接 `tenant=` 跨租户下拉，仅 superadmin 渲染。

**验收**：租户管理员真机走全部管理页，零「看得见、点了炸」；平台管理员视角只多一个 pill；单租户部署零变化。

### 1.3 3A 邀请流

**数据模型**（新表，登记 GLOBAL_TABLES 时按平台全局还是租户表定：本表含 `tenant_id`，走行级过滤，**不进** GLOBAL_TABLES）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_tenant_invite` (
    `tenant_id`   VARCHAR(32)  NOT NULL DEFAULT 'default',
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键',
    `token_hash`  CHAR(64)     NOT NULL COMMENT '邀请 token 的 SHA-256（明文只在生成时返回一次）',
    `token_prefix` VARCHAR(8)  NOT NULL COMMENT '前 8 位，列表辨识用',
    `role_ids`    VARCHAR(500) DEFAULT NULL COMMENT '预选角色 id，逗号分隔；空=租户默认角色',
    `dept_id`     VARCHAR(50)  DEFAULT NULL COMMENT '预选部门',
    `email`       VARCHAR(160) DEFAULT NULL COMMENT '限定邮箱（可空=不限定）',
    `max_uses`    INT          NOT NULL DEFAULT 1 COMMENT '可用次数（默认 1）',
    `used_count`  INT          NOT NULL DEFAULT 0,
    `expires_at`  DATETIME     NOT NULL COMMENT '到期时刻（默认 7 天，最长 30 天）',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/revoked/exhausted/expired',
    `created_by`  VARCHAR(64)  NOT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invite_token` (`token_hash`),
    KEY `idx_invite_tenant_status` (`tenant_id`, `status`, `expires_at`)
) COMMENT='租户邀请（一次性短链，注册落本租户）';
```

**后端**

| 端点 | 权限 | 说明 |
|---|---|---|
| `POST /api/ai/tenant/invites` | 租户管理员 | body: roleIds/deptId/email/maxUses/expireDays；返回 `{id, url, expiresAt}`，`url` 明文 token 仅此一次 |
| `GET /api/ai/tenant/invites` | 租户管理员 | 列表：创建人、角色、已用/上限、到期、状态；**不返回 token** |
| `POST /api/ai/tenant/invites/{id}/revoke` | 租户管理员 | 撤销 |
| `GET /api/ai/join/{token}` | 匿名 | 校验并返回 `{tenantName, inviterName, roles, expiresAt, emailBound}` |
| `POST /api/ai/join/{token}` | 匿名 | body: uid/password/name；落本租户、绑预选角色/部门；用完即 `used_count+1`（并发用 DB 条件更新 CAS） |

**规则**

- 注册只允许 `active` 且未到期；`email` 绑定时不校验邮箱（无邮件通道时的降级），改为「邀请链接本身就是凭据」，并在页面提示。
- 账号已存在 → 明确报错「该账号已被占用」，不自动并入租户（防越权把老账号划进新租户）。
- 注册成功直接签发 JWT 并登录，落 `tenant_id=邀请租户`。
- 邀请不产生站内通知；`maxUses` 用尽自动置 `exhausted`。
- 关闭邀请功能（`tenant.inviteEnabled` 默认开）后仅隐藏入口，已发链接仍按状态校验，避免「关开关导致在途链接全废」。

**前端**

- MembersPage 工具栏「新建用户」左侧加「邀请成员」ghost 钮（租户管理员可见）；弹窗 520px：角色多选 / 部门 / 有效期（7/15/30 天）/ 使用次数（1/5/10/不限）/ 限定邮箱（可选）。
- 生成后结果区：链接只读框 + 「复制链接」主色钮 + 灰字「链接只显示这一次，复制后发给对方；对方打开即填账号注册」。
- 弹窗内下方「待接受邀请」紧凑表（角色 / 已用 / 到期 / 状态 / 撤销），无邀请时空态一行。
- 新增匿名页 `/join/:token`：左侧租户品牌区（复用登录页骨架）、右侧表单（账号 / 密码 / 姓名）；过期/撤销给独立结果页，不出现「重新邀请」按钮。

### 1.4 3B 登录租户感知与品牌

**数据**

`c_ai_tenant` 增列（`SchemaMigrator` 自动补）：

| 列 | 类型 | 说明 |
|---|---|---|
| `brand_name` | VARCHAR(100) | 登录页展示名，空=用 `name` |
| `brand_logo` | VARCHAR(200) | logo 相对路径（`user-data/branding/{tenant}/logo.png`），空=问渠默认标 |
| `brand_color` | VARCHAR(20) | 主色（仅登录页按钮与细线，不改全站主题） |
| `login_hint` | VARCHAR(200) | 登录页副标题，空=默认欢迎语 |

**解析顺序（单一入口，禁止各页自解析）**

1. 子域：`<tenant>.example.com` → `tenant`（需部署方配置泛解析，方案不假设已配）；
2. 查询参数 `?tenant=`（便于内网单域名部署）；
3. 都没有 → `default`。

后端 `GET /api/ai/auth/brand?tenant=`（匿名、限频）返回品牌；不存在/停用租户回落 default 并显式提示「该租户不可用」，不泄露存在性以外的信息。

**前端**

- LoginPage 品牌面板：`brandName` 替「问渠」、`brand_logo` 替默认标、`login_hint` 替欢迎语；按钮主色仅登录页生效（CSS 变量作用域到 `.login-page`）。
- 子域命中时隐藏「租户」概念，用户无需输入；`?tenant=` 命中时在表单上方给一行灰字「正在登录：{brandName}」。
- 默认租户零变化（品牌为空即现状）。

**边界**：不做租户自定义域名绑定 UI（运维在 nginx 配）；不做主题全站化；logo 限 PNG/JPG ≤ 512KB、单边 ≤ 1024。

### 1.5 3C 清退流程

**刻意不做「删除租户」按钮**（行级隔离下删租户等于按归属删光数据）。改为三态：

- `status=1` 启用 / `0` 停用（现状）；
- 新增 `2` **归档**：成员不可登录、数据只读、平台管理员可导出落点清单，可恢复到 0/1；
- 彻底清除仍走运维脚本 `scripts/tenant-purge.sh --tenant=<id> --dry-run`（先打印行数，再要求二次确认），脚本只删 `tenant_id=?` 的业务行并保留审计行；保留期 `tenant.purgeRetentionDays`（默认 30 天）只作用于脚本，不自动执行。

**前端**：TenantsPage 状态列增「归档」；操作列仅 superadmin 显示「归档 / 恢复」；归档租户行整行 60% 透明并加灰字「只读，可导出后可清除」。

### 1.6 3D 新租户首登引导

- 新租户管理员首次登录（`tenant.onboardingDone=false`）时，工作台顶部展示一条 checklist 卡（非弹窗）：
  1. 修改初始密码；2. 新建部门；3. 邀请第一位成员；4. 绑定聊天模型；5. 建第一个知识库。
- 每项一行：勾选态用 `--app-ok` 圆点，未完成用空心圆；右侧「去完成」小字链直达对应页。
- 全部完成或用户点「不再提示」后写 `onboardingDone=true`（存 `user_config`，按租户管理员本人），单租户部署不出现。
- 不做多步向导、不做强制拦截。

### 1.7 验收与回归

- 邀请：生成→匿名打开→注册→落正确租户/角色/部门；重名账号拒绝；用完/撤销/过期三态拒绝；并发两请求只成功一次。
- 品牌：子域、`?tenant=`、default 三路径；空品牌回落零变化；停用租户提示。
- 归档：成员登录被挡、数据只读可见、恢复后正常；脚本 dry-run 行数与实际一致。
- 回归：现有「新建用户/重置密码/租户管理弹窗/开通申请」语义零变化；RBAC 判定核 19 测全绿。

---

## 2 通知外发通道（邮件）

### 2.1 现状

- 站内通知（`c_ai_notification`，26 类事件）+ WebPush（`WebPushNotifier`）已完整；**无邮件/短信通道**，离线用户收不到。
- `pom.xml` 无 mail 依赖，`AuthController` 无找回密码；邀请邮件也无从发送。
- 已有可复用件：`OutboundWebhook` 的至少一次重试与耗尽通知口径（`c_ai_webhook_delivery`，`schema.sql:1805`）。

### 2.2 技术选型

- `spring-boot-starter-mail`（JavaMailSender），只支持 SMTP；不接第三方邮件 SaaS（私有化部署）。
- 密码类敏感配置走 `ConfigCryptoService` RSA 加密入库，与模型 Key 同款；编辑留空即沿用。

### 2.3 配置（系统设置新增「通知外发」面板）

| 键 | 默认 | 说明 |
|---|---|---|
| `notify.mail.enabled` | `false` | 总闸；关闭时全站零外发 |
| `notify.mail.host` / `port` | 空 / 587 | SMTP 地址与端口 |
| `notify.mail.security` | `starttls` | `none` / `ssl` / `starttls` |
| `notify.mail.username` / `password` | 空 | 认证账号；password 加密入库、不回显 |
| `notify.mail.from` / `fromName` | 空 / 问渠 | 发件人与显示名 |
| `notify.mail.types` | `tool.approval,tool.ask,plan.approval,workflow.approval,tenant.request,model.quota,quota` | 平台允许外发的类型白名单 |
| `notify.mail.minIntervalMs` | `300000` | 同类同 dedup_key 聚合窗口（5 分钟） |
| `notify.mail.retryDelaysSeconds` | `60,300,900` | 重试间隔 |

个人侧：`notification.mailTypes`（逗号列表，默认空 = 不发；只有显式勾选才发，避免「默认轰炸」）。

**默认行为**：总闸关 + 个人未勾选 = 现状零变化。

### 2.4 投递模型

新表 `c_ai_mail_delivery`（行级租户过滤）：

```sql
CREATE TABLE IF NOT EXISTS `c_ai_mail_delivery` (
    `tenant_id`       VARCHAR(32) NOT NULL DEFAULT 'default',
    `id`              VARCHAR(50) NOT NULL,
    `uid`             VARCHAR(64) NOT NULL COMMENT '接收人',
    `notification_id` VARCHAR(50) DEFAULT NULL COMMENT '来源通知（聚合时为多条中的首条）',
    `to_addr`         VARCHAR(160) NOT NULL,
    `subject`         VARCHAR(240) NOT NULL,
    `status`          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/success/exhausted/skipped',
    `attempts`        INT NOT NULL DEFAULT 0,
    `next_retry_at`   DATETIME DEFAULT NULL,
    `last_error`      VARCHAR(500) DEFAULT NULL,
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_mail_pending` (`status`, `next_retry_at`),
    KEY `idx_mail_uid_time` (`uid`, `create_time`)
) COMMENT='邮件外发投递台账（至少一次重试）';
```

**流程**

1. `NotificationService.notify()` 落库成功后，异步调用 `MailDispatchService.dispatch(notification)`；
2. 判定：总闸开 → 类型在白名单 → 用户邮箱存在 → 用户勾选了该类型 → 未在聚合窗口内重复；
3. 任一不满足记 `skipped`（留痕，方便排障），不发；
4. 成功记 `success`，失败按 `retryDelaysSeconds` 重试，耗尽记 `exhausted` 并给管理员一条站内通知（`notify.mail.exhausted`，仅管理员）；
5. 用户邮箱来源：`c_ai_user.email`（没有则不发，不猜账号后缀）。

**邮件内容**

- 统一 HTML 模板（内联 CSS，无外部图片）：租户品牌名 → 通知标题 → 一句话摘要（`content` 截 300 字）→ 主按钮「去处理」深链（`ref_type/ref_id/ref_sub` 拼前端地址）→ 底部「你可在个人设置 → 通知偏好中关闭邮件提醒」。
- 待处理类（审批/提问/计划）邮件标题前缀「待处理：」；失败类前缀「失败：」。
- 不开放自定义模板编辑（避免第二真相源），只允许改发件人显示名。

### 2.5 端点与前端

| 端点 | 权限 | 说明 |
|---|---|---|
| `GET /api/ai/config/mail` | 管理员 | 回显配置（密码只给 `hasPassword`） |
| `POST /api/ai/config/mail` | 管理员 | 保存；密码留空沿用 |
| `POST /api/ai/config/mail/test` | 管理员 | 发一封测试邮件到当前管理员邮箱；失败返回明确原因 |
| `GET /api/ai/notify/mail/prefs` | 本人 | 个人邮件类型勾选 |
| `POST /api/ai/notify/mail/prefs` | 本人 | 保存 |
| `GET /api/ai/notify/mail/deliveries?status=&page=` | 管理员 | 发送记录（本租户） |

**前端**

- 系统设置侧栏新增「通知外发」面板（排在「通知」之后）：SMTP 表单 + 传输安全 radio + 类型白名单多选 + 「发送测试邮件」+ 下部折叠「最近发送记录」（时间/收件人/主题/状态/重试次数）。
- 个人设置「通知偏好」：现有静音列表旁增加一列「邮件提醒」开关（仅平台白名单内的类型可勾）；顶部灰字「邮件提醒需管理员开启邮件通道」。
- 通知中心条目本身不加任何邮件标识（保持单一入口）。

### 2.6 边界与验收

- 不做短信、不做营销邮件、不做模板编辑器；SMTP 不可用只降级为站内，不影响主流程。
- 验收：总闸关零外发；开启后只发白名单且个人勾选的类型；聚合窗口内同 dedup_key 只一封；失败重试→耗尽→管理员通知；测试邮件成功/错误口令两路径；`c_ai_mail_delivery` 行状态与页面一致。
- 回归：站内通知 + WebPush 全链路零变化；通知深链仍直达。

---

## 3 OpenAI 兼容端点补齐

### 3.1 现状与缺口

`OpenAiCompatController.java`：

- `stream=true` 是「整轮生成完后单块下发」（`streamResponse` 注释自认，L221-223），不是真增量；
- 无 `POST /v1/responses`；
- 无 function calling：请求里的 `tools` 被忽略，工具只能由智能体静态配置决定；
- 多轮靠客户端重发完整 messages（无状态、临时会话），可用但无法承载工具调用回传。

### 3.2 3-1 真增量流式

- 在 `chatService.chat()` 的增量回调点接一个 `TokenSink`（优先复用 `ChatRunRuntime` 已登记的 SSE 通道；若通道是整轮收集型，则在 `ChatService` 内新增一个可选的增量观察者，不改现有通道语义）。
- 按 OpenAI chunk 逐段下发：role 帧 → content 增量帧 → finish 帧 → usage 尾帧 → `[DONE]`；错误在已开流后以 `event: error` + 关闭，不伪装成功。
- 保留 `X-Accel-Buffering: no`；首帧前不做整轮等待，首包时延目标 ≤ 2s。
- 边界：工具过程/思考过程**不进** content 增量（只发最终答案），避免第三方客户端收到半成品。

### 3.3 3-2 Responses API

- `POST /api/ai/v1/responses`：`model` = 智能体 id，`input` 支持字符串与 message 数组，`instructions` 映射为调用方补充指令（同现有 system 消毒口径）。
- 返回 `object=response`、`output[].content[].text`；`stream=true` 发 `response.output_text.delta` 事件 + `response.completed`。
- 与 chat/completions 共用同一临时会话管线，不新开第二套执行路径。

### 3.4 3-3 Function Calling（安全边界）

- 请求 `tools` **不能覆盖**平台工具面：只做「交集」——仅暴露该智能体已启用、且属于只读族的工具；未知工具静默忽略（`tool_choice=none` 时也不报错）。
- 有副作用/需审批的工具（沙盒命令、MCP 写、交付产物）在 HTTP 无交互通道下 **fail-closed 不暴露**，避免「无人可审批却执行」。
- 返回 `finish_reason=tool_calls` + `tool_calls[]`（`id/type/function{name,arguments}`）；客户端下轮以 `role=tool, tool_call_id` 回传，后端解析并在临时会话里恢复为工具结果消息，继续同一轮。
- 限制：单请求 `tools` ≤ 32、总 schema ≤ 64KB；工具调用轮数沿用 `maxToolSteps`。

### 3.5 兼容、限频与验收

- `model` 仍为智能体 id；`/v1/models` 返回可见智能体清单（可选加分，不在本期必须）。
- 限频与计量沿用 API Key 分桶与 `c_ai_usage_log.api_key_id`，不新增口径。
- 验收：OpenAI Python SDK 三路径（非流式 / 流式 / 工具调用）真机通过；首包时延与网页 SSE 同量级；错误形状为 OpenAI `{"error":{message,type}}`；未知工具与副作用工具均不出现在 `tools` 可见面。

---

## 4 MCP 面板升级（收口）

方案与交互规格全部沿用 `plan-mcp-panel-upgrade.md`（2026-11-07 立项），本文不改设计。当前状态：

- 效果图 `design/mockups/mcp-panel-upgrade/mcp-panel-upgrade.html` 已有 01 主页 / 02 试调用 / 03 统计抽屉 三景；
- 方案 §1.3 要求「主页全景 + 4 弹层」，**缺 04 编辑弹窗、05 导出弹窗** → 本次补齐，画布与 token 沿用同一份；
- 补图时不加「新」角标、默认收起、弱化新增元素三条硬规则不变。

实施顺序仍按原方案 §8：后端四件（schema / invoke / stats / 巡检）→ 前端一版 → 联调。

---

## 5 工作流游客放行（策略待拍板）

### 5.1 现状

- 工作流当工具在游客场景 fail-closed（`plan-agent-external-batch2.md` 遗留：等分享安全 P3/P4 拍板）。
- 游客轮次缺费用上限与注入面防护，直接放行会把平台额度暴露给匿名访问者。

### 5.2 三档策略

| 档 | 口径 | 风险 |
|---|---|---|
| A（现状） | 游客不可用任何工作流 | 功能缺失，但最安全 |
| B（建议） | 仅放行**只读工作流**：节点只含 LLM / 知识检索 / 模板转换 / 条件分支；任何代码执行、HTTP、MCP、kb_write、人工审核节点 → 整条工作流不可游客调用 | 低；费用仍受游客配额约束 |
| C | 全放行 + 游客轮次 token/费用硬上限 + 注入防护 | 高；需 P3/P4 完整落地 |

### 5.3 建议

先做 B：在发布/绑定 chatflow 时静态扫描节点类型，给工作流打 `guestSafe` 标记（不入 DSL，存工作流行）；游客分享页只列 `guestSafe=true` 且已发布的工作流，调用前再按租户游客配额闸一次。C 留到分享安全 P3/P4 落地后再评估。

### 5.4 前置与验收

- 前置：分享安全 P3 费用面（游客轮次记账与上限）、P4 注入面（`UntrustedContent` 已可用于节点输出，但需确认全链）。
- 验收：只读工作流游客可跑；含代码执行/HTTP/MCP 的工作流在游客侧不可见且直调 403；已发布未标记的工作流默认不可见；登录用户视角零变化。

---

## 6 排期建议

| 批次 | 内容 | 依赖 |
|---|---|---|
| P1-a | MCP 补 2 景 → 多租户 3E + 3A + 3D → 邮件通道（配置/投递/个人偏好） | 无 |
| P1-b | OpenAI 真流式 → responses → function calling（只读交集） | 引擎 token 通道 |
| P1-c | 工作流游客放行（B 档） | 分享安全 P3/P4 拍板 |

每批按 wenqu-dev 三道闸走；完工回写 `references/roadmap.md`。

## 7 待拍板

- 租户定位（外部客户 vs 内部分域）——决定邀请流与品牌是否必做、清退是否需要合同期语义（承 `plan-tenancy-real.md` §4-B）。
- 邮件通道是否作为平台必备（若定位纯内部桌面工具，可只保留邀请链接复制、不做 SMTP）。
- OpenAI function calling 的工具面：只读交集（建议）vs 允许副作用工具但要求调用方带审批回调（复杂度高）。
- 工作流游客放行选 B 还是继续等 C。
