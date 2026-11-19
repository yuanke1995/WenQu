# SSH 远程主机一等 provider 落地方案

> **2026-11-19 更新（当前实现口径）**：服务端 JSch + 托管凭据 + SSH 主机管理界面已按产品决定**移除**
> （代码与 V32 迁移退役 `c_ai_ssh_host` / `c_ai_project.ssh_host_id`）。
> 当前保留并作为落地形态的是**第 13 节「本机 OpenSSH 别名」`ssh_alias`**：连接在用户本机执行器上，
> 使用本机 `ssh` 与 `~/.ssh/config`，问渠不保存私钥/密码。第 1–12 节保留为早期设计记录。


> 对标 DSH 的 `dsh-ssh` / `dsh-fs-ssh` / `dsh-sandbox-ssh` / `dsh-subprocess-ssh`：
> 把一台 **远端 POSIX 主机** 当成工作区后端的“一等公民”——文件系统、命令执行、终端全部直连 SSH，
> 而不是让模型自己在 shell 里再套一层 `ssh`。
>
> 本文是「DSH 能力对标补齐清单」P1「SSH 一等 provider」的落地设计，2026-11-19。**不做基础版**：
> 凭据托管、主机指纹校验、连接复用、权限档位、路径围栏、审计、文件页、终端均在同一版落地。

---

## 1. 目标与非目标

### 目标
1. 工作区可以绑定 **SSH 远程主机 + 远端绝对路径**，与“本机执行器目录”同构：会话里 agent 的文件工具、命令工具、文件页、预览、下载、工作区记忆全部落到远端主机。
2. 凭据托管：密码 / 私钥（含 OpenSSH 格式与 passphrase）二选一，**RSA/JWE 加密落库**（复用 `ConfigCryptoService`），出参脱敏，明文只在建连瞬间使用。
3. 主机指纹校验：首次连接 TOFU 记录 `SHA256:` 指纹，此后严格比对；不一致 fail-closed（`E_HOST_KEY_MISMATCH`）。
4. 连接复用与自愈：按主机缓存 SSH Session，心跳保活；断线后一次重连；跨副本按需重建。
5. 权限档位沿用现有三档：`read-only` 不挂命令/写工具；`workspace-write` 把路径围栏在源文件夹内；`danger-full-access` 放开路径但仍受主机凭据约束。
6. 路径围栏双重校验：服务端按 `roots` 做词法规整 + 远端 `realpath` 真实路径校验，越界 `E_PATH_OUTSIDE`。
7. 审计：每次远端文件/命令/终端动作写 `c_ai_executor_audit`（op、路径、结果、耗时、会话/轮次），异步落库不阻塞工具调用。
8. 持久终端：WebSocket + SSH PTY（`ChannelShell`），支持窗口尺寸、Ctrl+C、断线提示。
9. 前端：SSH 主机管理、新建工作区时选择「SSH 远程主机」、远端目录浏览、工作区/会话头部状态、终端面板。

### 非目标（明确不做）
- 不做跳板机/多级 ProxyJump（数据结构预留 `jump_host_id`，本版不接线）。
- 不做远端 GUI 的 `openPath`（远端没有桌面文件管理器；接口回 `E_UNSUPPORTED`）。
- 不做远端 git 快照（每轮文件改动卡仍只对本机执行器开放；SSH 工作区静默跳过，不误报）。
- 不把 SSH 主机混进「本机执行器」设备清单（`c_ai_executor` 表不动，SSH 主机是独立表 `c_ai_ssh_host`）。

---

## 2. 总体架构

