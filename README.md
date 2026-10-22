# 问渠 WenQu · AI 智能体工作台

> **问渠那得清如许，为有源头活水来。** —— 答案，自有源头。
>
> 中文名：**问渠** ｜ 英文名：**WenQu** ｜ 英文 slogan：*Ask the source.*

## 产品简介

问渠是一款以智能体为核心的 AI 工作台，基于 Spring AI 构建，支持私有化部署。它将大模型、知识库与工具统一组织与编排，覆盖「智能对话 → 知识检索 → 工具执行 → 产物交付 → 对外能力开放」的完整链路，适用于知识管理、内部智能问答、智能体应用搭建与 AI 能力开放等场景。

- **智能体优先**：以智能体（人设 / 知识范围 / 工具集）为核心组织能力，支持智能体按需委派与可视化工作流
- **答案可溯源**：混合检索 + 引用溯源 + 位置级文档原图展示，每个结论都能回到源头
- **开放互联**：MCP 双向（接入外部 Server / 对外发布为 MCP Server）、API Key 开放接口、游客分享页
- **生产就绪**：RBAC、OIDC 单点登录、密钥加密存储、接口限流、操作审计、多副本水平扩展

### 核心能力一览

| 能力域 | 亮点 |
|--------|------|
| 智能对话 | SSE 流式回答、句末 `[N]` 引用溯源、深度思考（思考强度五档）、上下文容量面板（分类计量 + 缓存命中率）、过程独白与消息时间线、图片理解、@ 文档引用、回答末尾下一步建议、计划模式（执行计划卡：确认/继续对话/取消） |
| 知识库（RAG） | Word/PDF/Excel/TXT/Markdown/网页解析（扫描件 OCR）、混合检索 + GraphRAG 图谱扩展、父子分块与问答对增强、网页定时刷新 |
| 智能体 | 人设/知识/工具可配、会话级绑定、按需委派（agent-as-tool，跨主题问题派给对口助手各带知识库查证）、@ 强制咨询、定时执行 |
| 工具与沙盒 | 内置工具 + 联网搜索（Tavily/博查/SearXNG）+ 技能包（Skills）+ MCP 工具；Docker 沙盒隔离执行 shell/文件读写（输出实时流式回传）；有副作用工具支持执行前人工确认 |
| 可视化工作流 | DSL 唯一真源 + StateGraph 执行引擎、11 类节点、画布编辑器与节点级 trace 回放、版本发布/回滚、API 触发、cron 定时触发、智能体 chatflow 绑定 |
| MCP 双向 | 接入任意外部 MCP Server；智能体一键发布为 MCP 端点（Claude/Cursor 直连）；平台级 MCP 入口 |
| 安全管控 | RBAC 菜单/端点两级管控、OIDC 单点登录、API Key（仅存哈希）、接口限流、操作审计 |
| 运营闭环 | 个人使用统计（Token 热力图/趋势/模型用量）、问答日志、赞/踩反馈回流、数据看板（知识缺口一键补块）、检索量化评估（recall@k / MRR）、检索链路分步调试 |
| 长期记忆 | 问答后异步提炼用户画像、跨会话注入；个人设置与系统设置各有一道开关（只关提炼，不关提起） |

## 系统架构

```
浏览器（Vue 3 单页应用）
  │  REST + SSE · /api/ai/*
  ▼
问渠服务端（Java 17 · Spring Boot 3.5 · Spring AI）
  ├─ 问答管线：混合检索 → 重排 → 上下文组装 → 流式生成
  ├─ 智能体编排：按需委派（agent-as-tool，跨主题咨询对口助手）
  ├─ 工作流引擎：DSL → StateGraph 翻译执行 + 节点级 trace
  ├─ 沙盒调度 · 定时调度 · 配置中心 · RBAC · OIDC · 限流 · 审计
  │
  ├─ MySQL …… 业务数据（50+ 张 c_ai_* 表，启动自动建表）
  ├─ Redis Stack ………… 向量索引 / 配置变更广播（pub/sub）/ 分布式锁与限频
  ├─ Meilisearch ……… 关键词引擎（可选，默认 MySQL LIKE，零依赖）
  ├─ Docker 沙盒 ……… provisioner + 隔离容器执行环境
  └─ 模型网关 …………… OpenAI 兼容供应商自登记（DeepSeek / GLM / Qwen / Kimi / …）

对外开放：/s/{token} 游客分享 · /ai/mcp/{token} MCP 端点 · /ai/mcp 平台入口 · 工作流 API
```

### 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8（`spring-ai-bom` 统一版本） |
| ORM | MyBatis-Plus 3.5.12（逻辑删除 `deleted`，驼峰映射） |
| 数据库 | MySQL 8 等 MySQL 协议兼容库（驱动即 MySQL Connector/J，无任何数据库专有逻辑；内置 compose 提供 MySQL，生产环境亦可指向外部 MySQL；库名 `wenqu_ai` 经 `DB_NAME` 调整）；50+ 张 `c_ai_*` 表由启动自动建立 |
| 向量库 | Redis Stack（RediSearch 向量索引，Jedis 客户端，每个知识库独立索引；连接地址经 `REDIS_HOST`/`REDIS_PORT` 注入，默认 `127.0.0.1:6379`） |
| LLM | 聊天/视觉/向量/重排/OCR/图谱抽取模型均可跨厂商热切换（DeepSeek / 智谱 GLM / 阿里百炼 Qwen / Kimi / 豆包 / 腾讯混元 / 百度千帆 / MiniMax / SiliconFlow / Ollama 等 OpenAI 兼容网关预设，模型经「供应商表 + 引用」登记绑定，**无全局兜底**——聊天模型缺失/引用无效会 fail-loud 引导选择） |
| 智能体 / 问答引擎 | **AgentScope 2.1.0-BETA1**（`io.agentscope:agentscope-harness` 的 `HarnessAgent`：推理循环、工具派发、会话状态、上下文压缩、权限、子智能体、HITL、Plan Mode 全部由框架原生接管，单一引擎恒走框架）。问渠领域工具/语义经适配层（Spring AI ToolCallback → AgentScope 一等工具）注入 |
| 工作流引擎 | **Spring AI Alibaba Agent Framework 1.1.2.3** 的 graph 模块（`com.alibaba.cloud.ai.graph.StateGraph`）：DSL → StateGraph 的唯一翻译层（`WorkflowEngine`），负责节点装配、条件边、checkpoint、HITL 续跑、loop 回边闸。执行框架与智能体问答引擎（AgentScope）互相独立；子智能体节点运行时复用同一问答管线 |
| 沙盒 | 独立 provisioner 服务（Python 3.13 + FastAPI + Docker SDK，容器化部署）+ 全功能沙盒镜像（all-in-one-sandbox）；模型经 Function Calling 在隔离容器内执行 shell、读写文件 |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ 原生流（txt/md/csv）+ jieba-analysis 1.0.2（中文分词） |
| 序列化 / 文档 | fastjson2 2.0.31；springdoc-openapi 2.8.8（Swagger UI，生产默认关） |
| 安全 | RSA 加密落库模型 API Key（`ConfigCryptoService`）+ SHA-256 哈希签发对外 API Key + 图片 HMAC 签名 URL + PBKDF2 密码哈希 + JWT 登录令牌 + OIDC 单点登录 |
| 前端 | Vue ^3.4 + Vite ^5 + Ant Design Vue ^4.2 + markdown-it/DOMPurify/highlight.js + @vue-flow/core 1.48.2（工作流画布，锁版本）（`.nvmrc` 固定 Node **22** LTS，依赖链要求 ≥22.12 或 20.19+） |

## 快速开始

### 环境要求

- JDK 17、Node.js ≥22.12（或 20.19+；`.nvmrc` 固定 22 LTS，旧版本会构建失败）
- MySQL 协议数据库（MySQL 8）
- Redis Stack（向量索引）
- Docker（可选：沙盒、Docker Compose 一键部署）

### 1. 准备基础设施

**Redis Stack**（RediSearch 向量索引）：

```bash
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest
```

> 宿主端口按实际部署自行映射，后端以 `REDIS_HOST`/`REDIS_PORT` 指向该端口（docker-compose 的映射见 `docker-compose.yml`）。RedisVectorStore 自动配置使用 **Jedis** 客户端，工作区已引入 `redis.clients:jedis` 且 `spring.data.redis.client-type: jedis`。

**数据库**：库需预先存在（库名按环境配置，默认 `wenqu_ai`）。表结构由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全；存量库缺列缺索引由 `SchemaMigrator` 启动期自动补齐）；也可手动执行：

```bash
mysql -h<db-host> -P<db-port> -uroot -p wenqu_ai < src/main/resources/schema.sql
```

**模型供应商**（聊天/视觉/向量/重排等模型，含图片描述与扫描 PDF OCR）：不需要任何本地部署——在「智能体工作台 → 模型供应商」登记 OpenAI 兼容网关（网关地址 / API Key / 补全与向量路径），按类型登记模型后**按归属绑定**：聊天模型在对话页（会话级切换）与「个人设置」（个人默认）选择；知识库编辑里绑定向量（必选）/ 重排 / 视觉 / OCR / 图谱抽取模型；平台默认重排模型在系统设置登记（API Key RSA 加密入库）。若本机装了 Ollama，也可按「本地网关」供应商登记使用，但非必需。

