# 问渠 WenQu · AI 智能体工作台

> **问渠那得清如许，为有源头活水来。** —— 答案，自有源头。
>
> 中文名：**问渠** ｜ 英文名：**WenQu** ｜ 英文 slogan：*Ask the source.*

独立 AI 服务，基于 Spring AI 实现的 **AI 智能体工作台**：以智能体为核心组织模型、知识与工具——多智能体并行编排（StateGraph）、技能包（Skills）、MCP 双向（接入外部 Server + 把智能体对外发布为 MCP Server）、**沙盒隔离执行环境（容器内跑 shell / 读写文件，实时输出流式回传）**、定时执行智能体、产物交付、可视化工作流（DSL 唯一真源 + StateGraph 执行引擎与调试 trace，可视化画布编辑器可用），配合知识库（RAG：Word/PDF/Excel/TXT/Markdown 解析含扫描件 OCR、网页 URL 导入可定时自动刷新、混合检索 + 查询改写、知识块关联检索、语义缓存加速、回答中位置级展示文档原图、引用溯源）、深度思考、**用户长期记忆（问答后自动提炼、跨会话注入）**、过程独白与消息时间线、检索量化评估与数据看板，是面向"智能体 + 知识"场景的完整工作台。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8（`spring-ai-bom` 统一版本） |
| ORM | MyBatis-Plus 3.5.12（逻辑删除 `deleted`，驼峰映射） |
| 数据库 | OceanBase（MySQL 协议，库 `ai_doc_assistant`，可按环境调整）；30+ 张 `c_ai_*` 表由启动自动建立 |
| 向量库 | Redis Stack（RediSearch 向量索引，Jedis 客户端，索引 `ai-doc-index`；连接地址经 `REDIS_HOST`/`REDIS_PORT` 注入，默认 `127.0.0.1:6379`） |
| LLM | 问答/视觉/向量三类模型均可跨厂商热切换（DeepSeek / 智谱 GLM / 阿里百炼 Qwen / Kimi / 豆包 / 腾讯混元 / 百度千帆 / MiniMax / SiliconFlow / 本地 Ollama 等预设）；图片理解/OCR 可走本地 Ollama `qwen3-vl:2b` |
| 智能体编排 | Spring AI Alibaba Agent Framework 1.1.2.3（`StateGraph` 多子智能体并行编排） |
| MCP | `spring-ai-mcp`（MCP Java SDK 0.18.3）——外部工具服务器接入 |
| 沙盒 | 独立 provisioner 服务（Python 3.13 + FastAPI + Docker SDK，容器化部署）+ 全功能沙盒镜像（all-in-one-sandbox）；模型经 Function Calling 在隔离容器内执行 shell、读写文件 |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ 原生流（txt/md/csv）+ jieba-analysis 1.0.2（中文分词） |
| 序列化 / 文档 | fastjson2 2.0.31；springdoc-openapi 2.8.8（Swagger UI，生产默认关） |
| 安全 | RSA 加密落库模型 API Key（`ConfigCryptoService`）+ SHA-256 哈希签发对外 API Key + 图片 HMAC 签名 URL + PBKDF2 密码哈希 + JWT 登录令牌 + OIDC 单点登录 |
| 前端 | Vue ^3.4 + Vite ^5 + Ant Design Vue ^4.2 + markdown-it/DOMPurify/highlight.js + @vue-flow/core 1.48.2（工作流画布，锁版本）（`.nvmrc` 固定 Node **18.19.0**，Node ≥18 均可） |

## 目录结构

```
WenQu/                               # 项目根（git 仓库名 WenQu；本地目录名可自定义）
├── pom.xml                          # 后端 Maven 项目（com.wisesoft:wenqu，产物 target/wenqu.jar）
├── src/main/java/.../ai/
│   ├── WenQuApplication.java        # 入口
│   ├── config/                      # SecurityConfig(Token+RBAC) / SchemaMigrator(存量库补列补索引) / DynamicChatClientConfig / OpenApiConfig / GlobalExceptionHandler 等
│   ├── controller/                  # 28 个控制器、190+ 端点（完整契约以 Swagger 为准）：
│   │                                #   Auth(登录+OIDC) / Chat(SSE+会话+消息组+引用溯源+工具确认) / Document / Knowledge(知识块) / KnowledgeBase(知识库) / Qa(反馈+看板) / Config(设置 schema)
│   │                                #   Provider(模型供应商) / Agent(智能体) / Skill(技能) / Mcp / ScheduledJob(定时任务) / Artifact(产物) / ApiKey / ApiEndpoint(对外问答)
│   │                                #   Memory(用户长期记忆) / User / Role / Menu / Department(成员与 RBAC) / SearchIndex / DescCache / RetrievalDebug / Evaluation / Sandbox(沙盒工作区浏览) / Workflow(工作流)
│   ├── service/                     # 问答主链路：RagService(问答编排+工具注册) / HybridRetrievalService(混合检索+扩散汇总) / RerankService / KnowledgeRefService(引用识别+1-hop 扩散)
│   │                                #   KeywordIndexService(mysql|meilisearch 双引擎) / KeywordExtractor(jieba) / VisionService / ImageFilterService / ImageDescCache / UserImageService
│   │                                #   DocumentService(解析+向量化+全量重嵌入编排) / SessionService / QaLogService / ConfigService(config-schema 唯一定义源) / RateLimitService / AnswerCacheService / RetrievalEvaluationService
│   │                                #   智能体与工具：SubAgentOrchestrator(StateGraph 并行编排) / ArtifactService(产物交付) / BuiltinTools / KnowledgeRetrievalTool / PresentArtifactTool / SkillTools
│   │                                #   工作流：WorkflowDsl(DSL 唯一真源) / WorkflowValidator(结构校验) / WorkflowEngine(DSL→StateGraph 翻译层+执行) / WorkflowExpr(条件表达式) / WorkflowRunCtx(显式上下文+trace) / WorkflowService(CRUD+运行+记录)
│   │                                #   SandboxService(沙盒装配：scope 挂会话+空闲回收) / SandboxTools(沙盒六工具，含 deliver_artifact 产物交付) / AgentService / SkillService / McpClientService / ApiKeyService
│   │                                #   基础设施：DynamicOpenAiChatModel / DynamicEmbeddingModel(配置指纹热切换) / ModelRegistryService(供应商判权) / OidcService(单点登录) / ImageUrlSigner(HMAC)
│   │                                #   ConfigCryptoService(RSA) / ScheduleCenter(定时任务+沙盒回收调度) / ThreadPoolManager(线程池)
│   ├── sandbox/                     # 沙盒隔离执行环境：ProvisionerSandboxProvider(连接缓存+keepalive) / SandboxProvisionerClient(HTTP 客户端，强制 HTTP/1.1)
│   │                                #   ProvisionerSandboxBackend(execute/read/write/edit/ls) / AgentSandboxRuntimeClient(沙盒 runtime API) / SandboxPaths(uid 目录名+路径校验)
│   ├── parser/                      # DocumentParser 接口 + DocxParser(结构感知切分) / PdfParser(扫描件 OCR 降级) / ExcelParser / TextParser(txt/md/csv)
│   ├── util/                        # TokenCounter(分语言 token 估算) / ImageCompressor(识别用压缩图) / RequestUser(登录态 ThreadLocal)
│   └── model/ + mapper/ + dto/      # 30+ 实体（文档/知识块/知识库/会话/消息/智能体/技能/MCP/供应商/定时任务/产物/记忆/角色/菜单等）+ Mapper + DTO
├── config/
│   └── application-local.yml        # 本地开发私有配置（含密钥/数据目录，.gitignore 忽略；位于 Spring Boot 外部配置目录，不打进构建产物）
├── src/main/resources/
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD 缺失 fail-fast；JWT 密钥建议显式配置）
│   ├── config-schema.json           # 设置项 schema 唯一定义源（ConfigSchemaService 启动加载，缺失即启动失败；下发给前端渲染设置页）
│   └── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/ 提取图/用户图 + artifacts/{uid}/{yyyyMM}/ 交付产物 + secret/config-rsa.key + eval/ 评估集 + skills/ 技能包
├── deploy/
│   ├── nginx.conf                   # 生产 nginx 参考配置
│   └── sandbox-provisioner/         # 沙盒 provisioner 部署资产：app.py(FastAPI) + Dockerfile + docker-compose.yml + run.sh(启停/状态/日志) + sandbox.env
│       └── sandbox-image/Dockerfile # 派生沙盒镜像（基础镜像 + openjdk-17 等；沙盒内无 sudo，运行时依赖必须烧进镜像层）
├── user-data/                       # 沙盒持久卷：shared/{uid}/workspace/（bind 给容器内 /home/gem/user-data）
├── skill-projections/               # 沙盒技能投影（只读挂给容器内 /home/gem/skills）
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 18.19.0）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    ├── src/router.js                # 路由表（/chat 工作台 + /agents 智能体工作台 + /knowledge 知识库 + /artifacts 产物 + /profile 个人设置 + /s/:token 游客分享页 + 管理页；管理页路由带管理员守卫）
    ├── src/api.js / configSchema.js # 接口封装 / 设置项 schema 容器（applyServerSchema 填充）
    └── src/views/                   # AppLayout(侧边导航+最近会话) + ChatPage(对话) + AgentsHubPage(智能体工作台：模型供应商·智能体·技能·MCP·定时任务·工作流 六 Tab) + WorkflowPanel(工作流列表) + FlowEditor(工作流画布编辑器)
                                     #   KnowledgeBasePage(知识库) + DocumentsPage(文档管理) + ArtifactsPage(我的产物) + ProfilePage(个人设置·长期记忆管理)
                                     #   MembersPage(成员) + PermissionsPage(权限) + DashboardPage(数据看板) + EvaluationPage(检索评估)
                                     #   SettingsPage(系统设置) + ShareChatPage(游客分享对话) + LoginPage + OidcCallbackPage
```

