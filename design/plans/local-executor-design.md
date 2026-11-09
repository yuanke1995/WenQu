# 本机执行器（local executor）设计

> 目标：云端保留知识库 / 会话 / 模型 / 权限，用户自己电脑上的文件由**用户电脑上的一个进程**执行读写与命令。
> 模型参照 DeepSeek Harness（`packages/fs/fs-local`、`fs-sandbox`、`host/directory-picker*`、`workspace`）。

## 1. 目标与非目标

**目标**
- 会话里 agent 能读、写、改、执行**用户授权目录**内的文件，改完用户在自己电脑上立刻可见。
- 知识库、会话、模型调用、权限判定全部留在云端，多人共享。
- 权限按 DSH 的三档围栏生效，破坏性操作显式确认。

**非目标**
- 不做浏览器文件句柄方案（仅 Chrome/Edge、每次授权、不能跑命令，且写盘发生在浏览器而不是 agent 执行链上）。
- 不把知识库、向量索引、模型 Key 下发到用户电脑。
- 不做"云端直连用户电脑文件系统"（网络边界不允许）。

## 2. 数据放置

| 数据 | 位置 | 说明 |
|---|---|---|
| 知识库 / 文档 / chunk | 云端 | 共享、统一更新 |
| 向量索引、关键词索引 | 云端 | 随文档更新重建 |
| 文档原文（引用回原文） | 云端，可按需缓存 | 不要求落地客户端 |
| 会话、消息、产物、审计 | 云端 | |
| 模型 Key、供应商配置 | 云端 | 永不下发 |
| RBAC / 文档 ACL | 云端 | |
| 工作区目录（用户文件，可多个） | 本机 | 只存在于用户电脑；工作区绑定同一台机器上的一个或多个源文件夹，首个为主目录 |
| 工具执行（读/写/exec） | 本机代理 | 目录所在的那台机器 |

检索命中的片段作为**本轮请求上下文**下发到模型，不落用户电脑。

## 3. 总览

```
浏览器 ──HTTP/SSE──► 云端后端（知识库 / 会话 / 模型 / 权限 / 工具循环）
                          │  WSS（服务端主动下发工具调用）
                          ▼
                     本机代理 wenqu-agent
                          │  目录白名单 + 三档围栏 + 确认
                          ▼
                     用户授权目录
```

- 云端永远不碰用户文件，只发“要做什么”。
- 代理永远不碰知识库与模型 Key，只执行。
- 一条会话的工具调用路由到**会话绑定的那个执行器**。

## 4. 协议

### 4.1 传输
- 单条 WSS 长连接：`wss://<backend>/api/ai/executor/ws`，`Authorization: Bearer <device-token>`。
- 帧格式 JSON，`v=1`；服务端按 `id` 关联请求/响应；同一连接内并发上限 4。
- 心跳：服务端 15s `ping`，代理 10s 内未收到即重连；断线重连指数退避（1s→30s，上限 5 分钟）。

### 4.2 配对（设备授权流）
方向是**执行器发起**（与 `gh auth login` / `aws sso login` 同形），令牌不经过命令行、shell 历史和环境变量：
1. 本机：执行器启动时 `POST /api/ai/executor/device-code`（匿名）→ 拿到 `deviceCode`（执行器轮询用）与 6 位 `userCode`。
2. 终端打印 `<web>/pair?code=<userCode>`；用户在浏览器里打开、登录自己的问渠账号，
   `GET /api/ai/executor/device-code?code=` 显示这是哪台电脑，`POST /api/ai/executor/device-code/approve` 确认。
3. 执行器轮询 `POST /api/ai/executor/device-code/token {deviceCode}`；确认后一次性取回 device token + `executorId`
   （库里只存哈希；确认前服务端不签发任何设备身份）。
4. 执行器把令牌写到 `~/.wenqu/executor.json`（0600，按服务器分别保存），之后启动直接复用；
   令牌被撤销（401）时自动清掉本地令牌、重新走 1~3。
5. 码 10 分钟过期；同一账号 10 分钟内试错超过 10 次即拒绝，防遍历。

### 4.3 帧