**沙盒 provisioner**（可选，隔离执行环境）：

```bash
bash deploy/sandbox-provisioner/run.sh docker-up      # 构建 + 启动（127.0.0.1:8002，真沙盒）
bash deploy/sandbox-provisioner/run.sh status         # 状态 + 健康探测
bash deploy/sandbox-provisioner/run.sh docker-down    # 停止
bash deploy/sandbox-provisioner/run.sh docker-logs    # 跟踪日志
```

> 需要 Docker Desktop；首次 `docker-up` 会构建 provisioner 镜像并拉取沙盒运行时镜像（约 12GB，之后免拉取）。沙盒持久卷在工作区 `user-data/shared/{uid}/workspace/`（bind 给容器内 `/home/gem/user-data`）；用户自装技能在宿主 `user-data/skills-users/{uid}/`（跨会话、不依赖沙箱）；助手在对话里沉淀的技能写当前会话的沙盒工作区。provisioner 访问令牌默认 `wenqu-local-dev-sandbox-provisioner-token`（compose 默认值，应用侧用环境变量 `SANDBOX_PROVISIONER_TOKEN` 配同一个值，≥32 字符）。沙盒运行时镜像如需 JDK/Node 等依赖，用 `sandbox-image/Dockerfile` 派生新镜像并在 `.env` 设 `SANDBOX_IMAGE`（容器内无 sudo，依赖必须烧进镜像层）。

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
export AI_JWT_SECRET=xxx                  # 登录令牌签名密钥（≥32 位随机串；留空则每次启动随机生成，重启后已发令牌失效；多副本必须一致）

# ===== 仅 env/yml（不进设置页）=====
export AI_MEILI_KEY=xxx                   # Meilisearch master key（compose 部署必填：meilisearch 容器以它初始化；app 侧同值可在设置页「检索设置 → 服务连接」配）
export AI_MEILI_INDEX=wenqu-chunks        # 关键词索引名（只从 env/yml 读，永不入库）
export LOG_LEVEL_APP=info                 # 应用日志级别
export LOG_LEVEL_SPRING_AI=info           # Spring AI 日志级别
export MYBATIS_LOG_IMPL=org.apache.ibatis.logging.slf4j.Slf4jImpl
export ACTUATOR_HEALTH_DETAILS=never      # /actuator/health 详情级别

# ===== 沙盒 / OCR 等部署连接（不进设置页）=====
export SANDBOX_PROVISIONER_URL=http://127.0.0.1:8002
export SANDBOX_PROVISIONER_TOKEN=          # 与 provisioner 侧一致，≥32 字符
export SANDBOX_CALLBACK_BASE_URL=http://host.docker.internal:8090/ai
export SANDBOX_VIRTUAL_PATH_PREFIX=/home/gem/user-data
export OCR_PP_STRUCTURE_URI=               # PP-StructureV3 服务地址；空=不用
export OCR_MINERU_URI=                     # MinerU 服务地址；空=不用
export OCR_MINERU_BACKEND=pipeline         # pipeline / hybrid-auto-engine
export OCR_TIMEOUT_MS=600000
export SPRINGDOC_ENABLED=false            # Swagger/OpenAPI 开关（默认关，接口契约不外泄；本地调试可置 true）
```

> **行为参数与模型配置不走环境变量**：检索/重排/解析/限流/上下文/沙盒/记忆/定时任务/联网搜索等 170+ 行为参数统一在**系统设置页**维护（`config-schema.json` → `c_ai_config`，保存即生效）；聊天/视觉/向量等模型走**模型供应商**登记 + 引用绑定（API Key RSA 加密入库，**无全局兜底**——聊天模型缺失/引用无效会 fail-loud 引导选择）。`application.yml` 不存任何模型网关/密钥。

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

1. 访问 `http://localhost:5800/chat`，按引导创建管理员账号（接入 OIDC 后可单点登录）
2. 在「智能体工作台 → 模型供应商」登记模型网关并登记模型，再到「个人设置」绑定个人默认聊天模型（对话页可随时切换）
3. 在「知识库」新建库 → 上传文档（或粘贴网页 URL）→ 等待解析完成
4. 回到「对话」提问：句末 `[N]` 角标点开即见来源全文与原文图片
5. 在「智能体」开启工具/沙盒，让智能体执行命令、交付文件产物（`/artifacts` 查看下载）

## 功能详解

### 对话体验（`/chat`）

- **流式回答**：SSE 逐段下发，支持随时停止生成
- **引用溯源**：回答句末 `[N]` 角标 → 弹窗展示来源知识块全文（图文交错）+ 关联截图；回答下方**检索状态行**可展开查看全部命中来源与摘要，条目点击进同一溯源弹窗
- **位置级原图**：命中图片经相关性校验后按编号位置级插入回答，前端灯箱渲染原图（docx 双图策略：识别用压缩图、展示用原图）
- **深度思考**：思考块按轮就地嵌进消息时间线（每想一段出一行、可点开全文，段尾补 `thinking_done` 收口），前端流式渲染；**思考强度五档**（低/中/高/超高/极致，按模型登记档位过滤，经 `reasoning_effort` 等厂商方言贯通到请求；开启思考时不下发 temperature，网关本就会忽略它）；「关闭 / 开启 / 恒思考」三态与强度均按模型记忆，平台不代为路由
- **上下文窗口档位**：模型登记「最小~最大窗口」区间后，聊天面板可在档位间下调（省 token / 提速），默认用窗口上限；未登记区间的模型不可调
- **过程独白与消息时间线**：工具模式下模型的推理/试错独白与正文分流（`<process>` 标签整块剥离），前端灰字原位展示；整轮「文本段 + 工具段 + 产物段」编为消息时间线随消息落库——刷新页面后仍是交错的实时过程视图
- **计划模式**（人在回路）：输入框打 `/plan`（或「+」→ 快捷命令；手机在「+」→ 本轮模式拨开关）开启后，「计划」标记出现在正文里（手机压在输入框上方），每轮先产出**执行计划卡**（生成中 → 待确认），确认栏三选——**确认**（按计划执行）/ **继续对话**（修改意见作为一条用户消息在同一会话继续，本版作废、按意见重出，可反复）/ **取消**（本轮停止，卡定格「未批准」）；挂起有倒计时（默认 10 分钟，超时按未批准收尾）；没开计划模式时由模型自主判断是否调用框架 `plan_enter` 进入计划；计划卡随消息落库，刷新/换设备后可继续裁决，右栏「计划」卡可定位本会话每轮计划，卡内「修改记录」回看各版计划与意见
- **多模态输入**：拖入/粘贴/点击上传图片（最多 5 张）；`@` 引用指定文档（被 @ 文档内最相关块前置注入上下文）；聊天模型不支持图片理解时**可见降级**（图片仅随消息展示，并向模型如实说明图片不可见，不静默丢弃）
- **上下文容量面板**：输入框工具栏的容量圆环（占比 >80% / >95% 变色告警）悬浮展开明细——用量/窗口占比 + **七类计量**（系统提示词 / 历史对话 / 本轮提问 / 检索资料 / 工具结果 / 思考内容 / 工具声明；按 AgentScope 消息结构分类）+ **缓存命中率**（网关返回缓存命中 token 才显示；done 时按网关实测校准）
- **下一步建议**：回答末尾给出「接下来可以」可点击追问标签，点击即发起提问（条数为平台固定值 3 条；只跟在最新一轮后面，往上翻历史不重复摆出旧建议）
- **智能体**：会话**不锁定**智能体。不挑就用工作区或平台的默认智能体；挑了从下一问起立即生效，**不必新建会话**。资料域由模型按需经框架的子智能体派遣（`agent_spawn`）派给职责对口的助手去查（可派多位，每位带着自己的知识库跑一条子轮，结论与引用交回）；交回的资料按**父轮问题**再过一道相关度门与引用配额（与主管线同一把尺——子轮的门是子轮问题的门，管不了父轮面板），被滤掉的条数如实告知模型，不放无出处断言；作答的智能体不可用时 fail-loud 提示，不静默换人
- **用户长期记忆**（默认开）：问答完成后异步提炼值得长期记住的用户信息（事实/偏好、明确要求记住的事），写进本人的记忆文件并在后续问答里提起；默认同一用户最多每 10 分钟提炼一次（可调，0=每轮），`MEMORY.md` 有 token 硬上限（写入落盘时自动裁剪），日账按保留期归档。`/profile → 我的记忆` 可查看/编辑/清空 `MEMORY.md`、按天查看与删除日账、按上限裁剪；系统设置里另有平台总闸，个人设置里有关（只停提炼，已记住的照常提起）。公开分享页与 MCP 游客链路**不注入也不写入**发布者记忆；子智能体/咨询轮只读不写
- **会话管理**：置顶/收藏/重命名/搜索/批量删除/清空、导出 Markdown、重新生成（软删旧答案）、赞/踩反馈、按消息组删除与撤销删除

### 工作区（`/project/{id}`）

