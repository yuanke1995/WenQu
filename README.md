# 问渠 WenQu · AI 智能体工作台

> **问渠那得清如许，为有源头活水来。** —— 答案，自有源头。
>
> 中文名：**问渠** ｜ 英文名：**WenQu** ｜ 英文 slogan：*Ask the source.*

## 产品简介

问渠是一款以智能体为核心的 AI 工作台，基于 Spring AI 构建，支持私有化部署。它将大模型、知识库与工具统一组织与编排，覆盖「智能对话 → 知识检索 → 工具执行 → 产物交付 → 对外能力开放」的完整链路，适用于知识管理、内部智能问答、智能体应用搭建与 AI 能力开放等场景。

- **智能体优先**：以智能体（人设 / 知识范围 / 工具集）为核心组织能力，支持智能体按需委派与可视化工作流
- **长任务可控**：计划卡只放目标与前三步、完整计划与版本轨进右侧抽屉，执行中可带意见调整；目标模式由输入框顶部目标条驱动自动续跑与证据核验，插话让位后自动接续
- **答案可溯源**：混合检索 + 引用溯源 + 位置级文档原图展示，每个结论都能回到源头；低分全灭时保留唯一最相关块并显式标注低置信
- **开放互联**：MCP 双向（接入外部 Server / 对外发布为 MCP Server）、OpenAI 兼容端点（Chat Completions / Responses / 只读 function calling）、智能体轮终回调、工作流开放接口与 Webhook、游客分享页
- **生产就绪**：多租户生命周期与邀请流、OIDC / SAML / SCIM / LDAP 企业身份、RBAC、密钥加密存储、接口限流、哈希链审计、推理配额、内容安全、系统巡检、邮件与 Web Push 通知、多副本水平扩展

### 核心能力一览

| 能力域 | 说明 |
|--------|------|
| 智能对话 | SSE 流式回答、随时停止与断线重连接流、运行中插话、句末 `[N]` 引用溯源、深度思考（思考强度五档）、上下文容量计量与手动/自动压缩、正文与过程分离（过程块可展开）、图片与文件多模态输入、`@` 引用资料与 `#` 历史引用、回答末尾下一步建议 |
| 长任务执行 | 计划模式（计划卡「目标 + 前三步 + 卡脚」、完整计划与版本轨右侧抽屉、同轮按意见升版、执行中调整与血缘保留）；目标模式（输入区目标条、自动续跑、暂停/继续/取消、插话让位接续、终态卡留痕、证据逐条核验）；四项硬预算与软阈值收敛、任务清单步骤级进度、工具执行确认与智能体提问卡（人在回路） |
| 知识库（RAG） | docx / pdf / xlsx / ppt / pptx / txt / md / csv 解析与网页导入（扫描件 OCR）、混合检索（向量 + 中文分词词面）+ GraphRAG 图谱扩展、父子分块与问答对增强、网页定时刷新、文档版本与回收站、知识块预览与单块编辑、检索低分保底 |
| 智能体 | 人设 / 知识范围 / 能力开关可配、会话级不锁定、按需委派（跨主题问题派给职责对口助手）、子智能体两种执行形态、配置历史版本回滚、公开发布与 MCP 端点、定时执行、三级记忆域、HTTP 工具集、工作流工具、轮终回调、发布前评测门禁 |
| 工作区 | 你电脑上的一个或多个真实文件夹 + 一份工作区记忆；文件只在本机（本机执行器读写，服务器不存副本），支持多目录、单层浏览 / 预览 / 下载 / 在电脑上打开 / 应用内编辑；会话级权限档位（仅可查看 / 工作区内修改 / 完全权限）、图标与颜色、会话与自动化在侧栏按工作区归集、记忆隔离；事项清单（记下 → 去聊 → 勾掉）与模板复制（复制事项、记忆骨架、目录别名，不带文件/会话） |
| 工具与沙盒 | 人在回路提问 + 联网搜索（Tavily / 博查 / SearXNG）+ 技能包 + MCP 工具 + OpenAPI 导入的 HTTP 工具 + 已发布工作流工具 + 本机浏览器；Docker 沙盒隔离执行命令与读写文件（输出实时回传）、后台进程查看与停止、脚本批量执行；产物与沙盒文件整屏预览台 |
| 可视化工作流 | DSL 唯一真源 + StateGraph 执行引擎、13 类节点（含技能节点与 MCP 工具节点）、画布编辑器与节点级 trace 回放、版本发布 / 回滚、API 触发、Webhook 触发、cron 定时触发、终态回调、运行实时流；游客可跑只读工作流 |
| 技能与市场 | 技能三层来源（内置 / 我的 / 工作区）、zip 与 URL 安装、平台与个人两级停用；团队内市场分发技能、应用模板与工作流，安装即复制并提示「有更新」 |
| MCP 双向 | 接入任意外部 MCP Server（请求头凭据加密、JSON 粘贴导入、工具逐条命名、定时健康巡检）；智能体发布为 MCP 端点，平台级 MCP 入口对外提供四项工具；入站与出站调用统一审计 |
| 多租户与企业身份 | 租户开通 / 启停 / 归档 / 恢复、成员归属、租户品牌、邀请注册、首登引导、自助开通申请；OIDC / SAML / SCIM / LDAP 认证源、组→角色 / 部门映射、目录同步与同步日志、服务账号 |
| 安全与治理 | RBAC 菜单与接口两级管控、API Key（仅存哈希）、接口限流、数据按归属与租户隔离；内容安全输入 / 输出双闸；推理配额（平台 / 部门 / 成员三档）；哈希链审计、合规导出与链校验；会话治理（强制下线）与平台公告 |
| 开放接入 | OpenAI 兼容 Chat Completions / Responses（真增量流式、usage、只读 function calling）、外部工作流 API 与 Webhook、智能体轮终 Webhook（HMAC 签名 + 持久重试）、MCP 端点、CORS 白名单 |
| 运维保障 | 系统巡检（数据库 / Redis / 关键词引擎 / 磁盘 / 审计链 / 供应商 / 登录安全态势）、巡检历史与趋势、失败告警；定时任务运行状态与执行日志 |
| 运营闭环 | 个人使用统计（Token 热力图 / 趋势 / 模型用量 / 用途分项 / 累计费用）、数据看板（无命中率、热门与无命中问题、差评回流、知识缺口补块、执行 Trace、采样池、费用）、检索量化评估（recall@k / MRR / 忠实度与相关性评分）、检索链路分步调试、智能体回归评测与发布门禁 |
| 通知与可感知 | 站内通知中心覆盖解析、工作流、网页源刷新、定时任务、审批等待、模型额度等异步事件，未读计数、按类型静音、铃铛与列表双入口；邮件外发（SMTP、投递台账、个人偏好）与浏览器 Web Push |
| 长期记忆 | 问答后异步提炼用户画像与事实、跨会话注入、按工作区隔离；智能体可选用户级 / 专属 / 叠加三档记忆域；个人设置提供记忆查看、编辑与清空，平台另有总闸 |
| 终端形态 | 电脑端工作台与移动端原生壳（触屏窄屏自动切换），人在回路卡片、状态与来源、导出与分享在两端口径一致 |

## 系统架构

```
浏览器（Vue 3 单页应用 · 电脑端工作台 / 移动端原生壳）
  │  REST + SSE · /api/ai/*
  ▼
问渠服务端（Java 17 · Spring Boot 3.5 · Spring AI）
  ├─ 问答管线：混合检索 → 重排 → 上下文组装 → 流式生成 → 内容安全出口闸
  ├─ 智能体编排：按需委派、计划模式、目标模式（证据核验 + 预算闸）、人在回路审批
  ├─ 工作流引擎：DSL → StateGraph 翻译执行 + 节点级 trace
  ├─ 开放接入：OpenAI 兼容端点 · 外部工作流 API/Webhook · 智能体轮终回调 · MCP 双向
  ├─ 企业治理：多租户 · OIDC/SAML/SCIM/LDAP · 服务账号 · 推理配额 · 哈希链审计 · 内容安全
  ├─ 沙盒调度 · 目标调度 · 定时调度 · 站内通知 · 邮件外发 · Web Push · 系统巡检 · 市场分发 · 配置中心 · RBAC · 限流
  │
  ├─ MySQL …… 业务数据（81 张 c_ai_* 表，启动自动建表）
  ├─ Redis Stack ………… 向量索引 / 框架会话状态 / 配置变更广播（pub/sub）/ 分布式锁与限频 / 分享额度
  ├─ Meilisearch ……… 关键词引擎（可选，默认 MySQL LIKE，零依赖）
  ├─ Docker 沙盒 ……… provisioner + 隔离容器执行环境
  ├─ SMTP ………………… 通知外发（按个人偏好投递，失败退避重试）
  └─ 模型网关 …………… OpenAI 兼容供应商自登记（DeepSeek / GLM / Qwen / Kimi / …）

对外开放：/join/{token} 邀请注册 · /apply 租户开通申请 · /s/{token} 游客分享 · /shared/{token} 对话只读分享
          /ai/mcp/{token} MCP 端点 · /ai/mcp 平台入口 · /api/ai/v1/chat/completions · /api/ai/v1/responses
          /api/ai/v1/workflows/{id}/run · /api/ai/v1/workflows/webhook/{token}
```

### 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8（`spring-ai-bom` 统一版本）+ MCP Java SDK 0.18.3 |
| ORM | MyBatis-Plus 3.5.12（逻辑删除 `deleted`，驼峰映射） |
| 数据库 | MySQL 8 等 MySQL 协议兼容库（驱动即 MySQL Connector/J，无任何数据库专有逻辑；内置 compose 提供 MySQL，生产环境亦可指向外部 MySQL；库名 `wenqu_ai` 经 `DB_NAME` 调整）；81 张 `c_ai_*` 表由启动自动建立 |
| 向量库 | Redis Stack（RediSearch 向量索引，Jedis 7.4.1 客户端，每个知识库独立索引；连接地址经 `REDIS_HOST`/`REDIS_PORT` 注入，默认 `127.0.0.1:6379`） |
| LLM | 聊天 / 视觉 / 向量 / 重排 / OCR / 语音 / 全模态 / 其他八类模型可跨厂商热切换（DeepSeek / 智谱 GLM / 阿里百炼 Qwen / Kimi / 豆包 / 腾讯混元 / 百度千帆 / MiniMax / SiliconFlow / Ollama 等 OpenAI 兼容网关预设，模型经「供应商表 + 引用」登记绑定，**无全局兜底**——聊天模型缺失或引用无效会 fail-loud 引导选择；全模态模型按对话模型同等可用） |
| 智能体 / 问答引擎 | **AgentScope 2.1.0-BETA1**（`io.agentscope:agentscope-harness` 的 `HarnessAgent` 与常驻 `AgentSession`：推理循环、工具派发、会话状态、上下文压缩、权限档位、子智能体、人在回路、计划模式、断线续跑全部由框架原生接管，单一引擎恒走框架）。问渠领域工具与语义经适配层（Spring AI ToolCallback → AgentScope 一等工具）注入；会话状态存 Redis，过程事件写宿主事件日志并按代次轮换 |
| 工作流引擎 | **Spring AI Alibaba Agent Framework 1.1.2.3** 的 graph 模块（`com.alibaba.cloud.ai.graph.StateGraph`）：DSL → StateGraph 的唯一翻译层（`WorkflowEngine`），负责节点装配、条件边、checkpoint、人在回路续跑、loop 回边闸。执行框架与智能体问答引擎（AgentScope）互相独立；子智能体节点运行时复用同一问答管线 |
| 沙盒 | 独立 provisioner 服务（Python + FastAPI + Docker SDK，容器化部署）+ 全功能沙盒镜像（all-in-one-sandbox）；模型经 Function Calling 在隔离容器内执行命令、读写文件，命令输出实时回传 |
| 文档解析 | Apache POI 5.5.1（docx / xlsx / ppt / pptx，含旧版 OLE2 容器）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ jsoup 1.15.4（网页正文提取）+ 原生流（txt/md/csv）+ jieba-analysis 1.0.2（中文分词） |
| 计量 / 序列化 / 文档 | jtokkit 1.1.0（cl100k BPE token 计数）；fastjson2 2.0.31；springdoc-openapi 2.8.8（Swagger UI，生产默认关） |
| 安全 | RSA 加密落库模型 API Key、MCP 请求头、HTTP 工具凭据与认证源密钥（`ConfigCryptoService`）+ SHA-256 哈希签发对外 API Key 与服务账号令牌 + 图片与工作区文件 HMAC 签名 URL + PBKDF2 密码哈希 + JWT 登录令牌 + 会话代数强制下线 + 审计哈希链 |
| 身份与通知 | OIDC 授权码单点登录 + SAML 2.0（`spring-security-saml2-service-provider`）+ SCIM 2.0 入站（Entra / Okta 推送用户与组）+ LDAP 目录同步；邮件外发 `spring-boot-starter-mail`（SMTP，失败退避重试）+ Web Push（`webpush`，VAPID 密钥走环境变量） |
| 前端 | Vue ^3.4 + Vite ^5 + Ant Design Vue ^4.2 + markdown-it ^15 / DOMPurify ^3.4 / highlight.js ^11 + echarts 6.1 + mermaid ^11.17 + @vue-flow/core 1.48.2（工作流画布，锁版本）；视图全部懒加载按页分包（`.nvmrc` 固定 Node **22** LTS，依赖链要求 ≥22.12 或 20.19+） |

## 快速开始

### 环境要求

- JDK 17、Node.js ≥22.12（或 20.19+；`.nvmrc` 固定 22 LTS，旧版本会构建失败）
- MySQL 协议数据库（MySQL 8）
- Redis Stack（向量索引 + 框架会话状态，**硬依赖**）
- Docker（可选：沙盒、Docker Compose 一键部署）

### 1. 准备基础设施

**Redis Stack**（RediSearch 向量索引）：

```bash
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest
```

> 宿主端口按实际部署自行映射，后端以 `REDIS_HOST`/`REDIS_PORT` 指向该端口（docker-compose 的映射见 `docker-compose.yml`）。RedisVectorStore 自动配置使用 **Jedis** 客户端，工作区已引入 `redis.clients:jedis` 且 `spring.data.redis.client-type: jedis`。Redis 同时承载框架会话状态，连不上时应用启动即失败，不做降级运行。

**数据库**：库需预先存在（库名按环境配置，默认 `wenqu_ai`）。表结构由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全；存量库缺列缺索引由 `SchemaMigrator` 启动期自动补齐）；也可手动执行：

```bash
mysql -h<db-host> -P<db-port> -uroot -p wenqu_ai < src/main/resources/schema.sql
```

**模型供应商**（聊天 / 视觉 / 向量 / 重排 / OCR / 语音 / 全模态 / 其他八类模型，含图片描述、扫描件 OCR 与图谱抽取）：不需要任何本地部署——在「智能体 → 模型供应商」登记 OpenAI 兼容网关（网关地址 / API Key / 补全与向量路径），按类型登记模型（对话类需声明上下文窗口与最大输出；可登记输入/输出单价用于费用统计）后**按归属绑定**：聊天模型在对话页（会话级切换）与「个人设置 → 默认模型」选择；知识库编辑里绑定向量（必选）/ 重排 / 视觉 / OCR / 图谱抽取模型；平台默认重排模型与评估评判模型在系统设置登记（API Key RSA 加密入库）。个人供应商只有登记人自己可见可用。若本机装了 Ollama，也可按「本地网关」供应商登记使用，但非必需。

**沙盒 provisioner**（可选，隔离执行环境）：

```bash
bash deploy/sandbox-provisioner/run.sh docker-up      # 构建 + 启动（127.0.0.1:8002，真沙盒）
bash deploy/sandbox-provisioner/run.sh status         # 状态 + 健康探测
bash deploy/sandbox-provisioner/run.sh docker-down    # 停止
bash deploy/sandbox-provisioner/run.sh docker-logs    # 跟踪日志
```

> 需要 Docker Desktop；首次 `docker-up` 会构建 provisioner 镜像并拉取沙盒运行时镜像（约 12GB，之后免拉取）。服务器沙盒只服务「会话」栏与平台内部轮次：持久卷在 `user-data/shared/{uid}/workspace/`（bind 给容器内 `/home/gem/user-data`），会话目录按 `sessions/{会话id}` 隔离；**工作区的文件不走服务器沙盒**，由用户电脑上的执行器直接读写本机目录；用户自装技能在宿主 `user-data/skills-users/{uid}/`（跨会话、不依赖沙箱）；助手在对话里沉淀的技能写当前会话的沙盒工作区。provisioner 访问令牌默认 `wenqu-local-dev-sandbox-provisioner-token`（compose 默认值，应用侧用环境变量 `SANDBOX_PROVISIONER_TOKEN` 配同一个值，≥32 字符）。沙盒运行时镜像如需 JDK/Node 等依赖，用 `sandbox-image/Dockerfile` 派生新镜像并在 `.env` 设 `SANDBOX_IMAGE`（容器内无 sudo，依赖必须烧进镜像层）。

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码

# ===== 基础设施连接（有默认值，按部署环境调整）=====
export DB_HOST=127.0.0.1                  # 数据库主机（容器部署默认 mysql；外部 MySQL 改为实际地址）
export DB_PORT=3306                       # 数据库端口
export DB_NAME=wenqu_ai                   # 库名
export DB_USERNAME=root                   # 用户名
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379                    # Redis 端口（按实际部署调整，容器化部署见 docker-compose）
export REDIS_PASSWORD=                    # Redis 密码（默认空）
export REDIS_DB=0
export AI_IMAGES_DIR=./data               # 数据落盘目录（跨平台兜底；生产容器内为 /app/data）

