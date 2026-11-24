# 贡献指南（Contributing）

感谢对问渠（WenQu）的兴趣！欢迎提交 Issue 与 Pull Request。

## 许可约定

提交即表示你同意你的贡献以 [MIT License](LICENSE) 随本项目发布。

## 本地开发

环境准备、环境变量与一键起依赖（`docker compose up -d redis-stack meilisearch`）见 [README · 快速开始](README.md#快速开始)。缺必填环境变量会 fail-fast，按 README「配置环境变量」逐项配置即可。

- **后端**：Java 17 · Maven 单体工程（`pom.xml` 在仓库根），入口 `WenQuApplication`，启动方式见 README「启动后端」（直跑 / 打包 / Docker Compose 三选一）
- **前端**：`web/` 目录，Node ≥22.12（`.nvmrc` 固定 22 LTS），`npm install && npm run dev`；dev 端口 5800，`/proxy` 转发后端 `http://localhost:8090/ai`
- **本地私有配置**：放 `config/application-local.yml`（已被 .gitignore 忽略，不要 force add）；运行时数据在 `data/`、`user-data/`，同样不入库

## 提交规范

- 中文 conventional commit：`类型(范围): 描述`，如 `feat(chat): 支持xxx`、`fix(kb): 修复yyy`
- 一个 PR 只做一件事；UI 改动附截图，行为改动写清验证步骤
- **实机验证必须带截图**（见下节「实机验证规范」）
- 设置项相关改动须同步 `src/main/resources/config-schema.json`（设置页渲染的唯一 schema 源）

## 提交前自检（本地 CI）

CI 失败绝大多数不是功能写错，而是「行为或数据变了，守门断言和文档口径没跟上」。改完先跑受影响面的本地闸门，再 push：

```bash
./scripts/ci-local.sh        # 按改动面挑跑（后端 / 前端 / 契约 / SDK）
./scripts/ci-local.sh --all  # 全量，等价 CI 四个 job
```

仓库自带 `.githooks/pre-push`，启用后每次 push 自动跑：`git config core.hooksPath .githooks`；赶时间可 `git push --no-verify` 跳过。

改以下内容时，对应检查必须在同一个 commit 里同步，不能等 CI 红了再补：

| 改动 | 同 commit 必须同步 |
| --- | --- |
| 新增/删除 `src/main/resources/db/migration/V*.sql` | Flyway 测试已按解析结果自校验数量与最高版本，无需改数字；退役表/列登记 `SchemaMigrator` |
| 增删改 `config-schema.json` / `ConfigService.defaults()` | `ConfigContractTest`（隐藏项登记、读点、默认值）；README「系统设置」一节与目录树的计数口径 |
| 改权限档位 / 审批 / 执行策略语义 | 后端对应单测 + `web/test/permission.test.mjs`、`workspaceInfo.test.mjs` |
| 改对外 HTTP 端点 / scope / 可达性 | `node scripts/check-openapi-contract.mjs --update`，并补 `contract/CHANGELOG.md` 的变更原因 |
| 改 SDK 版本 | 四处版本号一致，跑 `node scripts/check-sdk-version.mjs` |
| 改前端模块导出 / 装配 | `web` 单测与 `vite build` |


## 实机验证规范（强制）

**没有截图的实机验证不算验证。** 单元测试与接口调用只能证明「代码路径跑到了」，证明不了「用户真的看得见」——
前端渲染、loading 态、字段缺一帧、订阅没建立，这些都只有截图能证伪。

### 什么算「实机验证」

| 算 | 不算 |
| --- | --- |
| 真实浏览器打开真实页面，走到用户实际会走的路径 | 只 `curl` 后端接口 |
| 前端 dev server + 真实后端（本地或线上） | Mock 出来的假响应 |
| 跑完整个交互并向用户看得到的区域截图 | 只断言 JSON 里出现了某个字段 |

### 必须附的证据

1. **截图**：至少覆盖「操作前 / 进行中 / 结果」三个时刻；做的是实时流类改动时，「进行中」那一张不可省——
   它正是这类功能的核心，事后态截图看不出来。
2. **一句话结论**：这张截图证明了什么、以及**没有证明什么**（比如「证明了后端发了帧，
   但没证明成员页订阅成功」）。
3. 截图放临时目录并在 PR/回复里引用；不要提交进仓库（`.playwright-mcp/`、`/tmp` 之类已被忽略）。

### 反例（真实踩过）

- 用接口抓 SSE 抓到 `turn_start`/`tool_status` 就宣布「直播跑通了」，但成员页 `/live` 订阅次数为 0、
  子轮正文一帧没上翻——用户打开页面看到的仍是空白。
- 把「页面 innerText 里出现某成员名」当成成员已创建，实际那段文字来自用户自己的提问内容。

### 起浏览器的现成做法

`playwright-core` + 本机缓存的 Chromium（不下载浏览器本体）：

```bash
npm i playwright-core --cache /tmp/npmcache
# macOS 上可执行文件在：
# ~/Library/Caches/ms-playwright/chromium-*/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing
```

脚本里监听 `request` 事件核对**请求真的发出去了**（例如 `/member/{id}/live` 订阅），
配合截图才能同时证明「发起了」和「显示了」。

### 跑不通时怎么说

实机验证没跑通、或只能跑到一半，**必须如实写明卡在哪**（缺账号、环境没起来、路径没走通），
不许用接口结果或单测结果顶替「实机通过」。

## 跨平台开发规范（Linux / Windows / macOS）

问渠主要在 macOS 上开发，但必须保证 Linux 与 Windows 可用。**平台差异只能收敛到少数能力层，禁止在业务代码里散落 `if (win32)` 式补丁或“另一套兜底”。**

### 本机执行器（`scripts/local-agent`）

- OS 相关能力只能放在 `scripts/local-agent/platform/`：`shell`、`sandbox`、`procs`、`system`、`git`、`browser`、`ssh`。
- 新增平台能力必须同时提供 `darwin / linux / win32` 三套完整实现 + 注入 fake platform 的契约测试；禁止硬编码 `/bin/sh`、`ps`、`lsof`、`ss`、`npx`、`explorer`、`osascript`、`zenity` 等二进制名。
- 业务代码不得直接读 `process.platform`（设备上报信息字段除外）；路径统一 `path.join/path.resolve`，禁止手拼分隔符。
- Windows 语义要点：`cmd.exe /d /s /c`、`taskkill /T /F`、`tasklist`/`netstat`、`npx-cli.js`、`explorer` 退出码非 0 属正常、目录选择取消需结构化返回。

### 服务端（Java）

- 文本读写一律走 `com.wenqu.ai.util.TextIo`（显式 UTF-8）；禁止裸 `Files.readString(单参)` / `writeString(单参)`。
- 落盘/下载文件名一律走 `com.wenqu.ai.util.FileNameSanitizer`（非法字符、Windows 保留名、尾部点空格、长度）。
- 路径拼接用 `Path.of(...)`，禁止 `System.getProperty("user.dir") + "/..."`。
- 标识类归一（扩展名、枚举、method、language）用 `Locale.ROOT`；时区统一由 `WenQuApplication` 固定，禁止依赖宿主默认时区。
- 服务端不得引入宿主进程执行（`Runtime.exec`/`ProcessBuilder`）：命令执行只走沙盒容器或本机执行器。

### 前端（`web/`）

- 路径展示/折目录走 `utils/pathUtil.js`（`toPosix/baseNameOf/dirOf`），禁止直接 `split('/')` 处理可能来自 Windows 的路径。
- 下载名走 `utils/fileName.js` 的 `sanitizeFileName`；跨源下载由 `utils/download.js` 统一拦截，禁止只依赖 `a[download]`。
- 复制走 `utils/clipboard.js` 的 `writeClipboard`（`navigator.clipboard` 在 http 内网不存在，禁止直连）。
- 快捷键统一同时判断 `ctrlKey` 与 `metaKey`；文案用 `saveKeyLabel()`，不要写死 `⌘`。
- JSON 导入走 `utils/json.js` 的 `parseJsonFile`（剥 BOM）。
- `package.json` 脚本禁止 POSIX 内联环境变量（`VAR=x node ...`）与 shell glob；测试统一 `node scripts/run-tests.mjs`。

### 脚本 / 部署 / CI

- 统一入口 `node scripts/wenqu.mjs`（Windows 用 `wenqu.bat/.ps1`）；中间件一律 docker compose，不新增 `brew services`/`/opt/homebrew` 依赖。
- 临时目录用 `os.tmpdir()`（Node）/`tempfile`（Python）；禁止字面 `/tmp`。
- 外部客户端（mysql/mysqldump）用 `shutil.which` 或 `MYSQL_BIN`/`MYSQLDUMP_BIN`，禁止写死安装路径。
- 行尾由根 `.gitattributes` 统一：文本 LF，`.bat/.cmd/.ps1` CRLF；新增脚本必须遵守。
- CI 是三平台矩阵（ubuntu/windows/macos）；涉及平台分支的改动必须在三平台跑通。

### 提交前必跑

```bash
node scripts/check-eol.mjs          # 行尾策略
node scripts/check-clipboard.mjs    # 剪贴板单一入口
node scripts/check-duplication.mjs  # SHA-256 / 下载 / 批量勾选走单一实现
node scripts/run-tests.mjs scripts  # 执行器/脚本契约测试
```

Windows/macOS 相关逻辑必须补 fake platform 契约测试；无法本地真跑的，在 PR 里写清真机冒烟清单（见 `docs/cross-platform-audit.md` 第 7 节）。

## 复用与死代码

写新代码前先查已收口的实现（`util/`、`web/src/utils/`）。下面的规则只回答一件事：
**什么该复用、什么该新建、什么算死代码**。适用范围 `src/main/java` 与 `web/src`。

### 0. 一句话原则

**同一个概念，全仓只允许一处实现。** 判断标准不是「代码像不像」，而是：

> 如果这条规则明天变了（时区口径、空值语义、撤销时机、审批顺序），我需要改几个地方？

如果需要改两个以上，就是重复建设，必须在**这次**改动里收口。

### 1. 复用优先：先查，再写

写任何工具方法前，按顺序查三处：

| 顺序 | 位置 | 例子 |
| --- | --- | --- |
| 1 | `src/main/java/com/wenqu/ai/util/` | `Hashes`、`TextIo`、`FileNameSanitizer`、`SsrfGuard`、`UntrustedContent` |
| 2 | 同包已有类 | 沙箱路径判断用 `SandboxPaths.isSameOrChild` |
| 3 | `web/src/utils/` | `format`、`download`、`fileName`、`clipboard`、`token`、`useBatchSelect` |

#### 1.1 已提供的公共实现（禁止再造）

**后端**

| 能力 | 唯一入口 | 禁用写法 |
| --- | --- | --- |
| SHA-256 / 定长前缀 | `util.Hashes.sha256Hex` / `sha256HexPrefix` / `sha256HexSalted` | 内联 `MessageDigest.getInstance("SHA-256")` |
| 文本读写（显式 UTF-8） | `util.TextIo` | 单参 `Files.readString` / `writeString` |
| 文件名清洗 | `util.FileNameSanitizer` | 自行 replace 路径分隔符 |
| 出站 URL 安全 | `util.SsrfGuard` | 自行解析 host 判内网 |
| 外部内容标注 | `util.UntrustedContent` | 直接把网页/MCP 返回拼进提示词 |

**前端**

| 能力 | 唯一入口 | 禁用写法 |
| --- | --- | --- |
| 文件体积 | `utils/format.fmtSize` | 页面内重写 `n >= 1024 ? ...` |
| 表格时间（原始串截断） | `utils/format.fmtClock(ts, len, empty)` | `String(s).replace('T',' ').slice(0,16)` |
| 详情时间（本地化） | `utils/format.fmtDate` / `fmtDateTime` | 手拼 `getFullYear()` |
| 内存内容落盘 | `utils/download.downloadBlob` | `createObjectURL → a.click → revoke` |
| 批量勾选 | `utils/useBatchSelect` + `reportBatchResult` | 各页面写 `toggleBatchMode` 五件套 |
| 文件名清洗 | `utils/fileName.sanitizeFileName` | 自行正则 |

#### 1.2 两条硬约束

**（a）失败不要静默降级成弱实现。**

```java
// ✗ 摘要失败降级成 32 位 hashCode：碰撞概率从 2^-128 掉到 2^-32，调用方完全无感
} catch (Exception e) {
    return Integer.toHexString(text.hashCode());
}

// ✓ 硬失败：SHA-256 缺失说明运行环境已坏，继续跑只会产生弱指纹
throw new IllegalStateException("SHA-256 不可用", e);
```

**（b）空值语义必须显式命名，不能靠「大家都懂」。**

同一个 `str(Object)` 曾同时存在三种口径（`null` / `""` / `trim` 后 `""`），
直接把一个换成另一个是**行为变更**。做法：

- 需要 `null` 透传 → `str(...)`
- 需要空串兜底 → `strOrEmpty(...)`
- 需要 trim → 名字里带 trim，或调用点自己 `.trim()`

> 禁止「为了统一而统一」：两种语义若都被业务依赖，就保留两个**名字不同**的函数，而不是合并成一个。

#### 1.3 什么情况**应该**新写实现

- 语义确实不同（例如 `SandboxPaths.isSameOrChild` 是纯字符串前缀判定，`AgentxDestructiveCommands.outside`
  需要 `Path.normalize` + 设备/fd 识别——**刻意不合并**）
- 每个「工具族」一个 provider 的既定模式（`McpToolProvider` / `HttpApiToolProvider` / `WorkflowToolProvider`
  形状相同但各自绑定一个 `ToolFamily`，这是设计，不是重复）
- 分层服务（`DocumentVectorOps` ← `DocumentChunkService` ← `DocumentParsePipeline`）职责清晰时

判断口诀：**重复的是「同一份知识」，还是「同一个模式的实例」？** 前者收口，后者保留。

### 2. 死代码：什么算、怎么处置

#### 2.1 判定（按可靠性排序）

| 手段 | 可靠性 | 说明 |
| --- | --- | --- |
| 前端 import 图全扫 | 高 | `web/src` 中无人 import 且非入口 → 基本可判死 |
| 全仓 grep 类名/方法名（含 `.vue`/`.xml`/`.yml`/`.md`） | 高 | 排除反射后可信 |
| `jdeps` 字节码入向依赖 | **低，必须复核** | 同类内引用、同包引用它看不见，误报率高 |
| 「零引用方法」正则扫描 | **低，必须复核** | 重载、接口实现、Spring 回调全是误报 |

#### 2.2 不要误判为死代码的东西

以下都**不是**死代码，删了会出事：

- **Spring 组件**：`@Component` / `@Service` / `@RestController` / `@Configuration`，
  包括通过 `List<ToolGuard>`、`List<ToolFamilyProvider>` 这类集合注入被收集的（如 `UserStoppedGuard`）
- **`@Controller` 的所有端点**：前端可能尚未接线（见 2.4）
- **接口 + 单一实现**（如 `ChatEngine` / `TurnStateStore`）：是接缝，不是冗余
- **`[保留]` 标记的文件**：仓库里有一批文件头带 `// [保留] …勿删` 的显式标记，
  说明过为何保留。改之前先读标记理由
- **事件/序列化契约**：`RunEventType`、`run events` 契约、`config-schema.json` 的键

#### 2.3 移除流程

1. grep 全仓（含 `web/`、`src/main/resources`、`contract/`、`.md`）确认零引用
2. 若功能已被别处重写，**先确认新实现覆盖了旧的边界**（如 `MembersPage` 比 `InviteMemberModal`
   多「已失效邀请·最近 30 天」）
3. 删除后必须：`mvn compile` + `npm run build` + 前端单测
4. commit message 写清「该能力现由 X 承载」

#### 2.4 例外：后端已实现、前端无入口的端点

这类**不删也不留白**。二选一，且写进代码：

- 产品确定要做 → 保留，并在 controller 方法上加注释说明计划/责任人
- 确定不做 → 删端点，同时删 `web/src/api.js` 里对应导出

**禁止**长期处于「后端有、前端死导出」的中间态——下一个开发者会照着它再实现一遍。

### 3. 文档与实现一致

代码注释描述**当前行为**（细则见 `CONTRIBUTING.md · 代码注释规范`）。本规范额外要求：

**当某个类只在「旁路」上生效时，必须在类注释里写明主路径走的是谁。**

真实例子：`ApprovalGate` / `StepBudgetGuard` / `PermissionGuard` 三者挂在
`ToolPipelineFactory` 守卫链上，而全仓只有 `RunCodeSubCallRuntime`（`run_code` 子调用）
一处调用 `wrap`，该路径上 `approvalMode()` 恒为 `"off"`。若注释不写清楚，
读者会以为主对话轮的审批走这条链——**这比死代码更危险**，因为它看起来是活的。

写法模板：

```java
/**
 * <p>
 * <b>现状（勿误读）</b>：本类在唯一活路径 X 上恒放行；主路径的同类判定在 Y。
 */
```

### 4. 前端专项

#### 4.1 页面间共享的行为必须抽 composable

一个交互模式出现在第 **2** 个页面时就该抽，不要等到第 6 个。判定信号：
`toggleXxx`、`xxxMode`、五件套齐全、注释逐字相同。

```js
// 六个运营页共用
const { batchMode, selected, allChecked, someChecked, toggleSelect, toggleBatchMode, toggleAll }
  = useBatchSelect(selectableIds)
```

composable **不要**自己去取 `message` / `Modal` 全局实例，从参数注入，
否则 `web/test` 里没法验证（参见 `reportBatchResult(r, verb, unit, { message, Modal })`）。

#### 4.2 组件复制 vs 参数化

两个组件「只有一处字面量不同」→ 参数化，不要复制。

`KbIcon` / `AgentAvatar` 归一化后只差：一个 icon 组件、一个 CSS 类名、一处布尔强转。
现在两者都是 `EntityIcon` 的薄封装（只做字段翻译），解析口径只剩一份。

#### 4.3 两种时间格式化不通用

- `fmtClock`：后端原始串截断，**不做时区换算** → 用于列表、要可对齐可排序
- `fmtDate` / `fmtDateTime`：`toLocaleString`，**跟随本地时区** → 用于详情给用户看

两者不能互相替代；也不要在页面里另写第三个。

#### 4.4 抽取后必须补单测

抽 composable / 工具函数时，在 `web/test/` 加对应用例（参考 `useBatchSelect.test.mjs`），
把「退出批量清空勾选」「空列表不算全选」这类易回归的边界锁住。

### 5. 本节的提交前自检

| 改动 | 必须同步 |
| --- | --- |
| 新增工具方法 | 先确认 `util/`、`web/src/utils/` 无等价实现；有则复用 |
| 新增 composable / 抽公共组件 | `web/test/` 对应用例 |
| 删除类/组件/端点 | 全仓 grep 零引用证据 + 写清能力去向 |
| 改摘要/加密/令牌相关 | 重点检查是否引入静默降级 |
| `src/main/java/com/wenqu/ai/agent/tool/guard/` | 复核守卫链是否仍被 `wrap` 调用 |

```bash
mvn -o -DskipTests compile          # 后端编译
cd web && npm run build             # 前端构建
cd web && npm test                  # 前端单测（当前 649 例）
node scripts/check-duplication.mjs  # 复用守卫（本规范第 1 节的落地检查）
./scripts/ci-local.sh               # 按改动面挑跑全部闸门
```

#### 5.1 复用守卫

`scripts/check-duplication.mjs` 把本规范可机械检出的三条落成 CI 闸门，已接入
`.github/workflows/ci.yml` 与 `scripts/ci-local.sh`：

| 规则 | 拦截写法 | 正确入口 |
| --- | --- | --- |
| `sha256` | 内联 `MessageDigest.getInstance("SHA-256")` | `util.Hashes` |
| `download-blob` | `URL.createObjectURL` 后造 `<a download>` 并 click | `utils/download.downloadBlob` |
| `batch-select` | 页面内声明 `allChecked` | `utils/useBatchSelect` |

**确属特例时**：在该规则 `allow` 列表登记文件路径，并**写明为什么这次不合并**。
已登记的特例本身就是文档（如 `sandboxIdForThread` 的派生 ID 必须与既有数据保持稳定）。

### 6. 审查清单（Code Review 用）

- [ ] 这个工具函数是不是抄来的？查过 `util/` 和 `utils/` 了吗？
- [ ] 两个实现的两个变体，是不是只差一个参数？
- [ ] 失败分支里有没有 `return "unknown"` / `hashCode()` / 空集合 这类静默降级？
- [ ] 空值语义是 `null` 还是 `""`？改调用方会变行为吗？
- [ ] 新类的注释里，「谁在调用我、我在主路径还是旁路」写清楚了吗？
- [ ] 删除的东西，确认过不是 Spring 收集、不是接口接缝、不是 `[保留]` 的吗？
- [ ] 新增/合并的行为，有没有对应单测？

## 代码注释规范

注释只描述**当前行为**：它是什么、为什么这样、改了会怎样，用现在时陈述。演进历史由 git 记录承载，代码与文档里都不留过程性叙述。具体禁忌：

- **不写变更过程**：不出现「原先 / 此前 / 曾经 / 早期版本 / 后来改为 / 现已调整为」这类时间线措辞
- **不写退役史**：不出现「已退役 / 已停用（指字段或机制）/ 不再提供 / 已删除 / 下一版本删除 / 仅为回滚窗口保留」；能力没了就把相关注释一并改掉，只写现状
- **不写版本·批次·计划编号**：不出现 `Phase 4`、`P1 · 3B`、`批次 5`、`§3.4`、`2026-10-22 对齐 DSH` 等只对当时排期有意义的标记；结论本身有价值时去掉编号直述结论
- **不写调试·标定过程**：不出现「实测 / 真机现场 / 先按 A 试过又按 B 试过」的探索叙事；标定得出的**结论值与依据**保留，过程删掉
- 判定标准：把注释逐句问一遍「读代码的人不需要知道这段历史也能理解现状」——是，则删；历史解释不了当前行为，它属于 commit message

## 报 Issue

- **Bug**：附部署方式（Compose / 裸机）、版本或 commit、复现步骤、相关日志片段
- **功能建议**：先讲场景再说方案；大特性建议先开 Issue 讨论再动手