```
浏览器
  │  HTTP/SSE（会话、文件页、主机管理）+ WebSocket（终端）
  ▼
问渠后端
  ├─ SshHostService        凭据托管 / TOFU / CRUD（c_ai_ssh_host）
  ├─ SshConnectionManager  按主机缓存 JSch Session（保活、重连、并发 channel）
  ├─ SshRemoteExecutor     实现与“本机执行器”相同的 op 协议（list/read/write/stat/grep/exec…）
  │    └─ 复用 ExecutorRegistry 的“虚拟执行器”分发：project.executorIdOf() = ssh:<hostId>
  ├─ SshAuditService       异步写 c_ai_executor_audit
  └─ SshTerminalEndpoint   WebSocket ↔ JSch ChannelShell(PTY)
  │
  ▼ SSH（TCP 22 或自定义端口）
远端 POSIX 主机（SFTP + exec + shell）
```

### 2.1 为什么复用 `ExecutorRegistry` 而不是再造一套文件后端
现有 `ExecutorFilesystem`（模型工具）和 `ExecutorFiles`（文件页）已经有完整的 op 协议与路径语义：
`list/stat/read/readBytes/write/writeBytes/writeChunk/mkdir/move/remove/tree/grep/find/exec`。
它们只依赖 `ExecutorRegistry.call(executorId, op, args)` 与 `registry.online(executorId)`。

因此给 `ExecutorRegistry` 增加 **虚拟执行器（VirtualExecutor）**：`ssh:<hostId>` 不是一台真设备，
而是本进程内一个把 op 翻译成 SFTP/SSH 的执行器。这样：
- 模型工具（`ExecutorFilesystem`）、文件页（`ProjectFileService`）、工作区记忆、产物收编、HITL 全部零改动复用；
- 远端与本机执行器在业务层完全同形，不会出现“文件页能看、模型工具不能写”这类两套语义；
- 多副本部署时任意副本都能按 `ssh:<hostId>` 从库里解析出虚拟执行器（`VirtualExecutorProvider`），无需粘会话。

### 2.2 目录/路径口径
`c_ai_project_root` 的 `path` 存 **远端绝对路径**，`alias` 语义不变：
- 空/相对路径 → 主根（`primary_root=1`）；
- `alias/xxx` → 对应源文件夹内相对路径；
- 绝对路径原样下发（由远端围栏再校验）。

`ExecutorFilesystem.resolve()` 已经实现上述映射，SSH 无需另写一份。

---

## 3. 数据模型（V30 迁移）

### 3.1 `c_ai_ssh_host`
| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(64) | 主键，`ssh-` 前缀 |
| tenant_id | varchar(32) | 多租户 |
| uid | varchar(64) | 归属人，个人资产 |
| name | varchar(128) | 显示名 |
| host | varchar(255) | 域名/IP |
| port | int | 默认 22 |
| username | varchar(128) | 登录用户 |
| auth_type | varchar(16) | `password` / `private_key` |
| password_cipher | text | 密码 JWE 密文（仅 password） |
| private_key_cipher | mediumtext | 私钥 JWE 密文（仅 private_key） |
| passphrase_cipher | text | 私钥口令 JWE 密文（可空） |
| host_key_fingerprint | varchar(128) | `SHA256:...`；首次 TOFU 写入 |
| host_key_algorithm | varchar(64) | 如 `ssh-ed25519` |
| status | varchar(16) | `active` / `disabled` |
| last_probe_at / last_probe_ok / last_error | | 最近一次探测结果（界面用） |
| create_time / update_time / deleted | | |

私钥/密码只以密文形态落库；列表/详情接口永不返回密文或明文，只回 `hasPassword/hasPrivateKey`。

### 3.2 `c_ai_executor_audit`
| 列 | 说明 |
|---|---|
| id | 主键 |
| tenant_id / uid | 归属 |
| executor_kind | `ssh` / `local`（本版先落 ssh） |
| executor_id | `ssh:<hostId>` 或设备 id |
| host_id | SSH 主机 id |
| project_id / session_id / turn_id | 归属链路 |
| op | `exec/read/write/edit/grep/find/list/stat/mkdir/move/remove/writeBytes/readBytes/terminal` |
| path | 相对源文件夹的路径（无法相对化时存绝对路径） |
| ok / error_code | 结果 |
| duration_ms / bytes_in / bytes_out | 计量 |
| create_time | 时间 |

