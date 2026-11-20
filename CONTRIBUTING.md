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
node scripts/run-tests.mjs scripts  # 执行器/脚本契约测试
```

Windows/macOS 相关逻辑必须补 fake platform 契约测试；无法本地真跑的，在 PR 里写清真机冒烟清单（见 `docs/cross-platform-audit.md` 第 7 节）。

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