- **一个容器收三样**：一个工作目录 + 一份工作区约定（每轮注入，对该工作区所有对话生效）+ 一位默认助手；会话、产物、定时任务、工作流都按 `project_id` 归到它名下。归属不是权限边界（任何检索/可见性判定都不读它），每人另有一个 implicit 兜底容器，界面上就是侧栏「会话」栏
- **文件栏可管理**：单层导航（面包屑 + 文件夹行）＋ 上传（按钮或拖拽，多文件、重名默认拒绝并可勾「重名时覆盖」）＋ 新建文件夹 ＋ 行内 ⋯ 菜单（重命名 / 移动到… / 删除）＋ 批量勾选（移动到… / 删除）＋ 单个下载 / 签名预览（新标签页，html 靠 CSP sandbox 才敢真渲染）＋ 整区 zip 导出
- **写侧四道闸**（`ProjectFileService.writableProject` / `assertWritable`）：路径逐段校验并按**真实路径**比前缀（软链目录借下载口越界已被拦，2026-10-21 冒烟实测）；隐藏名（点开头、`node_modules`、`__pycache__`）不许创建也不能列出——传进去就在界面上永远找不着；归档中的工作区一概不写；改名/移动/删除在该工作区有轮次正在跑时拒绝（那一轮以为文件还在原位），上传与新建不挡
- **未做完 / 已知边界**：① `sandbox.projectWorkdirEnabled` 默认关，关着时沙盒挂 `sessions/{会话}`、文件栏读写 `projects/{工作区}`，两边各一份——列表接口带 `agentShared` 字段，界面据此在文件栏顶部如实说明（不是静默无入口）；② 平台写进去的目录/文件属主是应用用户，provisioner 每次建容器只把**目录本身** chown 给沙盒运行时用户（uid 1000），于是 Linux 宿主上「先上传、再让模型改这一份」可能读得到、改不动，容器重建即自愈——Java NIO 取不到数字 uid，应用侧无法按同一口径 chown，放宽权限又是拿边界换功能；③ `directory_mode=linked`（关联用户已有目录）仍是保留位；④ 产物表与工作目录文件仍是两份拷贝，收成一个真源属立项级；⑤ 移动会话不搬已落盘文件；⑥ k8s 分支的挂载布局未对齐。帮助中心《工作区》篇同步 ①②，其余是工程内部账

### 知识库与检索增强（`/knowledge`）

- **多库管理**：每库可设解析参数与**检索参数**（库级“召回池”：向量阈值、两路权重、重排模型；**稀疏覆盖**留空跟随全局、保存即固化；**逐库生效**——同一轮跨库检索按命中块所属库各取各的）、文档级共享范围；**检索可见性两级判定**（文档自身 + 所属库共享范围，私有库不会被他人检索到）；**官方内置知识库**（随版本分发、全员可检索）管理员可维护检索/解析参数
- **文档解析**：docx（表格→Markdown、结构感知切分）/ xlsx / pdf（扫描件自动 OCR 抽取文本）/ txt/md/csv；大文件流式解析；解析中删除立即中断并清理产物；版本历史与回滚；知识块预览（切片列表/结构导图）与单块编辑重向量化
- **网页 URL 导入**：批量粘贴、正文提取（仅公网 http/https，逐跳防内网探测）、源文件为 HTML 快照；可开**定时自动刷新**（文档级 cron 周期重抓、同名替换重建，失败留痕不中断）
- **混合检索**：Redis 向量 Top-K + 关键词召回并行；**向量分归一化 + 双命中叠加**（语义+关键词命中 = 向量分+关键词分+标题奖励）；**重排/融合双相关分门槛**（重排门 0.6、融合门 0.5，拦词面重叠噪声，设置页可调）；超时/阈值/召回数等参数设置页可调，保存即生效
- **关键词引擎可切换**：默认 `mysql` LIKE 零依赖；切到 `meilisearch` 用 jieba 中文分词 + 相关度打分（搜索模式细粒度词元 + 长词 2-gram/4-gram 子词元），服务不可用自动降级回 MySQL；切换后需全量重建一次，写索引随解析/编辑/删除增量同步
- **GraphRAG 图谱增强**（库级开关，默认关）：解析后按批调 LLM 抽取「主体-关系-客体」三元组（哈希增量账本、每块条数上限的成本闸）；检索命中后**一跳图扩展**——命中内容匹配实体 → 取三元组 → 反查真实知识块，按衰减分并入结果（数量封顶）；抽取模型归知识库绑定，未绑定回落库主个人默认聊天模型，两者皆空不抽取并告警（不静默烧别的模型）
- **上下文工程**：`预算 = 模型窗口 × 安全系数(0.7) − 最大输出`（窗口/最大输出按模型在模型供应商里声明，对话类窗口必填，可登记最小~最大区间）；资料由模型经 `search_knowledge` 工具按需取用，咨询来源按单文档/单库配额收敛；**历史压缩由 AgentScope `CompactionMiddleware` 按模型窗口自动接管**（滚动摘要，完整记录仍可在会话中回看）；token 计数用 tiktoken（jtokkit cl100k BPE）
- **分块增强**：**父子分块**（超长块切子块做检索索引，命中子块返回父块完整正文）、**问答对增强**（解析时对每块由**库主个人默认聊天模型**生成 QA 并按问法向量化，对标 FastGPT 问答对模式，重嵌入不重调 LLM）

### 智能体（`/agents`，全员开放）

智能体工作台六个页内 Tab（清单与顺序由菜单数据驱动）：**模型供应商 · 智能体 · 技能 Skills · MCP 外部工具 · 工作流 · 定时任务**。

- **智能体配置**：System Prompt、知识库范围、能力开关（含联网搜索）、MCP/技能、委派关系、**检索开关覆盖**（只决定这一轮要不要重排：`rerank.enabled`，空=继承全局；阈值/权重/召回量归知识库与系统设置，不在智能体配；聊天模型不绑定智能体——走会话覆盖/个人默认）；卡片可**公开发布**（生成 `/s/{token}` 免登录链接与 iframe 嵌入代码，游客工具收窄为知识检索+内置项）；支持**编排视图**（主→子 SVG 拓扑图：委派链路高亮、悬空引用与孤儿子智能体警示）
- **按需委派（agent-as-tool）**：模型遇到跨主题问题自主发起一次派遣（`agent_spawn`），从候选助手中挑出对口者（各带自己的知识库跑一条子轮，结论与引用交回）；挑选理由随 SSE 下发并可解释；用户可用 `@` 强制咨询某助手；被咨询助手不可用时 fail-loud，不静默换人
- **资料收敛（无独立聚合策略）**：每个被咨询助手独立检索+作答，交回的资料按主管线统一的相关度门与引用配额收敛（与对话体验同把尺）；作答助手不可用时 fail-loud 提示，不静默换人
- **定时执行**：智能体按 cron（5 段：分 时 日 月 周）或固定间隔自动执行；执行历史与结果会话留痕；调度中心先推进 `next_run_at` 再执行（单实例防重复）、同任务串行防重叠
- **工具执行确认**：沙盒/MCP 类有副作用工具可设「执行前确认」（人在回路：批准/拒绝/超时按拒绝）或「禁用」

### 工具生态与沙盒执行

- **工具开关集中在 `tool.*`**：总开关 `tool.enabled` 默认**开**（基础工具开箱即用）；沙盒执行、`run_code`、`todo` 等具体能力默认**关**，需显式开启
- **内置工具**：计算器（递归下降自实现表达式求值，仅四则/幂/括号，**不执行任意代码**）、日期；另有知识检索工具（命中块进引用流）、产物交付工具、技能读取工具（渐进披露读 `SKILL.md`）
- **联网搜索**（默认关）：Function Calling 工具，服务商 Tavily / 博查 / generic（自建 SearXNG）；结果**强制注册进引用体系**（与知识库引用同源展示）；返回结果按**本轮问题**（多轮为指代消解后的检索查询）重排过相关度门，低于 `retrieval.minContextScore` 的不注册、不列给模型——服务商自带的相关度分（如位置衰减）没有相关性含义，不参与判定；重排不可用时明确提示「本轮搜索结果未经相关性过滤」，不把瞬时故障演变成「什么都搜不到」；单轮搜索次数配额防反复搜 token；可纳入「执行前确认」审批；游客分享链路不暴露
- **技能包（Skills）**：目录 + `SKILL.md` 形式的可插拔能力；三层来源（优先级递增）：内置（随版本分发，启动落盘 `user-data/skills-builtin/`）→ 沙盒工作区（助手在对话里沉淀，随会话）→ **用户自装（`user-data/skills-users/{uid}/`，跨会话）**。用户可自助 **粘贴 SKILL.md / 上传 zip / 从 URL 安装**，并编辑、删除、个人停用；内置技能只能停用、不能被同名覆盖。每轮模型先看清单、需要时才读正文，输入框点名的技能会把正文直接带上；安装侧校验 frontmatter、路径穿越、zip 体积/条目数、URL 域名白名单与逐跳公网校验。口径以产品内「帮助中心 · 09-技能与沙盒」为准（`src/main/resources/manual/`，随版本同步进官方手册库）。
- **沙盒隔离执行**：模型经 Function Calling 在隔离 Linux 容器内执行代码：问渠只提供 `run_code`（**输出实时流式回传**），读写文件与列目录由框架自带工具承担，交付物走「我的产物」；**scope 挂会话**（同会话文件跨轮保留，按用户隔离工作目录），会话空闲（默认 60 分钟）自动回收容器；命令超时/输出上限/keepalive 均可配；瞬时故障自动重试；对话右栏「沙盒」卡可浏览/下载工作区文件
- **产物交付**：`/artifacts` 汇总问答与定时任务交付的文件产物（Markdown/CSV/JSON/HTML/py/png/xlsx/zip 等），可预览、下载（HMAC 签名 URL）、删除；交付落盘 `data/artifacts/{uid}/{yyyyMM}/`，扩展名白名单 + 文件名净化，单个 ≤1MB