索引：`(uid, create_time)`、`(host_id, create_time)`。

### 3.3 `c_ai_project` 扩展
| 列 | 说明 |
|---|---|
| backend_type | `local`（默认，存量行）/ `ssh` |
| ssh_host_id | `c_ai_ssh_host.id`，`backend_type=ssh` 时必填 |

`executor_id` 保持“本机执行器”语义不变；SSH 工作区它为空。

---

## 4. 后端组件

### 4.1 `com.wenqu.ai.ssh` 包
- `SshHost` / `SshHostMapper`
- `SshHostService`：CRUD、凭据加解密、归属校验、`belongsTo`、`testConnection`、`credentialsOf`、`touchProbe`
- `SshConnectionManager`：按 `(hostId, updateTime)` 缓存 `com.jcraft.jsch.Session`；`setServerAliveInterval`；连接悲观锁 + 一次重连；`exec()`、`withSftp()`、`openShell()`
- `SshRemoteExecutor`：`ExecutorRegistry.VirtualExecutor` 实现；op 分发、路径围栏、结果码、审计
- `SshVirtualExecutorProvider`：`VirtualExecutorProvider` 实现，按 `ssh:<hostId>` 从库里装配执行器
- `SshAuditService`：有界队列 + 单线程异步落库；队列满丢弃并计数告警
- `SshPathGuard`：词法规整 + 远端 realpath 校验（纯函数部分可单测）
- `SshHostController`：主机 CRUD / 测试 / 目录浏览 / 指纹
- `SshTerminalWebSocketHandler` + `SshWebSocketConfig`：PTY 终端

### 4.2 `ExecutorRegistry` 扩展
```java
public interface VirtualExecutor {
    boolean online();
    Result execute(String op, Map<String, Object> args);
    default void close() {}
}
public interface VirtualExecutorProvider {
    VirtualExecutor resolve(String virtualId); // "ssh:<hostId>" -> executor，不认识返回 null
}
```
- `online(id)`：先查 `virtuals`；`ssh:` 前缀则让 provider 装配并缓存；其余仍走 Redis 在线键。
- `call(id, op, args, timeout)`：虚拟执行器走独立线程池 `future.get(timeout)`，超时 `E_TIMEOUT`，异常 `E_IO`。
- `invalidateVirtual(id)`：主机改配置/删除时清缓存并 `close()`。

### 4.3 op 语义（与 wenqu-agent 对齐）
| op | 实现 |
|---|---|
| `list` | SFTP `ls`；返回 `{path, entries:[{path,name,isDir,size,mtimeMs,isSymlink}]}` |
| `stat` | SFTP `stat`；`{path,name,size,mtimeMs,isDir}` |
| `read` | 文本：`sed -n 'start,endp'`（或小文件整读后按行切）；`{content,encoding}` |
| `readBytes` | SFTP 流式读区间；`{contentBase64,totalBytes}` |
| `write` | 原子写（临时文件 + rename）；带 `expectedHash` 时先校验，不一致 `E_STALE` |
| `writeBytes` / `writeChunk` | SFTP put；chunk 按 offset 拼接 |
| `mkdir` / `move` / `remove` | SFTP；remove 对目录递归 |
| `tree` | SFTP 递归，受 `maxFiles/maxDepth` 限制 |
| `grep` | 远端 `grep -rHnF`，截断 |
| `find` | 远端 `find -type f -name`，截断 |
| `exec` | `ChannelExec`，cwd=主根，超时杀 channel，输出截断 1MB；`{output,exitCode,truncated}` |
| `openPath` / `pickDir` / `turnStart` / `turnEnd` / `turnDiff` | `E_UNSUPPORTED`（业务层对 SSH 跳过） |

