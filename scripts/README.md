# 问渠 WenQu · scripts 脚本目录

本目录集中存放问渠的开发 / 运维脚本，按用途分目录管理，并通过 `wenqu.sh` 统一调度。

## 目录结构

| 目录 | 内容 | 运行方式 |
|---|---|---|
| `db/` | SQL：库初始化、schema 修复、数据迁移、配置清理 | `mysql -u root -p <db> --force < scripts/db/xxx.sql` |
| `services/` | 独立运行的服务 / 测试进程（MCP 测试服务、假上游） | `python3 scripts/services/xxx.py` |
| `codegen/` | Node 代码生成 / 一致性校验 | `node scripts/codegen/xxx.js` |
| `setup/` | 环境初始化（Ubuntu 安装 Node） | `sudo bash scripts/setup/setup-node.sh` |
| `loadtest/` | 压测（mock 网关 + 并发脚本） | `node scripts/loadtest/mock-gateway.mjs` 等 |
| `mac/` | macOS 启动器（开发栈、后端 jar） | `bash scripts/mac/xxx.sh` |
| `win/` | Windows 启动器（后端 jar） | `scripts\win\xxx.bat` |

## 统一调度器 `wenqu.sh`

无需记路径，用调度器列出与运行任意脚本（自动选解释器）：

```bash
./wenqu.sh                          # 打印全部分组与脚本清单
./wenqu.sh <group>                  # 列出某分组下的脚本
./wenqu.sh run <name> [args..]      # 按文件名（任意子目录）查找并运行
./wenqu.sh <group> <name> [args..]  # 在指定分组下运行
```

示例：
```bash
./wenqu.sh                          # 看全貌
./wenqu.sh run gen_config_schema.js # 跑配置一致性校验
./wenqu.sh mac dev-stack status    # 看本机开发栈状态
```

## 中间件管理 `infra`

`wenqu.sh infra` 统一管理问渠的全部中间件，覆盖启停与探活，无需记各自的 brew / docker 命令：

```bash
./wenqu.sh infra                          # 列出所有中间件状态(端口/运行/启停命令)
./wenqu.sh infra status [name|all]        # 仅打印状态
./wenqu.sh infra up   <name|all>          # 启动
./wenqu.sh infra down <name|all>          # 停止
./wenqu.sh infra restart <name|all>       # 重启
./wenqu.sh infra logs <name>              # 跟踪日志(单次阻塞)
```

中间件清单：

| 中间件 | 端口 | 类型 | 启停 |
|---|---|---|---|
| `mysql` | 3306 | brew `mysql@8.0` | `infra up/down mysql` |
| `redis` | 6379 | brew `redis` | `infra up/down redis` |
| `meili` | 7700 | docker `ai-doc-meilisearch` | `infra up/down meili` |
| `searxng` | 8888 | docker compose | `infra up/down searxng` |
| `sandbox` | 8002 | docker compose | `infra up/down sandbox` |
| `ocr` | 30011 | docker compose（MinerU） | `infra up/down ocr` |

示例：
```bash
./wenqu.sh infra                          # 看 6 个中间件当前是否运行
./wenqu.sh infra up sandbox ocr           # 拉起代码沙盒与 OCR
./wenqu.sh infra down sandbox             # 停掉沙盒(不影响其他)
./wenqu.sh infra restart meili            # 重启 Meilisearch
./wenqu.sh infra logs searxng             # 跟踪 SearXNG 日志
```

> 说明：本机 MySQL/Redis 走 Homebrew，Meili/SearXNG/Sandbox/OCR 走 Docker。
> `up` 默认只列状态、需显式指定才操作，不会误动正在运行的服务。
> `sandbox`/`ocr` 首次 `up` 需先拉好镜像（本机 docker.io 不通时走 daocloud 源）。

> 解释器映射：`.sh → bash`、`.py → python3`、`.js/.mjs → node`、`.bat` 仅 Windows、
> `.sql` 打印 `mysql` 执行命令（不直接执行，需连库）。

## 各脚本说明

### db/（SQL）
- `init-admin-2026-09-17.sql` — 初始化管理员账号
- `fix-schema-2026-09-16.sql` — 补齐早期 schema（表/列）
- `fix-schema-2026-09-16-auth.sql` — 补齐 auth 相关 schema
- `fix-schema-2026-10-12-tool-approval-text.sql` — 修复 tool_approval 文本字段
- `migrate-reasoning-levels-2026-10-03.sql` — 迁移 reasoning 级别
- `cleanup-personal-model-refs-2026-10-03.sql` — 清理个人模型引用
- `cleanup-deep-reasoning-switches-2026-10-03.sql` — 清理深度推理开关
- `cleanup-retired-config-2026-09-30.sql` — 清理已废弃配置

### services/（Python，独立进程）
- `mcp-test-server.py` — 最小 MCP 测试服务（:8931），验证 MCP 外部工具接入
- `fake_upstream_no_tools.py` — 假上游（:7999），模拟「上游拒 tools」用于换路降级实测

### codegen/（Node）
- `gen_config_schema.js` — 配置字段 ↔ `ConfigService.defaults()` 一致性校验

### setup/
- `setup-node.sh` — Ubuntu 安装 / 升级 Node（满足前端依赖链）

### loadtest/
- `mock-gateway.mjs` — 压测 mock 网关（:9101）
- `loadtest.mjs` — 100 路并发压测

### mac/
- `dev-stack.sh` — 本机开发栈统一启停：`mysql` / `redis` / `meili` / `searxng` / `sandbox`
  （`mysql`/`redis` 仍走 brew/原生实例——数据在那儿；`meili`/`searxng`/`sandbox` 走 `deploy/`
  下的 compose，meili 索引在命名卷 `wenqu-meili-data`）
- `start-backend.sh` — 启动后端 jar（`target/wenqu.jar`）

### win/
- `start-backend.bat` — 启动后端 jar（Windows）

## 说明：重排与 OCR 的部署方式

按线上发布口径，本地不再保留重排 / OCR 的「本地部署脚本」：

- **重排（rerank）**：由外部 OpenAI 兼容 `/v1/rerank` 服务承载——在设置页配置 `rerank.baseUrl`
  指向可用服务，或绑定供应商重排模型。无本地启动脚本。
- **OCR（MinerU）**：走 `deploy/ocr/docker-compose.yml` 的 docker 部署
  （`docker compose -f deploy/ocr/docker-compose.yml up -d --build`），不再有 `scripts/` 下的本地启动器。

`dev-stack.sh` 也不再管理 `ocr` 单元。