服务端 → 代理：
```json
{
  "v": 1, "id": "c-42", "type": "tool_call",
  "turnId": "t-9", "executorId": "ex-1",
  "op": "list|read|write|edit|exec",
  "roots": [                              // 工作区源文件夹（可多个），代理必须校验调用目标在其中之一
    { "path": "/Users/me/project", "alias": "project", "primary": true },
    { "path": "/Users/me/docs", "alias": "docs", "primary": false }
  ],
  "args": { "path": "a/b.md", "content": "...", "command": "..." },
  "policy": { "mode": "read-only|workspace-write|danger-full-access" },
  "deadlineMs": 180000
}
```

代理 → 服务端：
```json
{ "v": 1, "id": "c-42", "type": "tool_result", "ok": true,
  "data": { "content": "...", "bytes": 123, "mtime": 1793792066 } }

{ "v": 1, "id": "c-42", "type": "tool_result", "ok": false,
  "error": { "code": "E_PATH_OUTSIDE", "message": "...", "path": "..." } }
```

需要人工确认时（代理侧弹窗）：
```json
{ "type": "permission_request", "id": "p-7", "callId": "c-42",
  "action": "write", "path": "a/b.md", "diff": {"before":"...","after":"..."} }
```
云端回 `permission_result {id, approved}`；未批准 → 该调用以 `E_DENIED` 结束。

### 4.4 错误码（稳定，调用方按码分支，不解析文案）

| 码 | 含义 |
|---|---|
| `E_EXECUTOR_OFFLINE` | 执行器不在线 |
| `E_TIMEOUT` | 超时（含进程被杀） |
| `E_PATH_OUTSIDE` | 目标不在工作区根 / 授权目录内 |
| `E_NOT_OBSERVED` | 未读过就写（read-before-write） |
| `E_STALE` | 读取后文件已变化（mtime/size 不一致） |
| `E_SANDBOX_DENIED` | 权限档位拒绝（read-only 下的变更） |
| `E_DENIED` | 用户拒绝确认 |
| `E_IO` / `E_PERMISSION` | 代理侧文件系统错误 |

## 5. 云端改造

### 5.1 新包 `com.wenqu.ai.executor`
- `ExecutorRegistry`：连接表（executorId → session），在线状态、最近心跳、能力（os / 授权目录数）。
- `ExecutorWebSocketHandler`：握手校验 device token；处理 `tool_result` / `permission_result` / `hello` / `ping`。
- `ExecutorFsBackend implements SandboxFsBackend`：把 `list/read/write/edit/exec` 转成 `tool_call`，同步等待（CompletableFuture + 超时）。
  **上层工具、权限档位、HITL 卡片全部不变**——这是复用现有抽象的关键点（见 `src/main/java/com/wenqu/ai/sandbox/SandboxFsBackend.java`）。
- `ExecutorPairingService`：配对码、设备、撤销。
- `ExecutorAudit`：每次调用落审计（op / 路径 / 结果 / 耗时 / turnId），路径脱敏只留工作区相对路径。

### 5.2 路由
`SandboxService.frameworkFilesystem(...)` 增加分支：
- 工作区绑了本机执行器 → `ExecutorFilesystem`（多源文件夹 + 权限档位）。
- 没挂工作区的「会话」栏 / 平台内部轮次 → 现有服务器沙盒（会话级作用域）。

### 5.3 数据模型
`schema.sql` + `SchemaMigrator` 自动补列（沿用现有机制）：
```sql
ALTER TABLE c_ai_project ADD COLUMN executor_id VARCHAR(50) NULL;      -- 文件都在那台执行器上
CREATE TABLE c_ai_project_root (
  id VARCHAR(50) PRIMARY KEY, project_id VARCHAR(50), uid VARCHAR(50),
  alias VARCHAR(120), path VARCHAR(1024), primary_root INT, sort_no INT,
  UNIQUE KEY uk_project_alias (project_id, alias)
);
CREATE TABLE c_ai_executor (
  id VARCHAR(50) PRIMARY KEY, uid VARCHAR(50), name VARCHAR(100),
  token_hash VARCHAR(128), os VARCHAR(20), last_seen DATETIME,
  revoked TINYINT DEFAULT 0, create_time DATETIME
);
```
`directory_mode` / `workdir_path` / `root_path` / `shared_workdir` 均已退役：工作区只有本机目录一种形态，服务器目录列由 SchemaMigrator 删除。