错误码沿用既有稳定口径：`E_EXECUTOR_OFFLINE` / `E_TIMEOUT` / `E_PATH_OUTSIDE` / `E_STALE` /
`E_SANDBOX_DENIED` / `E_NOT_FOUND` / `E_UNSUPPORTED` / `E_IO` / `E_PERMISSION` / `E_HOST_KEY_MISMATCH`。

---

## 5. 权限与安全

### 5.1 档位映射
| 预设 | 沙盒档 | SSH 行为 |
|---|---|---|
| research 仅可查看 | read-only | 只挂读/列类工具（沿用 `ExecutionPolicy.allowsTool`，家族= SANDBOX + SideEffect=READ）；写/命令工具不进工具表 |
| standard 工作区内修改 | workspace-write | 可写/可执行；所有路径围栏在 `roots` 内 |
| autonomous 完全权限 | danger-full-access | 路径不再围栏（仍受 SSH 账号权限约束）；危险命令 HITL 照旧 |

### 5.2 路径围栏（双闸）
1. 词法：`SshPathGuard.normalize()` 折叠 `//`、`.`、`..`，必须以 `/` 开头，且落在某 root 的词法前缀内；
2. 真实路径：对变更类操作执行远端 `realpath -m`（并缓存 root 的 realpath），校验仍在 root 真实路径内——防符号链接逃逸；
3. 越界一律 `E_PATH_OUTSIDE`，同时写审计（`error_code`）。

### 5.3 凭据与指纹
- 密码/私钥/口令全部 `ConfigCryptoService.encrypt` 后入库；`SshHostService.toDto` 只回 `hasPassword/hasPrivateKey`。
- 首次连接 `StrictHostKeyChecking=no` 建立 TCP/SSH 后立即取 `session.getHostKey()`，算 `SHA256:` 指纹；
  库里为空则 TOFU 写入并记审计；非空且不一致立即断开并 `E_HOST_KEY_MISMATCH`。
- 私钥支持 OpenSSH / PEM；passphrase 可空。

### 5.4 审计
- 每次 op 由 `SshRemoteExecutor` 统一包一层：开始计时、结束写 `c_ai_executor_audit`；
- `path` 尽量相对化为 `alias/rel`，避免把用户远端绝对目录结构大量堆在审计表；
- 终端会话记录一条 `op=terminal` 的连接/断开审计（不记录终端输入输出内容）。

---

## 6. 路由改造清单（一处真源）

| 文件 | 改动 |
|---|---|
| `Project` | 新增 `backendType` / `sshHostId` 字段与常量 |
| `ProjectService` | `isSsh()` / `isDirect()` / `executorIdOf()` / `createSsh()` / `bindSshRoots()`；`isLocal()` 保持原义 |
| `SandboxService.frameworkFilesystem` | `isDirect` 分支：SSH 也装配 `ExecutorFilesystem`（executorId = `ssh:<hostId>`）；`frameworkSandboxContext` 对 SSH 返回 null |
| `SandboxService.sessionWorkspaceKey` | SSH 工作区键含 hostId + 主根，换主机重建实例 |
| `AgentscopeChatEngine` | `localProject` / `wantsSandbox` / `workspaceRootsOf` 用 `isDirect` |
| `ProjectFileService` | 所有 `isLocal` 判断改 `isDirect`；`executorId(p)` → `projectService.executorIdOf(p)` |
| `ProjectController` | DTO 增加 `backendType/isSsh/sshHostId/sshHostName`；新建支持 `sshHostId`；新增 `PUT /{id}/ssh` |
| `SessionService.workspaceOfSession` | DTO 增加 `isRemote/sshHostId/sshHostName/remoteOnline`；`executorId` 用 `executorIdOf` |
| `ChatController` | API Key 禁止访问 SSH 工作区（与“本机工作区”同一条闸） |
| `ToolSpillService` | SSH 工作区同样不落服务端工具原文 |
| `SessionProcService` | SSH 主机不参与本机后台进程清单（不误把 SSH 会话算到本机设备） |
| `ProjectTurnChangesService` | 仅 `isLocal` 才做轮前快照；SSH 静默跳过 |