## 启动方式

### 1. 环境准备

**Redis Stack**（RediSearch 向量索引）：
```bash
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack-server:latest
```
> 宿主端口按实际部署自行映射，后端以 `REDIS_HOST`/`REDIS_PORT` 指向该端口（docker-compose 的映射见 `docker-compose.yml`）。RedisVectorStore 自动配置使用 **Jedis** 客户端，项目已引入 `redis.clients:jedis` 且 `spring.data.redis.client-type: jedis`。

**本地 Ollama**（图片描述 / 扫描 PDF OCR）：安装 Ollama 并拉取视觉模型：
```bash
ollama pull qwen3-vl:2b
```
> 建议设置环境变量 `OLLAMA_NUM_PARALLEL=4`（否则多图描述串行排队）；4GB 显存机器并发度建议 `vision.concurrency=2`。

**数据库**：现有 OceanBase 库 `ai_doc_assistant`（库需预先存在，库名按实际环境配置）。表结构由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全；存量库缺列缺索引由 `SchemaMigrator` 启动期自动补齐）；也可手动执行：
```bash
mysql -h<db-host> -P<db-port> -uroot -p ai_doc_assistant < src/main/resources/schema.sql
```

**沙盒 provisioner**（可选，隔离执行环境）：
```bash
bash deploy/sandbox-provisioner/run.sh docker-up      # 构建 + 启动（127.0.0.1:8002，真沙盒）
bash deploy/sandbox-provisioner/run.sh status         # 状态 + 健康探测
bash deploy/sandbox-provisioner/run.sh docker-down    # 停止
bash deploy/sandbox-provisioner/run.sh docker-logs    # 跟踪日志
```
> 需要 Docker Desktop；首次 `docker-up` 会构建 provisioner 镜像并拉取沙盒运行时镜像（约 12GB，之后免拉取）。沙盒持久卷在本项目 `user-data/shared/{uid}/workspace/`（bind 给容器内 `/home/gem/user-data`），技能投影在 `skill-projections/`（容器内只读 `/home/gem/skills`）。provisioner 访问令牌默认 `wenqu-local-dev-sandbox-provisioner-token`（compose 默认值，须与设置页「沙盒 → 访问令牌」一致，≥32 字符）。沙盒运行时镜像如需 JDK/Node 等依赖，用 `sandbox-image/Dockerfile` 派生新镜像并在 `.env` 设 `SANDBOX_IMAGE`（容器内无 sudo，依赖必须烧进镜像层）。

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码

# ===== 基础设施连接（有默认值，按部署环境调整）=====
export DB_HOST=127.0.0.1                  # 数据库主机（容器部署默认 mysql；外部 OceanBase 改为实际地址）
export DB_PORT=3306                       # 数据库端口
export DB_NAME=ai_doc_assistant           # 库名
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
export AI_MEILI_INDEX=ai-doc-chunks       # 关键词索引名（只从 env/yml 读，永不入库）
export LOG_LEVEL_APP=info                 # 应用日志级别
export LOG_LEVEL_SPRING_AI=info           # Spring AI 日志级别
export MYBATIS_LOG_IMPL=org.apache.ibatis.logging.slf4j.Slf4jImpl
export ACTUATOR_HEALTH_DETAILS=never      # /actuator/health 详情级别
export SPRINGDOC_ENABLED=false            # Swagger/OpenAPI 开关（默认关，接口契约不外泄；本地调试可置 true）
```

> **行为参数与模型配置不走环境变量**：检索/重排/解析/限流/深度思考/上下文/沙盒/记忆/定时任务等 16 组 130+ 行为参数统一在**系统设置页**维护（`config-schema.json` → `c_ai_config`，保存即生效）；问答/视觉/向量模型走**模型供应商**登记 + 知识库/个人默认绑定（API Key RSA 加密入库）。`application.yml` 里的同名项（`AI_CHAT_KEY`、`AI_RERANK_*` 等）只在**首次启动向空库灌默认值**时生效一次，之后一律以 DB 为准——已初始化的库上改这些环境变量不会生效。旧变量 `AI_VISION_MODEL`/`AI_VISION_BASE_URL`/`AI_VISION_API_KEY`（全局视觉模型）已退役：视觉模型按知识库绑定（visionRef → 供应商表），环境变量链路无消费方。

**本地开发**：无需 export。密钥、数据目录等环境相关值放在项目根 `config/application-local.yml`（Spring Boot 外部配置目录，已被 .gitignore 忽略，**不打进构建产物**——密钥不会随 jar 分发），再以 `local` profile 启动（application.yml 已默认激活 local）：
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
# 使用外部 OceanBase/MySQL：先 docker compose up -d redis-stack meilisearch，
# 再以 DB_HOST/DB_PORT/DB_NAME/DB_USERNAME 指向外部库启动 app（或改 compose 环境变量）
```

后端监听 `http://localhost:8090/ai`（context-path `/ai`），API 前缀 `/api/ai/*`，
健康检查：`GET http://localhost:8090/ai/actuator/health`。
接口文档（Swagger UI）：`http://localhost:8090/ai/swagger-ui/index.html`（springdoc 自动生成；Try-it-out 在线调试需先登录，在 Authorize 中填入 `Bearer <登录令牌>`）。**默认关闭**：`SPRINGDOC_ENABLED` 缺省为 false（接口契约不外泄），需要时 export `SPRINGDOC_ENABLED=true` 开启。同理 docker-compose 默认仅把 8090 绑定到回环地址（`127.0.0.1`），对外直连需 `APP_PUBLISH=8090` 或经 nginx 反向代理。

### 4. 启动前端

```bash
cd web
nvm use            # .nvmrc 固定 Node 18.19.0（Node ≥18 均可，建议 18/20/22）
npm install
npm run dev        # 访问 http://localhost:5800/chat（端口被占直接报错，不会跳号）
```

Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 使用流程

启动前端后访问 `http://localhost:5800/chat`，未登录自动跳转 `/login`（本地账号按引导创建管理员；接入 OIDC 后可单点登录）。左侧导航由 `/auth/me` 下发的**菜单树**渲染（RBAC：按角色绑定下发，可在「权限管理」页维护）——典型入口：**对话 / 智能体 / 知识库 / 我的产物 / 成员管理 / 权限管理 / 数据看板 / 检索评估 / 系统设置**，下方「最近」列出会话（悬浮显示「导出 Markdown」「删除」），底部显示当前登录用户与自助改密入口。