# ===== 安全 =====
export AI_JWT_SECRET=xxx                  # 登录令牌签名密钥（≥32 位随机串；多副本必须一致，非 local profile 缺失即启动失败）
export AI_SECRETS_DIR=./data/secret       # 密钥目录（默认 {AI_IMAGES_DIR}/secret），放版本化 PEM 私钥 rsa/vN.key

# ===== 仅 env/yml（不进设置页）=====
export AI_AGENTSCOPE_STATE_DIR=./user-data/agentscope   # 框架工作区（个人记忆 MEMORY.md 与日账、plans、会话事件日志归档）；容器部署需指向挂载卷，多副本需共享
export WENQU_SKILLS_USERS_DIR=./user-data/skills-users  # 用户自装技能的宿主目录
export AI_MEILI_KEY=xxx                   # Meilisearch master key（compose 部署必填：meilisearch 容器以它初始化；app 侧同值可在设置页「检索设置 → 关键词引擎」配）
export AI_MEILI_INDEX=wenqu-chunks        # 关键词索引名（只从 env/yml 读，永不入库）
export LOG_DIR=./data/logs                # 应用日志目录（容器部署需指向挂载卷内，否则容器重建后丢失）
export LOG_LEVEL_APP=info                 # 应用日志级别
export LOG_LEVEL_SPRING_AI=info           # Spring AI 日志级别
export MYBATIS_LOG_IMPL=org.apache.ibatis.logging.slf4j.Slf4jImpl
export ACTUATOR_HEALTH_DETAILS=never      # /actuator/health 详情级别

# ===== 沙盒 / OCR 等部署连接（不进设置页）=====
export SANDBOX_PROVISIONER_URL=http://127.0.0.1:8002
export SANDBOX_PROVISIONER_TOKEN=          # 与 provisioner 侧一致，≥32 字符
export SANDBOX_CALLBACK_BASE_URL=http://host.docker.internal:8090/ai   # 沙盒脚本回程调用本服务的地址，须容器侧可达
export SANDBOX_VIRTUAL_PATH_PREFIX=/home/gem/user-data
export OCR_PP_STRUCTURE_URI=               # PP-StructureV3 服务地址；空=不用
export OCR_MINERU_URI=                     # MinerU 服务地址；空=不用
export OCR_MINERU_BACKEND=pipeline         # pipeline / hybrid-auto-engine
export OCR_TIMEOUT_MS=600000
export SPRINGDOC_ENABLED=false            # Swagger/OpenAPI 开关（默认关，接口契约不外泄；本地调试可置 true）

# ===== Web Push（可选，浏览器系统级通知）=====
export AI_VAPID_PUBLIC_KEY=               # VAPID 公钥；空=功能不可用
export AI_VAPID_PRIVATE_KEY=              # VAPID 私钥；多副本必须同一对（不进 DB、不进设置页）
export AI_VAPID_SUBJECT=mailto:admin@wenqu.local  # 推送服务要求的联系标识
```

> **行为参数与模型配置不走环境变量**：检索 / 重排 / 解析 / 上下文 / 工具 / 联网搜索 / HTTP 工具 / 工作流工具 / 智能体回调与记忆 / 目标模式 / 沙盒 / 记忆 / 定时任务 / 通知外发 / 内容安全 / 限流等 **260 项**行为参数统一在**系统设置页**维护（`config-schema.json` → `c_ai_config`，保存即生效）；聊天 / 视觉 / 向量 / 重排 / OCR 等模型走**模型供应商**登记 + 引用绑定（API Key RSA 加密入库，**无全局兜底**——聊天模型缺失或引用无效会 fail-loud 引导选择）。`application.yml` 不存任何模型网关与密钥。Web Push 的 VAPID 私钥只从环境变量读取；SMTP 口令走设置页（RSA 加密入库、不回显明文）。

**本机执行器（工作区文件）**：工作区的文件在用户电脑上，需要在**那台电脑**上跑执行器：
```bash
node scripts/local-agent/wenqu-agent.mjs --server http://<服务器>/ai
# 远程 http 目前临时放行，启动时会打印明文传输告警；正式环境建议换成 https。
# 想强制只允许 https：加 --require-https（本机回环地址始终可用 http）
# 首次启动打印链接 + 6 位码；浏览器登录后确认，令牌落到 ~/.wenqu/executor.json
# 工作区源文件夹需要能被执行器访问（默认浏览起点 ~；--root / 可看整盘）
# 标准档的 shell 会套 OS 写沙箱：macOS 用 sandbox-exec、Linux 用 bwrap，只允许写工作区 roots；
# 没有可用沙箱时命令直接拒绝执行（不会裸跑），需要全盘命令请把会话权限调到完全权限
# 默认不在终端逐次确认（页面上的权限档位才是闸门）；需要终端确认时加 --ask
# 每轮文件改动快照落在 ~/.wenqu/executor/turns，重启后仍可看差异；默认保留 14 天/200 轮/2GB，
# 可用 --data-dir / --turn-ttl-days / --turn-max / --turn-cache-max-mb 调整
```
开发态 `cd web && npm run dev`（scripts/mac/dev-with-agent.mjs）会同时拉起 vite 与执行器。

**本地开发**：无需 export。密钥、数据目录等环境相关值放在工作区根 `config/application-local.yml`（Spring Boot 外部配置目录，已被 .gitignore 忽略，**不打进构建产物**——密钥不会随 jar 分发），再以 `local` profile 启动（application.yml 已默认激活 local）：
- IDEA：Run Configuration → Active profiles 填 `local`
- 命令行：`SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`

### 3. 启动后端

```bash
# 方式一：直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/wenqu.jar

# 方式三：Docker Compose（含 redis-stack + meilisearch + 内置 MySQL，自包含）
docker compose up -d
# 使用外部 MySQL：先 docker compose up -d redis-stack meilisearch，
# 再以 DB_HOST/DB_PORT/DB_NAME/DB_USERNAME 指向外部库启动 app（或改 compose 环境变量）
```

开发机上依赖服务已各自跑着、只想按需拉起可选服务（联网搜索 / 沙盒 / OCR）时，用
`scripts/mac/dev-stack.sh [start|stop|restart|status|logs] [单元...]`（任意目录可执行，
单元为 `mysql redis meili searxng sandbox ocr`）。它在数据已有归属时不会另起一份空实例，默认组不含 OCR。
其中 `meili` 走 `deploy/meilisearch/docker-compose.yml`（容器 `wenqu-meilisearch`，宿主只绑
`127.0.0.1:7700`），关键词索引在命名卷 `wenqu-meili-data` 里——`down` 只删容器不动卷；
删了卷就是空索引，需在设置页全量重建（或 `POST /api/ai/search-index/reindex`）。

后端监听 `http://localhost:8090/ai`（context-path `/ai`），API 前缀 `/api/ai/*`，
健康检查：`GET http://localhost:8090/ai/actuator/health`。
接口文档（Swagger UI）：`http://localhost:8090/ai/swagger-ui/index.html`（springdoc 自动生成；Try-it-out 在线调试需先登录，在 Authorize 中填入 `Bearer <登录令牌>`）。**默认关闭**：`SPRINGDOC_ENABLED` 缺省为 false（接口契约不外泄），需要时 export `SPRINGDOC_ENABLED=true` 开启。同理 docker-compose 默认仅把 8090 绑定到回环地址（`127.0.0.1`），对外直连需 `APP_PUBLISH=8090` 或经 nginx 反向代理。

### 4. 启动前端

```bash
cd web
nvm use            # .nvmrc 固定 Node 22 LTS（依赖链要求 ≥22.12，Node 18 会构建失败）
                   # 本机没有 22 就先：nvm install 22
npm install
npm run dev        # 访问 http://localhost:5800/chat（端口被占直接报错，不会跳号）
```

Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 五分钟上手

1. 访问 `http://localhost:5800/chat`，按引导创建管理员账号；接入 OIDC 后可单点登录
2. 在「智能体 → 模型供应商」登记模型网关与模型，再到「个人设置 → 默认模型」绑定个人默认聊天模型（对话页可随时切换）
3. 在「知识库」新建库（绑定向量模型）→ 上传文档（或粘贴网页 URL）→ 等待解析完成
4. 回到「对话」提问：句末 `[N]` 角标点开即见来源全文与原文图片
5. 在「智能体 → 技能 / MCP 外部工具」装配能力，或在智能体高级设置导入 HTTP 工具 / 挂载已发布工作流，或在系统设置开启沙盒后让助手执行命令、交付产物（`/artifacts` 查看下载）
6. 需要把一件事的对话、文件与产物放在一起时，在侧栏新建**工作区**（选你电脑上的文件夹，可多个）；需要长任务自动推进时用 `/目标`，需要先确认方案时用 `/plan`

## 功能详解

### 对话与长任务（`/chat` · 移动端 `/m/chat`）

- **流式回答**：SSE 逐段下发，可随时停止本轮；中止同样留痕，已产出的正文、已执行的工具与用量一并落库。刷新页面、换设备或断线后由 `GET /api/ai/chat/resume` 接回同一轮运行总线，过程、待裁决卡片与计划原样恢复
- **运行中插话**：回答进行中可继续输入，新内容先排在输入框上方，点「立即发送」即作为插话送进正在执行的这一轮，在下一次调用模型前生效；未赶上本轮的内容在本轮结束时自动补发
- **引用溯源**：回答句末 `[N]` 角标点开弹窗，展示来源知识块全文（图文交错）与关联截图；回答下方的检索状态行可展开查看全部命中来源。知识库来源、联网搜索结果与外部 MCP 工具返回**共用同一套编号**；命中图片经相关性校验后按编号位置级插入正文
- **深度思考**：思考与工具调用统一收在本轮顶部的过程块，点开「已完成」查看每一段全文；最终答案始终连续展示。思考强度五档（低 / 中 / 高 / 超高 / 极致）与「关闭 / 开启 / 恒思考」按模型记忆，强度经 `reasoning_effort` 等厂商方言贯通到请求
- **上下文管理**：悬浮模型行可设置思考强度，并显示该模型登记的上下文窗口；输入框右下的容量圆环展示本轮上下文占用（最终轮那一次请求的输入）占窗口比例，并列出网关实测的用量去向（本轮送入及其未缓存输入 / 缓存读取 / 缓存写入、输出及其中思考）——只报实测值，不按消息结构估分类；接近窗口容量时自动折叠早期轮次为摘要，也可 `/compact` 手动压缩或带一句摘要要求重新压缩，压缩位置在对话流中留「已压缩上下文」分隔线
- **多模态输入**：图片（一轮最多 5 张）经视觉模型理解后参与回答；文件（一轮最多 5 个、单个 ≤15MB，含 PDF / Word / Excel / PPT 与文本代码类）读出正文作为该问材料；支持粘贴、拖拽与「+」上传，长文本粘贴自动收为附件卡片
- **引用资料**：`@` 或「+」面板的引用页，四类内容按当轮生效——知识库（收窄检索范围）、文档（相关内容直接带入）、智能体（本轮强制咨询一次）、技能（正文逐字带入）；`#` 另可引用历史问答（最多 10 条）
- **计划模式**：`/plan`（手机在「+」→ 本轮模式）开启后该轮先产出执行计划卡。卡上只放「目标 + 前三步 + 卡脚」，完整计划、版本轨、逐版意见与步骤级 diff 在右侧抽屉（PC 560px / H5 全屏），历史版本只读、只有当前版可裁决。确认栏提供**确认 / 提修改意见 / 取消**：同一轮内按意见升版、消息流不新增气泡；挂起有倒计时。批准后仍可在输入框继续调整：执行中的插话只影响尚未执行的步骤、不改计划正文；需要重排则按新意见重新规划，新卡标出血缘（基于哪一版、已执行到 N/M），旧卡执行记录与产物保留。执行进度以会话任务清单为数据源（校验清单与消息轮次一致），卡上只报进度摘要，逐条清单归位底部任务清单；执行详情抽屉按步骤展示工具、产物与失败步骤，无对应清单时退回计划步骤预览
- **目标模式**：`/目标`（或 `/goal`）输入一句话即入队自动续跑，不需要逐轮点继续。输入框顶部目标条分「身份 / 进度 / 动作」三段：显示目标原文与版本、轮次与预算进度，提供暂停、继续、修改原文与取消；取消需就地二次确认，历史、过程与终态卡都保留。运行中插话时目标让位，当前轮答完自动接续；终态卡留在对应目标回声下不消失，可查看过程与删除记录。助手通过 `goal_submit` 提交完成申请并附证据（产物 / 文件 / 命令 / 测试），平台逐条核验结构、环境与退出码后才判定完成；无法自行解决时转「已暂停」并写明原因。四项硬预算（轮数 40、工具调用 400 次、token 200 万、机器时长 2 小时，均可填 0 表示不限制）与软阈值收敛提示兜底
- **人在回路**：工具执行确认卡（批准 / 拒绝 / 超时按拒绝）、智能体提问卡（单选与多选，未作答不自动选择）、计划确认卡三类卡片随流弹出并落库，关闭页面后重新进入仍在原处，未处理期间铃铛提示待办
- **过程可视化**：整轮「文本段 + 工具段 + 产物段」编为消息时间线随消息落库，完成后折叠为一行总览（只报整轮墙钟耗时），展开是一行一步的执行清单；工具大输出超上限时整份留存，可在卡上「查看完整输出」读回或检索；会话事件账本按轮记录工具步与耗时、审批 / 提问 / 计划三张卡、产物、编排与轮终态，界面按人话渲染并以时间轴回放，会话级与单轮级都能打开，支持导出
- **代码与图表**：回答中的 HTML / SVG 代码块可弹层预览，Python / Node / Shell 可一键在沙盒运行并回填输出；`echarts` 与 `mermaid` 代码块在正文原位渲染为交互图表与示意图，配色跟随主题
- **产物与交付包**：一组产物合并为一张交付卡（文件数、体量、交付轮次、站点文件计数），清单与逐件动作收进交付抽屉（PC 右侧 420px / H5 底部 sheet），可「下载全部（ZIP）」。单件仍可预览或下载：网页可切视口宽度（自适应 / 375 / 768 / 1440 / 自填）与缩放，Markdown / 文本 / 代码就地排版，CSV / TSV 显示为只读表格，图片可 1:1 查看，PDF 用内置阅读器，Excel 与压缩包只提供下载；顶栏固定提供「新标签」打开
- **沙盒后台进程**：命令超时未退出但进程仍存活时，右侧「后台进程」卡列出命令行、运行时长、占用端口与进程树数量，可逐条停止（连同其子进程）；会话列表用与「正在回答」相同的动效标出仍持有后台进程的会话
- **会话管理**：侧栏会话与工作区支持右键菜单（置顶 / 收藏 / 重命名 / 删除等），重命名就地编辑、回车或失焦保存、Esc 取消；置顶 / 收藏 / 全文搜索 / 批量删除 / 清空、导出自包含 Markdown（图片内嵌、统计图绘制成图、mermaid 保留源码）、只读分享链接（可换链、停止、查看访问次数）、重新生成与多版本切换、按消息组删除与撤销、赞 / 踩反馈；逻辑删除保留期（默认 30 天）即撤销删除窗口
- **模型可用性提示**：会话记录的模型被停用或移除时，模型胶囊原地标红并写明原因，发问自动改用个人默认模型并留下一条说明；未设置默认模型时停止该轮并指引设置位置，不静默猜测

### 工作区（`/project/{id}`）

- **工作区 = 你电脑上的目录**：新建时选择本机上的一个或多个文件夹（首个为主目录，命令执行与相对路径以它为基准），文件只存在于你的电脑；问渠服务器只记「哪台电脑、哪些绝对路径、叫什么」，读写由那台电脑上的本机执行器完成。每人另有一个兜底容器，界面上就是侧栏「会话」栏
- **本机执行器**：终端运行 `wenqu-agent`，首次按提示在浏览器里完成设备授权；执行器主动长轮询取任务，服务端不开入站端口。离线时聊天页明确提示，不会静默退回服务器执行
- **页面结构**：工作区页分「工作区目录 / 事项 / 工作区记忆」三块；桌面两栏——左侧内嵌预览、右侧目录（384px），窄屏自动改单列
- **目录与预览**：源文件夹可增删（至少保留一个，首个为「主要」，命令 cwd 与相对路径以它为基准）、复制完整路径、在电脑上打开；目录逐级浏览、面包屑与当前层搜索；图片 / PDF / Markdown / 代码 / 表格就地预览，文本类可应用内编辑并保存，其他类型下载；大文件只读回前一段并提示下载完整内容
- **工作区记忆**：编辑 `.wenqu/MEMORY.md`，记录这个工作区的稳定事实（约定、决策、结构、坑、偏好），随该工作区的对话注入
- **工作区隔离**：页头开关，开启后该工作区的对话不带入也不写入个人记忆（新开轮次生效），上下文只来自目录文件与工作区记忆
- **会话级权限**：输入框左侧选择「仅可查看 / 工作区内修改 / 完全权限」，只对当前会话生效；完全权限需勾选风险确认，生效后权限胶囊变红。仍受平台上限与游客只读闸约束
- **外观与改名**：页头标题可直接改名；图标与颜色在外观弹层选择，侧栏会话与工作区按同一视觉归集
- **事项**：记下要做的事 → 点「去聊」新建归属本工作区的对话并预填内容（不自动发送）→ 做完勾掉
- **归档与删除**：归档后侧栏收进折叠区，目录仍可查看与预览但不能写入，随时可恢复；删除只解除服务器上的归属，**本机目录与文件一个都不动**，没有「连本机文件一起删」的选项

