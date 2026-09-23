# 问渠 WenQu

> 问渠那得清如许，为有源头活水来。

问渠（WenQu）是一个通用智能体平台：模型由统一的模型供应商管理接入，智能体在其之上挂载工具、技能与知识库，并在沙盒中执行任务；平台侧提供会话与工作区文件、定时任务、检索评测，并支持 Web 与 CLI / OIDC 两类接入。本仓库只含两个工程及配套脚本：

| 目录 | 说明 |
|------|------|
| `wenqu-server/` | 服务端：Java 17 + Spring Boot 3 + Spring AI 1.1.8 + MyBatis-Plus，端口 `8095`，`servlet.context-path` = `/v2` |
| `wenqu-web/` | 前端：Vite + Vue 3 单页应用（包管理器 pnpm 11.24.0） |
| `scripts/` | 本地配套：`start-minio.sh`（本地 MinIO 对象存储）、`rerank_server.py`（本地重排服务，供知识库 rerank 模型对接） |

## 服务端

- 设计基线：[`wenqu-server/DESIGN.md`](wenqu-server/DESIGN.md)
- 移植进度与遗留项：[`wenqu-server/PORTING_CHECKLIST.md`](wenqu-server/PORTING_CHECKLIST.md)

代码分层（均在 `com.wisesoft.wenqu` 下）：

| 包 | 职责 |
|------|------|
| `controller/` | HTTP 边界：参数校验、鉴权上下文、响应装配 |
| `service/` | 用例编排：文档导入、检索、问答链路、知识库管理 |
| `repository/` | 持久化边界，SQL 只出现在 MyBatis-Plus Mapper 里 |
| `agents/` | 智能体编排（Spring AI Alibaba Agent Framework + StateGraph） |
| `knowledge/` | 知识库与检索链路（向量 + 关键词加权、重排、引用溯源） |
| `config/` `common/` `dto/` `models/` `permissions/` `storage/` `workspace/` | 基础设施装配、统一响应、传输对象、权限解析、对象存储、工作区访问 |

外部依赖：MySQL（库 `wenqu`，建表脚本 [`wenqu-server/src/main/resources/db/schema-mysql.sql`](wenqu-server/src/main/resources/db/schema-mysql.sql)，**不随启动自动执行**）、Redis Stack（向量索引）、MinIO（对象存储）、沙盒 provisioner（[`wenqu-server/deploy/sandbox-provisioner/`](wenqu-server/deploy/sandbox-provisioner/)）。

### 编译与启动

本机没有 maven 时，用 [`wenqu-server/build.sh`](wenqu-server/build.sh) 代替 `mvn compile`（javac + `~/.m2` 下的依赖 jar）。运行必需的环境变量：

```
SERVER_PORT  WENQU_JWT_SECRET  JWT_SECRET_KEY  WENQU_INSTANCE_ID
SANDBOX_PROVISIONER_TOKEN  API_KEY_DERIVATION_SECRET  MINIO_*
```

`build.sh` 里的变量清单与 IDEA 的 `WenquServerApplication` Run Configuration 同源，改一处必须同步另一处，否则 IDEA 启动会崩。

两个启动类：`WenquServerApplication`（API）与 `WenquWorkerApplication`（后台 worker，`WebApplicationType.NONE`，不占端口）。

## 前端

```bash
cd wenqu-web
pnpm install
pnpm dev        # 开发服务器
pnpm build      # 生产构建，产物 dist/
pnpm test:unit
```

请求统一发往 `/api/...` 前缀，由 vite 代理转发；默认目标为容器名 `http://api:5050`，直连本机服务端时用 `VITE_API_URL` 覆盖（注意服务端 context-path 是 `/v2`）。代理与 rewrite 规则见 `wenqu-web/vite.config.js`，前端约定补充见 `wenqu-web/AGENTS.md`。