---

## 7. 接口

### 7.1 SSH 主机（`/api/ai/executor/ssh`，`@Permission("executor:self")`）
- `GET /hosts` 主机清单（含 `online / lastProbeOk / hasPassword / hasPrivateKey`）
- `POST /hosts` 新建（body：name/host/port/username/authType/password/privateKey/passphrase/hostKeyFingerprint）
- `PUT /hosts/{id}` 更新（密码/私钥留空=不改）
- `DELETE /hosts/{id}` 删除（同时断开连接；已被工作区引用时 409 说明）
- `POST /hosts/test` 用临时参数测试（新建前）：返回 `banner / fingerprint / algorithm / home / os`
- `POST /hosts/{id}/test` 测试已存主机
- `GET /hosts/{id}/browse?path=` 远端目录单层浏览（只列目录）
- `WS /hosts/{id}/terminal` 交互式 PTY（登录态）

### 7.2 工作区
- `POST /api/ai/project` 增加 `sshHostId`；非空即创建 SSH 工作区
- `PUT /api/ai/project/{id}/ssh` 重绑 SSH 主机与源目录（替换式）
- 其余文件页/预览/下载接口完全不变（底层按工作区类型路由）

---

## 8. 前端交互

1. **新建工作区**：顶部“位置”二选一「本机电脑 / SSH 远程主机」。
   - 选 SSH：下拉已托管主机（在线状态）+「管理主机」；点「浏览」打开远端目录树，选目录加为源文件夹（多个，第一个为主）。
   - 主机为空时给出“添加 SSH 主机”引导。
2. **SSH 主机管理弹窗**：列表 + 新建/编辑/删除 + “测试连接”（显示服务器 banner、指纹、home、OS）。
   新建表单：名称 / 地址 / 端口 / 用户名 / 认证方式（密码、私钥）/ 密码或私钥+口令 / 指纹（可留空由首次连接信任）。
3. **工作区/会话头部**：SSH 工作区显示“远程主机”标识与在线状态；离线提示改为“远程主机连不上，检查网络/凭据”。
4. **终端**：工作区页/聊天头部提供“终端”入口，打开 xterm 风格面板（WebSocket PTY）；断线显示重连提示。
5. 文件页：完全复用现有工作区文件页，预览/下载/编辑走 SSH。

---

## 9. 失败态

| 场景 | 表现 |
|---|---|
| 主机不可达 / 端口错 | `E_EXECUTOR_OFFLINE`，提示“SSH 主机连不上：检查地址/端口/防火墙” |
| 认证失败 | `E_PERMISSION`，提示“用户名/密码或私钥不正确” |
| 指纹不一致 | `E_HOST_KEY_MISMATCH`，fail-closed，提示重新核对指纹，绝不自动覆盖 |
| 路径越界 | `E_PATH_OUTSIDE`，提示只能访问工作区源文件夹 |
| 连接中途断开 | 本次调用失败；连接池标记失效，下一次调用重连一次；写操作不自动重放 |
| 工作区引用的主机被删 | 保存/删除时 409；会话文件工具 fail-loud 提示“重新绑定主机” |

---

## 10. 测试与验收

### 10.1 单元/集成
- `SshPathGuardTest`：`..` 折叠、前缀边界（`/a/bc` 不属于 `/a/b`）、绝对路径、越界。
- `SshHostServiceTest`：密码/私钥加密落库、出参脱敏、留空不覆盖。
- `SshRemoteExecutorIT`：内嵌 Apache MINA sshd（SFTP + exec + shell）：
  - `exec echo` 退出码与输出；
  - `list/read/write/stat`；
  - `../` 越界 `E_PATH_OUTSIDE`；
  - 指纹不匹配 `E_HOST_KEY_MISMATCH`。