### 知识库与检索增强（`/knowledge`）

- **多库管理**：每库可设解析参数与检索参数（**稀疏覆盖**留空跟随全局、保存即固化、逐库生效）与标签；检索可见性与库一致（文档跟随所属库）；个人默认库「问渠」自动创建，未指定库的上传归入它；官方内置库《问渠使用手册》全员可读不可编辑，管理员可维护其检索与解析参数
- **文档解析**：docx（表格转 Markdown、结构感知切分）/ pdf（扫描件自动 OCR）/ xlsx / ppt / pptx / txt / md / csv 与网页 URL 导入；上传只登记持久化解析任务，后台按并发抢占执行「解析 → 分块 → 入库 → 向量化 → 关键词索引」，队列有界、排满即拒绝；进度、失败原因与队列状态（排队 / 执行）在文档列表实时可见
- **文档治理**：版本历史与一键回滚、增量重解析（按内容指纹只重嵌变更块）、回收站（恢复 / 彻底删除 / 清空）、批量启用 / 弃用 / 删除 / 重解析、跨库移动（前后库向量模型不同时自动异步迁移向量）、库与文档导出导入（zip 含源文件与清单）、标签、全局搜索知识块、补图片描述
- **网页导入**：一次最多 20 个 URL，抓取正文后按上传同链路入库；源文件为 HTML 快照，重解析离线提取不重新抓网；仅公网 http/https，逐跳校验防内网探测；可开文档级 cron **定时自动刷新**，到期重抓并同名替换重建
- **知识块**：切片列表与结构导图两种视图，块数与 token 统计、未向量化块提示；单块编辑（自动重向量化）、停用、删除、手动建块
- **GraphRAG**：库级开关（默认关），解析时抽取「主体-关系-客体」三元组，检索命中后做一跳图扩展并按衰减分并入结果；图谱弹窗提供实体与三元组规模、构建与清空、力导向图（点实体看完整关系链与来源块溯源）
- **混合检索**：Redis 向量 Top-K 与关键词召回并行，向量分归一化后按权重融合（双命中叠加加分、标题命中奖励）；**重排门 0.6 / 融合门 0.5** 分别作用于重排分与融合分，重排执行过后未经重排的候选不得经融合阈值进入上下文。低分全灭时默认保留唯一最高分块并显式标注「低置信」（`retrieval.lowConfidencePolicy=top1`），避免隐性问答被绝对门全部误杀；重排链路异常时不启用保底
- **关键词引擎可切换**：默认 `mysql` LIKE 零依赖；切到 `meilisearch` 使用 jieba 中文分词与相关度打分，服务不可用自动降级回 MySQL；切换后需全量重建一次，写索引随解析 / 编辑 / 删除增量同步，并有定时精确对账
- **上下文工程**：窗口与最大输出按模型声明，登记时校验最大输出小于「窗口 × 0.7」；知识块填充上限、单文档与单库配额可调；历史压缩、逐轮裁剪与 token 计数由框架与 jtokkit 承担

### 智能体（`/agents`，全员开放）

页内七个 Tab（清单与顺序由菜单数据驱动，可逐项授权）：**模型供应商 · 智能体 · 技能 Skills · MCP 外部工具 · 工作流 · 定时任务 · 评测**。

- **配置分节**：运行方式（问答助手 / 子智能体 / 工作流三选一，子智能体可选被委派时的执行方式）、身份与角色（图标、名称、描述、系统提示词；智能体不绑定模型）、资料范围（全部知识库 / 指定知识库 / 不使用，可再按标签收窄）、能力开关（按用途分三组——检索与知识：知识库检索、联网搜索；工具与扩展：MCP 外部工具、HTTP 工具、工作流工具；执行与交付：目标模式、向用户提问、产物交付、技能。除目标模式只有开 / 关外，每项都可留「跟随系统」，例外项改为开 / 关 / 指定）
- **高级设置**（独立卡片，常用治理常显 + 专家选项折叠）：权限档位（仅可查看 / 工作区内修改 / 完全权限）、工具执行确认（跟随档位 / 自动执行 / 执行前确认 / 禁用）、记忆域（跟随用户级记忆 / 专属 / 叠加）、子智能体委派关系；专家选项内含单轮工具步数上限、检索重排覆盖、工具黑名单、轮终回调地址与签名密钥。右侧「生效摘要」列出与系统默认不同的 N 处并可一键恢复跟随
- **配置历史**：每次保存生成版本快照（字段级 diff），可查看历史并回滚到任一版本（回滚产生新版本，不改写历史）
- **三级记忆域**：智能体可选用户级（与网页问答共用同一份长期记忆）、专属（写入该智能体的独立记忆根）、叠加（专属写入 + 用户全局记忆只读注入）；平台总闸关闭时全部回落到用户级记忆
- **HTTP 工具集**：粘贴 OpenAPI 3.x JSON / YAML 规格即可预览并勾选接口导入，一个智能体可挂多个工具集；鉴权支持 Bearer / 自定义请求头（值 RSA 加密入库、界面只回显掩码），超时与内网访问由平台开关控制；操作按「工具集名_操作名」进入对话工具表，调用过程与结果在本轮工具卡与过程面板可见
- **工作流工具**：把已发布工作流挂载为对话工具，模型按自然语言选择调用；单智能体最多 8 条，工具名为 `wf_*`，运行结果回填对话并留运行记录；嵌套深度上限 2 层、委派分支不挂载，防止「智能体 → 工作流 → 智能体」递归
- **轮终回调**：智能体配置回调地址后，每轮结束（完成 / 停止 / 失败）向宿主系统 POST 签名事件，载荷含轮次、会话、消息、正文截断、用量与产物，并带接入 API Key 标识便于按来源分流；投递先落台账、失败按 60s / 5min / 30min / 2h 退避重试，耗尽后通知智能体主人
- **发布前评测门禁**：智能体可建评测套件与用例，跑回归后按「较基线」给出通过 / 低于门槛 / 有变更未评测等门禁状态；保存前检查会列出本次改动与门禁结论（详见「运营闭环与质控」）
- **按需委派**：当前助手缺少资料而另一位助手的职责范围明显匹配时，模型把该问委派给对方查询——对方在自己的知识范围内执行一条子轮并交回结论与引用；被委派方有**检索要点**（只交资料，开销小）与**全循环**（按完整问答执行、可生成与交付文件）两种形态；顶栏状态条显示本轮派出几位、几位在跑与最长一支耗时，执行中可查看当前步骤
- **资料收敛**：交回的资料按**本轮问题**再过一道相关度门与引用配额，未通过的条数如实告知模型；免登录分享页与被委派的助手本身不再向外委派
- **公开发布**：智能体卡片可发布为 `/s/{token}` 免登录对话与 iframe 嵌入，游客侧可选开放对话模型、允许发图与上传附件；可另开 MCP 端点，停用或撤销立即失效。分享链接有**游客额度闸**：每条分享每日轮次上限、每访客每日轮次上限与每日 token 上限（跨副本 Redis 计数，默认 200 轮 / 20 轮 / 200 万 token），超限返回 429；Redis 不可用时 fail-closed
- **定时执行**：按 cron（5 段 + 时区）或固定间隔自动执行，结果落进该任务的专属会话；调度中心先推进 `next_run_at` 再执行（单实例防重复），同任务串行防重叠，支持立即执行与执行记录
- **工具执行确认**：沙盒与 MCP 类有副作用工具可设执行前确认（批准 / 拒绝 / 超时按拒绝）或禁用；联网搜索默认自动执行，管理员可将其纳入审批

### 工具生态与沙盒执行

- **工具开关**：问渠工具总开关 `tool.enabled` 默认**开**（基础工具开箱即用）；沙盒执行 `tool.sandbox.enabled` 与沙盒脚本 `tool.runcode.enabled` 默认**关**，联网搜索 `webSearch.enabled` 默认**关**，需管理员在系统设置「工具调用」显式开启。HTTP 工具、工作流工具、智能体轮终回调与智能体级记忆默认**开**（没有配置的智能体天然无工具可出）；低风险工具族不再各留平台开关，按智能体三态设置或平台停用名单收口
- **工具族**：提问（人在回路）、知识库精确检索（命中块进引用流）、产物交付、技能、任务清单、联网搜索、沙盒执行、MCP 外部工具、HTTP 工具、工作流、本机浏览器共十一类，设置页提供只读工具名册
- **HTTP 工具**：按智能体导入 OpenAPI 规格（`POST /api/ai/agent/http-tools/preview` 先预览再保存），一个工具集可含多个操作；地址、方法、参数与鉴权来自规格，鉴权值加密入库、运行期才解出；调用受超时、内网访问开关、权限档位与工具黑名单约束
- **工作流工具**：智能体可把已发布工作流挂成对话工具（最多 8 条），运行走工作流引擎并受嵌套深度与分支闸约束；游客分享只挂「只读工作流」
- **本机浏览器**：用户本机执行器在线时挂载 `localBrowser*` 工具，让模型在用户电脑上打开网址、读页面快照、点击、填写与截图，可访问 localhost 与内网；执行器离线时不挂本机族，此时仍可用云端 `browser_*`（只能看公网）；两者工具名不撞，执行器在线时云端浏览器让位，避免两套浏览器给出两种结论
- **沙盒隔离执行**：模型在隔离 Linux 容器内使用命令执行、读文件、写文件、编辑文件、列目录、检索内容、按模式查找文件与交付产物（后两类由框架原生工具承担，界面统一给中文名），命令输出实时流式回传；作用域挂在会话上，同会话文件跨轮保留，空闲默认 60 分钟回收容器
- **沙盒脚本**：开启后模型可写一段 Python 或 JavaScript 在沙盒中批量执行本轮已挂载的同类调用（如逐份文档汇总），只把打印结果带回对话；脚本内调用走同一套管控与配额，不是旁路，停止本轮时运行凭据当场作废
- **权限档位**：仅可查看（只留读文件与列目录，命令执行与写入不进入工具表，外部工具逐项确认）、工作区内修改（可执行命令、在工作区目录内读写，越界写入被拒）、完全权限（不收束路径、不逐项确认；会话选择时需二次确认并标红）。管理员可设**平台上限**（高于上限的按上限执行并在过程面板说明被收紧）与**平台停用工具名单**；每个会话还可以在输入框左侧单独覆盖（DSH 的会话级 permission/preset）
- **联网搜索**：服务商 Tavily / 博查 / 自建 SearXNG；结果与知识库来源共用 `[N]` 编号进引用体系，并按本轮问题重排过相关度门，低于阈值的不列为引用、不交给模型；重排不可用时明确提示本轮结果未经相关性过滤；单轮调用次数配额可配
- **文件与产物**：对话右栏「沙盒文件」卡按目录树列出该会话沙盒目录，可打开、下载或存为产物；「我的产物」页（`/artifacts`）汇总助手主动交付的文件，支持预览、下载（签名 URL）、移动到其他工作区与删除；交付文件按扩展名白名单净化，单份 ≤1MB

### 技能与市场

- **技能（Skills）**：「目录 + `SKILL.md`」形式的可插拔能力，模型每轮先获得清单、需要时才读正文（渐进披露）。三类来源：内置（随版本分发，人人可用，只能停用不能改写）、我的（本人技能目录，可在对话中由助手修改）、工作区（助手在对话里沉淀，随会话存在、只读）。点名引用时正文逐字带入本轮
- **安装与管理**：支持新建、编辑、删除、上传 zip、从 URL 安装、下载与查看正文；安装校验 frontmatter、路径穿越、zip 体积与条目数、URL 域名白名单与逐跳公网校验；停用分两级——个人停用只影响本人，平台停用由管理员在系统设置执行、对全平台生效
- **市场**：团队内的资产分发入口，四类页签（技能 / 应用模板 / 工作流 / 我的上架）。**安装即复制**——技能进自己的技能目录、应用模板 fork 成自己的智能体、工作流复制为自己的草稿，作者此后改版或下架都不影响已装副本；重新上架后内容有变化时装过的人看到「有更新」；应用模板安装时按安装人权限自动裁剪引用（不可读知识库剔除、未登记 MCP 剔除、缺装技能提示去市场安装）；内置工作流模板以「平台官方」名义提供；详情页可查看内容全文并下载物品包

### 可视化工作流（`/agents?tab=workflow`）

- **DSL 唯一真源**：节点、边、变量引用与画布坐标全在 JSON DSL 中，画布只是编辑器，引擎只按 DSL 执行；结构校验器 fail-loud（节点数与边数有上限，条件 / 审核 / 循环三类路由器节点的分支出边必须连线）
- **13 类节点**：`开始` / `结束`；`LLM 调用`（模型引用过判权，token 流式透传）与 `子智能体`（复用完整问答管线，临时会话执行完即删）；`知识检索` / `HTTP 请求`（SSRF 逐跳校验、2MB 读取上限）/ `代码执行`（Python / Node 走沙盒，非 0 退出码 fail-loud）/ `模板转换`；`条件分支`（SpEL 安全求值，禁 `T()` 与构造器）/ `人工审核`（挂起 → 审批卡裁决 → 按快照续跑，已完成节点短路回放）/ `循环`（maxLoops 编译期与运行期双闸）；`技能`（把所选技能正文注入下游上下文）与 `MCP 工具`（调用名下一个外部工具）。变量引用统一写法 `{{nodeId.key}}`，HTTP Header 值可写 `{{credential:名称}}` 引用凭据
- **画布编辑器**：左侧节点面板按用途分组（流程 / 模型 / 数据 / 控制 / 生态），拖拽或点击添加、右键快捷菜单、连线校验、网格吸附与小地图；右侧抽屉分「属性 / 运行输出」；画布内调试填参即同步运行、逐节点 trace 染色回放
- **运行与版本**：草稿可继续修改，发布冻结成一版，回滚以历史 DSL 再发一版；运行读已发布快照，未发布或已下线不执行；每次运行锁定当时的 DSL 快照，改画布不影响已跑的 run
- **触发方式**：画布手动运行、**定时触发**（5 段 cron + 时区，只跑已发布版本）、**运行终态回调**（地址 + HMAC-SHA256 签名密钥，不得指向内网）、**API 触发**（`POST /api/ai/v1/workflows/{id}/run` 持 API Key 跑已发布版本；含人工审核节点的工作流一律拒绝）、**Webhook 触发**（自动化弹窗生成 `/api/ai/v1/workflows/webhook/{token}` 地址，令牌即凭据，同样只跑已发布版本、含审核节点拒绝）、**chatflow 绑定**（智能体绑定已发布工作流后对话由工作流驱动，结束节点 `answer` 即回答）
- **游客只读放行**：分享页绑定的 chatflow 若只含 `start / end / llm / retrieval / condition / loop / template` 节点，游客可正常对话；含代码执行、HTTP、MCP、知识写入、人工审核、子智能体或技能注入的工作流一律 fail-closed，工具装配与执行入口各有一次校验
- **运行治理**：独立运行历史页提供 run 列表与节点级 trace 回放、待审批运行就地裁决、实时进度流（SSE）；单次运行超时、最大图步数、并发上限与排队容量、运行记录保留期均可配；批量发布 / 下线 / 导出 / 删除与模板库、导入导出（跨环境迁移）

### MCP 双向互联

- **Client（接入外部工具）**：每人登记自己的 MCP Server（streamable / sse），连接池按用户隔离；支持连接状态查看、全部重连、单个重连、批量启停与删除、临时连通性探测；**定时健康巡检**对已连接服务定期 `listTools`，远端挂掉时状态翻红并接退避重试，避免「假绿」。请求头认证（Bearer / API Key）整块加密入库、只回显键名，编辑时不改认证区即保持原凭据；编辑弹窗自动识别粘贴的 URL 类型并查重名称；支持粘贴 Claude / Cursor 的 `mcpServers` JSON 片段导入（只收 http(s) 地址型，本地进程型跳过并说明）；工具清单缓存 5 分钟，可逐条起中文名（按登记人归属，工具卡与审批卡同源显示）
- **Server（对外提供）**：智能体发布时可开启 **MCP 端点 `/ai/mcp/{token}`**（Streamable HTTP、无状态），Claude Desktop / Cursor 等客户端粘贴地址即可调用；token 即凭据，与网页分享同源。另有**平台级入口 `/ai/mcp`**，一个地址提供四个工具（检索知识库 / 提问 / 列可见知识库 / 列可用智能体），凭据为 API Key 且需打开该 Key 的「MCP」开关，可见范围与 Key 创建者在网页上看到的完全一致；服务端总开关 `mcp.server.enabled` 默认关
- **调用审计**：入站（外部客户端调用平台端点）与出站（模型调用接入的外部工具）统一记账，记录渠道、工具名、耗时与成败，不记录参数与返回内容；支持按渠道、工具名、结果筛选与按工具 × 渠道汇总
- **本地统计 Server**（可选，开发机取数）：`scripts/mcp-stats/server.py` 提供只读 `sql_query` / `db_overview` 两个工具，白名单仅 `SELECT/SHOW/DESC/EXPLAIN`、强制限量，连接为本机回环

### 站内通知、产物与统计