### 5.4 权限判定顺序（云端 + 代理双侧）
1. 会话执行位置（工作区是否绑执行器）
2. 执行器在线
3. 权限档位（`read-only` 拒绝一切变更）
4. 目标路径规范化后必须落在某个源文件夹内（代理再独立校验一次）
5. `write/edit` 必须有本轮读过的记录（除非档位为 full）
6. 版本一致（mtime/size）
7. 会话级权限档位：仅可查看 / 工作区内修改 / 完全权限；完全权限在页面二次确认后生效
8. 破坏性操作 → 页面上的确认卡（终端 `--ask` 为可选）

## 6. 本机代理

- 形态：单文件可执行（Go 优先，跨平台、无运行时依赖），`wenqu-agent`。
- 子命令：`start`（默认）/ `dirs add|remove` / `status` / `logs`；首次启动即进入设备授权流（见 4.2）。
- 目录授权：云端只下发工作区的源文件夹清单；**每次调用代理都重新校验**目标路径落在某个源文件夹内（`danger-full-access` 档才放开）。软链一律拒绝——lexical 前缀对、真实路径可能越界。
- 执行：
  - `list/read/write/edit` 直接走文件系统 API；
  - `write` 采用临时文件 + rename 原子替换；
  - `exec` 走 `sh -c`（Windows `cmd /c`），cwd 固定为 `root`，超时杀进程组，输出截断到 1 MB。
- 确认：默认不在终端逐次确认——权限档位已经在页面选择器上定好，终端再问一次会让浏览器里的这一轮一直等不到返回；需要终端确认时以 `--ask` 启动代理。
- 日志：本地滚动日志，**只记路径哈希**，不记文件内容。

## 7. 前端（照 DSH，不另造）
- 输入框上方一个只读字段「选择工作区」，点击弹「选择工作区目录」：
  - 本机执行器在线时：列出该执行器已授权的目录（多一层进入）；
  - 未装/离线：显示在那台电脑上启动执行器的命令 + 「授权完成后刷新」。
- 工作区设置里显示：执行器名 + 目录绝对路径 + 权限档位。
- 工具调用沿用现有过程卡；写入在工具卡上显示 diff，确认即 HITL 审批卡（复用现有审批通道，不新增弹窗样式）。

## 8. 失败态

| 场景 | 表现 |
|---|---|
| 执行器离线 | 本轮开工前即 fail-loud：「本机执行器不在线，去这台电脑上启动后重试」 |
| 执行中掉线 | 本轮标失败，重连后**不自动重放**（写操作非幂等），由用户重发 |
| 路径漂移（目录被移动/删除） | `E_IO`，提示重新选择目录 |
| 超出白名单 | `E_PATH_OUTSIDE`，同时记安全审计 |
| 权限档位拒绝 | `E_SANDBOX_DENIED`，提示切换档位（复用现有升级提示） |

## 9. 里程碑

| 阶段 | 内容 | 估时 |
|---|---|---|
| M0 | 协议 + 配对 + WSS + 只读（list/read）+ 路由 | 1.5 天 |
| M1 | 写/编辑 + 读后写 + 版本校验 + 确认卡 | 1 天 |
| M2 | exec + 超时/截断 + 审计 | 1 天 |
| M3 | 断线重连、多目录、离线降级、代理设置界面 | 2 天 |

## 10. 验收（可执行断言）

1. 本机目录里 `notes.txt` 由 agent 改写后，`md5` 变化且 Finder 可见（不经浏览器）。
2. 未读就写 → `E_NOT_OBSERVED`。
3. 读到之后外部改了文件 → 写被 `E_STALE` 拒绝并提示重读。
4. 目标 `../` 越出工作区 → `E_PATH_OUTSIDE`，白名单外文件未被触碰。
5. 只读档下写 → `E_SANDBOX_DENIED`。
6. 杀掉代理 → 下一轮 `E_EXECUTOR_OFFLINE`，不静默成功。
7. 同一轮里知识库检索 + 本机写文件同时发生（云端库、本机文件）。
8. 断线中途掉线 → 本轮失败，不自动重放。

## 11. 未决

- 代理是否内置自动更新（先手动）。
- 多执行器同用户（办公室 + 家里）：先按工作区绑一台，不支持同会话漂移。
- `exec` 默认是否开启：建议默认关，按工作区显式打开。