- `ExecutorRegistry` 虚拟执行器：`online` / `call` / 超时。

### 10.2 手工验收（真实主机）
1. 新建 SSH 主机（`ubuntu@49.232.129.201`），测试连接通过并记录指纹；
2. 新建工作区绑定该主机 `/home/ubuntu`；
3. 会话里让 agent `ls`、`cat`、写一个文件、跑 `hostname; uptime`；
4. 文件页浏览/预览/下载/编辑；终端执行 `top` 后 Ctrl+C；
5. research 档：工具表无命令/写工具；standard 档：写 `../etc` 被 `E_PATH_OUTSIDE` 拒；
6. 审计表能看到每次 op。

---

## 11. 里程碑

| 阶段 | 内容 |
|---|---|
| M1 | 依赖 + V30 + ssh 包（连接池/SFTP/exec/围栏/审计）+ ExecutorRegistry 虚拟执行器 |
| M2 | 项目/路由集成 + 主机控制器 + 目录浏览 + 测试连接 |
| M3 | 前端主机管理 + 新建工作区 SSH + 文件页复用 + 状态提示 |
| M4 | PTY 终端（后端 WebSocket + 前端面板） |
| M5 | 测试、真实主机联调、文档与交付 |

---

## 12. 与现有能力的边界
- `c_ai_executor`（本机执行器）与 `c_ai_ssh_host`（远端主机）是两类个人资产，互不混表；
- SSH 不参与本机执行器的设备配对、后台进程卡、每轮文件改动快照；
- 企业级能力（多租户/RBAC/审计/配额）全部沿用现有体系，SSH 只新增“执行后端”这一维。

---

## 13. 本机 OpenSSH 别名模式（DSH `dsh-ssh` 同形态，二期补齐）

首版的「服务端 SSH provider」解决的是问渠后端统一托管远端主机的场景；但与 DSH 的真实形态还有一条差异：
DSH 的 `dsh-ssh` 使用 **deployment-owned OpenSSH alias**（`host` 填 `~/.ssh/config` 的 Host 名），
本地 `ssh` 带 `BatchMode` + 严格 host-key + ControlMaster/Unix-socket forwarding，问渠不保存私钥/密码。

因此在首版之外增加第二种工作区后端 `backend_type=ssh_alias`：

- **数据**：`c_ai_project.ssh_alias` 只存 Host 别名（如 `wenqu`）；`executor_id` 指向用户本机执行器；不存凭据。
- **连接发起端**：用户本机执行器 `wenqu-agent` 调本机 `ssh`，读该电脑的 `~/.ssh/config`、known_hosts、ssh-agent。
- **协议**：服务端把标准 op 包装成 `ssh` op（`{alias, op, args}`）下发给本机执行器；
  执行器在远端执行 list/stat/read/readBytes/write/writeBytes/writeChunk/mkdir/move/remove/tree/grep/find/exec。
- **虚拟执行器**：`ssh-alias:<deviceId>:<base64url(alias)>`，与 `ssh:<hostId>` 并列，复用同一套
  `ExecutorFilesystem` / `ExecutorFiles` / 权限档位 / 文件页。
- **UI**：新建工作区增加「本机 OpenSSH 别名」：选本机执行器 + 选/填 Host 别名 + 远端目录浏览；
  不出现密码/私钥输入。
- **限制**：不自动安装远端 helper；不提供远端桌面 `openPath`/每轮 git 快照/持久 PTY（后续可加）。
  远端需为 Linux/macOS，且本机 `ssh` 已能非交互连通（known_hosts/密钥/agent 就绪）。

对应 V31 迁移；agent 自检：`node scripts/local-agent/wenqu-agent.mjs --ssh-alias-selftest wenqu`。