- **通知中心**（铃铛 + `/notifications`）：解析完成与失败、批量解析失败、工作流失败 / 超时 / 待审批、网页源刷新失败、定时任务完成与失败、检索质量下滑、工具审批与提问待答、模型额度异常等异步事件写进站内通知；未读计数实时显示在铃铛，支持按类型筛选、仅未读、批量标记已读、删除与清空，并可按类型静音（只影响本人，不产生也不丢通知）
- **邮件外发**：管理员在「系统设置 → 通知外发（邮件）」配置 SMTP（主机 / 端口 / none·ssl·starttls / 账号 / 口令 / 发件人），可当场发送测试邮件到本账号邮箱；平台按个人「通知偏好」中的 `mailTypes` 投递待处理与失败类通知，同一事件聚合、失败退避重试，投递台账记录 pending / success / exhausted / skipped 与最后一次错误；总闸默认关
- **Web Push**：浏览器系统级推送，与站内通知共用事件；个人设置可订阅 / 退订本机、发送测试推送并查看设备列表，同一浏览器换账号不会收到上一个人的通知；VAPID 密钥走环境变量，多副本必须同一对，未配置时界面不展示订阅入口
- **通知深链**：通知条目带来源参数，点击直达对应的文档、工作流运行或会话
- **个人使用统计**（`/stats`，全员）：累计与峰值 Token、最长聊天时长、当前与最长连续天数、累计费用；近一年 Token 活动热力图（每日 / 每周 / 累计三口径）、每日趋势（近 7 / 30 日按模型分系列）、模型用量环形占比，以及按用途分项的用量去向（对话问答、子智能体分支、记忆提炼、历史压缩、引用自检等）
- **管理员费用视图**（数据看板 → 费用）：区间费用、按用户与按模型两张表，输入 / 输出单价分列（元 / 百万 tokens），未计价 tokens 单独列出并指引到模型供应商登记单价

### 多租户与企业身份（`/tenants` · `/apply` · `/join/{token}`）

- **租户生命周期**：平台级管理员开通租户（标识 / 名称 / 联系人 / 备注）、启停与归档 / 恢复；停用只挡登录、数据保留，归档为只读且可导出；刻意不提供「删除租户」——下线一个租户就是停用，避免一键删光整租户数据。租户管理员只能看到自己一行，成员归属变更与平台级动作仅平台级管理员可执行
- **租户品牌与登录**：每个租户可配品牌名、Logo、主色与登录提示；登录页按 `?tenant=` 参数展示对应品牌，不存在 / 停用 / 归档的租户回落默认品牌
- **邀请注册**：管理员生成邀请链接（预选角色、部门、邮箱、可用次数、有效天数），返回 `/join/{token}`；token 只存 SHA-256、明文仅出现一次，预览与注册均按 IP 限流，名额用 CAS 占用、失败归还，可随时撤销；注册成功直接返回登录令牌
- **首登引导**：新租户管理员登录后看到五项真实进度清单——修改初始口令、建部门、邀请成员、绑定模型、建知识库；单租户部署自动隐藏该引导
- **自助开通**：「申请开通」公开表单提交机构名、联系人、邮箱与用途，落一条待审记录；平台级管理员批准时自动开通租户并创建首位管理员账号（随机口令只出现一次），也可驳回并留说明；按 IP 限流、同机构 / 同邮箱有在途申请时拒绝重复提交
- **数据落点清单**：按租户列出成员、按租户归属的表与行数、磁盘目录（记忆 / 沙盒工作区 / 产物 / 附件 / 外溢原文 / 自装技能）与 Redis 键形态，备份恢复脚本直接按清单取数
- **企业身份**：统一认证源支持 OIDC、SAML 2.0、SCIM 2.0 与 LDAP；`(provider, sub)` 复合唯一防账号漂移，认证源密钥 RSA 加密、只回显掩码；组→角色 / 部门映射按优先级裁决，LDAP 支持手动与定时同步（远端建号 / 恢复 / 离员禁用），同步日志可回看；SCIM 由 Entra / Okta 推送用户与组，独立 Bearer 凭据、租户停用即 403
- **服务账号**：面向外部系统的非人类身份，可创建 / 停用 / 删除、绑定角色、设置到期时间；令牌只存哈希，明文仅在创建与轮换时返回一次，轮换立即作废旧令牌；删除时级联清理角色绑定

### 安全与权限

- **RBAC 两级管控**：角色-菜单（侧栏入口与页内 Tab 可逐项授权）+ 角色-接口（端点白名单，后端 fail-closed）；权限管理页以单棵树呈现菜单与其名下接口，勾选菜单即连带授权名下接口，未授权接口数量在页上直接告警；内置角色 `superadmin`（全部权限，含最后一名保护）/ `admin`（管理端全量）/ `user`（问答与个人资源），另有只读管理员、运维管理员等预置角色
- **资源可见性**：知识库、文档、智能体、工作流、API Key 全部按创建者隔离，并按租户行级隔离；管理员与普通用户同口径（无归属的历史数据已迁给 admin）；官方内置手册库登录即可读
- **认证**：本地账号（PBKDF2 密码哈希 + 失败锁定）+ OIDC 授权码单点登录 + SAML 2.0 + SCIM 2.0 入站 + LDAP 目录同步；各 IdP 独立维护 issuer / clientId / clientSecret（RSA 加密，不回显明文，编辑时留空即沿用）/ 重定向 / 前端基址 / scopes / 身份映射与建号规则；`(provider, sub)` 复合唯一；成员管理可按账号解绑
- **会话治理**：管理员可对单个账号或批量（单次 ≤100）执行强制下线——会话代数 +1，已签发的全部令牌当场失效；本人改密 / 管理员重置密码自动做同一件事；所有运维动作落审计
- **密钥安全**：模型 API Key、MCP 请求头、HTTP 工具凭据、认证源密钥 RSA 加密入库、不回显明文（编辑时留空即沿用已存值）；对外 API Key 与服务账号令牌只存 SHA-256 哈希 + 前 8 位前缀（明文仅签发时返回一次），权限固定为问答链路——即便持有 Key 也访问不了管理端点；图片、产物与工作区文件走 HMAC 签名 URL；RSA 密钥文件不入库、不打进构建产物
- **运行防护**：问答 / 上传按用户（无身份按 IP）Redis 固定窗口限频，超限返回 429，Redis 不可用自动放行；上传魔数校验、产物文件名白名单净化、隐藏名称拒绝写入；路径逐段校验并按真实路径比对前缀；工作区有轮次正在执行时拒绝改名 / 移动 / 删除该目录内文件；管理操作 `[AUDIT]` 审计
- **推理配额**：平台 / 部门 / 成员三档，按日或月设 token 与费用上限；可只约束用户主动发起的对话 / 子智能体 / 工作流调用，也可纳入辅助调用；支持「只告警不拦」与超额熔断，规则页直接显示本周期用量与进度；成员可在使用统计里自查受哪几条规则约束
- **内容安全**：输入闸与输出闸两道，规则支持关键词 / 正则、命中后 `block` 拦截或 `warn` 只告警、按排序先命中先生效；出口闸在流式生成时压住尾部若干字符再放行，`block` 命中即截断并追加平台提示，`warn` 只落审计；可选外部检查服务（按输入 / 输出分别开关、单次送检字数）；规则清单支持试跑（不落库），坏正则在闸门状态里单独列出；平台总闸与外部服务在系统设置维护
- **审计事件**：统一记录登录、配置、租户、成员、密钥、内容安全与运维动作；支持按时间 / 操作人 / 动作 / 关键字分页查询、跨租户审阅（仅平台级）、CSV 合规导出（最多 10 万行，头部带留存期与链校验结论）与哈希链校验；队列满导致的未入库条数会显式暴露，不静默丢审计
- **侧栏可编辑**：菜单顺序与显隐由数据驱动，管理员可拖拽排序、逐条显示 / 隐藏并恢复默认；隐藏只影响入口显示，不改变权限判定

### 安全与运维（`/security`）

- **系统巡检**：一键或按节拍体检 MySQL、Redis、关键词引擎、落盘磁盘、审计链、模型供应商、登录安全态势与工具闸漏建等项；逐项给出当前值、结论与处置建议，总评取最差项；报告落库后支持历史回看、按天趋势（次数 / 失败 / 平均耗时 / 最差结论）与失败告警
- **平台公告**：按通知 / 警示 / 紧急三级发布，可指定时间窗口与受众（全体 / 部门 / 角色）；登录后横幅展示、可标记已读；下线保留记录、可恢复，彻底删除只对已下线公告开放
- **会话治理**：与成员管理共用名单，支持单个与批量强制下线，离职即踢、疑似盗号即断
- **分工明确**：系统设置管参数（配什么），安全与运维管运行状态与人（现在好不好、谁在线上、全员该知道什么）

### 运营闭环与质控（`/dashboard` · `/evaluation` · `/agents?tab=eval`）

- **数据看板**四个页签：**总览**（问答总数、无命中率、有引用标注率、反馈满意率；检索质量自动体检；热门问题与无命中问题 TOP10；差评样本可加入评估集或补知识块；知识库缺口汇总可直接入库）、**执行 Trace**（按对话 / 工作流、时间、引用、评分与关键词筛选，详情抽屉回放）、**采样池**（差评必采 + 无引用 / 随机入池，待标注样本可回流评测集或忽略）、**费用**
- **检索评估**：评估集落库并按版本演进（历史问答引用回放 / 导入 / 手工维护，逐条人工确认与失效标签修复）→ 多参数组（单路 / 多路，预设关键词优先 / 向量优先 / 向量+重排 / 多路，可自定义权重、阈值、topK、低置信策略与重排）**异步实验**对比 **recall@k / MRR / 命中率 / 答案充分率**，结果逐题落库、可取消、可回看；运行历史支持趋势、基线、跨次逐题对比、置信区间与按标签/知识库分群；「应用此组」进入 24 小时灰度观察，核心指标明显下滑自动回滚；另提供**答案质量评测**（只测检索落空/低分题，按评判模型给出忠实度与相关性均分，逐条附问题说明），评估集为空/失效期望块/参数漂移/超时组均在页面显性提示
- **智能体评测与发布门禁**（`/agents?tab=eval`）：按智能体建评测套件与用例，可从历史会话采样（差评优先、自动带入当时引用作为期望），也可手写期望知识块 / 文档；支持批量增删改、启动回归、取消运行、设为基线、两次运行逐用例 A/B 对比；门禁五态（未评测 / 有变更未评测 / 运行中 / 通过 / 低于门槛）在智能体列表与编辑器同源显示，保存前检查会列出本次改动与门禁结论；评测默认只读，需人工确认的副作用工具自动拒绝
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元 / 关键词 / 向量 / 合并 / 重排 / 最终结果；问答页每条回答的「更多」菜单可直接打开「检索调试（为什么这么答）」，并受「调试设置 → 检索调试入口」统一门控（同时管回答区的归属徽标、路由提示与降级提示）
- **反馈闭环**：问答日志 + 赞 / 踩反馈 + 看板聚合 + 采样回流评测集，形成「发现问题 → 评估 → 优化 → 验证」闭环

### 终端形态与帮助中心

- **移动端原生壳**（`/m/chat`）：触屏且窗口宽度 ≤1024 时对话页交给移动壳，鼠标用户拖窄窗口不受影响；输入区「+」聚合技能 / 引用 / 历史 / 附件四个页签，「状态与来源」sheet 收纳引用来源、上下文容量、产物、目标与分享导出；人在回路三张卡（提问 / 审批 / 计划）与 PC 端同一契约
- **帮助中心**：《问渠使用手册》即官方内置知识库，任意页面右下角 `?` 悬浮入口或 `/help` 阅读；管理员可在帮助中心点「同步官方手册」把随包分发的新版写入知识库（内容未变则逐篇跳过，不产生调用）
- **配置引导**：侧栏底部的「配置引导 / 进阶配置」入口汇总尚未完成的必配项（聊天模型、向量模型等），菜单项上以「待配置」标记与圆点提示；必配项全部完成后自动退场
- **主题**：亮 / 暗两套主题，经 CSS 自定义属性贯通，图表与画布配色跟随

## 生产部署

> **完整部署步骤见 [`deploy/deployment-guide.html`](deploy/deployment-guide.html)**（Docker Compose 全栈自包含：环境变量清单、密钥生成、备份还原、升级与回滚、常见故障排查）。本节只给骨架。

### Docker Compose

```bash
docker compose up -d          # 自包含：redis-stack + meilisearch + 内置 MySQL
```

