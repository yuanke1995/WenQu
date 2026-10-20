# 问渠 WenQu · scripts 脚本目录

本目录集中存放问渠的开发 / 运维脚本，按用途分目录管理，并通过 `wenqu.sh` 统一调度。

## 目录结构

| 目录 | 内容 | 运行方式 |
|---|---|---|
| `redis/` | Redis / Meilisearch 侧的一次性迁移 | `bash scripts/redis/xxx.sh` |
| `services/` | 独立运行的服务 / 测试进程（MCP 测试服务） | `python3 scripts/services/xxx.py` |
| `codegen/` | Node 代码生成 / 一致性校验 | `node scripts/codegen/xxx.js` |
| `loadtest/` | 压测（mock 网关 + 并发脚本） | `node scripts/loadtest/mock-gateway.mjs` 等 |
| `mac/` | macOS 启动器（开发栈、后端 jar） | `bash scripts/mac/xxx.sh` |
| `win/` | Windows 启动器（后端 jar） | `scripts\win\xxx.bat` |

> 这里不放一次性 SQL（库初始化 / schema 修复 / 数据迁移 / 配置清理）：建表与预置数据以
> `src/main/resources/schema.sql` 为准，运行时数据变更走应用启动逻辑或管理界面。

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
| `meili` | 7700 | docker `wenqu-meilisearch` | `infra up/down meili` |
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

> 解释器映射：`.sh → bash`、`.py → python3`、`.js/.mjs → node`、`.bat` 仅 Windows。

## 各脚本说明

### redis/（Redis / Meilisearch 侧一次性迁移）
- `rename-ai-doc-indexes-2026-10-17.sh` — 把存量索引名从 `ai-doc-*` 收到 `wenqu-*`：Meilisearch 关键词索引 PATCH 改名、
  Redis 各库旧向量索引在「新索引文档数已齐平」后 `FT.DROPINDEX`（不带 DD，向量数据不动）。默认只报告，`--apply` 才动手；
  退役的全局索引 `ai-doc-index` 要连数据删需另加 `--purge-global-index`

### services/（Python，独立进程）
- `mcp-test-server.py` — 最小 MCP 测试服务（:8931），验证 MCP 外部工具接入

### codegen/（Node）
- `gen_config_schema.js` — 配置字段 ↔ `ConfigService.defaults()` 一致性校验

### loadtest/
- `mock-gateway.mjs` — 压测 mock 网关（:9101）
- `loadtest.mjs` — 100 路并发压测；跑完默认自动收尾（删压测用户/网关，并直连本机 MySQL 清掉
  自己写的会话、消息与用量台账），`KEEP=1` 保留现场

### mac/
- `dev-stack.sh` — 本机开发栈统一启停：`mysql` / `redis` / `meili` / `searxng` / `sandbox`
  （`mysql`/`redis` 仍走 brew/原生实例——数据在那儿；`meili`/`searxng`/`sandbox` 走 `deploy/`
  下的 compose，meili 索引在命名卷 `wenqu-meili-data`）
- `start-backend.sh` — 启动后端 jar（`target/wenqu.jar`）

### win/
- `start-backend.bat` — 启动后端 jar（Windows）

## 说明：重排与 OCR 的部署方式

重排与 OCR 按线上发布口径部署，`scripts/` 下没有它们的本地启动器：

- **重排（rerank）**：由外部 OpenAI 兼容 `/v1/rerank` 服务承载——在设置页配置 `rerank.baseUrl`
  指向可用服务，或绑定供应商重排模型。
- **OCR（MinerU）**：走 `deploy/ocr/docker-compose.yml` 的 docker 部署
  （`docker compose -f deploy/ocr/docker-compose.yml up -d --build`）。