### 可视化工作流（智能体工作台 → 工作流 Tab）

- **DSL 唯一真源**：节点/边/变量引用/画布坐标全在 JSON DSL 中，画布只是编辑器、引擎只认 DSL；执行底座为 `StateGraph`（Spring AI Alibaba graph 模块，`WorkflowEngine` 负责 DSL → 图翻译），与智能体问答引擎（AgentScope）无耦合；结构校验器 fail-loud
- **11 类节点全量可执行**：开始 / 结束 / LLM（模型引用过判权）/ 知识库检索（库界）/ 条件分支（SpEL 安全求值，禁 `T()` 与构造器）/ HTTP 请求（SSRF 逐跳内网校验、2MB 读取上限）/ 代码执行（Python/Node 走 Docker 沙盒，非 0 退出码 fail-loud）/ 子智能体（复用完整问答管线，临时会话跑完即删）/ **人工审核**（挂起 → 审批卡裁决 → 按快照续跑，已完成节点短路回放零重复消耗）/ 循环（maxLoops 编译期/运行期双闸）/ 模板转换；变量引用 `{{nodeId.key}}` 统一寻址
- **画布编辑器**：节点面板拖拽添加、右键快捷菜单、连线校验（禁自环、禁非法回跳、条件出边选分支）、属性抽屉按类型渲染表单；**画布内调试**：入参表单 → 同步运行 → 逐节点 trace 回放染色（成功/失败/待审核 + 耗时）→ 点节点看输入输出与 token；运行历史可整体回放任一次 run；**每次运行锁定当时 DSL 快照**
- **产品化能力**：**发布与版本**（草稿可继续改，发布冻结成一版，回滚 = 以历史 DSL 再发一版）；**API 触发**（`POST /api/ai/v1/workflows/{id}/run` 持 API Key 跑已发布版本，含人工审核节点一律拒绝）；**定时触发**（5 段 cron + 时区，只跑已发布版本，支持回调地址/密钥与「立即触发」）；**运行治理**（单次运行超时默认 10 分钟、并发上限与排队容量、运行记录保留 30 天、人工审核超时自动落 timeout 终态）；**chatflow 绑定**（智能体绑定已发布工作流，结束节点 `answer` 出参即回答，LLM 节点 token 流式透传）；**运行历史页**（run 列表 + 节点级 trace 回放 + 待审批运行就地裁决）

### MCP 双向互联

- **Client（接进来）**：接入任意 MCP Server（streamable/sse），连接状态、整体重连、临时连通性探测；按用户隔离连接池（每个用户在「MCP 外部工具」登记自己的 Server，无全局配置）
- **Server（送出去）**：智能体发布时可开启 **MCP 端点 `/ai/mcp/{token}`**（Streamable HTTP，无状态）——Claude Desktop / Cursor 等客户端粘贴地址即可调用该智能体；token 即凭据（与网页分享同源，停用/撤销立即失效），按发布者身份检索、能力收窄同游客模式；总开关 `mcp.server.enabled` 默认关
- **平台级入口 `/ai/mcp`**：一个地址暴露整套能力（检索知识库 / 提问 / 列可见知识库 / 列可用智能体），凭据为 API Key（需在「API 密钥」中打开该 Key 的「MCP」开关），可见范围与 Key 创建者在网页上看到的完全一致

### 安全与权限

- **RBAC**：角色-菜单（侧边栏入口）+ 角色-API（端点白名单）两级绑定；管理员判定 `admin`/`superadmin` 或角色 `admin_flag`；普通用户开放对话/知识库/智能体工作台/我的产物/使用统计等日常链路，管理端点（成员/看板/评估/权限/系统设置）403 fail-closed；前端管理页路由带守卫；权限页「接口按菜单归属分组」，勾选菜单一并授权名下接口
- **认证**：本地账号（PBKDF2 密码哈希 + 失败锁定）+ OIDC 标准授权码单点登录（自动建档、`oidc_sub` 唯一索引防账号漂移；配置在设置页 OIDC 面板，分离部署必填 `frontendBaseUrl`）
- **密钥安全**：模型 API Key **RSA 加密入库 + 掩码回显**；对外 API Key 只存 SHA-256 哈希 + 前 8 位前缀（明文仅签发时返回一次），权限固定为问答链路——**即便持有 Key 也访问不了管理端点**；图片 HMAC 签名 URL；RSA 密钥文件不入库、不打进构建产物
- **运行防护**：问答/上传按「用户（无身份则按 IP）」Redis 固定窗口限频（Redis 不可用自动放行）；上传魔数校验；产物文件名白名单净化；管理操作 `[AUDIT]` 审计；统一异常处理

### 运营闭环与质控

- **个人使用统计**（`/stats`，全员）：统计卡（累计/峰值 Token、最长聊天时长、连续打卡天数）+ 近 365 天 **Token 活动热力图**（每日/每周/累计三口径，末列收最近完整周）+ 每日 Token 趋势（近 7/30 日，按模型分系列）+ **模型用量占比**环形图；所有 LLM 调用（问答/工具/思考/子智能体）在路由出口**统一记账**，工具循环 token 按轮累计对齐供应商账单口径
- **数据看板**（`/dashboard`，管理员）：核心指标卡 + 检索质量自动体检 + 热门/无命中问题 TOP10 + 差评回流 + **知识库缺口一键补块**（自动生成向量）
- **检索评估**（`/evaluation`，管理员）：从历史问答引用回放生成评估集 → 多参数组对比 **recall@k / MRR / 命中率** → 一键体检、一键应用最优组到线上配置；期望块存活校验防「内容漂移误报下滑」
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元/关键词/向量/合并/重排/最终结果，问答页可视化排查
- **反馈闭环**：问答日志 + 赞/踩反馈 + 看板聚合；差评样本一键加入检索评估集，形成「发现问题 → 评估 → 优化 → 验证」闭环

## 生产部署

> **完整部署步骤见 [`docs/deployment-guide.html`](docs/deployment-guide.html)**（Docker Compose 全栈自包含：环境变量清单、密钥生成、备份还原、升级与回滚、常见故障排查）。本节只给骨架。

### Docker Compose

```bash
docker compose up -d          # 自包含：redis-stack + meilisearch + 内置 MySQL
```