使用外部 MySQL 时，先只起中间件，再以 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME` 指向外部库启动 app。生产 nginx 参考配置见 `deploy/nginx.conf`。

### 多副本部署要求

支持多实例水平扩展，需满足以下约束（均已代码化治理）：

1. **数据目录必须共享**：`AI_IMAGES_DIR` 指向所有实例都能访问的同一存储，框架工作区 `AI_AGENTSCOPE_STATE_DIR` 同样需要共享卷。docker-compose 用命名卷仅**同主机**共享；跨主机需挂 NFS/对象存储等共享卷
2. **静态配置一致**：各副本的 yml/环境变量（数据库、Redis、`AI_JWT_SECRET`、`AI_MEILI_KEY` 等**仅存 env/yml 的项**）必须一致（**`AI_JWT_SECRET` 尤其必须一致**）；动态配置（`c_ai_config`，含模型与行为参数）经 **Redis pub/sub** 广播即时生效，订阅断线由 **5 分钟兜底轮询**补齐
3. **并发防护**：重解析用 **DB 状态机 CAS**；解析队列有界，超限拒绝而非静默丢弃
4. **删除中断语义**：删除在任意实例生效，其他实例上的解析由 DB 兜底在检查点秒级停止清理
5. **总并发核算**：解析并发为「副本数 × `parse.concurrency`」，embedding 与视觉模型调用是共享瓶颈
6. **向量模型换绑为全实例串行**：Redis 分布式锁互斥，锁持有期间向量检索自动降级关键词路；任务每批续期锁，实例崩溃后锁 TTL 自愈
7. **启动对账/巡检开关需收敛为单点**：多副本上线前把 `keyword.reconcileOnStartup` 置 false（启动对账收敛为单点）
8. **密钥目录必须共享**：默认 `{AI_IMAGES_DIR}/secret`（`AI_SECRETS_DIR` 覆盖），敏感数据用其中 `rsa/vN.key` 的 PEM 私钥做 JWE 信封加密（密钥版本记在密文里）——各副本挂同一目录，否则 A 实例加密的 key 在 B 实例解不开；轮换＝放一把更高版本的 PEM 再重启，此后新写入用新版本；存量密文按自己记录的版本解密、不批量重封，故历史版本的密钥文件必须一直留着（无需停机）
9. **会话状态与事件日志**：框架会话状态在 Redis；会话事件日志与代次轮换写在宿主 `~/.wenqu/agent-journal`，事件账本内容按会话导出为 JSONL 归档（`chat.sessionLogArchive.*`），归档目录须指向共享卷
10. **目标模式与定时任务**：目标推进由 `GoalScheduler` 按租约抢占执行（DB 条件更新保证唯一执行权），定时任务调度中心先推进 `next_run_at` 再执行，多副本无需额外中间件
11. **本机执行器队列在 Redis**：调用队列、在线键、ack、结果回投都走 Redis，执行器可以轮询任意副本；等待方绑定发起调用的副本（owner），结果按 owner 回投。服务重启后 owner 的等待方租约 15 秒内过期，执行器 ack 会被拒，不会在“没人等结果”时继续执行；已产生的晚到结果在 Redis 结果队列保留 10 分钟供排障
12. **部署级密钥一致**：`AI_VAPID_PUBLIC_KEY` / `AI_VAPID_PRIVATE_KEY` 多副本必须同一对（否则 A 副本生成的订阅在 B 副本推不出去）；邮件 SMTP 配置走 `c_ai_config` 经 Redis pub/sub 同步，各副本保持一致；租户与身份配置同属动态配置，无需额外中间件

### 与平台网关集成

生产环境由平台网关做 JWT 鉴权并透传请求（前端调 `/api/ai/*`，见 `web/.env.production`）。注意：

1. 网关需额外透传图片路径 `/ai/images/**` 与工作区签名预览路径
2. SSE 接口（`/chat`、`/chat/resume`、`/share/{token}/chat`、工作流运行流、`/api/ai/v1/chat/completions`（`stream=true`）、`/api/ai/v1/responses`（`stream=true`））网关需关闭响应缓冲
3. **登录态透传**：用户身份只认 `Authorization: Bearer <JWT>`，**反向代理必须原样转发该头**；会话严格按登录用户隔离。OpenAI 兼容端点与对外工作流接口另走 API Key（`Authorization: Bearer sk-…` 或 `X-Api-Key`），网关需同样透传
4. **不接受自报身份**：不读取任何客户端自报的用户标识请求头
5. **接口限流**：问答 / 上传按「用户（无身份则按 IP）」Redis 固定窗口限频，Redis 不可用自动放行
6. **权限模型**：普通用户开放对话 / 知识库 / 智能体工作台 / 市场 / 我的产物 / 使用统计等日常链路；管理端点仅管理员可访问（403 fail-closed）
7. **普通用户 UI 收敛**：管理路由带守卫，非管理员侧边栏不展示、直达 URL 自动跳回对话页并给出提示

## API 参考

> 完整接口文档见 **Swagger UI**（`SPRINGDOC_ENABLED=true` 时访问 `/ai/swagger-ui/index.html`，随代码自动更新）。当前共 **57 个控制器、504 个 REST 端点**（含 SCIM 入站），另有 MCP 两条非 MVC 路由（`/ai/mcp`、`/ai/mcp/{token}`）与 SAML ACS 等不进控制器统计的协议路由。下表按域给出核心端点速查，完整契约以 Swagger 为准：

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（事件见下方 SSE 事件表） |
| `POST /api/ai/chat/stop`、`POST /api/ai/chat/steer` | 停止本轮 / 运行中插话 |
| `GET /api/ai/chat/run`、`GET /api/ai/chat/resume` | 探测是否有轮在跑 / 断线重连接流（SSE） |
| `POST /api/ai/chat/attachment`、`/attachment/live` | 聊天附件上传换 fileId / 批量校验附件是否仍可重发 |
| `POST /api/ai/tool-approval/{approvalId}`、`GET /tool-approval/pending` | 工具执行确认裁决 / 待裁决卡恢复 |
| `POST /api/ai/ask-user/{askId}`、`GET /ask-user/pending` | 回答智能体提问 / 待答提问卡恢复 |
| `POST /api/ai/plan-approval/{planApprovalId}`、`GET /plan-approval/pending` | 执行计划裁决（批准 / 取消 / `supersede` 继续对话）/ 待批准计划恢复 |
| `POST /api/ai/session/{id}/compact`、`GET /session/{id}/history-summary` | 手动压缩上下文（可带摘要要求）/ 当前生效摘要 |
| `GET /api/ai/session/{id}/events`、`GET /session/{id}/todos` | 会话事件账本（按轮过程回放）/ 任务清单快照 |
| `GET /api/ai/tool-output/{spillId}`、`GET /tool-output/{spillId}/search` | 工具大输出外溢原文读回 / 在留存原文中检索 |
| `GET /api/ai/sessions`、`POST /session/new`、`GET /session/{id}`、`PUT /session/{id}/rename\|pin\|favorite\|model\|project`、`DELETE /session/{id}`、`DELETE /sessions`、`POST /sessions/batch-delete` | 会话列表（游标分页 + 搜索）/ 新建 / 历史恢复 / 重命名 / 置顶 / 收藏 / 模型覆盖 / 移动到工作区 / 删除 / 清空 / 批量删除 |
| `GET /api/ai/session/{id}/model`、`PUT /session/{id}/permission`、`GET /session/{id}/workspace` | 会话记录的模型与档位 / 会话级权限档位（research·standard·autonomous，空=跟随智能体）/ 所属工作区与源文件夹 |
| `GET+POST+DELETE /api/ai/session/{id}/share`、`GET /session-shares`、`DELETE /session-shares/{sessionId}`、`DELETE /session-shares/{sessionId}/record` | 会话只读分享：查询 / 开启（返回 token）/ 关闭 / 列表管理 / 清除记录 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /message-group/undo`、`POST /message-group/switch` | 按消息组删除本轮 / 撤销删除 / 切换回答版本 |
| `POST /api/ai/chat/goal`、`POST /chat/goal/{goalId}`、`GET /chat/goal/pending`、`GET /chat/session/{id}/goal`、`/goal/events` | 目标模式：创建（`{sessionId,objective}`）/ 动作（`pause`·`resume`·`edit`·`clear`）/ 待处理目标恢复 / 会话当前目标视图 / 目标过程账本 |
| `POST /api/ai/v1/chat/completions`、`POST /api/ai/v1/responses` | OpenAI 兼容端点：`model` 填智能体 id；Chat Completions 支持非流式 / 真增量流式 / usage / 只读 function calling，Responses 使用 `input` / `instructions` 并支持 `response.created` / `output_text.delta` / `completed` 流事件；临时会话跑完即删，用量按 API Key 归属计量 |
| `GET /api/ai/auth/me`、`POST /auth/login`、`/auth/logout`、`/auth/password`、`GET /auth/first-run`、`POST /auth/initialize`、`GET /auth/brand` | 当前身份与菜单树 / 本地登录 / 退出 / 改密 / 首启判定 / 创建管理员 / 登录页租户品牌 |
| `GET /api/ai/oidc/list`、`POST /oidc`、`PUT /oidc/{id}`、`PUT /oidc/{id}/enabled`、`DELETE /oidc/{id}`、`DELETE /user/{uid}/oidc-binding` | OIDC 认证源（多 IdP）管理与按账号解绑（`clientSecret` 仅回显后 4 位；仍有账号绑定时拒绝删除） |
| `GET /api/ai/auth/oidc/config`、`/login-url`、`/callback`、`POST /auth/oidc/exchange-code` | 登录页公开认证源清单 / 生成授权跳转 / IdP 回调 / 授权码换身份 |
| `GET /api/ai/identity/providers`、`POST /identity/providers`、`PUT /{id}`、`DELETE /{id}`、`POST /providers/{id}/test`、`GET+POST+PUT+DELETE /identity/mappings`、`POST /identity/sync/{providerId}`、`GET /identity/sync/logs` | 企业认证源：OIDC / SAML / SCIM / LDAP 清单、增删改、LDAP 连接测试、组→角色 / 部门映射、立即同步与同步日志 |
| `GET /api/ai/auth/saml/config`、`GET /api/ai/auth/saml/login/{providerId}` | SAML 可用认证源清单 / 发起 SAML 登录（ACS 由 Spring Security SAML2 过滤链处理） |
| `GET /api/ai/scim/v2/ServiceProviderConfig`、`/Schemas`、`/Users`、`/Users/{id}`、`/Groups`、`/Groups/{id}` | SCIM 2.0 入站：能力发现、用户与组查询 / 创建 / 全量更新 / 删除 / PATCH（独立 Bearer 凭据，供 Entra / Okta 推送） |
| `GET /api/ai/service-account/list`、`POST /service-account`、`PUT /{id}`、`POST /{id}/rotate`、`GET /{id}/roles`、`DELETE /{id}` | 服务账号：清单 / 创建（明文令牌仅一次）/ 编辑与停用 / 轮换令牌 / 角色绑定 / 删除 |
| `GET /api/ai/tenant`、`POST /tenant`、`POST /tenant/{id}/status`、`/member`、`/archive`、`/restore`、`/brand`、`/{id}/inventory` | 租户管理：清单 / 开通 / 启停 / 成员归属 / 归档恢复 / 品牌 / 数据落点清单 |
| `GET /api/ai/tenant/requests`、`POST /tenant/apply`、`POST /tenant/requests/{id}/approve`、`/reject` | 自助开通：免登录申请、待审清单、批准（开租户并建首位管理员）与驳回 |
| `POST /api/ai/tenant/invites`、`GET /tenant/invites`、`POST /tenant/invites/{id}/revoke`、`GET /tenant/onboarding` | 邀请生成 / 在途邀请 / 撤销 / 首登引导状态 |
| `GET /api/ai/join/{token}`、`POST /join/{token}` | 邀请落地页：预览租户品牌与预选角色 / 接受邀请并注册（免登录，按 IP 限流） |
| `GET /api/ai/kb/list`、`POST /kb`、`PUT /kb/{id}`、`DELETE /kb/{id}`、`GET /kb/{id}/export`、`POST /kb/{id}/import`、`PUT /kb/doc/{docId}` | 知识库 CRUD（库级解析与检索参数稀疏覆盖，换绑向量模型时按库异步重嵌入）/ 整库导出导入 / 移动文档 |
| `GET /api/ai/kb/param-defaults`、`/kb/param-tips` | 全局解析与检索参数模板 / 参数说明（新建库预填与悬浮提示） |
| `POST /api/ai/document/upload`、`/upload/batch`、`/import-url` | 上传（异步解析）/ 批量上传 / 网页导入 |
| `GET /api/ai/document/list`、`/queue/stats`、`/stats`、`/health`、`/{id}/source`、`/{id}/vector-diagnose` | 文档列表 / 解析队列状态 / 统计 / 解析链路健康 / 源文件下载 / 向量诊断 |
| `POST /api/ai/document/{id}/reparse`、`/batch/reparse`、`/{id}/rollback`、`/{id}/backfill-descriptions`、`/{id}/restore`、`/{id}/purge`、`/rebuild-kb` | 重解析 / 批量重解析 / 版本回滚 / 补图片描述 / 恢复 / 彻底删除 / 整库重建 |
| `GET /api/ai/document/{id}/versions`、`/recycle`、`POST /recycle/purge\|batch/restore\|batch/purge`、`PUT /{id}/status\|refresh-config\|tags`、`DELETE /{id}` | 版本历史 / 回收站及批量处置 / 启停用 / 网页自动刷新配置 / 标签 / 删除（进回收站） |
| `GET /api/ai/knowledge/list?docId=`、`/knowledge/search`、`/knowledge/{id}`、`/knowledge/unmatched`、`POST /knowledge`、`PUT /knowledge/{id}`、`/{id}/status`、`DELETE /{id}` | 知识块预览（切片列表 / 结构导图）/ 跨文档搜索 / 详情 / 无命中问题 / 手动建块 / 编辑（自动重向量化）/ 启停用 / 删除 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试 |
| `GET /api/ai/eval/overview`、`/sets`、`/set`、`/cases`、`/runs`、`/runs/{id}`、`/runs/{id}/results`、`/runs/{id}/compare`、`/runs/{id}/export`、`/apply/status`、`/last-report`、`/answer/report`；`POST /eval/generate`、`/case(s)`、`/cases/import`、`/runs`、`/runs/{id}/cancel`、`/runs/{id}/baseline`、`/apply`、`/apply/rollback`、`/run-auto`、`/answer/run`；`PATCH/DELETE /eval/cases/{id}` | 检索评估总览 / 评估集版本与用例管理 / 异步参数组实验、历史、基线、跨次对比与导出 / 灰度应用与回滚 / 自动体检 / 答案质量评测 |
| `POST /api/ai/graph/{kbId}/build`、`GET /graph/{kbId}/status\|view\|triples\|search\|entity/{id}\|neighbor/{id}\|chunk/{id}`、`DELETE /graph/{kbId}` | GraphRAG 构建、状态、图谱视图、三元组与实体查询、按块溯源、清空 |
| `GET /api/ai/search-index/stats`、`POST /search-index/reindex`、`DELETE /search-index` | 关键词索引状态 / 全量重建 / 清空 |
| `GET /api/ai/desc-cache/stats`、`POST /desc-cache/prune`、`DELETE /desc-cache` | 图片描述缓存运维 |
| `GET /api/ai/ocr/health` | PDF 深度解析引擎（PP-StructureV3 / MinerU）逐引擎探活 |
| `GET /api/ai/provider/list`、`/available`、`/{id}/models`、`/{id}/references`、`POST /provider`、`/models/fetch`、`/test`、`/batch-enabled`、`/batch-delete`、`/{id}/clear-session-models`、`PUT /{id}`、`/{id}/models`、`/{id}/enabled`、`DELETE /{id}` | 模型供应商：登记（平台级 / 个人级）/ 可用列表 / 模型清单 / 引用清单 / 新建 / 拉取候选模型 / 连通测试 / 批量启停与删除 / 清除历史会话选择 / 编辑 / 保存模型 / 单个启停 / 删除 |
| `GET /api/ai/agent/list`、`/available`、`/consultable`、`/sub`、`/config-defaults`、`/tool-catalog`、`/permission-presets`、`/{id}/versions`、`POST /agent`、`/{id}/rollback`、`/{id}/default`、`/batch-delete`、`PUT /{id}`、`DELETE /{id}` | 智能体 CRUD / 对话页精简列表 / 可咨询列表 / 子智能体列表 / 默认值 / 工具目录 / 权限档位选项 / 配置版本与回滚 / 设默认 / 批量删除 / 删除 |
| `GET /POST /DELETE /api/ai/agent/{id}/publish` | 公开发布：查询 / 发布（生成 token 与 iframe 嵌入，可另开 MCP 端点）/ 撤销 |
| `POST /api/ai/agent/http-tools/preview` | HTTP 工具集导入预览：解析 OpenAPI JSON / YAML，返回建议名称、地址、操作清单与告警，供勾选后保存 |
| `GET /api/ai/agent-eval/summary`、`GET+POST /agent-eval/agents/{agentId}/suite`、`POST /agent-eval/agents/{agentId}/cases`、`/cases/generate`、`POST /agent-eval/agents/{agentId}/runs`、`GET /agent-eval/agents/{agentId}/runs`、`GET /agent-eval/runs/{runId}`、`POST /agent-eval/runs/{runId}/cancel`、`POST /agent-eval/agents/{agentId}/set-baseline`、`GET /agent-eval/agents/{agentId}/compare`、`GET+POST /agent-eval/agents/{agentId}/gate` | 智能体评测：总览与门禁状态、套件与用例、从会话采样、启动 / 取消回归、运行详情、设基线、A / B 对比、保存前检查 |
| `GET /api/ai/share/{token}/info`、`/history`、`/session/{token}`、`/artifact`、`POST /share/{token}/chat`（SSE）、`/attachment`、`/feedback`、`/chat/stop`、`GET /chat/run`、`/chat/resume`、`GET/DELETE /{token}/sessions` | 游客通道（免登录）：分享信息 / 历史 / 产物 / 流式对话 / 附件 / 反馈 / 停止 / 断线重连 / 游客会话查询与删除 |
| `GET /api/ai/skill/available`、`/list`、`/detail`、`/content`、`/file`、`/download`、`POST /skill`、`/install`、`/install/zip`、`PUT /skill/{name}`、`/{name}/disabled`、`DELETE /skill/{name}` | 技能：对话选用清单 / 管理页列表（来源、停用、体积、可否编辑）/ 详情（正文 + 文件清单）/ 正文 / 附属文件 / 下载 zip / 新建 / URL 安装 / zip 安装 / 编辑 / 个人停用 / 删除 |
| `GET /api/ai/market/list`、`/{id}`、`/{id}/download`、`POST /market/publish`、`/{id}/install`、`PUT /{id}/status`、`DELETE /{id}` | 市场：浏览与搜索 / 详情（含内容全文）/ 下载物品包 / 上架 / 安装（技能复制、模板 fork、工作流复制草稿）/ 上下架 / 删除 |
| `GET /api/ai/mcp/status`、`/audit/logs`、`/audit/summary`、`POST /mcp/servers`、`/servers/import`、`/servers/batch-delete`、`/servers/batch-enabled`、`/servers/{id}/enabled`、`/reload`、`/servers/{id}/reconnect`、`/probe`、`PUT /mcp/servers/{id}`、`/{id}/tool-label`、`DELETE /mcp/servers/{id}` | MCP Client：状态 / 调用审计（入站 + 出站）与汇总 / 登记 / JSON 批量导入 / 批量删除与启停 / 单个启停 / 整体重连 / 单个重连 / 连通性探测（可带临时 headers 或复用已存凭据）/ 编辑 / 工具中文名 / 删除 |
| `GET /api/ai/tools` | 平台已注册工具全景清单（按族分组，含展示名与说明） |
| `POST /ai/mcp/{token}`（`GET` 同路径；**不在 `/api` 之下**，token 即凭据） | MCP Server·per-agent 端点：一个智能体一个工具 |
| `POST /ai/mcp`（`GET` 同路径；`Authorization: Bearer sk-…` 或 `X-Api-Key`） | MCP Server·平台级入口：固定元工具集（检索 / 提问 / 列库 / 列智能体） |
| `GET /api/ai/scheduled/list`、`POST /scheduled`、`PUT /scheduled/{id}`、`DELETE /scheduled/{id}`、`PUT /{id}/enabled`、`POST /{id}/run`、`GET /{id}/runs`、`/batch-delete`、`/batch-enabled` | 定时任务：列表 / 新建 / 编辑 / 删除 / 启停 / 立即执行 / 执行记录 / 批量操作 |
| `GET /api/ai/workflow/list`、`/templates`、`/config-defaults`、`/{id}/export`、`POST /workflow`、`/import`、`/{id}/run`、`/run/{runId}/cancel\|resume`、`/validate`、`/{id}/publish\|unpublish\|rollback/{version}`、`/batch-publish\|unpublish\|delete`、`/from-agent/{agentId}`、`PUT /{id}`、`DELETE /{id}` | 工作流：列表 / 内置模板 / 节点默认值 / 导出 DSL / 新建 / 导入 / 运行 / 取消与续跑 / DSL 校验 + 编译 dry-run / 发布下线回滚 / 批量操作 / 由智能体转工作流 / 编辑 / 删除 |
| `GET /api/ai/workflow/{id}/run/list`、`/run/{runId}`、`/run/{runId}/stream`（SSE）、`GET+POST /{id}/run/{runId}/approval`、`/versions`、`GET+PUT /{id}/automation`、`POST /{id}/schedule/run-now` | 运行历史与详情 / 实时进度流 / 人工审核读取与裁决 / 版本 / 定时、回调与 Webhook 配置 / 立即运行一次 |
| `GET /api/ai/credential/list`、`POST /credential`、`PUT /{id}`、`DELETE /{id}` | 工作流凭据（值加密落库、出参脱敏，运行时按运行发起人解密填入） |
| `POST /api/ai/v1/workflows/{id}/run`、`GET /api/ai/v1/workflows/{id}/run/{runId}` | 对外工作流接口：持 API Key 触发**已发布**版本 / 按 runId 查结果 |
| `POST /api/ai/v1/workflows/webhook/{token}` | 工作流 Webhook 入站：令牌即凭据，按自动化配置的地址触发已发布版本（含人工审核节点的运行拒绝） |
| `GET /api/ai/project/list`、`/{id}`、`/{id}/files`、`/{id}/file`、`/{id}/file/content`、`GET+PUT /{id}/workspace-memory`、`GET+POST /{id}/items`、`PUT+DELETE /{id}/items/{itemId}`、`POST /project`、`PUT /project/{id}`、`PUT /{id}/executor`、`POST /{id}/open`、`PUT /{id}/status`、`DELETE /{id}` | 工作区：列表 / 详情（外观与记忆隔离）/ 本机目录单层浏览（虚拟路径 alias/相对路径，带全区合计）/ 下载 / 读文本原文 / 保存文本 / 工作区记忆读取与保存 / 事项清单与新增 / 事项更新与删除 / 新建（`{name, executorId, roots}`）/ 改名与外观 / 替换源文件夹 / 在电脑上打开 / 归档恢复 / 删除（本机文件不动） |
| `GET /ai/project-files/{token}` | 工作区文件签名预览（浏览器可直接打开的类型；`/api` 之外，token 带过期） |
| `GET /api/ai/sandbox/state`、`/tree`、`/files`、`/download`、`/files/download`、`POST /files/artifact`、`/run`、`GET /procs`、`/procs/live`、`POST /procs/kill` | 沙盒：容器状态 / 目录树 / 文件列表与下载 / 存为产物 / 执行 python·node·bash / 后台进程清单 / 实时探测 / 停止进程（连子进程一起） |
| `POST /api/ai/sandbox/tool-call` | 沙盒脚本回程调用本服务工具（一次性运行令牌） |
| `GET /api/ai/artifact/list`、`/{id}/content`、`PUT /artifact/{id}/project`、`POST /refresh-sign`、`/batch-delete`、`DELETE /{id}` | 我的产物：列表 / 读文本原文（预览台用，只回前 256KB 带 `truncated`）/ 移动到其他工作区 / 换发新鲜签名下载地址 / 批量删除 / 删除 |
| `GET /api/ai/api-key/list`、`POST /api-key`、`PUT /{id}`、`/{id}/disabled`、`/{id}/mcp`、`DELETE /{id}` | 对外 API Key：列表 / 签发（明文仅一次）/ 改名称与有效期 / 吊销 / MCP 授权 / 删除 |
| `GET /api/ai/notification/list`、`/unread-count`、`/counts`、`/preferences`、`POST /notification/read`、`/read-all`、`/delete`、`/clear`、`PUT /preferences` | 站内通知：列表（类型筛选 / 仅未读 / 游标分页）/ 未读数 / 各类型未读计数 / 静音与邮件偏好读取和保存 / 标记已读 / 全部已读 / 删除 / 清空（全部或仅已读） |
| `POST /api/ai/notify/mail/test`、`GET /notify/mail/deliveries` | 邮件外发：向当前管理员邮箱发送测试邮件 / 投递台账（按状态筛选，含尝试次数与最后错误） |
| `GET /api/ai/webpush/config`、`POST /webpush/subscribe`、`/unsubscribe`、`/test`、`/keys` | Web Push：能力与设备清单 / 订阅本机 / 退订 / 测试推送 / 生成 VAPID 密钥对（仅管理员，只回显不落库） |
| `POST /api/ai/ops/checkup`、`GET /checkup/last`、`/checkup/history`、`/checkup/trend`、`/checkup/{id}` | 系统巡检：立即体检 / 最近报告 / 历史 / 趋势 / 单次详情（逐项检查值、结论与处置建议） |
| `POST /api/ai/ops/user/{uid}/force-logout`、`/ops/user/batch-force-logout` | 会话治理：单个 / 批量强制下线（会话代数 +1，全部设备令牌当场失效） |
| `GET /api/ai/ops/announcement`、`POST /announcement`、`DELETE /{id}`、`DELETE /{id}/purge`、`GET /announcement/active`、`POST /announcement/{id}/read` | 平台公告：管理清单 / 发布与更新 / 下线 / 彻底删除 / 当前生效公告 / 标记已读 |
| `GET /api/ai/audit`、`GET /audit/export`、`GET /audit/verify` | 审计事件：分页查询 / CSV 合规导出 / 哈希链校验 |
| `GET /api/ai/quota`、`POST /quota`、`DELETE /quota/{id}`、`GET /stats/quota` | 推理配额：规则清单（含本周期用量与进度）/ 新建或覆盖 / 删除 / 成员自查 |
| `GET /api/ai/content-safety`、`/content-safety/status`、`POST /content-safety`、`DELETE /{id}`、`POST /dry-run` | 内容安全：规则清单 / 闸门状态 / 新建或修改 / 删除 / 试跑（不落库） |
| `GET /api/ai/stats/usage`、`/stats/cost` | 个人使用统计聚合（统计卡 / 热力图 / 每日趋势 / 模型用量 / 用途分项）/ 费用口径（区间费用、按用户与按模型、未计价 tokens） |
| `GET /api/ai/trace/list`、`/chat/{id}`、`/workflow/{runId}`、`/pool`、`/stats`、`POST /trace/pool/{id}/label\|dismiss`、`/sample/run` | 执行 Trace：列表与详情 / 采样池 / 采样统计 / 标注回流 / 忽略 / 立即采样 |
| `GET /api/ai/user/list`、`/user/page`、`/preference`、`/settings`、`POST /user`、`PUT /user/{uid}`、`/{uid}/password`、`/preference`、`/profile`、`/email`、`/settings`、`DELETE /{uid}`、`/{uid}/oidc-binding` | 成员列表 / 成员分页（关键字·部门·状态库侧过滤，供万级名单的「会话治理」用）/ 个人偏好（默认模型、记忆开关）读取与保存 / 个人配置覆盖通道（当前无 personal 字段，端点保留）/ 新建用户 / 编辑成员与重置密码 / 保存资料与头像 / 设置本人通知邮箱 / 删除 / 解绑单点登录 |
| `GET /api/ai/role/list`、`/options`、`/{code}/permissions`、`POST /role`、`PUT /{code}`、`/{code}/permissions`、`DELETE /{code}` | 角色管理与统一权限读写（权限 = 菜单 + 名下接口，单棵树勾选） |
| `GET /api/ai/menu/list`、`/my-layout`、`POST /menu`、`PUT /{id}`、`/my-layout`、`DELETE /{id}` | 菜单维护与个人侧栏布局（顺序、显隐）读写 |
| `GET /api/ai/department/list`、`POST /department`、`PUT /{id}`、`DELETE /{id}` | 部门树维护 |
| `GET /api/ai/api/list`、`POST /api`、`PUT /{id}`、`DELETE /{id}` | RBAC 接口清单维护（启动期由 `ApiEndpointScanner` 自动扫描登记，可按菜单归属分组） |
| `GET /api/ai/memory`、`/content`、`POST /memory/compact`、`PUT /memory`、`DELETE /memory`、`/daily` | 个人长期记忆面板：概览与 `MEMORY.md` 读取 / 保存 / 按上限裁剪 / 清空 / 删除日账 |
| `GET /api/ai/config`、`/schema`、`/public`、`/rerank/check`、`/keyword/check`、`POST /config/probe`、`/reset`、`PUT /config` | 配置读写（脱敏）/ 设置项 schema 下发 / 免登录公开项 / 重排与关键词引擎连通检查 / 用未保存值先测后存 / 恢复分组默认 |
| `POST /api/ai/feedback`、`GET /analytics/summary`、`GET /analytics/badcases` | 赞 / 踩反馈回流 / 看板聚合 / 差评样本 |
| `GET /api/ai/schedule/tasks`、`/runs`、`POST /schedule/tasks/trigger`、`DELETE /schedule/runs` | 调度中心：任务运行状态快照 / 执行日志 / 手动触发 / 清空日志 |
| `GET /api/ai/manual/documents`、`/documents/{id}/content`、`POST /manual/sync` | 帮助中心数据源：篇目列表 / 单篇 Markdown 原文 / 管理员手动同步官方手册入库（内容未变逐篇跳过） |

### SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 | 载荷 |
|------|------|------|
| `token` | 流式生成逐片 | 回答文本增量 |
| `plan` | 轮次开始，按当前配置确定会跑的步骤 | 执行计划步骤名数组，如 `["理解问题","深度思考","检索知识库","生成回答"]`（仅实时下发，不随消息持久化） |
| `image` | 生成之前 | 命中图片 URL 列表（按编号顺序，生产为 HMAC 签名 URL） |
| `retrieved` | 检索 + 重排 + 上下文填充完成 | 检索概览 `{keywords, refs, terms}`（随消息持久化） |
| `thinking` | 深度思考开启时 | 思考链增量 |
| `thinking_done` | 深度思考结束（正常完成或截断降级） | `{status: ok\|degraded, thinking: 完整思考链}` |
| `process` | 工具模式下模型推理 / 试错独白（与正文分流） | 过程独白增量（收在顶部过程块里展示；全文随消息持久化） |
| `tool_status` | 调用工具 | 工具名 / 状态（done\|error）/ 结果或错误（含尝试次数 `attempts`） |
| `tool_output` | 沙盒命令执行中 | 命令输出逐行增量（实时流式） |
| `approval_required` | 有副作用工具执行前等待确认 | `{approvalId,tool,args,timeoutMs}`（批准 / 拒绝经 `POST /api/ai/tool-approval/{id}`） |
| `plan_delta` | 计划模式产出执行计划时 | 计划文本增量（执行计划卡流式成形） |
| `plan_approval` | 计划生成完毕、等待确认 | `{planApprovalId,plan,timeoutMs,auto?}`（确认 / 取消 / 带稿批准经 `POST /api/ai/plan-approval/{id}`，继续对话走同端点 `supersede`） |
| `plan_cancelled` | 计划未批准（取消或超时收尾） | `{}`（卡片定格「未批准」，本轮停止） |
| `plan_superseded` | 用户提修改意见、本版被取代 | `{}`（卡片定格「已被取代」，意见作为新消息在同会话继续） |
| `subagent_step` | 被委派那一支正在执行的第几步 | 子轮工具调用与输出增量（过程由「委派助手执行」卡片呈现） |
| `agent_delegated` / `agent_consulted` | 本轮由被 @ 的智能体作答 / 本轮咨询过谁 | 编排信息与归属 |
| `artifact` | 产物交付落盘 | 产物卡片字段 `{id,url,filename,ext,size,description}` |
| `usage` | 生成开始预下发 prompt 侧用量，`done` 时以网关实测覆盖 | tokens `{window,windowSource,context,prompt,cached,cacheWrite,reasoning,output,…}` |
| `warn` | 非致命降级 / 告警 | 提示文案 |
| `done` | 回答完成 | `sources`、`related`（下一步建议数据源）、`messageId`、`thinking`、`finalContent`/`finalImages`（校验修正后的全文与图片）、`processText`、`timeline`（段数组，刷新后重建过程块与最终答案的数据源）、`degradations`、`artifacts`、`toolCalls`、`tokens`（窗口与来源、缓存读取与写入 token、实测用量、滚动压缩轮数）、`subagentBranches`/`subagentRoute`；缓存命中时仅 `finalContent`/`finalImages` |
| `error` | 处理或下发异常 | 错误文案 |

> **完整思考链**在 `done.thinking` 一次性给出；`GET /api/ai/chat/resume`、`GET /api/ai/share/{token}/chat/resume` 与 `GET /api/ai/workflow/{id}/run/{runId}/stream` 复用同一套事件语义，客户端接流后无须区分首发与重放。

## 配置参考

### application.yml 关键项

完整默认值见 `src/main/java/com/wenqu/ai/config/AppProperties.java`：

```yaml
ai-app:
  chunk:
    max-size: 800
    overlap: 100                           # 分块重叠（只进向量化文本，不入库）
    max-chunks: 3000                       # 单文档解析最大知识块数（0=不限制）
    structural: true                       # 结构感知切分（docx，需重解析生效）
    heading-depth: 4                       # 章节标题识别上限层级 1~6（需重解析生效）
  retrieval:
    vector-weight: 0.6                     # 混合检索：向量权重
    keyword-weight: 0.4                    # 混合检索：关键词权重
    rerank:                                # 平台内置重排服务（OpenAI 兼容 /v1/rerank；由外部供应商网关或自建服务承载）
      enabled: ${AI_RERANK_ENABLED:true}   # 默认开；未绑定模型或服务不可用时按融合分排序，不阻塞问答
      base-url: ${AI_RERANK_BASE_URL:}     # 重排模型引用归知识库与平台默认槽位，不在这里配模型名
  keyword:                                 # 关键词召回引擎（mysql|meilisearch）
    engine: ${AI_KEYWORD_ENGINE:mysql}
    base-url: ${AI_MEILI_BASE_URL:http://localhost:7700}
    api-key: ${AI_MEILI_KEY:}
    index: ${AI_MEILI_INDEX:wenqu-chunks}  # 只从 env/yml 读，永不入库
  ratelimit:                               # 接口限流（Redis 固定窗口）
    enabled: ${AI_RATELIMIT_ENABLED:true}
    chat-per-minute: ${AI_RATELIMIT_CHAT:10}
    upload-per-minute: ${AI_RATELIMIT_UPLOAD:10}
  context:                                 # 上下文填充配额（窗口与最大输出按模型声明，无全局兜底；0.7 系数只用于登记校验）
    max-context-hits: 8                    # 知识块填充上限
  images:
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（源文件 / 图片 / 产物 / 密钥都在此）
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:true}
  auth:
    jwt-secret: ${AI_JWT_SECRET:}          # 多副本必须一致；非 local profile 缺失即启动失败
    token-ttl-hours: 168
    max-login-failures: 5                  # 连续失败次数
    lock-minutes: 15                       # 锁定时长

spring:
  servlet.multipart: { max-file-size: 256MB, max-request-size: 260MB }
  data.redis:
    client-type: jedis
    host: ${REDIS_HOST:127.0.0.1}
    port: ${REDIS_PORT:6379}
    connect-timeout: 5s
    jedis.pool: { max-active: 64, max-idle: 32, min-idle: 8, max-wait: 2s }
  ai:
    model:                   # spring-ai-starter-model-openai 的自动装配全部关闭：
      chat: false            #   yml 里没有明文 api-key 时这些 Factory bean 启动即抛 "API key must be set"
      embedding: false
      audio: { speech: false, transcription: false }
      image: false
      moderation: false
    vectorstore:
      type: none             # 关闭全局 RedisVectorStore（per-KB 独立索引由 KbVectorStoreRegistry 管理）
```

> **模型配置无兜底**：`spring.ai.openai.*` 不配置；聊天 / 视觉 / 向量 / 重排 / OCR 模型引用解析不出时显式报错并引导到可修改的位置，不静默回落任何全局默认。记忆提炼复用本轮会话模型，不单独配置引用。

### 系统设置（260 项行为参数）

设置项 schema 唯一定义源是 `src/main/resources/config-schema.json`：后端据此校验并下发前端渲染设置页，缺失即启动失败。字段按**层级**标注（必需 8 项 / 调优 86 项 / 工程排障 166 项），设置页默认折叠低层项；另有 37 项内部键只从 `ConfigService.defaults()` 读取、不进设置页（如 `chat.resumeEnabled`、`ratelimit.chatPerMinute`、`sandbox.provisionerUrl`、`share.guestDailyTurns`、`webpush.enabled` 等）。

**归属分层**：

| 归属 | 说明 |
|------|------|
| 平台全局 | 设置页 260 项中除 `rerank.model` 之外全部 |
| 个人专属 | `rerank.model`（无全局槽位，只对登记人生效）；另有个人在「个人设置」里的默认模型、记忆开关、通知静音 / 邮件偏好、通知邮箱（走 `c_ai_user`，不占设置页） |
| 资源级绑定 | 知识库的向量 / 视觉 / OCR / 重排 / 图谱抽取模型与解析、检索参数；文档的图片理解模型；智能体的人设、能力开关、权限档位、工具确认模式、步数上限、目标模式开关、记忆域、HTTP 工具集、工作流工具、轮终回调；会话的模型覆盖；用户的 MCP 服务、技能与 Web Push 设备 |

**敏感项按后缀判定**（`.apiKey` / `.clientSecret` / `.token` / `*Password`）：RSA 加密入库、页面不回显明文（编辑时输入框留空即沿用已存值）、「恢复默认」跳过敏感项。多副本部署时动态配置经 Redis pub/sub 广播即时生效，另有 5 分钟兜底轮询。

按前缀列主要项（括号内为默认值）：

| 前缀 | 主要键（默认值） | 生效方式 |
|------|------------------|----------|
| `chat.*` | `citationCheckEnabled`(true)、`sseTimeoutMs`(300000)、`approvalTimeoutMs`(120000)、`askTimeoutMs`(600000)、`planTimeoutMs`(600000)、`modelStallTimeoutMs`(60000)、`maxImagesPerMessage`(9)、`maxImageMb`(10)、`uploadRetentionHours`(24)、`uploadCleanupIntervalMs`(1h)、`retrievalDebugEnabled`(true) | 聊天模型走「会话选择 > 个人默认」；行为项保存即生效。「超时与等待」提供**耐心档位**（标准 / 长任务 / 无人值守）一次写齐四个超时，也可逐项微调；`modelStallTimeoutMs` 管的是一次模型调用**停住不动**多久判这条流已断（还在出字就不算，长回答不受约束），故不随档位调整；回答温度、断线重连、流水线线程数为内部键 |
| `memory.*` | `enabled`(true)、`flushThrottleMinutes`(10，0=每轮)、`maxMdTokens`(3000) | 长期记忆平台总闸与预算；合并时给模型的目标预算按上限的 2/3 推导，写入落盘再按上限强制裁剪；日账保留期沿用框架默认 90 天 |
| `retrieval.*` | `vectorWeight`(0.6)、`keywordWeight`(0.4)、`vectorTopK`(15)、`vecThreshold`(0.3)、`keywordLimit`(20)、`minContextScore`(0.6 重排门)、`minFusionScore`(0.5 融合门)、`lowConfidencePolicy`(top1)、`aclTopKSupplant`(true) | 保存即生效；同一组参数也可在知识库编辑里逐库稀疏覆盖；低分全灭时 `top1` 保底唯一最高分块，`off` 关闭保底 |
| `rerank.*` | `enabled`(true)、`platformRef`（平台默认重排模型，空=不重排）、`minHits`(6 少于几条不重排)、`maxHits`(15 重排窗口)、`model`（个人专属重排模型） | 取值顺序：知识库检索设置 > 平台默认 > 内部回落；未绑模型或服务不可用时按融合分排序并提示，不阻塞问答 |
| `keyword.*` | `engine`(mysql)、`baseUrl`、`apiKey`、`reconcileIntervalMs`(1h)、`reconcileOnStartup`(false) | 保存即生效；切换引擎需全量重建一次索引 |
| `context.*` | `maxContextHits`(8)、`maxBlocksPerDoc`(3)、`maxBlocksPerKb`(4) | 保存即生效；历史压缩由框架按模型窗口触发，无独立开关 |
| `chunk.*` / `parse.*` / `upload.*` | `maxSize`(800)、`overlap`(100)、`maxChunks`(3000)、`maxImages`(100)、`structural`(true)、`structuralRatio`(0.8)、`headingDepth`(4)、`qaEnabled`(true)、`qaPerChunk`(2)、`childEnabled`(true)、`childSize`(400)、`ocrEngine`(none)、`ocrMinText`(20)、`ocrDpi`(200)、`concurrency`(3)、`queue.capacity`(500)、`taskTimeoutMs`(20min)、`retryMaxAttempts`(3)、`upload.maxFileSizeMB`(200) | **需重解析或新上传才生效**（解析参数只影响之后的解析）；`ocrEngine` 可选 none / vision / pp_structure_v3 / mineru |
| `vision.*` | `enabled`(true)、`prompt`、`concurrency`(2)、`userImageConcurrency`(2)、`timeoutMillis`(30000)、`retryCount`(1)、`descCacheVersion`("1")、`descCacheTtlDays`(180) | 保存即生效（`timeoutMillis` 需重启）；视觉模型按知识库绑定，无全局键 |
| `images.*` | `maxWidth`(1280)、`quality`(0.9)、`authEnabled`(true)、`authExpireSeconds`(3600)、`chatCleanupIntervalMs`(1d)、`chatRetentionMillis`(7d) | 保存即生效 |
| `tool.*` | `enabled`(true)、`knowledgeRetrieval.maxHits`(5)、`sandbox.enabled`(false)、`runcode.enabled`(false)、`runcode.maxSubCalls`(50)、`maxParallel`(8)、`timeoutSeconds`(120)、`subCallTimeoutMs`(60000)、`spillCleanupIntervalMs`(1h)、`spillMaxMbPerUser`(200)、`deny`（平台停用工具名单） | 保存即生效。知识检索 / 产物交付 / 内置 / 联网搜索 / 任务清单随总闸启用，由智能体三态或停用名单限定；工具大输出超上限时整份留存供读回，按人配额 |
| `agent.*` | `permissionPreset.default`(standard)、`permissionPreset.max`(autonomous)、`maxToolSteps`(15，0=不限制)、`consultMaxPerTurn`(3)、`consultTimeoutMs`(300000)、`subagentMode`(digest)、`versionKeep`(20) | 保存即生效；档位取值 research / standard / autonomous，平台上限之下的智能体按上限执行 |
| `httpTool.*` | `enabled`(true)、`timeoutMs`(10000)、`allowPrivateNetwork`(true) | HTTP 工具族总闸（仍需 `tool.enabled`）；超时为工具集未单独配置时的默认值；公网多租户部署建议关闭内网访问 |
| `workflowTool.*` | `enabled`(true) | 工作流工具族总闸（仍需 `tool.enabled`）；没有配置工作流的智能体天然无工具可出 |
| `agentWebhook.*` | `enabled`(true)、`retryMax`(4) | 智能体轮终回调总开关与最大重试次数；关闭后已有 pending 投递按原排程走完或就地耗尽 |
| `agentMemory.*` | `enabled`(true) | 智能体级记忆总闸；关闭后所有智能体回落用户级记忆，`memory_mode` 失效 |
| `apiOpen.*` | `allowedOrigins`("") | OpenAI 兼容端点与开放 API 的 CORS 白名单（逗号分隔；空=不放开跨域） |
| `notify.mail.*` | `enabled`(false)、`host`、`port`(587)、`security`(starttls)、`username`、`smtpPassword`(敏感)、`from`、`fromName`(问渠)、`types`(审批 / 提问 / 计划 / 工作流 / 租户申请 / 额度)、`minIntervalMs`(5min 聚合窗口)、`retryDelaysSeconds`(60,300,900)、`webBaseUrl` | 保存即生效；口令 RSA 加密；总闸关闭时全站零外发，站内通知与 Web Push 不受影响 |
| `contentSafety.*` | `enabled`(true)、`provider`(local)、`baseUrl`、`apiKey`(敏感)、`timeoutMs`(3000)、`remoteOnInput`(true)、`remoteOnOutput`(false)、`remoteCheckChars`(400)、`outputHoldChars`(24)、`scanThinking`(true)、`maxScanChars`(20000)、`inputRefusal`、`outputSuffix` | 输入 / 输出双闸与可选外部检查服务；出口流式按 `outputHoldChars` 压尾再放行；规则清单在「权限管理 → 内容安全」维护 |
| `ops.*` | `checkupIntervalMs`、`checkupRetentionDays`、`checkupAlertEnabled` | 系统巡检节拍、报告保留期与失败告警；手动巡检不受间隔限制 |
| `share.guest*`（内部键） | `share.guestDailyTurns`(200)、`share.guestDailyTurnsPerVisitor`(20)、`share.guestDailyTokens`(200 万) | 分享链接的游客轮次与 token 日额度，跨副本 Redis 计数；0=关闭该层 |
| `webSearch.*` | `enabled`(false)、`provider`(tavily)、`apiKey`(敏感)、`maxResults`(5)、`maxCallsPerTurn`(2)、`requireApproval`(true)、`baseUrl`、`timeoutMs`(8000)、`snippetChars`(600) | 服务商 tavily / bocha / generic（自建 SearXNG）；保存即生效 |
| `skill.*` | `disabled`（平台停用清单，从当前可用技能多选）、`remoteAllowedHosts`（URL 安装允许的域名，逗号分隔；空=关闭远程安装） | 个人停用在「智能体 → 技能」自助；正文读取上限 20000 字为平台常量；技能目录 `WENQU_SKILLS_USERS_DIR` |
| `chat.goal.*`（目标模式面板，20 项） | `maxRounds`(40)、`nextRoundDelayMs`(2000)、`maxCompletionChecks`(2)、`maxVerifyFailures`(5)、`maxTokens`(200 万)、`maxMs`(2 小时)、`maxToolCalls`(400)、`maxRetries`(2)、`maxRecoveries`(3)、`retryBaseSeconds`(15)、`retryMaxSeconds`(120)、`softBudgetRatio`(0.8)、`claimBatch`(5)、`workers`(4)、`tickMs`(2000)、`leaseSeconds`(300)、`heartbeatStaleSeconds`(90)、`staleGraceSeconds`(90)、`verifyLocalFiles`(false)、`initialTickDelayMs`(1000) | 四项硬预算均可填 **0 = 该项不限制**；软阈值决定预算用到几成开始在提示里要求收敛；租约与心跳参数由 `GoalScheduler` 用于多副本抢占执行权 |
| `workflow.*` | `maxSteps`(50)、`runTimeoutSeconds`(600)、`subagentTimeoutMs`(180000)、`maxConcurrentRuns`(4)、`runQueueCapacity`(50)、`runLogRetentionDays`(30)、`runCleanupIntervalMs`(1d)、`approvalReapIntervalMs`(60s)、`scheduleScanIntervalMs`(30s) | 保存即生效；`maxConcurrentRuns` 与 `runQueueCapacity` 需重启 |
| `scheduled.*` | `enabled`(true)、`maxPerUser`(20)、`scanIntervalMs`(30000)、`timeoutMs`(300000) | 定时执行智能体 |
| `web.*` | `refreshEnabled`(true)、`refreshScanIntervalMs`(60s) | 网页源定时刷新总开关与扫描间隔 |
| `graphrag.*` | `batchChunks`(3)、`maxTriplesPerChunk`(10)、`expandTopK`(5) | 抽取成本与图扩展规模；开关归知识库 `graph_enabled`（默认关） |
| `cleanup.*` | `sessionRetentionDays`(30)、`sessionCleanupIntervalMs`(1d)、`sessionEventRetentionDays`(30)、`toolSpillRetentionDays`(7)、`visitorIdleDays`(30) | 会话、事件账本、工具外溢原文与游客会话的保留期 |
| `eval.*` / `trace.*` | `autoIntervalMs`(1d)、`autoThresholdPct`(10)、`judgeEnabled`(false)、`judgeModel`、`answerModel`、`samplingIntervalMs`(1d)、`sampleRandomDaily`(20)、`sampleNoHitDaily`(10)、`sessionEventEnabled`(true) | 自动体检、评判模型与线上采样；`eval.judgeModel` 未配置时体检跳过评分 |
| `notification.*` | `retentionDays`(30)、`dedupWindow`(1440 分钟)、`cleanupIntervalMs`(1d) | 站内通知保留期、同类去重窗口与清理间隔 |
| `artifact.*` | `retentionDays`(90)、`cleanupIntervalMs`(1d) | 产物超期清理 |
| `schedule.*` | `runLogRetentionDays`(7)、`runLogCleanupIntervalMs`(1d) | 调度中心执行日志 |
| `sandbox.*` | `projectUploadMaxMb`(100)、`commandTimeoutSeconds`(180)、`idleReleaseMinutes`(60)、`project.turnGuard.leaseTtlMs`(2h)；`cleanupIntervalMs`(10min)、`maxOutputBytes`(256KB)、`keepaliveIntervalSeconds`(30)、`deleteTimeoutSeconds`(120) 为内部默认 | 保存即生效（客户端懒构建）。连接地址 / 令牌 / 回调地址 / 虚拟根走环境变量；`projectUploadMaxMb` 服务端再钳到 256（multipart 上限）；轮次租约用于「有轮次在跑时禁止改名移动删除」 |
| `mcp.*` | `server.enabled`(**false**)、`server.timeoutMs`(50000)、`server.allowedOrigins`("")、`patrolIntervalMs`(5min) | `server.allowedOrigins` 为空时仅允许本机回环（Origin 校验防 DNS rebinding）；`server.timeoutMs` 压在多数 MCP 客户端自身的 60 秒请求超时之下；`patrolIntervalMs` 为连接健康巡检间隔（0=关闭） |
| `chat.agentscope*` / `chat.sessionLogArchive.*`（调试设置） | `agentscopeMaxIters`(12，0=不限制)、`agentscopeStateDir`(`user-data/agentscope`)、`agentscopeRedisPrefix`(`agentscope:session:`)、`sessionLogArchive.enabled`(true)、`.keepDays`(90)、`agentscopeJournalRotateEvents`(12000)、`agentscopeJournalRotateKeepDays`(30) | 框架引擎推理步数上限、状态目录与 Redis 前缀、会话事件日志归档与换代；多副本必须把状态与归档目录指向共享卷 |

### 设置页 16 个导航项（21 个配置面板，260 项参数）

**对话模型**（24）· **检索设置（RAG）**（4 页签：检索与重排 20 / 上下文 3 / 文档解析 19 / 图片描述 8）· **工具调用**（3 页签：总开关与子工具 19 / 联网搜索 10 / 技能 2，底部附只读工具与技能名册）· **HTTP 工具**（3）· **工作流工具**（1）· **智能体轮终回调**（2）· **智能体级记忆**（1）· **开放 API**（1）· **接口限流**（1）· **定时任务**（76，4 页签：参数配置 / 运行状态 / 执行日志 / 索引与健康）· **通知外发（邮件）**（12）· **沙盒**（9）· **MCP 服务（双向）**（5）· **调试设置**（11）· **目标模式**（20，3 节：推进与轮次 / 预算与恢复 / 调度与核验）· **内容安全**（13）。

`webSearch`、`skills` 与 RAG 链路的 `context`、`chunk`、`vision` 五个面板并入宿主面板页签，导航不单列；页头「保存配置」一次性提交全部改动，支持参数搜索与「恢复本组默认」（定时任务、调试设置两组不提供整组恢复）。模型不在设置页登记——统一走「智能体 → 模型供应商」；深度思考也不设全局面板，思考强度在对话页按模型选择。内容安全规则、企业认证源与服务账号在「权限管理」各页签维护；智能体评测在「智能体 → 评测」；审计事件与系统巡检在「安全与运维」。

## 后台定时任务

全部周期任务集中在 `ScheduleCenter`：单 daemon 线程按 **10s 节拍**轮询「是否到点」，间隔每次实时读配置（改配置即时生效、≤0 暂停），任务体提交线程池执行，上一轮未结束则跳过（防重叠），失败仅告警并下轮重试。执行日志（`c_ai_schedule_run`）只记「有信息量」的执行：失败 / 手动触发 / 有实质产出——高频任务空跑不落行；运行状态快照与手动触发在「系统设置 → 定时任务 → 运行状态」查看。

| 任务 | 间隔键（默认） | 做什么 |
|------|----------------|--------|
| 关键词索引精确对账 | `keyword.reconcileIntervalMs`(1h) | 按 `(id, contentHash)` 双向比对 MySQL 有效块与 Meilisearch 文档，定向修复漂移；差异超限转全量重建，`GET_LOCK` 互斥保证多副本安全 |
| 聊天图片目录清理 | `images.chatCleanupIntervalMs`(1d) | 删除超过 `images.chatRetentionMillis`（7 天）的用户聊天图片 |
| 聊天附件超期清理 | `chat.uploadCleanupIntervalMs`(1h) | 删除超过 `chat.uploadRetentionHours`（24 小时，0=不清理）的会话附件 |
| 工具外溢原文清理 | `tool.spillCleanupIntervalMs`(1h) | 删除超过 `cleanup.toolSpillRetentionDays`（7 天，0=不清理）的工具大输出留存原文 |
| 检索评估自动体检 | `eval.autoIntervalMs`(1d) | 按线上参数跑评估集、落一条 scheduled 运行历史并与上期对比，下滑写预警结论供看板红绿灯；灰度应用观察期内下滑自动回滚（观察期 `eval.applyObserveHours`，默认 24h）；评估集为空自动跳过 |
| 系统巡检 | `ops.checkupIntervalMs` | 按节拍全面体检并把报告落库；失败时给管理员发站内通知，手动巡检不受间隔限制 |
| 巡检报告清理 | `ops.checkupRetentionDays` | 删除超过保留期的巡检历史报告（0=永久保留） |
| 智能体回调重试 | 内置节拍（退避 60s / 5min / 30min / 2h） | 扫描到期的轮终 Webhook 投递行并补发；耗尽后通知智能体主人（`agentWebhook.enabled` 总开关之下） |
| 长任务接续扫描 | `chat.turnResumeWindowMinutes` | 把「发出去却再没有过回答」的轮按中断收口，留下已生成部分并提示发「继续」 |
| 聊天档临时目录清理 | `cleanup.tempFileRetentionDays` | 清理超过保留期的聊天档宿主临时目录（0=不清理） |
| 配置缓存兜底刷新 | `config.reloadIntervalMs`(5min) | 补齐 Redis 订阅断线期间错过的配置变更 |
| 过期会话/消息清理 | `cleanup.sessionCleanupIntervalMs`(1d) | 物理删除逻辑删除超过 `cleanup.sessionRetentionDays`（30 天）的会话与消息 |
| 产物超期清理 | `artifact.cleanupIntervalMs`(1d) | 删除超过 `artifact.retentionDays`（90 天，0=不清理）的产物文件与记录 |
| 会话日志归档清理 | `chat.sessionLogArchive.cleanupIntervalMs`(1d) | 按文件最后写入时间删除超过 `chat.sessionLogArchive.keepDays`（90 天，0=不清理）的归档，以及换代搬走的旧代次（`agentscopeJournalRotateKeepDays`，30 天） |
| 定时智能体任务 | `scheduled.scanIntervalMs`(30s) | 扫描到期的用户定时任务并投递线程池执行（扫描与执行分离，长任务不拖节拍） |
| 文档解析队列扫描 | `parse.queue.scanIntervalMs`(5s) | 抢占 `c_ai_parse_task` 到期任务交给 worker 池（上传与重解析全靠它推动，DB 队列不怕进程重启） |
| 网页源定时刷新 | `web.refreshScanIntervalMs`(60s) | 扫描到期且开启自动刷新的 URL 文档，重抓网页并同名替换重建 |
| 沙盒空闲回收 | `sandbox.cleanupIntervalMs`(10min) | 回收空闲超过 `sandbox.idleReleaseMinutes`（60 分钟，0=不回收）的会话沙盒容器 |
| 工作流审批超时回收 | `workflow.approvalReapIntervalMs`(60s) | 挂起超过节点 `timeoutSeconds` 的人工审核 run 落 timeout 终态 |
| 工作流运行记录清理 | `workflow.runCleanupIntervalMs`(1d) | 物理删除超过 `workflow.runLogRetentionDays`（30 天）的运行记录（挂起审批的 run 排除） |
| 工作流定时触发 | `workflow.scheduleScanIntervalMs`(30s) | 扫描到期的定时工作流并派发已发布版本运行（先推进 `next_run_at` 防重复） |
| Trace 线上采样 | `trace.samplingIntervalMs`(1d) | 线上对话按规则入采样池（差评必采 + 无引用 / 随机限额），供标注回流评测集 |
| 任务执行日志清理 | `schedule.runLogCleanupIntervalMs`(1d) | 物理删除超过 `schedule.runLogRetentionDays`（7 天）的调度执行日志 |
| 站内通知清理 | `notification.cleanupIntervalMs`(1d) | 物理删除超过 `notification.retentionDays`（30 天）的站内通知 |

**目标模式调度器**独立于 `ScheduleCenter`：`GoalScheduler` 按 `chat.goal.tickMs`（默认 2s）节拍批量认领（`claimBatch` 5）到期的目标轮次，用 DB 租约条件更新（`leaseSeconds` 300）保证同一目标只有一个副本在执行，心跳超过 `heartbeatStaleSeconds` 判失活并在 `staleGraceSeconds` 宽限后恢复或转暂停；`workers`（4）为并发执行线程数（改动需重启）。

**MCP 连接巡检**由 `McpClientService` 自身的 `@Scheduled` 驱动（固定 60s 节拍、内部按 `mcp.patrolIntervalMs` 节流），只检查非空闲池里处于 connected 的服务；`listTools` 失败才翻红并接退避重试，远程调用一律在锁外执行。

## 目录结构

```
WenQu/                               # 工作区根（git 仓库名 WenQu；本地目录名可自定义）
├── pom.xml                          # 后端 Maven 工作区（com.wenqu:wenqu，产物 target/wenqu.jar）
├── src/main/java/com/wenqu/ai/
│   ├── WenQuApplication.java        # 入口
│   ├── config/                      # SecurityConfig（JWT + RBAC）· SchemaMigrator（存量库补列补索引）·
│   │                                #   ApiEndpointScanner（启动扫描接口清单）· ConfigSchemaService ·
│   │                                #   OpenApiConfig / OpenApiCorsConfig · GlobalExceptionHandler · McpEndpointConfig
│   ├── controller/                  # 57 个控制器、504 个端点（完整契约以 Swagger 为准）
│   ├── agentx/                      # AgentScope 适配层：问答引擎、工具注入与挂载过滤、权限档位、步数预算、
│   │                                #   框架工具中文名、流式刷新、事件日志归档与换代、目标控制工具
│   ├── agent/                       # 工具族与注册中心 · 权限档位 · 目标模式（调度、提示装配、证据核验）
│   ├── service/                     # 问答与检索（ChatService / HybridRetrievalService / RerankService /
│   │                                #   RetrievalGate / GraphRagService）· 知识库与文档 · 工作流（DSL / Validator /
│   │                                #   Engine / 运行流）· 智能体与市场 · 沙盒与工具 · 开放接入（httptool /
│   │                                #   workflowtool / webhook）· 企业治理（TenantService / MailDispatchService /
│   │                                #   DirectorySyncService / AuditService / UsageQuotaService /
│   │                                #   contentsafety）· 基础设施（ModelRegistryService / ConfigCryptoService /
│   │                                #   ScheduleCenter / NotificationService / WebPushNotifier /
│   │                                #   UsageLedgerService / ToolSpillService）
│   ├── sandbox/                     # 沙盒客户端：provisioner 连接、命令与文件通道、路径校验、宿主事件日志存储
│   ├── parser/                      # DocumentParser 接口 + Docx / Pdf（扫描件 OCR）/ Excel / Ppt / Text 解析器
│   ├── model/ + mapper/ + dto/      # 81 张表的实体 + Mapper + DTO
│   ├── schedule/                    # ScheduleCenter（周期任务注册、运行状态快照、执行日志）
│   ├── startup/                     # RbacSeedRunner（菜单与角色幂等补齐）· ApiEndpointScanner（接口清单扫描）·
│   │                                #   MarketBuiltinSeeder（内置工作流模板）· ManualSeedService（官方手册同步）·
│   │                                #   ContentSafetySeedRunner（内容安全首批规则）· BrowserCapabilitySeeder（本机浏览器助手）
│   └── util/                        # TokenCounter（jtokkit BPE）· ImageCompressor · RequestUser（登录态）·
│                                    #   OutboundWebhook（出站投递唯一出口）· TenantContext（租户作用域）
├── config/
│   └── application-local.yml        # 本地开发私有配置（含密钥与数据目录，.gitignore 忽略，不打进构建产物）
├── src/main/resources/
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD 缺失 fail-fast）
│   ├── config-schema.json           # 设置项 schema 唯一定义源（21 面板 / 260 字段，缺失即启动失败）
│   ├── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
│   ├── manual/*.md                  # 《问渠使用手册》16 篇（随包分发，同步后成为官方内置知识库）
│   └── skills/*/SKILL.md            # 内置技能
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/ + artifacts/{uid}/{yyyyMM}/ + secret/rsa/vN.key
├── user-data/                       # agentscope（框架状态、个人记忆、plans、session-archive）· 沙盒持久卷 shared/{uid}/workspace/sessions/{会话id}
│   └── skills-users/                # 用户自装技能（{uid}/技能名/SKILL.md，宿主、跨会话）
├── deploy/
│   ├── deployment-guide.html        # 独立部署手册（Compose 全栈：环境变量 / 密钥 / 备份 / 升级 / 排障）
│   ├── nginx.conf                   # 生产 nginx 参考配置
│   ├── meilisearch/                 # 关键词引擎独立 compose（命名卷 wenqu-meili-data）
│   ├── searxng/                     # 自建联网搜索引擎
│   ├── ocr/                         # MinerU 等 PDF 深度解析服务编排
│   └── sandbox-provisioner/         # 沙盒 provisioner：app.py（FastAPI）+ Dockerfile + compose + run.sh + sandbox.env
│       └── sandbox-image/Dockerfile # 派生沙盒镜像（容器内无 sudo，运行时依赖必须烧进镜像层）
├── scripts/                         # 启动与运维脚本：wenqu.sh（统一调度器）、mac/ 与 win/（平台启动器、开发栈按需拉起）、
│   │                                #   setup/（Node 环境准备）、services/（MCP 测试服务）、mcp-stats/（本地只读统计 Server）、
│   │                                #   loadtest/（压测）、dr/（按租户备份与恢复）
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 22 LTS）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    ├── src/router.js                # 路由：/chat /m/chat /project/:id /knowledge /knowledge/:kbId/docs /agents
    │                                #   /market /artifacts /stats /notifications /profile /help /docs /error
    │                                #   + 管理页 /members /tenants /security /permissions /dashboard /settings
    │                                #   /evaluation（带守卫）
    │                                #   + 免登录 /login /apply /join/:token /pair /auth/oidc/callback
    │                                #   /s/:token /shared/:token
    ├── src/chat/                    # 对话装配：Composer（内联 chip）/ ChatThread / TurnBar / SourceList /
    │                                #   引擎与投影（projections、useChatEngine）/ 附件与输入校验 / 通知与恢复
    ├── src/components/              # PlanCard / PlanViewer / PlanAdjustSheet · GoalCard / GoalViewer /
    │                                #   GoalTerminalCard · DeliveryCard / DeliveryPanel · 问答记录、子智能体卡、
    │                                #   SessionEventsPanel、AnnouncementsBar、TenantOnboardingCard 等
    ├── src/h5/                      # 移动端原生壳（会话、附件、状态与来源、本轮操作、执行过程等 sheet）
    └── src/views/                   # AppLayout / ChatPage / ProjectPage / KnowledgeBasePage / DocumentsPage /
                                     #   AgentsHubPage（页内 Tab 由菜单数据驱动）/ ProvidersPage / AgentsPage /
                                     #   SkillsPage / McpPanel / WorkflowPanel / FlowEditor / WorkflowRunHistory /
                                     #   ScheduledPanel / MarketplacePage / ArtifactsPage / StatsPage / NotificationCenter /
                                     #   ProfilePage / MembersPage / TenantsPage / SecurityOpsPage（巡检 / 会话治理 / 公告 / 审计）/
                                     #   IdentityProvidersPage / ServiceAccountsPage / PermissionsPage（含内容安全等页签）/ DashboardPage /
                                     #   EvaluationPage / AgentEvalPage（智能体评测）/ SettingsPage / HelpPage / ApiDocsPage /
                                     #   ShareChatPage / SharedSessionPage / JoinPage / TenantApplyPage /
                                     #   LoginPage / OidcCallbackPage / PairDevicePage / HttpErrorPage