1. **对话**（`/chat`）：欢迎页有推荐问题标签；输入框支持拖入/粘贴/点击上传图片（最多 5 张）、`@` 引用指定文档（被 @ 文档优先参考）；工具行可切换**智能体**、上传图片、**深度思考**开关，右侧显示本轮生效模型名。**智能体会话级绑定**：会话首问时锁定（含"自动派遣"也只在首问路由一次），全程同一人设/知识库/工具集——**切换智能体请新建会话**，输入区上方实时提示当前绑定。提问后：混合检索 + 查询改写 + 知识块关联扩散 → 流式回答，句末 `[N]` 引用角标可点开溯源弹窗（来源全文与图片）、位置级插入文档原图、回答下方**检索状态行**可展开看全部来源；深度思考先出折叠面板流式展示思维链；模型推理/试错的**过程独白**以灰字原位展示（与正文分流，不干扰回答）；**智能体/工具调用**（知识库检索、沙盒执行、产物交付、内置工具、MCP、技能）以状态行实时反馈（沙盒命令输出实时流式滚动），整轮过程以**消息时间线**呈现且随消息落库——刷新页面后仍是交错的"文本+工具+产物"过程视图；**用户长期记忆**（memory.enabled）：问答完成后异步提炼值得长期记住的用户信息并注入后续对话（`/profile` 个人设置可查/改/删，公开分享页不携带）；产物以可下载卡片下发。回答支持复制/有帮助/没帮助/重新生成/检索调试/**加入评测集**（差评回流）/导出 Markdown/删除本轮。
2. **智能体工作台**（`/agents`，管理员）：同页五个 Tab——**模型供应商**（登记各厂商网关，平台级/个人级归属，普通用户只读可用）、**智能体**（主/子智能体：模型、提示词、知识库范围、能力开关、委派关系——附**编排视图**拓扑图展示委派链路/失效引用/孤儿子智能体、设默认；卡片可**公开发布**——生成 `/s/{token}` 免登录链接与 iframe 嵌入代码，并可选把同一 token 兼作 **MCP 端点 `/ai/mcp/{token}`**（Claude / Cursor 等外部客户端直接调用该智能体），停用/撤销随时可控，游客工具收窄为知识检索+内置项；**工具执行确认**——沙盒/MCP 类有副作用工具可设"执行前确认"（人在回路：批准/拒绝/超时按拒绝）或"禁用"）、**技能 Skills**（新建/URL 安装/启停用，个人资产）、**MCP 外部工具**（登记/重连/探测 MCP Server）、**定时任务**（智能体定时执行：cron/间隔调度、执行历史、结果会话）。
3. **知识库**（`/knowledge`）：多知识库管理——每库可设解析参数与检索参数（**留空跟随全局默认**，保存即固化覆盖）、文档级共享范围；**父子分块**（parse.childEnabled，默认关）：超长块确定性切成小子块向量化做检索索引，命中子块后返回父块完整正文（短文本召回更准、上下文不丢）；**问答对增强**（parse.qaEnabled，默认关）：解析时对每块用对话模型生成 QA 并按问法向量化（命中问法后返回来源块，对标 FastGPT 问答对模式；行落库 `c_ai_knowledge_qa`，重嵌/迁移不重调 LLM）；点入库进入**文档管理**：上传（多选/拖拽/带描述）与**网页 URL 导入**（批量粘贴、正文提取、源文件为 HTML 快照，仅公网 http/https 并逐跳防内网探测；URL 文档可开**定时自动刷新**——按文档级 cron 周期重抓、同名替换重建，失效链接失败留痕不中断）、解析进度（图片逐张进度）、知识块预览（切片列表/结构导图、编辑单个块并重向量化）、版本历史与回滚、启停用/重解析/批量操作、全局知识块搜索。
4. **我的产物**（`/artifacts`）：问答里智能体交付的文件产物（Markdown/CSV/JSON/HTML 等）汇总，可预览、下载、删除；定时任务产出的结果会话也在此链路。
5. **数据看板**（`/dashboard`，管理员）：核心指标卡 + 检索质量自动体检 + 热门/无命中问题 TOP10 + 差评回流 + 知识库缺口一键补块。
6. **检索评估**（`/evaluation`，管理员）：从历史问答回放生成评估集，多参数组对比 recall@k / MRR / 命中率，一键体检、一键应用最优组到线上配置。
7. **系统设置**（`/settings`，管理员）：左侧分组导航（**智能问答模型 / 图片描述（视觉；模型归各知识库解析设置）/ 文档解析默认模板 / 检索设置 / 上下文控制 / 深度思考 / 工具调用（含沙盒执行工具开关）/ API Key 管理 / 技能 Skills / 并行检索 / 接口限流 / 定时维护 / 单点登录 OIDC / 沙盒（隔离执行环境）/ 用户长期记忆 / MCP 服务（双向）**），共 16 组、130+ 设置字段，改动后页头「保存配置」一次性保存；**问答/视觉/向量三类模型跨厂商热切换**（四要素存库、API Key RSA 加密、保存即生效；向量模型保存前先探测维度、通过后自动全量重嵌入）。
8. **成员与权限**（`/members` `/permissions`，管理员）：成员账号管理、角色维护（`admin`/`superadmin`/自定义角色）、角色-菜单绑定（决定侧边栏入口）、角色-API 绑定（决定可访问端点）；OIDC 自动建档的用户也走同一权限模型。**个人设置**（`/profile`，所有用户）：自助改密、三类个人默认模型、长期记忆管理（自动提取的记忆可编辑/删除，含来源会话溯源与被注入次数）。

## 核心功能

- **混合检索**：Redis 向量 Top-K + 关键词召回并行（**超时/阈值/召回数/位置奖励等行为参数设置页可调**，保存即生效）；**向量分归一化 + 双命中叠加**（语义+关键词命中 = 向量分+关键词分+标题奖励）；**关键词引擎可切换**（默认 `mysql` LIKE 零依赖；切到 `meilisearch` 用中文分词 + 相关度打分，服务不可用/超时自动降级回 MySQL，首次切换需 `POST /api/ai/search-index/reindex` 全量重建，写索引随解析/编辑/删除增量同步）；**jieba 中文分词**（搜索模式细粒度词元 + 长词 2-gram/4-gram 子词元补充召回宽度，启动预热词典；**检索状态行只展示主词元**，子词元仅参与召回不展示）；**分块位置奖励**（文档首块加权）
- **知识块关联检索**（`c_ai_knowledge_ref`）：解析时识别块内交叉引用（详见/参见/见 编号节/《章节名》/章节名+章节后缀等 7 类模式），建块间引用边；检索命中 A 时 **1-hop 扩散**自动带出关联块 + **结构上下文扩展**沿章节路径带出父章节摘要；扩散块走独立配额，引用来源带 `origin` 标注（REF_OUT/REF_IN/PARENT）；引用关系与块/文档同生命周期
- **模型热切换**（问答/视觉/向量三类，均免重启）：四要素（网关地址 / API Key / 模型名 / 接口路径）全部存 `c_ai_config`，设置页保存即生效，API Key **RSA 加密入库**；`DynamicOpenAiChatModel` / `DynamicEmbeddingModel` 每次调用校验配置指纹，变化即本地重建客户端；路径归一化兼容智谱 `/v4`、方舟 `/v3`、千帆 `/v2`、Ollama 等 OpenAI 兼容端点；多副本经 Redis pub/sub 广播各自重建
- **向量模型切换：维度护栏 + 全量重嵌入编排**：三道护栏前置（initialize-schema 校验 → 真实探测维度 → 维度比对判定 schema 重建），按序执行：清语义缓存 → DROP 索引 → 重建 schema → 游标分批全量重嵌 → 回写维度 + 索引对账；**期间向量检索降级关键词路，服务不中断**；Redis 分布式锁保证多副本单点执行
- **查询改写**：LLM 将用户问题改写为检索关键词（默认开启）；支持多轮对话上下文改写（追问"那删除呢？"自动补全），改写结果入库可评估
- **上下文与长度控制**：`预算 = min(模型窗口×安全系数 − 输出限制, 成本软上限)`；**价值驱动填充**（按相关度分数累积填充）；**块内命中片段截取**（±150 字窗口，被截掉的图片占位自动补齐）；**关联扩散块独立配额**；**信息增益去冗余**（与已选块词元重叠过高即跳过）；**历史裁剪**；token 按中英文分语言估算
- **图片链路**：docx 提取图片（去重 + **双图策略**：识别用压缩图 1280px，展示用原图）→ 视觉模型生成描述随分块落库 → 检索命中后**相关性预筛** → 全局编号 `[图片N：描述]` → **相关性校验兜底**（错配/编造编号自动剔除）→ SSE `image` 事件 → 前端灯箱渲染原图；图片描述完成逐张上报进度
- **引用溯源**：回答句末 `[N]` 角标 → 弹窗展示来源知识块全文（图文交错）+ 关联截图；**检索状态行**展开列出全部命中来源与摘要，条目点击弹同一溯源弹窗
- **语义缓存**（`c_ai_answer_cache`）：相似问题直接复用历史回答（embedding 余弦相似度 ≥ 阈值，默认 0.96）；知识库任何变动**整体失效**；**维度护栏**：缓存向量与当前模型维度不一致一律判不命中——跨模型向量空间不可比
- **文档解析**：docx（表格→Markdown、结构感知切分）/ xlsx / pdf（扫描件自动 OCR，逐页 200DPI → 本地视觉模型）/ txt/md/csv；大文件流式解析；分块重叠只进向量化文本；解析删除感知（删除立即停止并清理产物）
- **数据闭环**：问答日志 + 👍👎 反馈 + 看板聚合；**无命中问题 → 一键创建知识块**（自动生成向量）；**差评回流**：看板差评样本 → 一键加入检索评估集
- **智能体（Agent）**：`c_ai_agent` 存智能体预设（模型 / System Prompt / 知识范围 / 工具开关 / MCP / 技能 / 是否子智能体 / 委派列表 / 是否默认）；**智能体会话级绑定**（对齐 Coze/Dify/Claude Projects 主流做法）：会话**首问时锁定**（`c_ai_session.agent_id`，`bindAgent` 以 `isNull(agent_id)` 只写一次），全程同一人设/知识库/工具集——已锁定会话忽略请求里的 agentId，**切换智能体 = 新建会话**；列值语义 `NULL`=未绑定、空串=已决定不绑定（走全局配置），显式不用智能体不会被反复重路由；「自动派遣」= **首问路由一次并锁定**（`agent_dispatched` 事件 + 就地下发 `agent_bound` 锁定结果），不再每轮静默换人；绑定智能体不可访问（被删/权限变更）时 fail-loud 降级提示（`agentUnavailable`/`agentMissing`），不静默回落全局配置；**主智能体可把复杂问题并行委派给子智能体**（各自检索+提炼，汇总节点合并）；**按需委派路由**（`agent.autoRoute`）：主模型先从候选中挑出与问题相关的子智能体，路由结果与**挑选理由**随 SSE 下发并随消息持久化；可开**委派收窄检索范围**（`agent.dispatchNarrowScope`，默认关）：路由挑出子集后主检索收窄到「主智能体库∪被选中助手库」；**结果聚合策略可配**（`agent.aggregateMode`）：`concat` 按分支直拼 / `rerank` 命中按重排分降序合并（高分块优先进上下文预算）/ `supervisor` 监督者二次聚合（LLM 去重合并、按价值排序、结论矛盾显式标注「⚠ 冲突」，失败回退直拼）；要点段字符预算（`agent.digestMaxChars`）防多分支要点挤占上下文；失败分支显式占位（`agent.aggregateMarkFailed`）让模型可声明"该方面资料不足"；智能体页提供**委派编排视图**（主→子 SVG 拓扑图：悬停高亮委派链路、悬空引用与孤儿子智能体警示、点击节点进配置）；对话编排卡片实时展示各分支状态、任务描述、要点、耗时占比条与路由理由
- **沙盒隔离执行**（`tool.sandbox.enabled`，默认关）：模型经 Function Calling 在**隔离 Linux 容器**内获得 6 个工具——`execute`（shell 命令，**输出实时流式回传**：SSE `tool_output` 逐行增量，长命令不用等跑完才见结果）/ `read_file` / `write_file`（创建语义，改已有文件用 edit_file）/ `edit_file`（精确串替换）/ `ls` / `deliver_artifact`（沙盒文件交付为「我的产物」，扩展名白名单放宽到 py/png/xlsx/zip 等，单个 ≤1MB，路径限定用户数据根）；**scope 挂会话**（同一会话文件跨轮保留，按用户隔离工作目录），会话空闲（默认 60 分钟）由定时任务回收容器；数据根 `user-data/shared/{uid}/workspace` 持久化，技能目录 `/home/gem/skills` 只读挂载；命令超时/输出上限/keepalive/删除超时均可在设置页「沙盒」面板调整；**provisioner 容器化部署**（见启动方式第 1 步），Java 客户端强制 HTTP/1.1（uvicorn 拒绝 h2c 升级且会丢 body）；**沙盒运行时依赖必须烧进镜像**（容器内 gem 用户无 sudo，装不了 JDK/Node 等——用 `sandbox-image/Dockerfile` 派生镜像 + `SANDBOX_IMAGE` 指定）；**右栏「沙盒」卡**可浏览/下载会话沙盒工作区文件（`/sandbox/state|tree|download` 只读端点，只 discover 不创建容器）；**工具瞬时故障自动重试**（失败 500ms 重试一次，`tool_status.attempts` 透出尝试次数）
- **定时执行智能体**（`/agents` → 定时任务 Tab）：智能体按 cron（6 段）或固定间隔自动执行，执行历史与结果会话留痕；调度中心先推进 `next_run_at` 再执行（单实例防重复）、同任务串行（未完成记 `skipped`）
- **可视化工作流**（智能体工作台 → 工作流 Tab）：DSL（JSON）是**唯一真源**——节点/边/变量引用/画布坐标全在其中，画布只是编辑器、引擎只认 DSL；`StateGraph` 执行底座复用（与多智能体编排同源）；结构校验器 fail-loud（11 类节点注册表、回跳规则（仅 loop 节点可发起回跳）/悬空/分支键对账/变量引用检查）；**11 类节点全量可执行**：开始 / 结束 / LLM（模型引用过判权，`modelRef` 留空走个人默认）/ 知识库检索（kbIds 库界）/ 条件分支（SpEL 安全求值，禁 `T()`/构造器，`else` 兜底）/ **HTTP 请求**（SSRF 逐跳内网校验 fail-loud、2MB 读取上限）/ **代码执行**（Python/Node 走 Docker 沙盒，非 0 退出码 fail-loud，`tool.sandbox.enabled` 门）/ **子智能体**（复用完整问答管线、可见性按调用者、临时会话跑完即删）/ **人工审核**（挂起 waiting_approval → 审批卡裁决 → 按快照续跑，已完成节点短路回放零重复消耗；approve/reject 双分支；超时回收落 timeout）/ **循环**（回跳 + maxLoops 编译期/运行期双闸）/ **模板转换**（多路输出聚合）；变量引用 `{{nodeId.key}}` 统一寻址；**画布编辑器**：左侧节点面板拖拽/点击添加（新节点瀑布排布）、右键快捷菜单（节点：打开属性/复制/删除；连线：删除；画布：添加节点（二级飞出全类型）/粘贴/全选/适应视图与缩放，Del 键删除同步脏标记）、连线校验（禁自环、start 无入边、end 无出边、非 loop 禁回跳、条件类出边弹窗选已声明分支）、右侧属性抽屉按类型渲染表单、新建画布预置 start→end 骨架；**画布内调试运行**：入参表单 → 同步 run → **node_traces 逐节点回放染色**（成功绿/失败红/待审核橙 + 耗时角标）→ 点节点看输入输出/耗时/token，运行历史可整体回放任一次 run；**每次运行锁定当时 DSL 快照**（改画布不影响历史回放）；运行期最大步数闸（`workflow.maxSteps`）；归属 M0~M3 仅创建者可见（M4 接发布语义/智能体绑定/API 触发）
- **工具生态**：内置工具（计算器——递归下降自实现表达式求值，仅 `+ - * / % ^` 与括号，**不执行任意代码**；日期）、知识检索工具（命中块注册进引用流）、产物交付工具（`present_artifacts` 落盘 `data/artifacts/{uid}/{yyyyMM}/`，扩展名白名单 + 文件名净化，SSE 下发卡片）、技能读取工具（渐进披露读 `SKILL.md`）；开关集中在 `tool.*`，**总开关与子开关默认均为 false**
- **技能包（Skills）**：目录 + `SKILL.md` 形式的可插拔能力（内置目录 + 用户目录），支持新建 / 从 URL 安装（远程域白名单 `skill.remoteAllowedHosts`）/ 启停用 / 删除；可注入 System Prompt 或由模型按需读取；**按用户隔离**（个人资产），沙盒内以只读投影暴露给容器
- **MCP 双向**：
  - **Client（接进来）**：接入任意 MCP Server（streamable/sse），连接状态、整体重连、临时连通性探测；按用户隔离连接池
  - **Server（送出去）**：智能体发布时可开启 **MCP 端点 `/ai/mcp/{token}`**（Streamable HTTP，无状态），
    Claude Desktop / Cursor / 其他 Agent 粘贴地址即可调用该智能体——token 即凭据（与网页分享同源，
    停用/撤销立即失效），按发布者身份检索、能力收窄同游客模式（沙盒/产物/个人技能与个人 MCP 不暴露）；
    总开关 `mcp.server.enabled` 默认关
  - **平台级入口 `/ai/mcp`**：一个地址暴露整套能力（检索知识库 / 提问 / 列可见知识库 / 列可用智能体），
    凭据是请求头的 API Key（需在 API Key 管理中给该 Key 打开「MCP」开关），身份 = Key 的创建者，
    可见范围与他在网页上看到的完全一致；与 per-agent 端点共存互不干扰
- **知识库多库管理**：库级解析参数/检索参数（**稀疏覆盖**：留空跟随全局默认）；**检索可见性两级判定**（文档自身 + 所属库共享范围，私有库不会被他人检索到）
- **RBAC 权限**：角色-菜单（侧边栏入口）+ 角色-API（端点白名单）两级绑定；管理员判定 `admin`/`superadmin` 或角色 `admin_flag`；普通用户仅开放问答链路，管理端点 403 fail-closed；前端管理页路由带守卫
- **OIDC 单点登录**（可选）：标准授权码流程，自动建档（不设本地密码），绑定 `c_ai_user.oidc_sub` 唯一索引；配置在设置页「单点登录（OIDC）」面板，分离部署必填 `oidc.frontendBaseUrl`
- **对外 API Key**：只存 SHA-256 哈希 + 前 8 位前缀（明文仅签发时返回一次），权限固定为问答链路——**即便持有 Key 也访问不了管理端点**（仍 403）
- **@ 引用（atRef）**：问答输入框可 @ 指定文档，被 @ 文档内最相关块前置注入上下文
- **深度思考（生产级）**：思考流式展示（model/prompt 双模式）→ `<search>` 检索计划 → 多路并行检索 → 思考链注入最终回答 + 思考关键词增强检索 + 失败细化降级 + 思考长度护栏 + 前端自动折叠 + 自动路由（默认关）
- **用户长期记忆**（`c_ai_user_memory`，`memory.enabled` 默认开）：问答完成后异步用对话模型提炼"值得长期记住的用户信息"（事实/偏好/项目背景），落库并按用户隔离；后续问答按预算注入（`memory.injectBudgetChars`、`maxInjectCount`，语义相关优先 `useSemanticInject`、去重阈值 `dedupThreshold`，上限 `maxPerUser`）；`/profile` 个人设置页可查/改/删（含来源会话溯源与被注入次数）；公开分享页与 MCP 游客链路不携带个人记忆
- **过程独白与消息时间线**：工具模式下模型的推理/试错独白包在 `<process>` 标签内，后端整块剥离分流（`c_ai_message.process_text` 落库 + SSE `process` 事件），前端原位灰字展示、不混入回答正文；整轮"文本段 + 工具段 + 产物段"编为**消息时间线**（`c_ai_message.timeline`，文本只存区间下标、工具/产物只存引用下标）随消息落库并在 `done`/历史接口下发——刷新后仍是交错的实时过程视图；任何"只有实时才有的顺序信息"都必须落库，不靠前端重组
- **网页源定时刷新**（`web.refreshEnabled` 默认开）：URL 导入的文档可逐篇开启自动刷新（文档级 cron），调度任务扫描到期文档重抓网页、同名替换重建（复用导入全套链路，`next_refresh_at` 推进保证单实例不重复触发；失败 fail-loud 不中断其他文档）
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元/关键词/向量/合并/重排/最终结果，前端问答页可视化排查
- **检索评估**：从历史问答引用回放生成评估集 → 批量参数组对比 **recall@k / MRR / 命中率**；一键体检、预设参数组、一键应用（写入线上配置）、自动结论；期望块存活校验防"内容漂移误报下滑"

## API 一览

> 完整接口文档见 **Swagger UI**（启动后访问 `/ai/swagger-ui/index.html`，随代码自动更新）。代码共 **28 个控制器、190+ 端点**，下表为核心端点速查（完整契约以 Swagger 为准）：

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（`token`/`stage`/`plan`/`image`/`retrieved`/`thinking`/`thinking_done`/`process`/`tool_status`/`tool_output`/`subagent`/`subagent_route`/`agent_dispatched`/`agent_bound`/`approval_required`/`artifact`/`warn`/`done`/`error` 事件，见下方 SSE 事件表） |
| `POST /api/ai/tool-approval/{approvalId}` | 工具执行确认（人在回路）：批准/拒绝有副作用的沙盒/MCP 工具调用 |
| `GET /api/ai/auth/me` | 当前身份、权限与菜单树（前端据此渲染侧边栏与管理入口） |
| `POST /api/ai/auth/login`、`/api/ai/auth/oidc/{config,login-url,callback,exchange-code}` | 本地登录 / OIDC 单点登录四端点 |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}`、`PUT /api/ai/session/{id}/rename` | 会话列表（搜索）/ 新建 / 历史恢复 / 重命名 |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏 |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions`、`POST /api/ai/sessions/batch-delete` | 删除单会话 / 清空 / 批量删除 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /api/ai/message-group/undo` | 按消息组删除问答 / 撤销删除 |
| `GET /api/ai/suggested`、`POST /api/ai/suggested` | 推荐问题池读取（普通用户可访问）/ 追加（管理员） |
| `GET /api/ai/kb/list`、`POST /api/ai/kb`、`PUT /api/ai/kb/{id}`、`DELETE /api/ai/kb/{id}` | 知识库 CRUD（含库级解析/检索参数稀疏覆盖） |
| `GET /api/ai/kb/param-defaults` | 全局解析/检索参数模板（普通用户可读，新建库预填用） |
| `POST /api/ai/document/upload`、`/upload/batch` | 上传文档（异步解析） |
| `POST /api/ai/document/import-url` | 网页 URL 导入（抓取 HTML 快照，file_type=url，异步解析；支持逐篇定时自动刷新配置） |
| `GET /api/ai/document/list`、`DELETE /{id}`、`PUT /{id}/status`、`POST /{id}/reparse` | 文档列表 / 删除 / 启停用 / 重解析 |
| `GET /api/ai/document/{id}/versions`、`POST /{id}/rollback` | 版本历史 / 回滚 |
| `GET /api/ai/knowledge/{id}`、`GET /api/ai/knowledge/list?docId=`、`PUT /api/ai/knowledge/{id}` | 知识块详情 / 预览 / 编辑（重向量化） |
| `GET /api/ai/knowledge/unmatched`、`POST /api/ai/knowledge` | 无命中问题 / 手动创建知识块（自动向量化） |
| `POST /api/ai/feedback`、`GET /api/ai/analytics/summary`、`GET /api/ai/analytics/badcases` | 反馈 / 看板聚合 / 差评坏例 |
| `GET/PUT /api/ai/config`、`GET /api/ai/config/schema`、`POST /api/ai/config/reset` | 配置读写（脱敏）/ 设置项 schema 下发 / 恢复分组默认 |
| `POST /api/ai/config/probe`、`GET /api/ai/config/rerank/check`、`GET /api/ai/config/keyword/check` | 连通性探测（chat/vision/embedding/rerank/keyword）/ 重排 / Meilisearch |
| `GET /api/ai/config/embedding/reindex` | 全量重嵌入任务状态（含索引对账） |
| `GET /api/ai/provider/*` | 模型供应商：登记（平台级/个人级）、启停、判权（`ModelRegistryService.assertUsable`） |
| `GET /api/ai/agent/list`、`GET /api/ai/agent/available`、`POST /api/ai/agent`、`PUT /api/ai/agent/{id}`、`DELETE /api/ai/agent/{id}`、`POST /api/ai/agent/{id}/default` | 智能体 CRUD / 对话页精简列表（普通用户可访问）/ 设默认 |
| `GET/POST /api/ai/agent/{id}/publish`、`DELETE /api/ai/agent/{id}/publish` | 公开分享发布/查询/撤销（生成 token，免登录对话入口） |
| `GET /api/ai/share/{token}/info`、`GET /api/ai/share/{token}/history`、`POST /api/ai/share/{token}/chat` | 游客通道（免登录）：分享信息 / 游客会话历史 / 流式对话（IP 限频、工具白名单收窄） |
| `GET /api/ai/skill/list`、`GET /api/ai/skill/detail`、`POST /api/ai/skill`、`POST /api/ai/skill/install`、`PUT /api/ai/skill/{name}/disabled`、`DELETE /api/ai/skill/{name}` | 技能包：列表 / 详情 / 新建 / URL 安装 / 启停 / 删除 |
| `GET /api/ai/mcp/status`、`POST /api/ai/mcp/reload`、`POST /api/ai/mcp/probe` | MCP Client：状态 / 重连 / 临时探测 |
| `POST /ai/mcp/{token}`（`GET` 同路径；**不在 `/api` 之下**，token 即凭据） | MCP Server·per-agent 端点：一个智能体一个工具 |
| `POST /ai/mcp`（`GET` 同路径；凭据走 `Authorization: Bearer sk-…` 或 `X-Api-Key`） | MCP Server·平台级入口：固定元工具集（检索 / 提问 / 列库 / 列智能体） |
| `PUT /api/ai/api-key/{id}/mcp` | 授权某把 API Key 访问平台级 MCP 入口 |
| `GET /api/ai/scheduled/*`、`POST /api/ai/scheduled/{id}/trigger` | 定时任务：列表 / 新建 / 编辑 / 立即触发（含执行历史） |
| `GET/POST /api/ai/workflow`、`GET/PUT/DELETE /api/ai/workflow/{id}`、`POST /api/ai/workflow/validate` | 工作流定义：CRUD / DSL 校验 + 编译 dry-run（errors 逐条 + compiled） |
| `POST /api/ai/workflow/{id}/run`、`GET /api/ai/workflow/{id}/run/list`、`GET /api/ai/workflow/{id}/run/{runId}` | 调试运行（同步，body `{"inputs":{…}}`，失败返回 failed 不抛 500）/ 运行列表（近 50 条）/ 单次 trace 详情（节点级输入输出/耗时/token） |
| `GET /api/ai/artifacts`、`DELETE /api/ai/artifacts/{id}` | 产物列表 / 删除（下载走签名 URL） |
| `GET /api/ai/api-key/list`、`POST /api/ai/api-key`、`PUT /api/ai/api-key/{id}/disabled` | 对外 API Key：列表 / 签发（明文仅一次）/ 吊销 |
| `GET /api/ai/user/list`、`GET /api/ai/role/*`、`GET /api/ai/menu/*` | 成员 / 角色 / 菜单（RBAC 维护） |
| `GET/POST /api/ai/memory`、`PUT/DELETE /api/ai/memory/{id}` | 用户长期记忆：列表（含来源与被注入次数）/ 手动添加 / 编辑 / 删除（仅本人） |
| `GET /api/ai/search-index/stats`、`POST /api/ai/search-index/reindex`、`DELETE /api/ai/search-index` | 关键词索引运维 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试 |
| `POST /api/ai/eval/generate`、`GET /api/ai/eval/set`、`POST /api/ai/eval/run`、`POST /api/ai/eval/case`、`POST /api/ai/eval/run-auto` | 检索量化评估全家桶 |

> 上表为速查，**并非全量**：完整契约以 Swagger UI 为准。

### SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 | 载荷 |
|------|------|------|
| `token` | 流式生成逐片（语义缓存命中时一次性整段） | 回答文本增量 |
| `stage` | 问答阶段推进 | 阶段标识（前端显示进度提示） |
| `plan` | 轮次开始，按当前配置确定会跑的步骤 | 执行计划步骤名数组，如 `["理解问题","深度思考","检索知识库","生成回答"]`（仅实时下发，不随消息持久化；模型临时决定的工具调用不在计划内） |
| `image` | 生成之前 | 命中图片 URL 列表（按编号顺序，生产为 HMAC 签名 URL） |
| `retrieved` | 检索 + 重排 + 上下文填充完成 | 检索概览 `{keywords, refs, terms}`（随消息持久化） |
| `thinking` | 深度思考开启时 | 思考链增量 |
| `thinking_done` | 深度思考结束（正常完成或截断降级） | `{status: ok\|degraded, thinking: 完整思考链}` |
| `process` | 工具模式下模型推理/试错独白（`<process>` 标签内，与正文分流） | 过程独白增量（前端原位灰字展示；全文随 `done.processText` 与 `process_text` 列持久化） |
| `tool_status` | Agent 调用工具 | 工具名 / 状态（done\|error）/ 结果或错误（含尝试次数 `attempts`） |
| `tool_output` | 沙盒 `execute` 命令执行中 | 命令输出逐行增量（实时流式；长命令边跑边看） |
| `subagent` | 子智能体并行执行推进 | 分支状态 `{id,name,status,hits,elapsedMs,delegated,description,digest}` |
| `subagent_route` | 按需委派路由判定完成 | `{candidates,picked,names,reasons}`——reasons 为各被选助手的「挑选理由」（可解释性；随消息持久化，历史回显） |
| `agent_dispatched` | 会话首问「自动派遣」选定智能体 | `{candidates,id,name,description,fallback}`（fallback=true 表示路由失败回落默认智能体） |
| `agent_bound` | 会话首问智能体绑定完成（就地广播，不等 done） | `{locked:true, agentId, agentName}`（agentId 空串=已绑定为「不使用智能体」） |
| `approval_required` | 有副作用工具（沙盒/MCP）执行前等待确认 | `{approvalId,tool,args,timeoutMs}`（批准/拒绝经 `POST /api/ai/tool-approval/{id}`） |
| `artifact` | 产物交付工具落盘 | 产物卡片字段 `{id,url,filename,ext,size,description}` |
| `warn` | 非致命降级/告警 | 提示文案 |
| `done` | 回答完成 | `sources`、`related`、`messageId`、`thinking`、`finalContent`/`finalImages`（校验修正后全文/图片）、`processText`（过程独白全文，前端须先于时间线恢复赋值）、`timeline`（段数组：文本区间段/工具下标段/产物下标段——刷新后重建交错过程视图的数据源）、`degradations`、`artifacts`、`toolCalls`、`tokens`（用量）、`subagentBranches`/`subagentRoute`（编排终态收敛）；缓存命中仅 `finalContent`/`finalImages` |
| `error` | 处理或下发异常 | 错误文案 |

> **思考结束没有独立事件**：完整思考链在 `done.thinking` 一次性给出。

## 多副本部署要求

支持多实例水平扩展，需满足以下约束（均已代码化治理）：

1. **数据目录必须共享**：`AI_IMAGES_DIR` 指向所有实例都能访问的同一存储。docker-compose 用命名卷仅**同主机**共享；跨主机需挂 NFS/对象存储等共享卷
2. **静态配置一致**：各副本的 yml/环境变量（数据库、Redis、`AI_JWT_SECRET`、`AI_MEILI_KEY` 等**仅存 env/yml 的项**）必须一致（**`AI_JWT_SECRET` 尤其必须一致**）；动态配置（`c_ai_config`，含模型与行为参数）经 **Redis pub/sub** 广播即时生效，订阅断线由 **5 分钟兜底轮询**补齐
3. **并发防护**：重解析用 **DB 状态机 CAS**；解析队列有界，超限拒绝/降级不失控
4. **删除中断语义**：删除在任意实例生效，其他实例上的解析由 DB 兜底在检查点秒级停止清理
5. **总并发核算**：解析并发为"副本数 × parse.concurrency"，embedding/Ollama 为共享瓶颈
6. **语义缓存一致性（无需手工同步）**：命中时校验 DB 行存在兜底，任一实例失效后其它实例立即感知
7. **向量模型热切换为全实例串行**：Redis 分布式锁互斥，锁持有期间所有实例向量检索自动降级关键词路；任务每批续期锁，实例崩溃后锁 TTL 自愈
8. **启动对账/巡检开关需收敛为单点**：`keyword.reconcileOnStartup` 与 `parse.recoverStuckOnStartup` 多副本上线前请置 false
9. **RSA 密钥文件必须共享**：默认在 `{AI_IMAGES_DIR}/secret/config-rsa.key`，可用 `AI_CONFIG_RSA_KEY` 覆盖路径——否则 A 实例加密的 key 在 B 实例无法解密

## 与其它平台集成

生产环境由平台网关做 JWT 鉴权并透传请求（前端调 `/api/ai/*`，见 `web/.env.production`）。
**注意**：
1. 平台网关需额外透传图片路径 `/ai/images/**`
2. SSE 接口（`/chat`）网关需关闭响应缓冲
3. **登录态透传**：用户身份只认 `Authorization: Bearer <JWT>`，**反向代理必须原样转发该头**；会话严格按登录用户隔离
4. **不接受自报身份**：不读取任何客户端自报的用户标识请求头（`X-User-Id` 已废弃移除）
5. **接口限流**：问答/上传按"用户（无身份则按 IP）"Redis 固定窗口限频，Redis 不可用自动放行
6. **权限模型（用户问答 / 管理员运维）**：普通用户仅开放问答链路；其余端点仅管理员可访问（403 fail-closed）。管理员判定：登录用户角色为 `admin`/`superadmin`，或 `c_ai_role.admin_flag=1`
7. **普通用户 UI 收敛**：`/agents`、`/members`、`/permissions`、`/knowledge`、`/dashboard`、`/evaluation`、`/settings` 等管理路由带守卫，非管理员侧边栏不展示、直达 URL 自动跳回对话页

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
docker compose up meilisearch          # 需先设置 AI_MEILI_KEY
curl http://localhost:7700/health       # 期望 {"status":"available"}

# 6. 沙盒（隔离执行环境）
bash deploy/sandbox-provisioner/run.sh docker-up
bash deploy/sandbox-provisioner/run.sh status
# 设置页开启「工具调用 → 沙盒执行工具」+ 配好「沙盒」面板后，对话中即可让模型执行 shell/读写文件
```

**端到端手动验证**（建议每次改动后走一遍）：
1. 知识库上传含图片 docx → 解析进度 → 问答引用 `[N]` 溯源与图片展示
2. 智能体对话：开启工具后让模型执行计算/生成产物卡片 → 定时任务触发一次执行看历史
3. 沙盒：对话中要求"用 Python 生成数据并统计" → 观察工具状态行与 `execute` 实时输出流 → 产物卡片 → `/artifacts` 可下载
4. 设置页修改检索参数 → 检索调试对比前后召回差异 → 检索评估页验证指标

## 产品化特性

- **安全**：关键密钥零默认值（`DB_PASSWORD` fail-fast）、JWT 签名校验（HS256）、PBKDF2 密码哈希 + 失败锁定、模型 API Key **RSA 加密入库 + 掩码回显**、对外 API Key 只存哈希、图片 HMAC 签名 URL、统一异常处理、上传魔数校验、接口限流、**计算器不执行任意代码**、产物文件名白名单净化、管理操作 `[AUDIT]` 审计、OIDC 绑定唯一索引防账号漂移
- **可靠性**：上传失败自动补偿清理、解析异步化、**解析中删除立即中断**、SSE 停止生成、查询改写线程池隔离、沙盒空闲回收 + keepalive + 删除超时护栏
- **可配置**：**设置项 schema 唯一定义源**（`config-schema.json` 启动加载，新增配置项零前端改动即出现在设置页）；三类模型跨厂商热切换 + 危险配置前置护栏（探测失败拒绝保存）；检索/重排/解析/问答/沙盒/记忆/定时任务等 16 组 130+ 行为参数收口配置化
- **可观测性**：健康检查、日志级别环境变量化、检索调试 API、Swagger UI、降级 fail-loud 日志（`[FAIL-LOUD]`）、**消息时间线随消息落库**（刷新后过程视图可完整回放，实时顺序不丢失）
- **多用户与部署**：本地登录 + OIDC 单点登录、会话按用户隔离、RBAC 菜单+端点两级管控、multi-stage Dockerfile（非 root + HEALTHCHECK）、docker-compose（redis-stack + meilisearch + 内置 MySQL）、nginx 参考配置

## 后台定时任务

全部周期任务集中在 `ScheduleCenter`（**无 `@Scheduled`**）：单 daemon 线程按 **10s 节拍**轮询"是否到点"，间隔每次实时读配置（改配置即时生效、≤0 暂停），任务体提交线程池执行，上一轮未结束则跳过（防重叠），失败仅告警并下轮重试。

| 任务 | 间隔键（默认） | 做什么 |
|------|----------------|--------|
| 关键词索引精确对账 | `keyword.reconcileIntervalMs`(1h) | 双向比对 MySQL 与 Meilisearch，定向修复漂移 |
| 聊天图片目录清理 | `images.chatCleanupIntervalMs`(1d) | 删除超过保留期的用户聊天图片 |
| 检索评估自动体检 | `eval.autoIntervalMs`(1d) | 按线上参数跑评估集并与上期对比，下滑预警 |
| 配置缓存兜底刷新 | 固定 5 分钟 | 补齐 Redis 订阅断线期间错过的配置变更 |
| 过期会话/消息清理 | `cleanup.sessionCleanupIntervalMs`(1d) | 物理删除超过 `sessionRetentionDays`（30 天）的会话与消息 |
| 产物超期清理 | `artifact.cleanupIntervalMs`(1d) | 删除超过 `artifact.retentionDays`（90 天，≤0 不清理）的产物文件与记录 |
| 定时智能体任务 | `scheduled.scanIntervalMs`(30s) | 扫描到期的用户定时任务并投递线程池执行（扫描与执行分离，长任务不拖节拍） |
| 网页源定时刷新 | `web.refreshScanIntervalMs`(60s) | 扫描到期且开启自动刷新的 url 文档，重抓网页 + 同名替换重建 |
| 沙盒空闲回收 | `sandbox.cleanupIntervalMs`(10min) | 回收空闲超过 `sandbox.idleReleaseMinutes`（60 分钟）的会话沙盒容器（0=不回收） |

## 配置说明

关键配置项（`application.yml`，完整默认值见 `AiAppProperties.java`）：

```yaml
ai-app:
  chunk:
    max-size: 800
    overlap: 100                           # 分块重叠（只进向量化文本，不入库）
    structural: true                       # 结构感知切分（docx，需重解析生效）
    heading-depth: 4                       # 章节标题识别上限层级 1~6（需重解析生效）
  retrieval:
    vector-weight: 0.6                     # 混合检索：向量权重
    keyword-weight: 0.4                    # 混合检索：关键词权重
    rerank:                                # 重排（OpenAI 兼容 /v1/rerank）
      enabled: ${AI_RERANK_ENABLED:false}
      base-url: ${AI_RERANK_BASE_URL:http://localhost:7997}
      model: ${AI_RERANK_MODEL:BAAI/bge-reranker-v2-m3}
  keyword:                                 # 关键词召回引擎（mysql|meilisearch）
    engine: ${AI_KEYWORD_ENGINE:mysql}
    base-url: ${AI_MEILI_BASE_URL:http://localhost:7700}
    api-key: ${AI_MEILI_KEY:}
  ratelimit:                               # 接口限流（Redis 固定窗口）
    enabled: ${AI_RATELIMIT_ENABLED:true}
  context:                                 # 上下文与长度控制（设置页可调）
    model-windows: "qwen-plus=131072,qwen3=131072,qwen-max=32768,deepseek=65536,default=32768"
    cost-cap-tokens: 8000
    max-output-tokens: 2000
    max-context-hits: 8
  images:
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（源文件/图片/产物/密钥都在此）
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:true}
  vision:                                  # 视觉模型（本地 Ollama）
    model: ${AI_VISION_MODEL:qwen3-vl:2b}
    base-url: ${AI_VISION_BASE_URL:http://localhost:11434}
    concurrency: 2
    num-ctx: 16384                         # 1280px 图视觉 token 1600-2500，默认 4096 会截断
  deep-reasoning:                          # 深度思考
    enabled: ${AI_DEEP_REASONING_ENABLED:true}
    thinking-mode: ${AI_DEEP_REASONING_MODE:model}
  auth:
    jwt-secret: ${AI_JWT_SECRET:}          # 留空自动生成（重启后令牌失效）；多副本必须一致
    require-login: ${AI_AUTH_REQUIRE_LOGIN:true}
  schema-auto-index: true                  # SchemaMigrator 启动时自动补索引

spring:
  servlet.multipart: { max-file-size: 1024MB }
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }
  ai.openai:
    api-key: ${AI_CHAT_KEY}
    chat: { options: { model: qwen3.8-27b } }
  ai.vectorstore.redis: { initialize-schema: true, index-name: ai-doc-index, prefix: "ai:chunk:" }
```

> **模型配置以 DB 为准**：`spring.ai.openai.*` 仅作 `c_ai_config` 未配置时的**回退默认值**；设置页保存后一律以 DB 为准。

### 仅存 DB 的配置键（`c_ai_config`）

大量参数只存在于 `c_ai_config`（由 `config-schema.json` 定义、`ConfigService.defaults()` 灌默认值），**不写 yml 也能用，保存即生效**。按前缀列主要项（括号内为默认值）：

| 前缀 | 主要键（默认值） | 生效方式 |
|------|------------------|----------|
| `chat.*` | `temperature`、`systemPrompt`、`baseUrl`/`apiKey`/`completionsPath`（遗留回落网关）、`historyRounds`(5)、`pipelineThreads`(8)、`suggestedQuestions` | 聊天模型走供应商/个人默认（`chat.model` 全局兜底已退役）；网关回落项保存即生效 |
| `embedding.*` | `baseUrl`/`apiKey`/`embeddingsPath`（遗留回落网关）、`dimensions`(系统回写，只读) | 向量模型绑定知识库 `embedding_ref`（`embedding.model` 已退役）；重嵌入先探测维度，通过才 DROP 重建 |
| `retrieval.*` | `vecThreshold`(0.3)、`vectorTopK`(15)、`keywordLimit`(20)、`fusionMode`(sum)、`refExpand*`（关联扩散 9 项） | 多数保存即生效；引用识别开关**需重解析** |
| `chunk.*` / `parse.*` | `maxSize`(800)、`structural`(true)、`concurrency`(2)、`ocrDpi`(200) | **需重解析/对后续解析生效** |
| `vision.*` | `prompt`、`concurrency`(2)、`descCacheVersion`(1)、`numCtx`(16384) | 保存即生效（`timeoutMillis` 需重启） |
| `context.*` / `atRef.*` / `rerank.*` / `semanticCache.*` | 去冗余阈值、@引用上限、重排区间、缓存阈值(0.96) | 保存即生效 |
| `keyword.*` / `eval.*` / `cleanup.*` / `images.*` / `upload.*` | 引擎兜底冷却、体检参数、会话保留期(30 天)、图片鉴权、上传上限(200MB) | 保存即生效 |
| `tool.*` | `enabled`(**false**)、`knowledgeRetrieval.enabled`、`artifact.enabled`、`builtin.enabled`、**`sandbox.enabled`** | 保存即生效——**默认全关，需显式开启** |
| `skill.*` | `enabled`、`dir`(./data/skills)、`injectEnabled`(true)、`remoteAllowedHosts`(精确 host 白名单) | 保存即生效 |
| `agent.*` | `enabled`(**false**)、`subAgents`(2)、`topKPerAgent`(3)、`digestEnabled`、`aggregateMode`(concat/rerank/supervisor)、`digestMaxChars`(1500)、`aggregateMarkFailed`、`autoRoute`(true)、`dispatchNarrowScope`(**false**)、`autoDispatch`(true)、`routeTimeoutMs`(8000)、`maxToolSteps`(15) | 保存即生效；MCP **客户端**无全局配置（每人自己的 `c_ai_user_mcp`） |
| `memory.*` | `enabled`(true)、`maxPerUser`(50)、`maxInjectCount`(30)、`injectBudgetChars`(1500)、`dedupThreshold`(0.9)、`useSemanticInject`(true) | 用户长期记忆：保存即生效（提取在问答完成后异步执行） |
| `artifact.*` | `retentionDays`(90，≤0 不清理)、`cleanupIntervalMs`(1d) | 产物超期清理 |
| `web.*` | `refreshEnabled`(true)、`refreshScanIntervalMs`(60s) | 网页源定时刷新总开关与扫描间隔 |
| `scheduled.*` | `enabled`(true)、`maxPerUser`(20)、`scanIntervalMs`(30s)、`timeoutMs`(300000) | 定时执行智能体 |
| `workflow.*` | `maxSteps`(50)、`subagentTimeoutMs`(180000) | 工作流单次运行最大图步数（StateGraph recursionLimit，超限 fail-loud）；子智能体节点回答等待超时 |
| `mcp.server.*` | `enabled`(**false**)、`timeoutMs`(180000)、`allowedOrigins`("") | MCP **服务端**：对外提供 `/ai/mcp/{token}` 端点；`allowedOrigins` 为空时仅允许本机回环（Origin 校验防 DNS rebinding） |
| `sandbox.*` | `provisionerUrl`(127.0.0.1:8002)、`token`(敏感，RSA 入库)、`virtualPathPrefix`(/home/gem/user-data)、`commandTimeoutSeconds`(180)、`maxOutputBytes`(262144)、`keepaliveIntervalSeconds`(30)、`idleReleaseMinutes`(60)、`cleanupIntervalMs`(600000) | 保存即生效（client 懒构建）；token 与 provisioner 侧 `SANDBOX_PROVISIONER_TOKEN` 一致且 ≥32 字符 |
| `oidc.*` | OIDC 面板 20 项（issuer/clientId/clientSecret(敏感)/scopes/frontendBaseUrl 等） | 保存即生效（分离部署必填 `frontendBaseUrl`） |
| 用户/角色 | `default_model`/`default_vision_model`（个人默认模型）、角色-菜单/角色-API 绑定 | 即时生效 |

> **敏感项按后缀判定**（`.apiKey`/`.clientSecret`/`.token`）：RSA 加密入库 + 页面仅回显 `****后4位` + 恢复默认跳过。

## 已知注意事项

- **智能体 / 工具 / 技能 / 沙盒默认全关**：`agent.enabled`、`tool.*`（含沙盒）、`skill.enabled` 默认均为 false，需在设置页显式开启；MCP 客户端无全局开关（每个用户在「MCP 外部工具」登记自己的 Server）；MCP **服务端** `mcp.server.enabled` 默认关（对外暴露能力需显式开启）
- **智能体会话级绑定**：会话首问锁定智能体后全程不变，切换智能体须新建会话；存量会话 `agent_id` 为 NULL（未绑定）→ 下次提问时按当轮选择锁定，属预期迁移行为；绑定智能体被删/无权时 fail-loud 提示，不静默换人
- **需重解析才生效的配置**：`chunk.maxSize` / `headingDepth` / `structural`、`retrieval.refDetectEnabled` 等；`refExpand*` 扩散类参数保存即生效
- **`vision.timeoutMillis` 需重启**（视觉客户端启动时构建，其余 `vision.*` 保存即生效）
- **`.nvmrc` 固定 Node 18.19.0**（Node ≥18 均可构建）
- **MaaS 网关模型格式**：部分模型返回 DashScope 原生格式（`{"text":...}`）Spring AI 无法解析（0 token 无回答）；需用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- **切换向量模型必然触发全量重嵌入**：耗时与知识块数成正比（万级块可达数十分钟），期间向量检索降级关键词路、语义缓存清空；**避免业务高峰切换**；重嵌入与并发解析撞车可能丢块，任务末尾索引对账会告警，解析空闲时手动补跑一次即可
- **base-url 不含 `/v1`**：Spring AI 与 VisionService 都会自动补
- **图片访问路径**：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时已去 `/ai` 前缀
- **存量库升级**：`SchemaMigrator` 启动自动补列补索引（幂等）；JSON 列需库侧表达式默认值
- **沙盒 token**：设置页「沙盒 → 访问令牌」须与 provisioner 侧 `SANDBOX_PROVISIONER_TOKEN` 一致且 ≥32 字符（不足 fail-closed 拒绝启用）；provisioner 容器需先启动，token 校验推迟到首次使用（未启用沙盒的部署不因缺 token 起不来）
- **过期会话/消息自动清理**：默认每日清除逻辑删除超过 30 天的会话与消息（保留期即"撤销删除"窗口）
- **沙盒 write_file 为创建语义**：文件已存在报错，修改用 `edit_file` 或 shell 追加
- **沙盒运行时依赖必须烧进镜像**：容器内 gem 用户不在 sudoers，装不了 JDK/Node/ffmpeg 等——用 `deploy/sandbox-provisioner/sandbox-image/Dockerfile` 派生镜像（如 +openjdk-17），`.env` 设 `SANDBOX_IMAGE` 后重建 provisioner；旧沙盒容器直接删（get_or_create 会按需重建，工作区在宿主 bind mount 不丢）