使用外部 MySQL 时，先只起中间件，再以 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME` 指向外部库启动 app。生产 nginx 参考配置见 `deploy/nginx.conf`。

### 多副本部署要求

支持多实例水平扩展，需满足以下约束（均已代码化治理）：

1. **数据目录必须共享**：`AI_IMAGES_DIR` 指向所有实例都能访问的同一存储。docker-compose 用命名卷仅**同主机**共享；跨主机需挂 NFS/对象存储等共享卷
2. **静态配置一致**：各副本的 yml/环境变量（数据库、Redis、`AI_JWT_SECRET`、`AI_MEILI_KEY` 等**仅存 env/yml 的项**）必须一致（**`AI_JWT_SECRET` 尤其必须一致**）；动态配置（`c_ai_config`，含模型与行为参数）经 **Redis pub/sub** 广播即时生效，订阅断线由 **5 分钟兜底轮询**补齐
3. **并发防护**：重解析用 **DB 状态机 CAS**；解析队列有界，超限拒绝/降级不失控
4. **删除中断语义**：删除在任意实例生效，其他实例上的解析由 DB 兜底在检查点秒级停止清理
5. **总并发核算**：解析并发为「副本数 × parse.concurrency」，embedding/视觉为共享瓶颈
6. **向量模型热切换为全实例串行**：Redis 分布式锁互斥，锁持有期间所有实例向量检索自动降级关键词路；任务每批续期锁，实例崩溃后锁 TTL 自愈
7. **启动对账/巡检开关需收敛为单点**：`keyword.reconcileOnStartup` 与 `parse.recoverStuckOnStartup` 多副本上线前请置 false
8. **RSA 密钥文件必须共享**：默认在 `{AI_IMAGES_DIR}/secret/config-rsa.key`，可用 `AI_CONFIG_RSA_KEY` 覆盖路径——否则 A 实例加密的 key 在 B 实例无法解密

### 与平台网关集成

生产环境由平台网关做 JWT 鉴权并透传请求（前端调 `/api/ai/*`，见 `web/.env.production`）。注意：

1. 网关需额外透传图片路径 `/ai/images/**`
2. SSE 接口（`/chat`）网关需关闭响应缓冲
3. **登录态透传**：用户身份只认 `Authorization: Bearer <JWT>`，**反向代理必须原样转发该头**；会话严格按登录用户隔离
4. **不接受自报身份**：不读取任何客户端自报的用户标识请求头
5. **接口限流**：问答/上传按「用户（无身份则按 IP）」Redis 固定窗口限频，Redis 不可用自动放行
6. **权限模型**：普通用户开放对话/知识库/智能体工作台/我的产物/使用统计等日常链路；管理端点仅管理员可访问（403 fail-closed）
7. **普通用户 UI 收敛**：管理路由带守卫，非管理员侧边栏不展示、直达 URL 自动跳回对话页

## API 参考

> 完整接口文档见 **Swagger UI**（启动后访问 `/ai/swagger-ui/index.html`，随代码自动更新）。共 **40 个控制器、290+ 端点**，下表为核心端点速查（完整契约以 Swagger 为准）：

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（事件见下方 SSE 事件表） |
| `POST /api/ai/tool-approval/{approvalId}` | 工具执行确认（人在回路）：批准/拒绝有副作用的沙盒/MCP 工具调用 |
| `POST /api/ai/plan-approval/{planApprovalId}` | 执行计划裁决（计划模式）：批准（可带修改后计划全文）/ 取消 / `supersede` 取代（意见作为新消息继续对话、按意见重做计划） |
| `GET /api/ai/plan-approval/pending?sessionId=` | 待批准计划卡（刷新/换设备后的恢复入口，含版本链与剩余时间） |
| `GET /api/ai/auth/me` | 当前身份、权限与菜单树（前端据此渲染侧边栏与管理入口） |
| `POST /api/ai/auth/login`、`/api/ai/auth/oidc/{config,login-url,callback,exchange-code}` | 本地登录 / OIDC 单点登录四端点 |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}`、`PUT /api/ai/session/{id}/rename` | 会话列表（搜索）/ 新建 / 历史恢复 / 重命名 |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏 |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions`、`POST /api/ai/sessions/batch-delete` | 删除单会话 / 清空 / 批量删除 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /api/ai/message-group/undo` | 按消息组删除问答 / 撤销删除 |
| `GET /api/ai/project/list`、`POST/PUT/DELETE /api/ai/project/{id}`、`PUT /{id}/status` | 工作区容器：列表（含约定与默认助手）/ 新建 / 改设置（名称·约定·默认助手）/ 删除（可 `deleteFiles=true` 连目录）/ 归档恢复 |
| `GET /api/ai/project/{id}/files?dir=`、`POST /{id}/files`、`POST /{id}/dir`、`PUT /{id}/file`、`POST /{id}/files/batch` | 工作区目录管理：单层列表（带全区合计、文件夹清单、`agentShared`）/ 上传（multipart）/ 新建文件夹 / 改名或移动 / 批量移动·删除（`op` 必填）；另有 `GET /{id}/file` 下载、`GET /{id}/file/content` 读文本原文（预览台里 Markdown/代码/表格那一档用，按 uid+工作区+路径校归属，只回前 256KB 带 `truncated`）、`GET /ai/project-files/{token}` 签名预览 |
| `GET /api/ai/kb/list`、`POST /api/ai/kb`、`PUT /api/ai/kb/{id}`、`DELETE /api/ai/kb/{id}` | 知识库 CRUD（含库级解析/检索参数稀疏覆盖） |
| `GET /api/ai/kb/param-defaults` | 全局解析/检索参数模板（新建库预填用） |
| `POST /api/ai/document/upload`、`/upload/batch` | 上传文档（异步解析） |
| `POST /api/ai/document/import-url` | 网页 URL 导入（抓取 HTML 快照，异步解析；支持逐篇定时自动刷新） |
| `GET /api/ai/document/list`、`DELETE /{id}`、`PUT /{id}/status`、`POST /{id}/reparse` | 文档列表 / 删除 / 启停用 / 重解析 |
| `GET /api/ai/document/{id}/versions`、`POST /{id}/rollback` | 版本历史 / 回滚 |
| `GET /api/ai/knowledge/{id}`、`GET /api/ai/knowledge/list?docId=`、`PUT /api/ai/knowledge/{id}` | 知识块详情 / 预览 / 编辑（重向量化） |
| `GET /api/ai/knowledge/unmatched`、`POST /api/ai/knowledge` | 无命中问题 / 手动创建知识块（自动向量化） |
| `POST /api/ai/feedback`、`GET /api/ai/analytics/summary`、`GET /api/ai/analytics/badcases` | 反馈 / 看板聚合 / 差评坏例 |
| `GET /api/ai/stats/usage?range=7\|30` | 个人使用统计聚合（统计卡 / 热力图 / 每日趋势 / 模型用量，一次返回） |
| `GET/PUT /api/ai/config`、`GET /api/ai/config/schema`、`POST /api/ai/config/reset` | 配置读写（脱敏）/ 设置项 schema 下发 / 恢复分组默认 || `POST /api/ai/config/probe`、`GET /api/ai/config/rerank/check`、`GET /api/ai/config/keyword/check` | 连通性探测（chat/vision/embedding/rerank/keyword）/ 重排 / Meilisearch |
| `GET /api/ai/config/embedding/reindex` | 全量重嵌入任务状态（含索引对账） |
| `GET /api/ai/provider/*` | 模型供应商：登记（平台级/个人级）、启停、判权 |
| `GET /api/ai/agent/list`、`GET /api/ai/agent/available`、`POST /api/ai/agent`、`PUT /api/ai/agent/{id}`、`DELETE /api/ai/agent/{id}`、`POST /api/ai/agent/{id}/default` | 智能体 CRUD / 对话页精简列表（普通用户可访问）/ 设默认 |
| `GET/POST /api/ai/agent/{id}/publish`、`DELETE /api/ai/agent/{id}/publish` | 公开分享发布/查询/撤销（生成 token，免登录对话入口） |
| `GET /api/ai/share/{token}/info`、`GET /api/ai/share/{token}/history`、`POST /api/ai/share/{token}/chat` | 游客通道（免登录）：分享信息 / 游客会话历史 / 流式对话（IP 限频、工具白名单收窄） |
| `GET /api/ai/skill/available`、`GET /api/ai/skill/list` | 技能清单：对话选用（含本会话工作区）/ 管理页（来源、停用、可否编辑删除、体积） |
| `POST /api/ai/skill`、`PUT /api/ai/skill/{name}`、`DELETE /api/ai/skill/{name}` | 用户自装技能：新建 / 编辑 / 删除（仅本人；内置与历史只读） |
| `POST /api/ai/skill/install`、`POST /api/ai/skill/install/zip` | 安装：URL（白名单域名 + 逐跳公网校验）/ zip（逐条目路径与体积校验） |
| `GET /api/ai/skill/detail`、`GET /api/ai/skill/file`、`GET /api/ai/skill/download`、`PUT /api/ai/skill/{name}/disabled` | 详情（正文 + 文件清单）/ 附属文件预览 / 下载 zip / 个人停用启用 |
| `GET /api/ai/mcp/status`、`POST /api/ai/mcp/reload`、`POST /api/ai/mcp/probe` | MCP Client：状态 / 重连 / 临时探测 |
| `POST /ai/mcp/{token}`（`GET` 同路径；**不在 `/api` 之下**，token 即凭据） | MCP Server·per-agent 端点：一个智能体一个工具 |
| `POST /ai/mcp`（`GET` 同路径；凭据走 `Authorization: Bearer sk-…` 或 `X-Api-Key`） | MCP Server·平台级入口：固定元工具集（检索 / 提问 / 列库 / 列智能体） |
| `PUT /api/ai/api-key/{id}/mcp` | 授权某把 API Key 访问平台级 MCP 入口 |
| `GET /api/ai/scheduled/*`、`POST /api/ai/scheduled/{id}/trigger` | 定时任务：列表 / 新建 / 编辑 / 立即触发（含执行历史） |
| `GET/POST /api/ai/workflow`、`GET/PUT/DELETE /api/ai/workflow/{id}`、`POST /api/ai/workflow/validate` | 工作流定义：CRUD / DSL 校验 + 编译 dry-run |
| `POST /api/ai/workflow/{id}/publish`、`POST /api/ai/workflow/{id}/unpublish`、`GET /api/ai/workflow/{id}/versions`、`POST /api/ai/workflow/{id}/rollback/{version}` | 工作流发布与版本：草稿发布成一版 / 下线 / 版本历史 / 回滚（以历史版本 DSL 再发一版） |
| `GET/PUT /api/ai/workflow/{id}/automation`、`POST /api/ai/workflow/{id}/schedule/run-now` | 工作流自动化：定时 cron（5 段）+ 时区 + 回调地址/密钥 / 手动触发一次定时运行 |
| `POST /api/ai/v1/workflows/{id}/run`、`GET /api/ai/v1/workflows/{id}/run/{runId}` | 对外工作流接口：持 API Key（`X-Api-Key`）触发**已发布**版本 / 按 runId 查结果 |
| `GET /api/ai/artifacts`、`DELETE /api/ai/artifacts/{id}` | 产物列表 / 删除（下载走签名 URL） |
| `GET /api/ai/api-key/list`、`POST /api/ai/api-key`、`PUT /api/ai/api-key/{id}/disabled` | 对外 API Key：列表 / 签发（明文仅一次）/ 吊销 |
| `GET /api/ai/user/list`、`GET /api/ai/role/*`、`GET /api/ai/menu/*` | 成员 / 角色 / 菜单（RBAC 维护） |
| `GET /api/ai/skill/available`、`GET /api/ai/skill/list` | 技能清单（内置 + 用户自装 + 会话工作区）；增删改见上方技能管理接口 |
| `GET/PUT /api/ai/user/preference` | 个人偏好（默认聊天模型、自动提炼记忆开关） |
| `GET/PUT/DELETE /api/ai/memory`、`GET /memory/content`、`POST /memory/compact`、`DELETE /memory/daily` | 个人长期记忆面板：查看/保存 `MEMORY.md`、按天读/删日账、清空、按上限裁剪（仅本人） |
| `GET/PUT /api/ai/user/settings` | 个人配置覆盖通道（键限定为 config-schema.json 标记 personal 的字段）；当前无此类字段，端点保留待将来再有个人可覆盖项时使用 |
| `GET /api/ai/search-index/stats`、`POST /api/ai/search-index/reindex`、`DELETE /api/ai/search-index` | 关键词索引运维 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试 |
| `POST /api/ai/eval/generate`、`GET /api/ai/eval/set`、`POST /api/ai/eval/run`、`POST /api/ai/eval/case`、`POST /api/ai/eval/run-auto` | 检索量化评估 |

### SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 | 载荷 |
|------|------|------|
| `token` | 流式生成逐片 | 回答文本增量 |
| `plan` | 轮次开始，按当前配置确定会跑的步骤 | 执行计划步骤名数组，如 `["理解问题","深度思考","检索知识库","生成回答"]`（仅实时下发，不随消息持久化） |
| `image` | 生成之前 | 命中图片 URL 列表（按编号顺序，生产为 HMAC 签名 URL） |
| `retrieved` | 检索 + 重排 + 上下文填充完成 | 检索概览 `{keywords, refs, terms}`（随消息持久化） |
| `thinking` | 深度思考开启时 | 思考链增量 |
| `thinking_done` | 深度思考结束（正常完成或截断降级） | `{status: ok\|degraded, thinking: 完整思考链}` |
| `process` | 工具模式下模型推理/试错独白（与正文分流） | 过程独白增量（前端原位灰字展示；全文随消息持久化） |
| `tool_status` | Agent 调用工具 | 工具名 / 状态（done\|error）/ 结果或错误（含尝试次数 `attempts`） |
| `tool_output` | 沙盒 `execute` 命令执行中 | 命令输出逐行增量（实时流式；长命令边跑边看） |
| `subagent_step` | 被咨询那一支正在跑的第几步 | 子轮工具调用与输出增量（过程由「问另一位助手」那张工具卡呈现） |
| `approval_required` | 有副作用工具（沙盒/MCP）执行前等待确认 | `{approvalId,tool,args,timeoutMs}`（批准/拒绝经 `POST /api/ai/tool-approval/{id}`） |
| `plan_delta` | 计划模式产出执行计划时 | 计划文本增量（执行计划卡流式成形） |
| `plan_approval` | 计划生成完毕、等待用户确认 | `{planApprovalId,plan,timeoutMs,auto?}`（确认/取消/带稿批准经 `POST /api/ai/plan-approval/{id}`，取代走同端点 `supersede`） |
| `plan_cancelled` | 计划未批准（用户取消或超时按未批准收尾） | `{}`（卡片定格「未批准」，本轮停止） |
| `plan_superseded` | 用户对计划提修改意见、「继续对话」取代本版 | `{}`（卡片定格「已被取代」，意见作为新消息在同会话继续） |
| `artifact` | 产物交付工具落盘 | 产物卡片字段 `{id,url,filename,ext,size,description}` |
| `usage` | 生成一开始预下发 prompt 侧用量（点亮容量圆环；done 时以网关实测覆盖） | tokens `{window,windowSource,cached,parts,…}` |
| `warn` | 非致命降级/告警 | 提示文案 |
| `done` | 回答完成 | `sources`、`related`（下一步建议数据源）、`messageId`、`thinking`、`finalContent`/`finalImages`（校验修正后全文/图片）、`processText`、`timeline`（段数组——刷新后重建交错过程视图的数据源）、`degradations`、`artifacts`、`toolCalls`、`tokens`（用量：窗口/窗口来源、缓存命中 token、七类计量明细、滚动压缩轮数）、`subagentBranches`/`subagentRoute`（编排终态收敛）；缓存命中仅 `finalContent`/`finalImages` |
| `error` | 处理或下发异常 | 错误文案 |

> **思考结束没有独立事件**：完整思考链在 `done.thinking` 一次性给出。

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
      enabled: ${AI_RERANK_ENABLED:true}   # 默认开；未绑定模型/服务不可用时按融合分排序，不阻塞问答
      base-url: ${AI_RERANK_BASE_URL:}     # 重排模型引用归知识库/智能体检索设置绑定，不在这里配模型名
  keyword:                                 # 关键词召回引擎（mysql|meilisearch）
    engine: ${AI_KEYWORD_ENGINE:mysql}
    base-url: ${AI_MEILI_BASE_URL:http://localhost:7700}
    api-key: ${AI_MEILI_KEY:}
  ratelimit:                               # 接口限流（Redis 固定窗口）
    enabled: ${AI_RATELIMIT_ENABLED:true}
  context:                                 # 上下文填充配额（窗口/最大输出按模型声明，无全局兜底；安全系数固定 0.7）
    max-context-hits: 8                    # 上下文填充块数兜底上限
  images:
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（源文件/图片/产物/密钥都在此）
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:true}
  # 视觉/向量/聊天模型不在 yml 配置：网关统一来自「模型供应商」表（供应商登记 + 引用绑定），
  # 行为参数（视觉开关/提示词/并发、向量、检索等）在系统设置页维护（c_ai_config）
  auth:
    jwt-secret: ${AI_JWT_SECRET:}          # 留空自动生成（重启后令牌失效）；多副本必须一致
    require-login: ${AI_AUTH_REQUIRE_LOGIN:true}

spring:
  servlet.multipart: { max-file-size: 256MB, max-request-size: 260MB }
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }
  ai.vectorstore.type: none                # 关闭全局 RedisVectorStore（per-KB 独立索引由 KbVectorStoreRegistry 管理）
```

> **模型配置无兜底**：`spring.ai.openai.*` 不配置（模型网关统一来自「模型供应商」表）；聊天/视觉/向量模型引用解析不出时显式报错引导，不静默回落任何全局默认。

### 系统设置（仅存 DB 的配置键）

大量参数只存在于 `c_ai_config`（由 `config-schema.json` 定义、启动灌默认值），**不写 yml 也能用，保存即生效**。按前缀列主要项（括号内为默认值）：

| 前缀 | 主要键（默认值） | 生效方式 |
|------|------------------|----------|
| `chat.*` | `citationCheckEnabled`、`sseTimeoutMs`、`approvalTimeoutMs`、`askTimeoutMs`(10min)、`planTimeoutMs`(10min)、`maxImagesPerMessage`(9)、`maxImageMb`、`uploadRetentionHours`、`uploadCleanupIntervalMs`(1h)、`retrievalDebugEnabled` | 聊天模型走「会话覆盖 > 个人默认」引用；行为项保存即生效。设置页的「耐心档位」一次写齐四个超时（标准 / 长任务 / 无人值守），`chat.resumeEnabled`（断线重连接流）为内部键、不在设置页。回答温度不在设置页与个人偏好里（知识库问答固定低温度更稳）；提示词归智能体配置，历史压缩与流式重试由 AgentScope 接管 |
| `embedding.*` | （无全局键） | 向量模型绑定知识库 `embedding_ref`；维度按知识库登记在 `c_ai_knowledge_base.embedding_dimensions`，换绑重嵌入先探测维度，通过才 DROP 重建 |
| `retrieval.*` | `vecThreshold`(0.3)、`vectorTopK`(15)、`keywordLimit`(20)、`minContextScore`(0.6 重排门)、`minFusionScore`(0.5 融合门) | 多数保存即生效 |
| `chunk.*` / `parse.*` | `maxSize`(800)、`structural`(true)、`concurrency`(2)、`ocrDpi`(200)；问答对生成无独立模型键（跟随库主个人默认聊天模型） | **需重解析/对后续解析生效** |
| `vision.*` | `enabled`(true)、`prompt`、`concurrency`(2)、`retryCount`(1)、`descCacheVersion`(1)、`descCacheTtlDays`(180) | 保存即生效（`timeoutMillis` 需重启）；视觉模型按知识库 `visionRef` 绑定，无全局键 |
| `context.*` | `maxContextHits`(8)、`maxBlocksPerDoc`(3)、`maxBlocksPerKb`(4) | 保存即生效。历史压缩由 AgentScope `CompactionMiddleware` 按模型窗口触发 |
| `rerank.*` | 设置页只保留 `enabled`(true，平台默认值；未绑模型/服务不可用时按融合分排序并提示，不阻塞问答)、`platformRef`（**平台默认重排模型**）；`minHits`/`maxHits` 为隐藏调优默认，`baseUrl`/`model` 为内部回落槽位。取值顺序：知识库/智能体检索设置 > `rerank.platformRef` 平台默认 > 内部回落 | 保存即生效 |
| `keyword.*` / `eval.*` / `cleanup.*` / `images.*` / `upload.*` | 引擎兜底冷却、体检参数与评判模型 `eval.judgeModel`（全局仅存的模型键，未配置时体检跳过评分）、会话保留期(30 天)、图片鉴权、上传上限(200MB) | 保存即生效 |
| `tool.*` / `webSearch.*` | `tool.enabled`(true，问渠工具总开关)、`sandbox.enabled`(false)、`runcode.enabled`(false)；知识检索/产物/内置/咨询/任务清单随总闸启用，大输出留存与 MCP 引用注册为固定行为；联网搜索 `enabled`(false)、`provider`(tavily/bocha/generic)、`apiKey`(敏感)、`maxCallsPerTurn`(2)、`requireApproval`(true) | 保存即生效。低风险工具不各留平台开关，按智能体三态或 `tool.deny` 收口 |
| `skill.*` | `disabled`(平台停用清单，从当前可用技能里多选)、`remoteAllowedHosts`(URL 安装允许的域名，逗号分隔；空=关闭远程安装)；正文读取上限为平台常量 `SKILL_MAX_FILE_CHARS`(20000)；存储目录 `wenqu.skills.users-dir`(默认 `user-data/skills-users`) | 个人停用由用户在「智能体 → 技能」自助；保存即生效 |
| `agent.*` | `permissionPreset.default`(standard)/`.max`(autonomous)、`maxToolSteps`(15)、`consultMaxPerTurn`(3)、`consultTimeoutMs`(120000)、`subagentMode`(digest/full，子智能体分支执行形态)、`versionKeep`(20，配置版本快照保留份数) | 保存即生效。设置页位置：`maxToolSteps`/`consult*`/`subagentMode` 在「工具调用 → 执行预算与助手咨询」，`versionKeep` 在「定时任务 → 智能体配置版本」 |
| `memory.*` | `enabled`(true)、`flushThrottleMinutes`(10，0=每轮)、`maxMdTokens`(3000，硬上限) | 长期记忆的平台总闸与预算：关掉后问答不再自动提炼新记忆（每轮收尾与压缩前的预提取都停；已记住的照常提起，文件不删）；节流只限每轮收尾的提炼。容量只留 `maxMdTokens` 一个旋钮：合并时给模型的目标预算按它的 2/3 推导，写入落盘时再按它强制裁剪；日账保留期沿用框架默认 90 天，不单独配置。个人层另有自己的开关与「我的记忆」面板 |
| `artifact.*` | `retentionDays`(90，≤0 不清理)、`cleanupIntervalMs`(1d) | 产物超期清理 |
| `web.*` | `refreshEnabled`(true)、`refreshScanIntervalMs`(60s) | 网页源定时刷新总开关与扫描间隔 |
| `scheduled.*` | `enabled`(true)、`maxPerUser`(20)、`scanIntervalMs`(30s)、`timeoutMs`(300000) | 定时执行智能体 |
| `workflow.*` | `maxSteps`(50)、`subagentTimeoutMs`(180000)、`runTimeoutSeconds`(600)、`maxConcurrentRuns`(4)、`runQueueCapacity`(50)、`runLogRetentionDays`(30)、`runCleanupIntervalMs`(1d)、`scheduleScanIntervalMs`(30s) | 单次运行最大图步数（超限 fail-loud）；子智能体节点等待超时；运行超时/并发与排队/运行记录保留期；定时触发扫描 |
| `mcp.server.*` | `enabled`(**false**)、`timeoutMs`(180000)、`allowedOrigins`("") | MCP **服务端**：`allowedOrigins` 为空时仅允许本机回环（Origin 校验防 DNS rebinding） |
| `sandbox.*` | 设置页只保留 `projectWorkdirEnabled`（false）、`projectUploadMaxMb`(100)、`commandTimeoutSeconds`(180)、`idleReleaseMinutes`(60)；连接地址/令牌/回调地址/虚拟根走 `SANDBOX_PROVISIONER_URL` / `SANDBOX_PROVISIONER_TOKEN` / `SANDBOX_CALLBACK_BASE_URL` / `SANDBOX_VIRTUAL_PATH_PREFIX`；输出上限/keepalive/删除超时/回收扫描间隔为内部默认 | 保存即生效（client 懒构建）；token 与 provisioner 侧 `SANDBOX_PROVISIONER_TOKEN` 一致且 ≥32 字符；`projectUploadMaxMb` 服务端再钳到 256（spring multipart 上限） |
| `oidc.*` | OIDC 面板 20 项（issuer/clientId/clientSecret(敏感)/scopes/frontendBaseUrl 等） | 保存即生效（分离部署必填 `frontendBaseUrl`） |
| 用户/角色 | `default_model`（个人默认聊天模型）、`memory_enabled`（用户级记忆开关）、角色-菜单/角色-API 绑定（视觉模型看模型能力位、重排归知识库绑定） | 即时生效 |

> **敏感项按后缀判定**（`.apiKey`/`.clientSecret`/`.token`）：RSA 加密入库 + 页面仅回显 `****后4位` + 恢复默认跳过。

### 设置页 10 个导航分组（15 个配置面板）

对话模型（含长期记忆平台总闸）/ 检索设置（RAG：检索 / 上下文 / 文档解析 / 图片描述，页签切换）/ 工具调用（含沙盒 API 与工作流节点开关、联网搜索与技能页签、工具步数与助手咨询）/ API 密钥 / 接口限流 / 定时任务 / 单点登录 OIDC / 沙盒 / MCP 服务（双向）/ 调试设置。改动后页头「保存配置」一次性保存；**模型跨厂商热切换**（聊天走会话/个人引用、向量/视觉/OCR/图谱抽取/重排归知识库绑定，API Key RSA 加密、保存即生效；向量模型换绑先探测维度、通过后自动全量重嵌入）。深度思考不设全局面板——思考强度等在对话页按模型选择，历史压缩由 AgentScope 接管。

## 后台定时任务

全部周期任务集中在 `ScheduleCenter`（无 `@Scheduled`）：单 daemon 线程按 **10s 节拍**轮询「是否到点」，间隔每次实时读配置（改配置即时生效、≤0 暂停），任务体提交线程池执行，上一轮未结束则跳过（防重叠），失败仅告警并下轮重试。执行日志（`c_ai_schedule_run`）只记「有信息量」的执行：失败 / 手动触发 / 有实质产出——解析队列扫描等高频任务空跑不落行，避免日志被心跳淹没（运行状态仍可在设置页快照查看）。

| 任务 | 间隔键（默认） | 做什么 |
|------|----------------|--------|
| 关键词索引精确对账 | `keyword.reconcileIntervalMs`(1h) | 双向比对 MySQL 与 Meilisearch 定向修复漂移（按主键游标批量拉取，差异超限转全量重建；`GET_LOCK` 分布式锁互斥，多副本安全） |
| 聊天图片目录清理 | `images.chatCleanupIntervalMs`(1d) | 删除超过保留期的用户聊天图片 |
| 聊天附件超期清理 | `chat.uploadCleanupIntervalMs`(1h) | 删除超过保留期（`chat.uploadRetentionHours`，≤0 不清理）的会话附件文件 |
| 检索评估自动体检 | `eval.autoIntervalMs`(1d) | 按线上参数跑评估集并与上期对比，下滑预警 |
| 配置缓存兜底刷新 | 固定 5 分钟 | 补齐 Redis 订阅断线期间错过的配置变更 |
| 过期会话/消息清理 | `cleanup.sessionCleanupIntervalMs`(1d) | 物理删除超过 `sessionRetentionDays`（30 天）的会话与消息 |
| 产物超期清理 | `artifact.cleanupIntervalMs`(1d) | 删除超过 `artifact.retentionDays`（90 天，≤0 不清理）的产物文件与记录 |
| 定时智能体任务 | `scheduled.scanIntervalMs`(30s) | 扫描到期的用户定时任务并投递线程池执行（扫描与执行分离，长任务不拖节拍） |
| 文档解析队列扫描 | `parse.queue.scanIntervalMs`(5s) | 抢占 `c_ai_parse_task` 到期任务投给 worker 池（上传/重解析全靠它推动，DB 队列不怕进程重启） |
| 网页源定时刷新 | `web.refreshScanIntervalMs`(60s) | 扫描到期且开启自动刷新的 url 文档，重抓网页 + 同名替换重建 |
| 沙盒空闲回收 | `sandbox.cleanupIntervalMs`(10min) | 回收空闲超过 `sandbox.idleReleaseMinutes`（60 分钟）的会话沙盒容器（0=不回收） |
| 工作流审批超时回收 | 固定 60s | 挂起超过节点 `timeoutSeconds` 的人工审核 run 落 timeout 终态 |
| 工作流运行记录清理 | `workflow.runCleanupIntervalMs`(1d) | 物理删除超过 `workflow.runLogRetentionDays`（30 天）的运行记录（挂起审批的 run 排除） |
| 工作流定时触发 | `workflow.scheduleScanIntervalMs`(30s) | 扫描到期的定时工作流并派发已发布版本运行（先推进 next_run_at 防重复） |
| Trace 线上采样 | `trace.samplingIntervalMs`(1d) | 线上对话按规则入采样池（差评必采 + 无引用/随机），供标注回流评测集 |
| 任务执行日志清理 | `schedule.runLogCleanupIntervalMs`(1d) | 物理删除超过 `schedule.runLogRetentionDays`（7 天）的定时任务执行日志 |
| 站内通知清理 | `notification.cleanupIntervalMs`(1d) | 物理删除超过 `notification.retentionDays`（30 天）的站内通知 |

## 目录结构

```
WenQu/                               # 工作区根（git 仓库名 WenQu；本地目录名可自定义）
├── pom.xml                          # 后端 Maven 工作区（com.wenqu:wenqu，产物 target/wenqu.jar）
├── src/main/java/.../ai/
│   ├── WenQuApplication.java        # 入口
│   ├── config/                      # SecurityConfig(Token+RBAC) / SchemaMigrator(存量库补列补索引) / DynamicChatClientConfig / OpenApiConfig / GlobalExceptionHandler 等
│   ├── controller/                  # 40 个控制器、290+ 端点（完整契约以 Swagger 为准）
│   ├── service/                     # 问答主链路（ChatService/HybridRetrievalService/RerankService/GraphRagService）
│   │                                #   智能体与工具（ArtifactService/BuiltinTools/websearch/）
│   │                                #   工作流（WorkflowDsl/WorkflowValidator/WorkflowEngine/WorkflowService）
│   │                                #   沙盒（SandboxService/SandboxTools）· 基础设施（DynamicOpenAiChatModel/
│   │                                #   DynamicEmbeddingModel/ModelRegistryService/OidcService/ConfigCryptoService/
│   │                                #   ScheduleCenter/UsageLedgerService）
│   ├── sandbox/                     # 沙盒客户端：provisioner 连接/执行/文件读写/路径校验
│   ├── parser/                      # DocumentParser 接口 + Docx/Pdf(扫描件 OCR)/Excel/Text 解析器
│   ├── util/                        # TokenCounter(jtokkit BPE token 计数) / ImageCompressor / RequestUser(登录态 ThreadLocal)
│   └── model/ + mapper/ + dto/      # 50+ 实体 + Mapper + DTO
├── config/
│   └── application-local.yml        # 本地开发私有配置（含密钥/数据目录，.gitignore 忽略；不打进构建产物）
├── src/main/resources/
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD 缺失 fail-fast）
│   ├── config-schema.json           # 设置项 schema 唯一定义源（启动加载，缺失即启动失败；下发给前端渲染设置页）
│   └── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/ + artifacts/{uid}/{yyyyMM}/ + secret/config-rsa.key + eval/ + skills/
├── docs/
│   └── deployment-guide.html        # 独立部署手册（Compose 全栈：环境变量/密钥/备份/升级/排障，浏览器直接打开）
├── deploy/
│   ├── nginx.conf                   # 生产 nginx 参考配置
│   └── sandbox-provisioner/         # 沙盒 provisioner 部署资产：app.py(FastAPI) + Dockerfile + compose + run.sh + sandbox.env
│       └── sandbox-image/Dockerfile # 派生沙盒镜像（容器内无 sudo，运行时依赖必须烧进镜像层）
├── user-data/                       # 沙盒持久卷：shared/{uid}/workspace/（bind 给容器内 /home/gem/user-data）
├── user-data/skills-users/          # 用户自装技能（{uid}/技能名/SKILL.md，宿主、跨会话）
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 22 LTS）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    ├── src/router.js                # 路由表（/chat /agents /knowledge /artifacts /stats /profile /s/:token /shared/:token + 管理页；管理页带管理员守卫）
    └── src/views/                   # AppLayout / ChatPage / AgentsHubPage(页内 Tab 由菜单数据驱动) / FlowEditor / KnowledgeBasePage /
                                     #   DocumentsPage / ArtifactsPage / StatsPage / ProfilePage / MembersPage / PermissionsPage /
                                     #   DashboardPage / EvaluationPage / SettingsPage / ShareChatPage / LoginPage / OidcCallbackPage
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
scripts/mac/dev-stack.sh up meili    # 开发机：起 wenqu-meilisearch（索引在卷 wenqu-meili-data）
docker compose up meilisearch        # 全栈 Compose 部署：起 wenqu-meilisearch，需先设置 AI_MEILI_KEY
curl http://localhost:7700/health     # 期望 {"status":"available"}；两套 compose 同名同端口，同机只起一个

# 6. 沙盒（隔离执行环境）
bash deploy/sandbox-provisioner/run.sh docker-up
bash deploy/sandbox-provisioner/run.sh status
# 配好「沙盒」面板后，聊天里的 shell/读写文件由框架原生工具提供（受权限档位控制）；
# 工作流沙盒节点需在「工具调用」里开启「沙盒 API 与工作流节点」
```

**端到端手动验证**（建议每次改动后走一遍）：
1. 知识库上传含图片 docx → 解析进度 → 问答引用 `[N]` 溯源与图片展示
2. 智能体对话：开启工具后让模型执行计算/生成产物卡片 → 定时任务触发一次执行看历史
3. 沙盒：对话中要求「用 Python 生成数据并统计」→ 观察工具状态行与 `execute` 实时输出流 → 产物卡片 → `/artifacts` 可下载
4. 设置页修改检索参数 → 检索调试对比前后召回差异 → 检索评估页验证指标

## 运维须知

**默认关闭项（安全边界）**
- 默认关闭的具体能力：`run_code` / `todo` / 沙盒执行默认关，需在设置页显式开启；联网搜索 `webSearch.enabled` 默认 false（受 `tool.enabled` 总闸约束）；MCP 客户端无全局开关（每用户自建）；MCP **服务端** `mcp.server.enabled` 默认关。**总开关 `tool.enabled` 默认开**（基础工具可用）。**智能体无全局启用开关**（全员默认可用）
- 沙盒 token：应用侧环境变量 `SANDBOX_PROVISIONER_TOKEN` 须与 provisioner 侧一致且 ≥32 字符（不足 fail-closed 拒绝启用）；token 校验推迟到首次使用（未启用沙盒的部署不因缺 token 起不来）

**配置生效时机**
- 需重解析才生效：`chunk.maxSize` / `headingDepth` / `structural` 等解析参数（检索/上下文类参数均保存即生效）
- `vision.timeoutMillis` 需重启（视觉客户端启动时构建，其余 `vision.*` 保存即生效）

**智能体行为**
- 智能体：会话不锁定，换人下一问立即生效；跨主题由模型经框架的子智能体派遣把那一问派给职责对口的助手去查（各带自己的知识库），作答智能体被删/无权时 fail-loud 提示，不静默换人

**部署与数据**
- `.nvmrc` 固定 Node 22 LTS（要求 ≥22.12，Node 18/21 不可构建）
- 存量库升级：`SchemaMigrator` 启动自动补列补索引（幂等）；JSON 列需库侧表达式默认值
- 运行标识改名 `ai-doc-*` → `wenqu-*`（Redis 向量索引名、Redis 键前缀、Meilisearch 索引名、Compose 容器名）：
  存量实例跑一次 `bash scripts/redis/rename-ai-doc-indexes-2026-10-17.sh` 收尾。顺序是先部署新代码、每个知识库被访问过
  一次（应用按新名建索引，同一批向量键自动重新入索引——不重嵌入、不重建关键词索引），再 `--apply` 回收旧索引
- 图片访问路径：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时已去 `/ai` 前缀
- 过期会话/消息自动清理：默认每日清除逻辑删除超过 30 天的会话与消息（保留期即「撤销删除」窗口）
- 沙盒运行时依赖必须烧进镜像：容器内 gem 用户不在 sudoers——用 `deploy/sandbox-provisioner/sandbox-image/Dockerfile` 派生镜像（如 +openjdk-17），`.env` 设 `SANDBOX_IMAGE` 后重建 provisioner；旧沙盒容器直接删（工作区在宿主 bind mount 不丢）
- 沙盒 `write_file` 为创建语义：文件已存在报错，修改用 `edit_file` 或 shell 追加

**模型兼容**
- MaaS 网关模型格式：部分模型返回 DashScope 原生格式（`{"text":...}`）Spring AI 无法解析；需用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- 切换向量模型必然触发全量重嵌入：耗时与知识块数成正比（万级块可达数十分钟），期间向量检索降级关键词路；**避免业务高峰切换**；重嵌入与并发解析撞车可能丢块，任务末尾索引对账会告警，解析空闲时手动补跑一次即可
- base-url 不含 `/v1`：Spring AI 与 VisionService 都会自动补

## 开源许可

本工作区以 [MIT License](LICENSE) 开源。核心依赖均为宽松许可（后端 Spring 生态 Apache-2.0、前端 Vue 生态 MIT / Apache-2.0；`mysql-connector-j` 为 GPLv2 + 通用 FOSS 例外），可放心商用与二次开发。

> **品牌声明**：MIT 许可仅及于代码本身。「问渠」「WenQu」中英文名称与问渠 logo **不在授权范围内**——你可以在 fork 中自由使用、修改代码，但请勿以「问渠 / WenQu」名义分发、对外提供服务或进行宣传。