```

## 测试与验证

```bash
# 1. 后端健康检查
curl http://localhost:8090/ai/actuator/health          # 期望 {"status":"UP"}

# 2. 前端构建验证
cd web && npm run build

# 3. 检索链路调试（无需重新解析，直接验证召回质量）
curl -X POST http://localhost:8090/api/ai/debug/retrieval \
  -H "Content-Type: application/json" \
  -d '{"question":"如何删除报表"}'                      # 返回分词/关键词/向量/合并/重排分步结果

# 4. 检索量化评估
curl -X POST http://localhost:8090/api/ai/eval/generate \
  -H "Content-Type: application/json" -d '{"maxCases":100}'
curl -X POST http://localhost:8090/api/ai/eval/run \
  -H "Content-Type: application/json" -d '{
    "kList":[5,10,20],
    "groups":[
      {"name":"当前配置","mode":"normal"},
      {"name":"向量0.7/关键词0.3","mode":"normal","vectorWeight":0.7,"keywordWeight":0.3},
      {"name":"多路合并","mode":"multi"}
    ]}'

# 5. Meilisearch 关键词引擎（可选）
scripts/mac/dev-stack.sh start meili    # 开发机：起 wenqu-meilisearch（索引在卷 wenqu-meili-data）
docker compose up meilisearch           # 全栈 Compose 部署：需先设置 AI_MEILI_KEY
curl http://localhost:7700/health       # 期望 {"status":"available"}；两套 compose 同名同端口，同机只起一个

# 6. 沙盒（隔离执行环境）
bash deploy/sandbox-provisioner/run.sh docker-up
bash deploy/sandbox-provisioner/run.sh status
# 配好连接后，在「系统设置 → 工具调用」开启「沙盒」，聊天里的命令执行与文件读写即由框架原生工具提供（受权限档位控制）；
# 工作流「代码执行」节点同样依赖该开关

# 7. OpenAI 兼容端点（任选一种 SDK，base_url 指向 /api/ai/v1）
curl -X POST http://localhost:8090/api/ai/v1/chat/completions \
  -H "Authorization: Bearer sk-..." -H "Content-Type: application/json" \
  -d '{"model":"<智能体id>","messages":[{"role":"user","content":"你好"}],"stream":false}'
```

**端到端手动验证**（建议每次改动后走一遍）：
1. 知识库上传含图片 docx → 解析进度 → 问答引用 `[N]` 溯源与图片展示
2. 智能体对话：开启工具后让模型执行计算 / 生成产物卡片 → 定时任务触发一次执行看历史
3. 沙盒：对话中要求「用 Python 生成数据并统计」→ 观察工具状态与实时输出流 → 产物交付卡 → 预览台整屏预览 → `/artifacts` 可下载
4. 长任务：`/plan` 起一轮计划 → 改意见看同轮升版与版本抽屉 → 批准后执行 → 看步骤进度与失败步骤；再 `/目标` 起一个多轮任务 → 目标条暂停 / 继续 / 取消 → 终态卡与过程抽屉可回看
5. 设置页修改检索参数 → 检索调试对比前后召回差异 → 检索评估页验证指标
6. 租户：`/apply` 提交开通申请 → `/tenants` 批准 → 用返回的管理员口令登录走首登引导 → 生成邀请 → `/join/{token}` 注册新成员
7. 通知：配置 SMTP → 发送测试邮件 → 勾选个人通知偏好 → 触发一条待审批通知；配置 VAPID 后在个人设置订阅 Web Push 并发送测试推送

## 运维须知

**默认关闭项（安全边界）**
- `tool.sandbox.enabled` / `tool.runcode.enabled` / `webSearch.enabled` / `mcp.server.enabled` / `notify.mail.enabled` / `webpush.enabled` 默认关闭，需管理员显式开启；**问渠工具总开关 `tool.enabled` 默认开**，基础工具开箱即用；HTTP 工具、工作流工具、智能体轮终回调、智能体级记忆默认开（未配置的智能体天然无能力可出）
- **内容安全总闸默认开**，按租户各登记两条「个人信息类」只告警示例规则（不预置拦截词表）；拦什么由管理员在「权限管理 → 内容安全」决定，关闭总闸即完全不判
- 智能体无全局启用开关（全员默认可用）；MCP 客户端无全局开关（每人登记自己的 Server）；MCP 健康巡检默认开（`mcp.patrolIntervalMs` 5 分钟）
- 高风险工具的范围由**权限档位**（仅可查看 / 工作区内修改 / 完全权限）与逐工具黑名单收敛，管理员另可设平台上限与平台停用名单
- 沙盒 token：应用侧环境变量 `SANDBOX_PROVISIONER_TOKEN` 须与 provisioner 侧一致且 ≥32 字符（不足 fail-closed 拒绝启用）；token 校验推迟到首次使用，未启用沙盒的部署不因缺 token 起不来
- OpenAI 兼容端点默认不放开跨域；`apiOpen.allowedOrigins` 为空时仅同源可用
- Web Push 未配置 VAPID 密钥时不展示订阅入口；邮件总闸关闭时全站零外发，站内通知与 Web Push 不受影响

**配置生效时机**
- 需重解析或新上传才生效：`chunk.maxSize` / `headingDepth` / `structural` / `overlap` 等解析参数（检索、上下文类参数均保存即生效）
- `vision.timeoutMillis` 需重启（视觉客户端启动时构建）；`workflow.maxConcurrentRuns`、`workflow.runQueueCapacity`、`chat.goal.workers` 需重启
- 换绑**向量模型**必然触发该库全量重嵌入（与知识块数成正比），期间向量检索降级关键词路；全量重嵌入只在知识库编辑里换模型时按库触发，无平台级重嵌入口
- 停用或删除供应商 / 模型不会自动改动选过它的地方：停用成功的当场列出仍在引用的位置并逐条给「去修改」入口，对话页与个人设置会把不可用的选项在原地标红并写明原因

**数据与运行标识**
- `.nvmrc` 固定 Node 22 LTS（要求 ≥22.12，更早版本不可构建）
- 存量库升级由 `SchemaMigrator` 启动自动补列补索引（幂等）
- 默认租户 `default` 恒可用、不可停用；其余租户按行级隔离，停用只挡登录、归档只读，本平台不提供删除租户
- 邮件投递台账在 `c_ai_mail_delivery`（pending / success / exhausted / skipped），Web Push 设备订阅在 `c_ai_webpush_subscription`；两者都按用户归属
- 图片访问路径：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时已去 `/ai` 前缀
- 会话与消息逻辑删除保留期默认 30 天，即「撤销删除」窗口，到期由清理任务物理删除
- 沙盒运行时依赖必须烧进镜像：容器内用户不在 sudoers——用 `deploy/sandbox-provisioner/sandbox-image/Dockerfile` 派生镜像（如 +openjdk-17），`.env` 设 `SANDBOX_IMAGE` 后重建 provisioner
- 会话事件日志与换代工作目录在宿主 `~/.wenqu/agent-journal`：容器部署需连同 `agent-journal` 一起挂卷，否则换代搬走的旧代次随容器丢失；长期留存件是 JSONL 归档

**模型兼容**
- MaaS 网关模型格式：部分模型返回 DashScope 原生格式（`{"text":...}`）无法解析，需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 兼容）
- base-url 不含 `/v1`：后端会自动补
- 登记为「恒思考」的模型即使界面关闭思考仍会先推理一次；思考强度经各厂商方言贯通，未登记的方言不下发该参数
- 思考强度的下发口径可在模型登记页改：「参数方言」点名网关真实那一家（默认按模型名判定，中转改名常判不出），「档位参数映射」逐档覆盖 `reasoning_effort` 取值与思考预算 token；留空的档位跟随内置表，内置表随各家文档修正一起生效

## 开源许可

本工作区以 [MIT License](LICENSE) 开源。核心依赖均为宽松许可（后端 Spring 生态 Apache-2.0、前端 Vue 生态 MIT / Apache-2.0；`mysql-connector-j` 为 GPLv2 + 通用 FOSS 例外），可放心商用与二次开发。

> **品牌声明**：MIT 许可仅及于代码本身。「问渠」「WenQu」中英文名称与问渠 logo **不在授权范围内**——你可以在 fork 中自由使用、修改代码，但请勿以「问渠 / WenQu」名义分发、对外提供服务或进行宣传。
