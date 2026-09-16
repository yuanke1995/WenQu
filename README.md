# 问渠 WenQu · AI 文档问答助手

> **问渠那得清如许，为有源头活水来。** —— 答案，自有源头。
>
> 中文名：**问渠** ｜ 英文名：**WenQu** ｜ 英文 slogan：*Ask the source.*

独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答。支持 Word/PDF/Excel/TXT/Markdown 文档解析（含扫描 PDF OCR、大文件流式解析）、混合检索 + 查询改写、**知识块关联检索（交叉引用 1-hop 扩散 + 父章节上下文带出）**、价值驱动上下文控制、**语义缓存加速**、回答中位置级展示文档原图（识别用压缩图、展示用原图的双图策略 + 相关性预筛防错配）、解析进度实时展示（含图片识别逐张进度）、引用溯源与**检索状态行（来源可点击弹原文）**、**差评回流闭环**、会话管理（搜索/置顶/收藏/按组删除）、文档版本管理、数据看板与知识缺口闭环、检索量化评估（一键体检 + 预设参数对比 + 一键应用），是面向"操作手册问答"场景的完整智能助手。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8（`spring-ai-bom` 统一版本） |
| ORM | MyBatis-Plus 3.5.12（逻辑删除 `deleted`，驼峰映射） |
| 数据库 | OceanBase（MySQL 协议，库 `ai_doc_assistant`，可按环境调整）；13 张 `c_ai_*` 表由启动自动建立 |
| 向量库 | Redis Stack（RediSearch 向量索引，Jedis 客户端，索引 `ai-doc-index`；连接地址经 `REDIS_HOST`/`REDIS_PORT` 注入，默认 `127.0.0.1:6379`） |
| LLM | 阿里云 MaaS 网关（OpenAI 兼容：chat=`qwen3.8-27b`，embedding=`qwen3.7-text-embedding-flash`，base-url 不含 `/v1`）；图片理解/OCR 可走本地 Ollama `qwen3-vl:2b` |
| 智能体编排 | Spring AI Alibaba Agent Framework 1.1.2.3（`StateGraph` 多子智能体并行编排） |
| MCP | `spring-ai-mcp`（MCP Java SDK 0.18.3）——外部工具服务器接入 |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ 原生流（txt/md/csv）+ jieba-analysis 1.0.2（中文分词） |
| 序列化 / 文档 | fastjson2 2.0.31；springdoc-openapi 2.8.8（Swagger UI，生产默认关） |
| 安全 | RSA 加密落库 API Key（`ConfigCryptoService`）+ SHA-256 哈希签发对外 API Key + 图片 HMAC 签名 URL |
| 前端 | Vue ^3.4 + Vite ^5 + Ant Design Vue ^4.2 + markdown-it/DOMPurify/highlight.js（`.nvmrc` 固定 Node **18.19.0**，Node ≥18 均可） |

## 目录结构

```
WenQu/                               # 项目根（git 仓库名 WenQu；本地目录名可自定义）
├── pom.xml                          # 后端 Maven 项目（com.wisesoft:wenqu，产物 target/wenqu.jar）
├── src/main/java/.../ai/
│   ├── AiApplication.java           # 入口
│   ├── config/                      # AiAppProperties / SecurityConfig(Token+普通用户白名单) / AdminGuard / ImageWebConfig+ImageAuthInterceptor / SchemaMigrator(存量库补列补索引) / DynamicChatClientConfig / OpenApiConfig / GlobalExceptionHandler
│   ├── controller/                  # 14 个控制器 / 85 个端点：
│   │                                #   Chat(SSE+会话+消息组+引用溯源) / Document / Qa(反馈+看板) / Config(模型配置+重嵌入) / AiKnowledge(知识块+缺口回流)
│   │                                #   Agent(智能体) / ApiKey / Mcp / Skill(技能包) / DescCache(图片描述缓存) / AnswerCache / SearchIndex / RetrievalDebug / Evaluation
│   ├── service/                     # 主链路：RagService(问答编排) / HybridRetrievalService(混合检索+扩散汇总) / RerankService / KnowledgeRefService(引用识别+1-hop 扩散)
│   │                                #   KeywordIndexService(mysql|meilisearch 双引擎) / KeywordExtractor(jieba) / VisionService / ImageFilterService / ImageDescCache / UserImageService
│   │                                #   DocumentService(解析+向量化+全量重嵌入编排) / SessionService / QaLogService / ConfigService / RateLimitService / AnswerCacheService / RetrievalEvaluationService
│   │                                # 智能体与工具：SubAgentOrchestrator(StateGraph 并行编排) / ArtifactService(产物交付) / BuiltinTools(计算器·日期) / KnowledgeRetrievalTool / PresentArtifactTool / SkillTools
│   │                                #   AgentService / SkillService / McpClientService / ApiKeyService
│   │                                # 基础设施：DynamicOpenAiChatModel / DynamicEmbeddingModel(配置指纹热切换) / ImageUrlSigner(HMAC) / ConfigCryptoService(RSA) / ConnectivityProbeService / DocumentMetaCache / ScheduleCenter(定时任务) / ThreadPoolManager(线程池)
│   ├── parser/                      # DocumentParser 接口 + DocxParser(结构感知切分) / PdfParser(扫描件 OCR 降级) / ExcelParser / TextParser(txt/md/csv)
│   ├── util/                        # TokenCounter(分语言 token 估算) / ImageCompressor(识别用压缩图) / UserContext(X-User-Id 解析)
│   └── model/ + mapper/ + dto/      # 14 个实体（含 AiDocumentVersion / AiKnowledgeRef / AiAgent / AiApiKey / AiImageDesc）+ 12 个 Mapper + ResultJson·ChatRequest·ChatRef·SessionInfo
├── config/
│   └── application-local.yml        # 本地开发私有配置（含密钥/数据目录，.gitignore 忽略；位于 Spring Boot 外部配置目录，不打进构建产物）
├── src/main/resources/
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD/AI_TRUSTED_TOKEN 缺失 fail-fast）
│   └── schema.sql                   # 建表脚本（13 张表，启动自动执行，幂等可重复运行）
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/{docId}/ 提取图 + images/chat/ 用户图 + artifacts/{sessionId}/ 交付产物 + secret/config-rsa.key + eval/ 评估集
├── deploy/nginx.conf                # 生产 nginx 参考配置
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 18.19.0）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    ├── src/router.js                # 路由表（工作台 `/v2/*`；管理页路由带管理员守卫）
    ├── src/api.js / configSchema.js # 接口封装 / 设置项 schema（分组、默认值、提交与校验）
    └── src/views/v2/                # 工作台：V2Layout(侧边导航+最近会话) + ChatPage(对话) / AgentsPage(智能体)
                                     #   DocumentsPage(文档管理) / DashboardPage(数据看板) / EvaluationPage(检索评估) / SettingsPage(系统设置)
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

**数据库**：现有 OceanBase 库 `ai_doc_assistant`（库需预先存在，库名按实际环境配置）。表结构（`c_ai_document`/`c_ai_knowledge`/`c_ai_session`/`c_ai_message`/`c_ai_qa_log`/`c_ai_qa_feedback`/`c_ai_config`/`c_ai_document_version`/`c_ai_knowledge_ref`/`c_ai_answer_cache`/`c_ai_image_desc`（图片描述缓存）/`c_ai_api_key`（对外 Key）/`c_ai_agent`（智能体配置），共 13 张）由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全）；也可手动执行：
```bash
mysql -h<db-host> -P<db-port> -uroot -p ai_doc_assistant < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码
export AI_TRUSTED_TOKEN=xxx               # 内部鉴权 token（与平台网关一致，SecurityConfig 校验）

# ===== 可选 =====
export AI_CHAT_KEY=sk-xxxx                # chat 模型密钥（MaaS 网关；有默认空值，缺失不启动失败，但聊天不可用）
export DB_HOST=127.0.0.1                  # 数据库主机（容器部署默认 mysql；外部 OceanBase 改为实际地址）
export DB_PORT=3306                       # 数据库端口
export DB_NAME=ai_doc_assistant           # 库名
export DB_USERNAME=root                   # 用户名
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379                    # Redis 端口（按实际部署调整，容器化部署见 docker-compose）
export AI_VISION_MODEL=qwen3-vl:2b        # 图片描述/OCR 模型（本地 Ollama）
export AI_VISION_BASE_URL=http://localhost:11434  # 视觉地址（不含 /v1，代码自动拼）
export AI_VISION_THINK=false              # 关闭 qwen3 思考模式（提速且输出稳定）
export AI_IMAGES_DIR=./data               # 数据落盘目录（跨平台兜底；生产容器内为 /app/data）
export AI_IMAGES_AUTH_ENABLED=true        # 图片访问鉴权（HMAC 签名 URL；默认开启，本地调试可置 false）
export AI_QUERY_REWRITE_ENABLED=true      # 查询改写开关（默认开启）
export AI_IMAGE_FILTER_ENABLED=true       # 回答图片相关性校验开关（默认开启）
export AI_RATELIMIT_ENABLED=true          # 接口限流开关（Redis 固定窗口，按用户/IP；也可设置页改）
export AI_RATELIMIT_CHAT=10               # 问答限频：次/分钟/用户（0=不限）
export AI_RATELIMIT_UPLOAD=10             # 上传限频：次/分钟/用户（0=不限）
export AI_RERANK_ENABLED=false            # 重排开关（需本地 reranker 服务，OpenAI 兼容 /v1/rerank）
export AI_RERANK_BASE_URL=http://localhost:7997
export AI_RERANK_MODEL=BAAI/bge-reranker-v2-m3
export AI_KEYWORD_ENGINE=mysql            # 关键词召回引擎：mysql（LIKE 零依赖）| meilisearch
export AI_MEILI_BASE_URL=http://localhost:7700
export AI_MEILI_KEY=xxx                   # Meilisearch master key（仅 env/yml，不入库；compose 中必填）
export AI_MEILI_INDEX=ai-doc-chunks       # 关键词索引名（只从 env/yml 读，永不入库）
export AI_EMBEDDING_KEY=xxx               # 向量模型密钥（留空回落 AI_CHAT_KEY）
export AI_EMBEDDING_BASE_URL=             # 向量模型网关地址（留空用默认 MaaS）
export AI_EMBEDDING_MODEL=qwen3.7-text-embedding-flash
export AI_INTENT_CLASSIFY_ENABLED=false   # 意图分类（chat/doc 分流，默认关）
export AI_DEEP_REASONING_ENABLED=true     # 深度思考总开关
export AI_DEEP_REASONING_MODE=model       # model=透传 enable_thinking / prompt=提示词引导
export AI_CONTEXT_MODEL_WINDOWS="qwen-plus=131072,qwen3=131072,qwen-max=32768,deepseek=65536,default=32768"
export AI_CONTEXT_COST_CAP=8000           # 上下文成本软上限（token）
export AI_SESSION_ANONYMOUS_SHARED=true   # anonymous 存量会话池是否对具名用户可见（false=收紧越权面）
export AI_VISION_API_KEY=ollama           # 视觉模型密钥（Ollama 不校验，占位值）
export REDIS_PASSWORD=                    # Redis 密码（默认空）
export REDIS_DB=0
export LOG_LEVEL_APP=info                 # 应用日志级别
export LOG_LEVEL_SPRING_AI=info           # Spring AI 日志级别
export MYBATIS_LOG_IMPL=org.apache.ibatis.logging.slf4j.Slf4jImpl
export ACTUATOR_HEALTH_DETAILS=never      # /actuator/health 详情级别
export AI_ADMIN_USERS=""                  # 管理员用户白名单（逗号分隔 X-User-Id；"*"=全员管理员，单机自用）
export AI_ADMIN_TOKEN=""                  # 管理员口令（请求头 X-Admin-Token；无网关/本地部署与前端 VITE_ADMIN_TOKEN 一致）
                                          # 两者任一命中即管理员；均未配置则管理端点默认 403（只问答可用的最小开放）
export SPRINGDOC_ENABLED=false            # Swagger/OpenAPI 开关（默认关，接口契约不外泄；本地调试可置 true）
```

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
接口文档（Swagger UI）：`http://localhost:8090/ai/swagger-ui/index.html`（springdoc 自动生成；Try-it-out 在线调试需在请求头携带 `X-Trusted-Token`）。**默认关闭**：`SPRINGDOC_ENABLED` 缺省为 false（接口契约不外泄），需要时 export `SPRINGDOC_ENABLED=true` 开启。同理 docker-compose 默认仅把 8090 绑定到回环地址（`127.0.0.1`），对外直连需 `APP_PUBLISH=8090` 或由 nginx/网关注入鉴权。

### 4. 启动前端

```bash
cd web
nvm use            # .nvmrc 固定 Node 18.19.0（Node ≥18 均可，建议 18/20/22）
npm install
npm run dev        # 访问 http://localhost:5800/v2/chat（端口被占直接报错，不会跳号）
```

Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 使用流程

启动前端后访问 `http://localhost:5800/v2/chat` 进入工作台。左侧是固定导航——**新建对话 / 对话 / 智能体 / 文档管理 / 数据看板 / 检索评估 / 系统设置**（除「对话」外均为管理页，仅管理员可见），下方「最近」列出会话（悬浮显示「导出 Markdown」「删除」），底部显示当前用户与**管理员验证**入口；左上角折叠按钮可把侧边栏收成图标条（状态记忆）。对话页为「消息流 + 可收起右侧状态栏」，其余页面为「页头 + 内容区」。

1. **文档管理**（侧边栏「文档管理」）：右上角「上传文档」多选上传，或把文件拖到页面任意处（出现「松开鼠标上传到知识库」遮罩）；可先填「文档描述（可选）」随文件入库；「全局搜索」跨全部文档搜知识块。列表按行展示 **文件名 / 片段 / 命中 / 大小 / 状态 / 上传时间 / 操作**——状态为「已入库 / 已弃用 / 解析失败（点开看原因）/ 解析中（进度条 + 当前阶段）」；行内操作：**知识块 / 版本 / 弃用（或「启用」）/ 下载 / 重解析 / 删除**，勾选多行后出现批量栏（**批量启用 / 批量弃用 / 批量删除 / 批量重解析**）。
   - **「知识块预览」弹窗**：可按标题/内容过滤，切换 **「切片列表」与「结构导图」** 两种视图（导图按章节路径聚合成树，点章节名筛选切片，显示每节点块数 / tokens / 图片数），顶部实时统计「共 N 块 / 合计 tokens / 平均 / 未向量化 N 块」，点行内联展开完整内容，可**编辑单个知识块**（Markdown 工具栏 + 左写右看实时预览 + 图片点选插入 + 未保存关闭提醒，保存后自动重新向量化）、**删除**、**停用**（停用块不参与召回）。
   - **「版本历史」弹窗**：每次解析/重解析自动存快照，可查看历史版本并一键回滚（回滚前二次确认）。
   - 上传大小上限、解析并发、分块与图片上限、结构感知切分等参数在「系统设置 → 文档解析」调整（部分改后需重解析生效）。

2. **对话**（侧边栏「对话」或「新建对话」）：欢迎页是「渠」字标 +「有什么可以帮你？」+ 推荐问题标签（点击直接提问）。输入框提示「问点什么？输入 @ 指定参考文档；Enter 发送，Shift+Enter 换行」，支持**拖入/粘贴发图**（最多 5 张，缩略图可点开预览、单张移除）；工具行从左到右为**智能体下拉**（「默认（全局配置）」或某个预设，底部「管理智能体」跳转配置页）、**上传图片**、**深度思考开关**（灯泡图标，状态记忆），右侧显示本轮实际生效的**模型名**与**发送/停止**按钮。输入 `@` 弹出文档候选（↑↓ 选择 · Enter 确认 · Esc 关闭），被 @ 的文档优先参考，已选引用以 chip 显示并可一键移除。
   - **提问后**：混合检索 + 查询改写 + 知识块关联扩散 → 流式回答，位置级插入文档原图、句末 `[N]` 引用角标（点击弹窗看来源全文与图片）、末尾「猜你想问」；工具调用（开启智能体/工具时）在回答内以状态行实时反馈「调用中/已完成/失败 + 耗时」；产物交付以可下载卡片下发。
   - **检索状态行**：回答下方「搜索 N 个关键词，参考 M 段资料」可展开——显示检索词、模型主动补充的「精确检索」query、逐条来源与命中摘要，点条目弹同一溯源窗口（历史消息无检索数据时降级为来源数）。
   - **右侧状态栏**（标题栏「状态」按钮开关）：当前智能体与模型（被智能体覆盖时标「智能体指定」）、深度思考状态与本会话轮数、**最近一次检索**概览、**本次用量（估算）**（上下文 / 预算 / 输出 / 填充块数）、引用来源列表（点击弹原文）。
   - **消息操作**（悬浮在回答下方）：**复制 / 有帮助 / 没帮助（单选锁定，刷新或重进会话仍保持）/ 重新生成 / 更多**（检索调试、导出 Markdown、删除本轮对话），并显示本轮 token 估算与时间；用户消息可**编辑后重新发送**。
   - **其它**：标题栏「AI 回答可能有误，重要信息请核实」点开完整免责声明；降级情形（未命中 / 超时截断 / 深度思考降级 / 图片剔除等）在回答顶部以提示条说明（调试级提示需在设置页开启）；断连自动重试并内联提示；图片点击进入灯箱（滚轮缩放 · 拖动平移 · 双击重置 · ESC 关闭，多图可左右切换）；生成中上翻回看不打断滚动，右下出现「回到底部」。**快捷键**：Esc 停止生成 / 清空输入；输入框 Enter 发送、Shift+Enter 换行；`@` 候选面板 ↑↓ 选择 · Enter 确认 · Esc 关闭。

3. **会话**（左侧「最近」）：点击条目切换会话，悬浮条目显示**导出 Markdown**与**删除**（删除有二次确认），空会话不进列表；删除当前会话后自动落到最近会话或新建。要清掉某一轮问答，在该条回答的「更多」里选**删除本轮对话**。

4. **数据看板**（侧边栏「数据看板」）：核心指标卡（**问答总数 / 无命中率 / 有引用标注 / 反馈满意率**，满意率附 👍👎 明细）+ **检索质量自动体检**（定时按线上参数跑评估集，指标较上期下滑即预警，也可手动触发）+ 热门问题 TOP10 + 无命中问题 TOP10 + **差评样本（反馈回流）**（还原问题与引用过的知识块，一键加入评估集）+ **知识库缺口管理**（无命中问题汇总，一键「补充知识块」写入并自动生成向量）。

5. **系统设置**（侧边栏「系统设置」）：左侧是分组导航（**智能问答模型 / 视觉模型 / 文档解析 / 向量模型 / 检索设置 / 上下文控制 / 深度思考 / 工具调用 / MCP 外部工具 / 语义缓存 / 接口限流 / 定时维护 / API Key 管理 / 技能 Skills / 并行检索**），每组顶部有说明条、底部有「恢复本组默认」；**改动后点页头右上角「保存配置」生效**（按钮上显示待保存项数，未保存时页头提示；参数旁的 `?` 为说明，调试类开关带橙色「调试」徽标）。**问答 / 视觉 / 向量三类模型均可跨厂商热切换**（网关地址 / API Key / 模型名 / 接口路径，API Key 以 RSA 加密入库，页面仅回显 `****后4位`），保存即生效免重启；**向量模型**保存前先探测新配置（不可达或维度非法一律拒绝保存），通过后自动重建索引并后台全量重嵌入，面板内展示探测结果、重嵌入进度/维度变化/耗时/索引对账并可手动重试（期间向量检索降级关键词路，服务不中断）；检索权重与行为参数、上下文预算、解析与分块、深度思考增强、限流阈值、语义缓存等均在此在线调整；**工具调用 / MCP 外部工具 / 技能 Skills / 并行检索**四组为能力开关，默认关闭，开启后对话链路才会挂载对应工具（MCP 组支持增删服务、整体重连与临时探测，服务列表也可切「高级编辑（原始 JSON）」维护）；**API Key 管理**为第三方系统签发仅具问答链路权限的 Key（明文仅签发时返回一次，可改名 / 停用 / 删除，页面附 curl 调用示例）。

6. **检索评估**（侧边栏「检索评估」）：页头显示当前评估集用例数与生成时间，按钮**「重新生成评估集」**从历史问答回放重建（问题 → 期望知识块，差评案例自动补充）；页内 **「怎么用（三步）」** 说明卡可展开查看用法。「运行配置」里设置 recall 的 k 列表（默认 5,10,20）并按需增删**参数组**（向量权重 / 关键词权重 / 标题奖励 / 向量阈值 / 关键词上限 / topK / 重排下限 / 重排上限，未拨动的参数框灰色占位回显当前线上值、拨动即为该组显式覆盖）；然后走两条路——**一键体检**（用当前配置全量跑，给红绿灯结论与落空/低分题清单，零参数门槛）或**运行对比评估**（多组参数并行对比）。结果表按 **recall@k / MRR / 命中率** 与基线 ↑↓ 对比并给出自动结论，表现好的组可**一键应用**直接写入线上配置生效（不污染评估，应用前可看逐问题明细）。

7. **智能体**（侧边栏「智能体」，管理员；**下列能力默认全部关闭**，需先在系统设置的「工具调用 / MCP 外部工具 / 技能 Skills / 并行检索」分组开启）：列表提供搜索、刷新与「新建智能体」，每张卡片可**配置 / 设为默认 / 删除**。编辑页按分区填写——**身份**（名称、描述）、**模型与提示词**（模型名留空跟随全局；提示词填写后完全替换全局系统提示词）、**知识库范围**（限定可检索的文档）、**能力**（工具 / 技能 / MCP 默认沿用全局开关，需要为该智能体单独破例时才改成「开启 / 关闭」，或选「指定」逐项挑，一项都不选等同「不使用」）、**子智能体委派**（选择允许委派的子智能体，最多 4 个；自身是子智能体时不显示该分区）、**默认**（是否作为对话页默认智能体）。标记为子智能体的配置可被主智能体按需**并行委派**（多视角子查询各自「检索 + 提炼」，再由汇总节点合并，去重与摘要在「并行检索」分组配置）；工具调用过程在回答内以状态行实时反馈。

## 核心功能

- **混合检索**：Redis 向量 Top-K + 关键词召回并行（**超时/阈值/召回数/位置奖励等行为参数设置页可调**，保存即生效）；**向量分归一化 + 双命中叠加**（语义+关键词命中 = 向量分+关键词分+标题奖励）；**关键词引擎可切换**（默认 `mysql` LIKE 零依赖；切到 `meilisearch` 用中文分词 + 相关度打分，服务不可用/超时自动降级回 MySQL，首次切换需 `POST /api/ai/search-index/reindex` 全量重建，写索引随解析/编辑/删除增量同步）；**jieba 中文分词**（搜索模式细粒度词元 + 长词 2-gram/4-gram 子词元补充召回宽度，启动预热词典；**检索状态行只展示主词元**，子词元仅参与召回不展示，检索调试面板保留全量）；**分块位置奖励**（文档首块加权）；Ollama 支持 rerank 时自动启用（候选数在可配置区间内触发），否则回退规则排序
- **知识块关联检索**（`c_ai_knowledge_ref`）：解析时识别块内交叉引用（详见/参见/见 编号节/《章节名》/章节名+章节后缀等 7 类模式，排除图表引用与相对引用、提及类仅精确匹配防误报，单块最多 8 条）→ 建块间引用边；检索命中 A 时 **1-hop 扩散**自动带出 A 引用的块 B（入边 C 默认关）+ **结构上下文扩展**沿章节路径带出父章节摘要（默认 2 级、summary 200 字）；扩散块走独立配额（`refExpandMaxHits=3`/`refExpandMaxTokens=800` 双上限，超限可舍弃不阻断回答），引用来源带 `origin` 标注（REF_OUT/REF_IN/PARENT）；引用关系与块/文档同生命周期（重解析/编辑/删除/回滚自动重建），任一环节失败降级为不扩散
- **模型热切换**（问答/视觉/向量三类，均免重启）：四要素（网关地址 / API Key / 模型名 / 接口路径）全部存 `c_ai_config`，设置页保存即生效，API Key **RSA 加密入库**（页面仅回显 `****后4位`，掩码原样提交不覆盖真实 key，存量明文启动自动迁移为密文）；`DynamicOpenAiChatModel` / `DynamicEmbeddingModel` 每次调用校验配置指纹，变化即本地重建客户端（无网络开销），路径归一化兼容智谱 `/v4`、方舟 `/v3`、千帆 `/v2`、Ollama 等 OpenAI 兼容端点；多副本经 Redis pub/sub 广播各自重建
- **向量模型切换：维度护栏 + 全量重嵌入编排**：不同 embedding 模型的向量**数学上不可迁移**（维度与语义空间均不同，即使维度相同也不可复用），故切换必须重算全库向量。编排把三道护栏全部前置到破坏性操作之前——① `initialize-schema` 必须为 `true`（否则 DROP 后无法重建索引，向量路永久不可用）② 真实探测新模型维度（不可达/维度非法即放弃，**旧索引与旧向量保持完整、服务不降级**）③ 与 `embedding.dimensions` 记录的旧维度比对判定 schema 是否需重建；护栏通过后按序执行：**先清语义缓存**（旧模型问题向量即刻作废，避免整个重嵌窗口内命中错答案）→ DROP 向量索引（连数据）→ 按新维度重建 schema → 游标分批全量重嵌（批内失败记数不终止，可重跑补齐）→ 回写 `embedding.dimensions` + **索引对账**（`FT.INFO num_docs` vs 成功写入数，不一致 fail-loud 告警并在设置页提示）。保存配置检测到 embedding 变化即自动触发（也可手动触发），**期间向量检索降级关键词路，服务不中断**；进度/维度/耗时/对账在设置页轮询展示
- **查询改写**：LLM 将用户问题改写为检索关键词（默认开启）；支持多轮对话上下文改写（追问"那删除呢？"自动补全），改写结果入库可评估
- **上下文与长度控制**：`预算 = min(模型窗口×安全系数 − 输出限制, 成本软上限)`，窗口按当前模型动态匹配；**价值驱动填充**（知识块按相关度分数累积填充，替代固定 8 块）；**块内命中片段截取**（±150 字窗口，边界对齐行/图片标记，长命令不被切断；**被截掉的图片占位自动补到片段末尾**，保证 LLM 配图依据完整）；**关联扩散块独立配额**（原始命中块用 `max-context-hits`，扩散块用 `refExpandMaxHits/MaxTokens`，共享剩余预算）；**信息增益去冗余**（`context.dedupEnabled` 默认开：候选块与已选块词元重叠过高即跳过，同一操作被切成多块时只保留最高相关那块进上下文，防重复内容浪费预算与多块表述不一致；同章节路径用更低阈值 `dedupPathThreshold`，仅影响问答上下文不影响检索/评估）；**历史裁剪**（单条 200 字 + 总量上限 + 剥离 `[图片N]`）；输出 maxTokens 限制；token 按中英文分语言估算（TokenCounter）
- **图片链路**：docx 提取图片（去重 + **双图策略**：识别用压缩图 1280px 进视觉模型，**展示用原图**落盘）→ 视觉模型生成描述并随分块落库（Ollama `num_ctx=16384` 防 1280px 视觉 token 截断）→ 检索命中后**相关性预筛**（与问题无关的图不分配编号，LLM 生成时即避开，避免"先输出后剔除"的图闪现）→ 全局编号 `[图片N：描述]` 供 LLM 选图 → **相关性校验兜底**（错配/编造编号自动剔除并重建，被剔除提示用户）→ SSE `image` 事件 → 前端按标记渲染原图（灯箱：滚轮按幅度平滑缩放/拖动/多图切换/ESC）；图片描述完成逐张上报进度（10→30 区间"识别图片 k/total"）
- **引用溯源**：回答句末 `[N]` 角标 → 弹窗展示来源知识块全文（图文交错，还原原文结构）+ 关联截图；**回答下方检索状态行**（搜索 N 个关键词/参考 M 段资料，**参考段数在回答完成、引用最终确定后一次性显示**，不与生成中的候选数跳变）展开列出全部命中来源与摘要片段，条目点击弹同一溯源弹窗（历史消息兼容：无检索数据时降级显示来源数）；`done` 事件携带 sources/related/messageId，`retrieved` 事件携带检索概览（随消息持久化，引用剔除后 refs 同步重算）
- **语义缓存**（`c_ai_answer_cache`）：相似问题直接复用历史回答（embedding 余弦相似度 ≥ `semanticCache.threshold`，默认 0.96），命中秒回；知识库文档增删改/启停用/重解析/知识块编辑（含图片补描述回写）**整体失效**，带图片的提问不走缓存；最大条数 LRU 淘汰，设置页可调（`semanticCache.enabled/threshold/maxEntries`）；**维度护栏**：缓存向量与当前模型维度不一致（向量模型切换后的存量条目）一律判不命中并告警——跨模型向量空间不可比，按较短长度截断算出的相似度是噪声且可能越过阈值返回语义无关的旧回答，脏条目由重嵌入编排第一步清空
- **文档解析**：docx（段落/标题大纲级别/**表格→Markdown 表格、单列表格→代码块**/内嵌图/单元格换行保留）/ xlsx（sheet 转文本）/ pdf（PDFBox 文本抽取）/ **txt/md/csv**（md 按标题分块、代码围栏跟踪不切断，csv 首行表头）；**扫描件自动 OCR**（文本 <20 字符判定，逐页渲染 200DPI → 本地视觉模型识别，OCR 专用提示词）；**大文件流式解析**（解析器按 `Path` 流式读取，不整载内存）；**结构感知切分**（docx：标题层级开新块 + 章节路径独立存储、向量化/检索时拼入 `【上下文】章节 > 小节`、达到边界阈值在段落交界断块、表格独立成块；`chunk.structural` 可关，需重解析生效）；**分块重叠只进向量化文本**（不入库、不进指纹，邻块变动不连锁重嵌）；**解析删除感知**（内存删除标志 + 线程中断，删除立即停止并清理本次产物）
- **数据闭环**：问答日志（含改写后问题/命中文档/耗时）+ 回答 👍👎 反馈 + 看板聚合；**无命中问题汇总 → 一键创建知识块（自动生成向量）**，形成"发现缺口→补充→验证"闭环；**差评回流**：看板差评样本还原问题与引用块 → 一键加入检索评估集（`POST /api/ai/eval/case` 单条增补），调参后可用真实坏例回归验证
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元（分词结果）/关键词/向量/合并/重排/最终结果与命中率，前端问答页"检索调试"按钮可视化排查召回问题
- **检索评估**：`POST /api/ai/eval/generate` 从历史问答引用（`c_ai_message.sources`）回放生成评估集（问题→期望知识块，失效期望块自动剔除并计数；差评回流单条增补）→ `POST /api/ai/eval/run` 批量参数组对比 **recall@k / MRR / 命中率** + 弃用文档召回断言；前端四件套：**一键体检**（当前配置全量评估 → 红绿灯结论 + 落空/低分题清单）、**预设参数组**（关键词优先/向量优先/向量+重排/多路模拟，空参数框占位回显当前值）、**一键应用**（好的组直接写入 `c_ai_config` 广播生效，检索 topK/阈值/关键词上限/重排区间等键已纳入在线白名单）、**自动结论**（相对基线 ↑↓ 与可应用建议）；**自动体检基线护栏**（报告携带评估集标识，重新生成评估集后样本变化，本期自动记为新的基线、不做跨样本 delta 判定，避免假性下滑；**每次回放前对期望块做存活校验**——文档删除/重解析后的失效标签运行期剔除、全失效 case 跳过，期望标签漂移同样记为新的基线，杜绝"内容漂移被误报为检索质量下滑"）；**LLM 评判检索充分性**（`eval.judgeEnabled` 调试开关：对每个 case 判“命中资料是否足以直接回答”，证据窗口与产品上下文上限一致（`context.maxContextHits` 条），评判模型可用 `eval.judgeModel` 独立配置（留空回落 `chat.model`），产出 judgeScore，作为 recall 之外的端到端度量）；参数覆盖走线程局部 override，**不写 DB 不污染生产配置**（multi 模式为确定性拆分近似，衡量多路合并机制而非 LLM 深度思考质量）
- **智能体（Agent）**：`c_ai_agent` 存智能体预设（模型 / System Prompt / 知识范围 / 工具与技能开关 / MCP / 是否子智能体 / 子智能体列表 / 是否默认）；每轮问答可带 `agentId` 指定智能体，未填维度继承全局配置；对话页顶部下拉切换（`GET /api/ai/agent/available` 为普通用户可访问的精简列表，只含 id/name/description/model/isDefault）
- **子智能体编排（StateGraph）**：主智能体回答时把问题拆给若干子智能体**并行**执行「独立检索 + 提炼」，再由汇总节点合并回主流程（`SubAgentOrchestrator`，基于 Spring AI Alibaba StateGraph；`agent.enabled`/`agent.subAgents`/`agent.topKPerAgent`/`agent.digestEnabled` 可调，**总开关默认 false**）；工具调用过程经 SSE `tool_status` 事件实时反馈
- **工具生态**：内置工具（计算器——递归下降自实现表达式求值，仅 `+ - * / % ^` 与括号，**不执行任意代码**；日期）、知识检索工具（`@Tool knowledge_retrieval`，命中块注册进当前流 `sources` 并续编引用编号）、产物交付工具（`present_artifacts` 落盘 `data/artifacts/{sessionId}/`，扩展名白名单 + 文件名净化，SSE 下发卡片）、技能读取工具（`SkillTools`，渐进披露读 `SKILL.md`，默认关）；开关集中在 `tool.*`，**总开关与四个子开关默认均为 false**
- **技能包（Skills）**（`skill.enabled` 默认 false）：目录 + `SKILL.md` 形式的可插拔能力（内置目录 + 用户目录，默认 `./data/skills`），支持新建 / 从 URL 安装 / 启停用 / 删除 / 查看全文；可把技能说明注入 System Prompt（`skill.injectEnabled`/`skill.injectMaxChars`）或由模型按需读取（`skill.toolEnabled`）；`skill.disabledNames` 由系统写入
- **MCP 外部工具**（`mcp.enabled` 默认 false）：接入任意 MCP Server（`mcp.servers` 为 `[{name,url,type}]`，type=streamable\|sse），提供连接状态（含每 server 工具数）、整体重连、以及**不落配置的临时连通性探测 + 工具清单**（`POST /api/ai/mcp/probe`）
- **对外 API Key**：`c_ai_api_key` 只存 SHA-256 哈希 + 前 8 位前缀（明文仅签发时返回一次），权限固定为问答链路——**即便持有 Key 也访问不了管理端点**（仍 403）；支持改名、启停用（即刻吊销）、删除
- **@ 引用（atRef）**：问答输入框可 @ 指定文档，被 @ 文档内与问题最相关的块**前置注入上下文**（`atRef.maxChunksPerDoc` 默认 3、`atRef.maxTotal` 默认 6，防 @ 块挤占普通检索命中）；请求体字段 `refs[]`（`ChatRef.type=doc`）
- **意图分类（默认关）**：先判用户消息是 `chat`（问候/闲聊/与知识库无关）还是 `doc`（可能需要查手册），`chat` 分支走简短回应且不引用资料；`intent.enabled` 默认 false（`AI_INTENT_CLASSIFY_ENABLED` 可开），`intent.model` 可配更小更快的模型，超时按 `doc` 处理，消息带图时不分类
- **图片描述缓存**：`c_ai_image_desc` 按**内容寻址**（`v{版本}_{sha256}`）持久化图片描述，同一张图跨文档/重解析不再重复调用视觉模型；`vision.descCacheVersion`（改动即全量重新描述）/`vision.descCacheTtlDays` 控制生命周期，运维端点可看统计、清理过期与旧版本、清空
- **会话**：MySQL + Redis 双层存储，历史恢复（含图片/引用来源/messageId/检索状态行）、新建/切换/导出 Markdown/删除、消息时间戳、推荐问题池（`chat.suggestedQuestions`，设置页编辑 + 看板热门问题一键加入，欢迎页展示）；后端另已提供会话搜索（关键词）、置顶、收藏、重命名与按消息组删除问答（含撤销）接口（`c_ai_session.is_pinned/is_favorite`），当前工作台界面未接入
- **前端体验**：markdown-it + DOMPurify + highlight.js 安全渲染（代码块复制按钮、**长行自动折行 + 限高滚动**、**表格渲染容错**：LLM 在标题/列表后未留空行的表格自动补空行独立渲染、结尾孤立竖线清理）、重新生成/编辑重问（编辑图标悬浮气泡下方）、图片灯箱（**滚轮按幅度平滑缩放**：每 100 单位滚轮量 8%、单次 clamp ±30% 防惯性跳变）、发送后自动滚动到底部（不等首个 token）、问答 👍👎 反馈（**单选锁定**：评价后两按钮禁用不可再点，刷新/重进会话仍保持——历史消息回带既有评价）、断连自动重试内联提示、全局错误边界（渲染异常友好提示防白屏、401 统一提示）、图片加载失败占位图、上传进度条
- **深度思考（生产级）**：思考流式展示（`model` 透传 reasoning_content / `prompt` 引导双模式）→ 提取 `<search>` 检索计划（精化 query + 子问题）→ 多路并行检索合并 → 复用上下文构建与回答流。增强：**思考链注入最终回答**（`injectThinking` 默认开：推理过程截断后作为参考注入生成 prompt，明确"以参考资料为准"，让"想过的拆解"作用于"答"）；**思考关键词增强检索**（`injectKeywords` 默认开：从思考全文提取词元补充多路检索，思考失败时也用于增强降级检索，思考不白费）；**失败细化降级**（超时/异常时已收集内容若含检索计划仍走多路，否则"原问题 + 思考词元"增强检索）；**思考长度护栏**（`maxThinkingChars` 默认 3000：超限截断思考流并保留已想内容，防刷爆上下文/token）；**前端思考完成自动折叠**（避免超长思维链刷屏，想看再点开）；**自动路由**（`autoRoute` 默认关：长问 ≥25 字或含多条件/对比/递进词自动启用深度思考，保守启发式避免常见问题全量思考成本翻倍）。全部参数在设置页"深度思考设置"（含"思考增强·护栏与路由"子分组）

## API 一览

> 完整接口文档见 **Swagger UI**（启动后访问 `/ai/swagger-ui/index.html`，随代码自动更新；接口按"智能问答/会话与消息组/文档管理/知识库/反馈与看板/系统配置/智能体/对外 API Key/MCP/技能包/检索调试/检索评估/关键词索引/图片描述缓存"分组，Try-it-out 需携带 `X-Trusted-Token`）。下表为核心端点速查（完整 85 个端点以 Swagger 为准）：

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（`token`/`image`/`retrieved`/`thinking`/`tool_status`/`done`/`error` 事件，见下方 SSE 事件表） |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}`、`PUT /api/ai/session/{id}/rename` | 会话列表（支持关键词搜索）/ 新建 / 历史恢复 / 重命名（≤50 字，校验归属） |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏（`{pinned:true}` 或 `{favorite:true}`） |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions` | 删除单会话（软删除会话+消息）/ 清空全部会话 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /api/ai/message-group/undo` | 按消息组删除问答（问题+回答）/ 撤销删除 |
| `GET /api/ai/suggested`、`POST /api/ai/suggested` | 推荐问题池读取 / 追加（去重上限 8 条）。**两者权限不同**：`GET` 在普通用户白名单内（欢迎页展示），`POST` 属管理员端点 |
| `GET /api/ai/analytics/badcases` | 差评坏例列表（还原问题与引用块，供回流评估集） |
| `GET/DELETE /api/ai/answer-cache` | 语义缓存统计 / 清空 |
| `POST /api/ai/document/upload`、`/upload/batch` | 上传文档（docx/pdf/xlsx，异步解析） |
| `GET /api/ai/document/list`、`DELETE /{id}`、`PUT /{id}/status`、`POST /{id}/reparse` | 文档列表 / 删除 / 启停用 / 重解析 |
| `GET /api/ai/document/{id}/versions`、`POST /{id}/rollback` | 版本历史 / 回滚到指定版本（按原 ID 重建知识块+向量） |
| `POST /api/ai/document/batch/delete`、`/batch/status`、`/batch/reparse`、`GET /document/stats` | 批量操作（删除/启停用/重解析）+ 命中次数统计 |
| `GET /api/ai/knowledge/{id}`、`GET /api/ai/knowledge/list?docId=` | 知识块详情（引用溯源） / 按文档预览 |
| `PUT /api/ai/knowledge/{id}`、`DELETE /api/ai/knowledge/{id}`、`PUT /{id}/status` | 编辑知识块（重新向量化：删旧向量+插新）/ 删除知识块 / 知识块级启停用（停用不参与召回，关键词索引同步） |
| `GET /api/ai/knowledge/search?keyword=` | 跨文档全局搜索知识块（含已停用，诊断用） |
| `GET /api/ai/knowledge/unmatched`、`POST /api/ai/knowledge` | 无命中问题列表 / 手动创建知识块（自动生成向量） |
| `POST /api/ai/feedback`、`GET /api/ai/analytics/summary` | 回答反馈 / 看板聚合 |
| `GET/PUT /api/ai/config`、`GET /api/ai/config/keyword/check` | 模型配置读取（apiKey 脱敏）/ 保存即生效（含三类模型热切换、检索权重、上下文参数）/ 探测 Meilisearch 可用性 |
| `GET/POST /api/ai/config/embedding/reindex` | 全量重嵌入：查询任务状态（status/total/done/failed/维度 oldDim→newDim/索引对账 indexed/起止时间）/ 手动触发（已在跑返回 409；向量模型切换保存时自动触发） |
| `GET /api/ai/search-index/stats`、`POST /api/ai/search-index/reindex`、`DELETE /api/ai/search-index` | 关键词索引运维：状态统计（indexedCount vs mysqlCount 对比漂移）/ 全量重建（后台执行）/ 清空 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试（含检索词元） |
| `POST /api/ai/eval/generate`、`GET /api/ai/eval/set`、`POST /api/ai/eval/run`、`POST /api/ai/eval/case`、`GET /api/ai/eval/last-report`、`POST /api/ai/eval/run-auto` | 检索量化评估：生成评估集 / 读取 / 批量参数组对比（recall@k/MRR/命中率 + 弃用文档断言）/ 差评增补 / 最近自动体检报告 / 手动触发自动体检 |
| `GET /api/ai/auth/me` | 当前身份与权限（`{user, admin}`，前端据此显示/隐藏管理入口） |
| `GET /api/ai/agent/list`、`GET /api/ai/agent/sub` | 智能体列表（默认在前）/ 可委派子智能体列表（`is_subagent=1`） |
| `GET /api/ai/agent/available` | 对话页可用精简列表（**普通用户可访问**：id/name/description/model/isDefault） |
| `POST /api/ai/agent`、`PUT /api/ai/agent/{id}`、`DELETE /api/ai/agent/{id}`、`POST /api/ai/agent/{id}/default` | 智能体：新建 / 编辑（仅更新出现的字段）/ 删除（物理删）/ 设为默认 |
| `GET /api/ai/api-key/list`、`POST /api/ai/api-key`、`PUT /api/ai/api-key/{id}/name`、`PUT /api/ai/api-key/{id}/disabled`、`DELETE /api/ai/api-key/{id}` | 对外 API Key：列表（不含明文与哈希）/ 签发（**明文仅此一次返回**）/ 改名 / 启停用（停用即吊销）/ 删除 |
| `GET /api/ai/mcp/status`、`POST /api/ai/mcp/reload`、`POST /api/ai/mcp/probe` | MCP：连接状态（总开关 + 每 server 状态与工具数）/ 重连全部 / 临时探测（不落配置） |
| `GET /api/ai/skill/list`、`GET /api/ai/skill/detail`、`POST /api/ai/skill`、`POST /api/ai/skill/install`、`PUT /api/ai/skill/{name}/disabled`、`DELETE /api/ai/skill/{name}` | 技能包：列表（内置 + 用户）/ 详情（含 SKILL.md 全文）/ 新建 / 从 URL 安装 / 启停用 / 删除（仅用户目录） |
| `GET /api/ai/desc-cache/stats`、`POST /api/ai/desc-cache/prune`、`DELETE /api/ai/desc-cache` | 图片描述缓存运维：统计 / 清理过期与旧版本 / 清空 |
| `POST /api/ai/sessions/batch-delete` | 批量删除会话（逐个校验归属） |
| `GET /api/ai/document/{id}/source`、`POST /api/ai/document/{id}/backfill-descriptions` | 下载源文件（RFC5987 编码文件名）/ 补齐图片描述（后台补描述并回写向量与关键词索引） |
| `POST /api/ai/config/probe`、`GET /api/ai/config/rerank/check`、`GET /api/ai/config/keyword/check`、`POST /api/ai/config/reset` | 通用连通性探测（先测后存：`{group,baseUrl,apiKey,model,path}` → `{available,latencyMs,detail}`，group ∈ chat/vision/embedding/rerank/keyword）/ 探测重排服务 / 探测 Meilisearch / 恢复分组默认值（不含 embedding 与密钥） |

> 上表为速查，**并非全量**：代码共 14 个控制器、85 个端点，完整契约以 Swagger UI 为准。

### SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 | 载荷 |
|------|------|------|
| `token` | 流式生成逐片（语义缓存命中时一次性整段） | 回答文本增量 |
| `image` | 生成之前 | 命中图片 URL 列表（按编号顺序，生产为 HMAC 签名 URL） |
| `retrieved` | 检索 + 重排 + 上下文填充完成、生成之前 | 检索概览 `{keywords:搜索词元数, refs:参考段数, terms:主词元列表}`（随消息持久化；引用自检剔除后 `refs` 同步重算） |
| `thinking` | 深度思考开启时 | 思考链增量 |
| `tool_status` | Agent 调用工具 | 工具名 / 状态（done\|error）/ 结果或错误 |
| `done` | 回答完成 | `sources`（签名后的引用来源）、`related`（相关追问）、`messageId`、`thinking`（正常路径汇总的完整思考链）；缓存命中路径另带 `finalContent`/`finalImages`/`degradations` |
| `error` | 处理或下发异常 | 错误文案（系统繁忙 / 系统处理异常 / 回答下发失败等） |

> **思考结束没有独立事件**：完整思考链在 `done.thinking` 一次性给出，不要再找 `thinking_done` 之类的事件名。

## 多副本部署要求

支持多实例水平扩展，需满足以下约束（均已代码化治理）：

1. **数据目录必须共享**：`AI_IMAGES_DIR` 指向所有实例都能访问的同一存储（源文件/提取图/评估集都在此）。docker-compose 用命名卷 `app-data:/app/data` 仅**同主机**多副本共享；跨主机（集群）需挂 NFS/对象存储等共享卷，否则副本 A 上传的文档在副本 B 无法重解析、图片 URL 在 B 侧 404
2. **静态配置一致**：各副本的 yml/环境变量（数据库、Redis、`AI_TRUSTED_TOKEN`、模型密钥等）必须一致；动态配置（`c_ai_config`）无需手工同步——任意实例保存后经 **Redis pub/sub**（channel `ai:config:changed`）广播，其他实例立即重载缓存；订阅断线期间的变更由 **5 分钟兜底轮询**补齐
3. **并发防护**：重解析用 **DB 状态机 CAS**（`SET status=2 WHERE status≠2`，原子）——两实例同时重解析同一文档只有一个成功，另一个返回"正在解析中"；解析队列有界（50）+ 图片描述线程池有界，超限拒绝/降级不失控
4. **删除中断语义**：删除在任意实例生效——本实例解析的文档立即中断；其他实例上的解析由 DB 兜底在检查点（入库每 10 块/向量化每批）秒级停止清理，不产生孤儿数据
5. **总并发核算**：解析并发为"副本数 × parse.concurrency"（默认 2/实例），embedding/Ollama 为共享瓶颈，多副本时需下调单实例并发或扩容推理资源
6. **语义缓存与文档名缓存一致性（无需手工同步）**：相似问题答案缓存的失效以"命中时校验 DB 行存在（原子自增命中数）"兜底——任一实例清空/淘汰后，其它实例下一次命中即感知并整体失效本地索引，绝不返回基于旧知识库的过期答案；文档名缓存带 10 分钟 TTL 回源自愈
7. **向量模型热切换为全实例串行**：重嵌入由保存配置的实例执行，并通过 Redis 分布式锁（`ai-doc:reembed:lock`）互斥——其它实例不会切入执行造成索引互删；锁持有期间所有实例的向量检索路自动跳过（安静降级关键词路），避免命中半成品索引。任务每批续期锁，实例崩溃后锁 TTL（120s）自愈，不再永久降级
8. **启动对账/巡检开关需收敛为单点**：`keyword.reconcileOnStartup`（配置默认项，可在 `c_ai_config` 改）与 `parse.recoverStuckOnStartup`（同上）默认 true，适合单实例；多副本同时启动会互相复位对方正在解析/对账的任务。多副本上线前请置 false（或仅首实例/运维单点触发），对应周期兜底任务（ScheduleCenter 每小时精确对账）仍会补齐
9. **RSA 密钥文件必须共享**：模型 API Key 加密解密的私钥文件默认在 `{AI_IMAGES_DIR}/secret/config-rsa.key`（随数据卷持久化），可用环境变量 `AI_CONFIG_RSA_KEY` 覆盖路径。多实例共享同一 DB 时必须让所有实例使用同一密钥文件——否则 A 实例加密保存的 key 在 B 实例无法解密，问答/向量/视觉链路全部不可用（删除密钥文件后已加密配置将永久不可解密）

## 与其它平台集成

生产环境由平台网关做 JWT 鉴权并透传请求（前端调 `/api/ai/*`，见 `web/.env.production`）。
**注意**：
1. 平台网关需额外透传图片路径 `/ai/images/**`（生产开启图片鉴权时，图片 URL 带 HMAC 签名与过期时间，由本服务动态生成）
2. SSE 接口（`/chat`）网关需关闭响应缓冲，否则流式 token 无法实时到达
3. 内部 token `AI_TRUSTED_TOKEN` 由网关注入请求头，前端不携带共享密钥
4. **用户身份透传**：网关鉴权后必须注入（并覆盖客户端自带的）`X-User-Id` 请求头作为用户标识——会话按该标识隔离（列表/删除/清空只作用于本人；anonymous 名下的存量会话为**升级兼容池**，默认对全员可见，设 `AI_SESSION_ANONYMOUS_SHARED=false` 收紧为仅 anonymous 调用方可访问）。前端在无网关的本地调试场景会用 localStorage 稳定 ID 自行携带该头。**生产网关若不注入，所有人共用 anonymous 池，等于无隔离**
5. **接口限流**：问答/上传按"用户（无身份则按 IP）"做 Redis 固定窗口限频（默认 10 次/分钟，设置页可调，超限返回 429）；Redis 不可用自动放行
6. **权限模型（用户问答 / 管理员运维）**：普通用户仅开放问答链路——`/chat`、会话管理、反馈提交、引用溯源（GET 单个知识块）、`/config/public`、`/suggested`、`/auth/me`。**其余端点（文档上传/删除、模型与系统配置、评估、看板、检索调试、索引重建、语义缓存/图片描述缓存运维等）仅管理员可访问**（403，fail-closed）。管理员判定：网关透传的 `X-User-Id` ∈ `AI_ADMIN_USERS` 白名单，或请求携带 `X-Admin-Token` == `AI_ADMIN_TOKEN`。前端在侧边栏底部用户区提供「管理员验证」入口（口令仅存本机 localStorage）；生产多用户建议用账号白名单而非共享口令
7. **普通用户 UI 收敛**：前端 `/v2/agents`、`/v2/documents`、`/v2/dashboard`、`/v2/evaluation`、`/v2/settings` 路由带管理员守卫（`router.beforeEach` + `/auth/me`），非管理员侧边栏不展示这些入口、直达 URL 自动跳回对话页

## 测试与验证

> 按项目维护偏好未引入单元测试设施（`src/test` 为空，pom 无测试依赖）。推荐按以下方式验证：

```bash
# 1. 后端健康检查
curl http://localhost:8090/ai/actuator/health          # 期望 {"status":"UP"}

# 2. 前端构建验证
cd web && npm run build

# 3. 检索链路调试（无需重新解析，直接验证召回质量）
curl -X POST http://localhost:8090/api/ai/debug/retrieval \
  -H "Content-Type: application/json" \
  -d '{"question":"如何删除报表"}'                      # 返回分词/关键词/向量/合并/重排分步结果

# 4. 检索量化评估（回放历史问答引用生成评估集 → 批量参数组对比，验证参数改动是好是坏）
curl -X POST http://localhost:8090/api/ai/eval/generate \
  -H "Content-Type: application/json" -d '{"maxCases":100}'   # 生成 data/eval/retrieval-eval.json（问题→期望知识块）
curl -X POST http://localhost:8090/api/ai/eval/run \
  -H "Content-Type: application/json" -d '{
    "kList":[5,10,20],
    "groups":[
      {"name":"当前配置","mode":"normal"},
      {"name":"向量0.7/关键词0.3","mode":"normal","vectorWeight":0.7,"keywordWeight":0.3},
      {"name":"多路合并","mode":"multi"}
    ]}'                                                       # recall@k/MRR/命中率 + 弃用文档断言
# 5. Meilisearch 关键词引擎（可选，替代 MySQL LIKE 全表扫描）
docker compose up meilisearch          # 需先设置 AI_MEILI_KEY（master key，与 app 的 AI_MEILI_KEY 一致）
curl http://localhost:7700/health       # 期望 {"status":"available"}
# 设置页把"关键词引擎"切到 meilisearch（会自动校验服务可用性）→ 保存
curl -X POST http://localhost:8090/api/ai/search-index/reindex   # 首次切换/索引漂移后全量重建（后台执行）
curl http://localhost:8090/api/ai/search-index/stats             # indexedCount 应与 mysqlCount 一致
# 此后解析/编辑/删除会增量同步索引；服务不可用或超时自动降级回 MySQL LIKE，不影响问答
```

**端到端手动验证**（建议每次改动后走一遍）：
1. 文档管理上传含图片 docx → 状态轮询看进度（图片阶段"识别图片 k/total"递增）→ 生效
2. 解析中删除文档 → 后端日志出现"解析已被删除中断"，无孤儿知识块
3. 问答提问 → 深度思考开关（可选）→ 流式回答带引用 `[N]` 角标 → 点击看来源全文与图片 → 👍/👎 反馈
4. 设置页修改任意行为参数（如向量阈值）→ 保存 → 检索调试对比前后召回差异
5. 知识块关联检索：上传含"详见/参见《章节名》"的文档 → 查库 `SELECT * FROM c_ai_knowledge_ref WHERE doc_id=...` 有引用边 → 问命中章节的问题 → 回答引用弹窗出现"关联引用块/父章节上下文"（origin=REF_OUT/PARENT）；设置页关闭"关联扩散"后行为回到只检索直接命中块

## 产品化特性

- **安全**：关键密钥零默认值（`DB_PASSWORD`/`AI_TRUSTED_TOKEN` 缺失 fail-fast，模型密钥允许空默认仅功能不可用）、token 恒定时间比较、模型 API Key **RSA 加密入库 + 页面掩码回显**、**对外 API Key 只存 SHA-256 哈希 + 前 8 位前缀**（明文仅签发时返回一次，权限固定问答链路）、图片访问 HMAC 签名 URL（`AI_IMAGES_AUTH_ENABLED=true`）、统一异常+参数校验（`@Valid`）、错误信息不泄露内部细节、**上传魔数校验**（文件头字节须与扩展名匹配，docx/xlsx=PK、pdf=%PDF，防伪造扩展名）、**接口限流**（问答/上传按用户/IP 固定窗口限频，超限 429，Redis 不可用自动放行）、**内置计算器不执行任意代码**（递归下降自实现，仅 `+ - * / % ^` 与括号）、**产物交付文件名白名单净化**（`artifacts/` 静态映射前做扩展名白名单 + 名净化）、**管理操作审计**（上传/删除/批量删除/回滚记录操作者 `[AUDIT]` 日志）
- **可靠性**：上传失败自动补偿清理（删向量+MySQL+图片）、脏解析记录清理、解析异步化（不阻塞上传）、**解析中删除文档立即中断**（内存标志 + 线程 interrupt + 阶段检查点，清理本次产物）、SSE 异步订阅支持停止生成、查询改写专用线程池（超时隔离 + daemon + PreDestroy 回收）
- **可配置**：**问答/视觉/向量三类模型跨厂商热切换**（网关地址/API Key/模型名/接口路径，API Key RSA 加密入库）+ 温度/System Prompt 角色段/视觉提示词/检索权重与行为参数/重排区间/解析并发/上下文参数/关联扩散参数/限频/语义缓存/推荐问题池 **数据库存储、保存即生效**（`c_ai_config`，存量升级自动补默认项；检索 topK/向量阈值/关键词上限/重排区间等键支持在线修改，检索评估"应用此组"即写入这些键）；prompt 调整无需重启；检索/重排/解析/问答/关联扩散 5 组 30+ 项行为参数收口配置化（原硬编码移除），另有 `tool.*`/`skill.*`/`agent.*`/`mcp.*` 四组能力开关；**危险配置有前置护栏**——切 Meilisearch 先探可用性、切向量模型先探可达性与维度合法性，探测失败一律拒绝保存而非存下坏配置
- **可观测性**：`/actuator/health` 健康检查、日志级别环境变量化、MyBatis 日志走 slf4j、检索调试 API、Swagger UI 接口文档（springdoc 自动生成，随代码实时更新）；**降级提示统一开关**（fail-loud：所有回答降级事件——无命中/改写失败/图片剔除/未标注引用/缓存命中——默认不展示，全部写 `[FAIL-LOUD]` 日志；排障时开 `chat.showDebugDegradations` 才在回答下方显示）
- **多用户与部署**：**会话按用户隔离**（网关透传 `X-User-Id`，列表/历史/删除/清空均校验归属；anonymous 为存量兼容池）、multi-stage Dockerfile（非 root 运行 + HEALTHCHECK + `JAVA_OPTS` 内存注入）、docker-compose（redis-stack + meilisearch + **内置 MySQL**，亦可经 `DB_HOST` 等指向外部 OceanBase）、nginx 参考配置（`deploy/nginx.conf`，SPA fallback + SSE 关缓冲 + 图片缓存）

## 后台定时任务

全部周期任务集中在 `ScheduleCenter`（**无 `@Scheduled`**）：单 daemon 线程按 **10s 节拍**轮询"是否到点"，间隔每次实时读配置（改配置即时生效、≤0 暂停），任务体提交线程池执行（慢任务不占调度线程），上一轮未结束则跳过（防重叠），失败仅告警并下轮重试；最小间隔护栏 1 分钟。

| 任务 | 间隔键（默认） | 启动即跑 | 做什么 |
|------|----------------|----------|--------|
| 关键词索引精确对账 | `keyword.reconcileIntervalMs`(1h) | `keyword.reconcileOnStartup`(true) | 按 `(id, contentHash)` 双向比对 MySQL 有效块与 Meilisearch 文档，定向修复漂移 |
| 聊天图片目录清理 | `images.chatCleanupIntervalMs`(1d) | 否 | 删除超过 `images.chatRetentionMillis`（7 天）的用户聊天图片 |
| 检索评估自动体检 | `eval.autoIntervalMs`(1d) | 否 | 按线上参数跑评估集并与上期对比，结论落 `eval.lastReport`（评估集为空自动跳过；退化判定阈值 `eval.autoThresholdPct`=10%） |
| 配置缓存兜底刷新 | 固定 5 分钟 | 否 | `ConfigService.reload()`——补齐 Redis 订阅断线期间错过的配置变更 |
| 过期会话/消息清理 | `cleanup.sessionCleanupIntervalMs`(1d) | 否 | 物理删除逻辑删除超过 `sessionRetentionDays`（30 天）的会话与消息（保留期即"撤销删除"窗口） |

## 配置说明

关键配置项（`application.yml`，完整默认值见 [AiAppProperties.java](src/main/java/com/wisesoft/ai/config/AiAppProperties.java)）：

```yaml
ai-app:
  chunk:
    max-size: 800
    overlap: 100                           # 分块重叠（只进向量化文本，不入库、不进指纹）
    max-chunks: 3000                       # 单文档最大知识块数（0=不限制，防超大文档 embedding 数万次）
    structural: true                       # 结构感知切分（docx，需重解析生效）
    structural-ratio: 0.8
    heading-depth: 4                       # 章节标题识别上限层级 1~6（需重解析生效）
    # max-images: 100                      # 单文档最多提取图片数（0=不限制；代码默认 100，设置页可改）
  retrieval:
    vector-weight: 0.6                     # 混合检索：向量权重
    keyword-weight: 0.4                    # 混合检索：关键词权重
    title-bonus: 0.1                       # 混合检索：标题命中奖励
    rerank:                                # 重排（独立 reranker 服务，OpenAI 兼容 /v1/rerank）
      enabled: ${AI_RERANK_ENABLED:false}
      base-url: ${AI_RERANK_BASE_URL:http://localhost:7997}
      model: ${AI_RERANK_MODEL:BAAI/bge-reranker-v2-m3}
      timeout-ms: 5000
  keyword:                                 # 关键词召回引擎（mysql=LIKE 零依赖；meilisearch=中文分词+相关度）
    engine: ${AI_KEYWORD_ENGINE:mysql}     # 切 meilisearch 后需先 reindex；不可用时自动降级回 mysql
    base-url: ${AI_MEILI_BASE_URL:http://localhost:7700}
    api-key: ${AI_MEILI_KEY:}              # 仅 yml/env，密钥不入库
    index: ${AI_MEILI_INDEX:ai-doc-chunks} # 索引名只从 yml/env 读，永不入库
    timeout-millis: 1000                   # 辅助召回，超时即降级，不宜过大
  ratelimit:                               # 接口限流（Redis 固定窗口，按用户/IP；Redis 不可用自动放行）
    enabled: ${AI_RATELIMIT_ENABLED:true}
    chat-per-minute: ${AI_RATELIMIT_CHAT:10}
    upload-per-minute: ${AI_RATELIMIT_UPLOAD:10}
  context:                                 # 上下文与长度控制（设置页可调，保存即生效）
    model-windows: "qwen-plus=131072,qwen3=131072,qwen-max=32768,deepseek=65536,default=32768"  # 模型窗口映射（按 chat.model 子串匹配，未匹配用 default）
    default-window-tokens: 32768
    safety-factor: 0.7                     # 窗口安全系数（预算 = 窗口×系数 − 输出）
    cost-cap-tokens: 8000                  # 成本软上限（0=不限制）
    max-output-tokens: 2000                # 输出限制
    history-max-tokens: 1200               # 历史注入上限
    history-per-msg-chars: 200             # 单条历史截断
    snippet-window-chars: 150              # 知识块命中片段窗口（0=整块）
    max-context-hits: 8                    # 知识块填充上限
    # dedup-enabled: true                  # 信息增益去冗余：跳过与已选块语义重复的候选（防重复内容进上下文）
    # dedup-threshold: 0.45                # 去冗余词元重叠阈值（越高越宽松）
    # dedup-path-threshold: 0.28           # 同章节路径下去冗余阈值（同章节切片更易剪）
  session:
    max-history: 10
    expire-minutes: 30
    anonymous-shared: ${AI_SESSION_ANONYMOUS_SHARED:true}  # anonymous 历史兼容池对具名用户共享可见；false=仅 anonymous（无 X-User-Id）调用方可访问（收紧越权面）
  images:
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（默认 ./data 跨平台兜底；容器内由 AI_IMAGES_DIR 指定为 /app/data）
    max-width: 1280                         # 识别用压缩图最长边（qwen3-vl 最佳清晰度档，视觉 token 约 1600-2500；展示用原图不受限）
    quality: 0.9                            # JPEG 压缩质量（识别用；带透明通道自动转 PNG）
    url-prefix: /ai/images
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:true}   # 图片访问鉴权默认开启（防漏配裸奔；本地调试可置 false）
    auth-expire-seconds: 3600
    image-filter:                          # 回答 [图片N] 相关性校验（防 LLM 错配）
      enabled: ${AI_IMAGE_FILTER_ENABLED:true}
      min-hits: 1                          # 描述 2 字窗口命中数阈值（宁漏检勿误杀）
      pre-context-chars: 100               # 取标记前文最大字符数
  vision:
    model: ${AI_VISION_MODEL:qwen3-vl:2b}  # 本地 Ollama（不含 /v1，代码自动拼）
    base-url: ${AI_VISION_BASE_URL:http://localhost:11434}
    api-key: ollama                        # Ollama 不校验密钥，占位值
    enabled: true
    timeout-millis: 180000                 # 单张描述超时（图多排队+推理）
    concurrency: 2                         # 并发度（4GB 显存建议 2；Ollama 需 OLLAMA_NUM_PARALLEL 配合）
    retry-count: 1                         # 单图失败重试
    keep-alive-minutes: 30                 # 模型常驻内存（云端服务需设 0）
    think: ${AI_VISION_THINK:false}        # 关闭 qwen3 思考（实测 max_tokens 会导致空输出，勿加）
    num-ctx: 16384                         # Ollama 上下文窗口（1280px 图视觉 token 1600-2500，默认 4096 会截断；0=不设置）
  query-rewrite:                           # 查询改写（默认开启，保存即生效）
    enabled: ${AI_QUERY_REWRITE_ENABLED:true}
    timeout-millis: 5000
    # history-rounds(=2) / prompt / prompt-multi-turn 为代码默认值，DB 也可覆盖
  intent:                                  # 意图分类（默认关闭）
    enabled: ${AI_INTENT_CLASSIFY_ENABLED:false}
    timeout-millis: 3000                   # 分类只输出一个单词；超时按 doc 处理
    # model（留空回落 chat.model）/ prompt / chatPrompt 在 DB
  deep-reasoning:                          # 深度思考
    enabled: ${AI_DEEP_REASONING_ENABLED:true}
    thinking-mode: ${AI_DEEP_REASONING_MODE:model}   # model=extraBody 透传 enable_thinking 取 reasoning_content / prompt=提示词引导写 content
    enable-thinking: true
    search-tag: search                     # 检索计划标签 <search>query|子问题1|子问题2</search>
    max-sub-queries: 3
    multi-retrieval: true                  # 多路并行检索
    timeout-millis: 30000                  # 超时用已收集内容降级
    max-thinking-tokens: 0                 # 0=不设（规避 qwen 思考模式 max_tokens 空输出）
    # 细节键在 DB：maxThinkingChars(3000) / injectThinking(true) / injectThinkingMaxChars(800)
    #              injectKeywords(true) / injectKeywordsMax(5) / autoRoute(false) 及 autoRoute* 阈值
  system-prompt: "你是\"问渠\"..."          # 回答角色段默认值（DB chat.systemPrompt 可覆盖，保存即生效）
  trusted-token: ${AI_TRUSTED_TOKEN}       # 无默认值，缺失 fail-fast
  admin-users: ${AI_ADMIN_USERS:}          # 管理员白名单（逗号分隔 X-User-Id；"*"=全员管理员）
  admin-token: ${AI_ADMIN_TOKEN:}          # 管理员口令（X-Admin-Token；无网关本地部署与前端 VITE_ADMIN_TOKEN 一致）
  schema-auto-index: true                  # SchemaMigrator 启动时自动补索引（大表可设 false 由运维窗口期手工执行）

spring:
  servlet.multipart: { max-file-size: 1024MB, max-request-size: 1100MB }  # 物理上限 1GB；业务上限由 c_ai_config upload.maxFileSize 控制（默认 200MB，设置页可调）
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }
  ai.openai:
    api-key: ${AI_CHAT_KEY}
    base-url: <MaaS 网关 /compatible-mode> # 不含 /v1（Spring AI 自动补）
    chat: { options: { model: qwen3.8-27b, temperature: 0.3 } }
    embedding: { base-url: ... , options: { model: qwen3.7-text-embedding-flash } }
  ai.vectorstore.redis: { initialize-schema: true, index-name: ai-doc-index, prefix: "ai:chunk:" }  # Spring AI 1.1 起属性为 index-name；initialize-schema 必须 true，否则全量重嵌入被护栏拒绝执行（DROP 后无法重建索引）
```

> **模型配置以 DB 为准**：`spring.ai.openai.*`（问答）与 `spring.ai.openai.embedding.*`（向量）仅作 `c_ai_config` 未配置时的**回退默认值**；设置页保存后一律以 DB 为准，改 yml 不再生效。

### 仅存 DB 的配置键（`c_ai_config`）

上面 `ai-app.*` 只是**能被 yml/env 覆盖的那部分**；实际大量参数只存在于 `c_ai_config`（由 `ConfigService.defaults()` 灌默认值、`EDITABLE` 白名单控制可改性，约 130 个键），**不写 yml 也能用，保存即生效**。按前缀列主要项（括号内为默认值）：

| 前缀 | 主要键（默认值） | 生效方式 |
|------|------------------|----------|
| `chat.*` | `model`(qwen3.8-27b)、`temperature`(0.3)、`baseUrl`/`apiKey`/`completionsPath`(=/v1/chat/completions)、`systemPrompt`、`historyRounds`(5)、`pipelineThreads`(8)、`sseTimeoutMs`(300000)、`streamRetryCount`(1)、`remainTokenFloor`(800)、`truncateFallbackChars`(200)、`citationCheckEnabled`(true)、`maxImagesPerMessage`(9)、`maxImageMb`(10)、`showDebugDegradations`(false)、`retrievalDebugEnabled`(false)、`suggestedQuestions` | 保存即生效（模型四要素由 `DynamicOpenAiChatModel` 按配置指纹重建客户端） |
| `embedding.*` | `model`/`baseUrl`/`apiKey`/`embeddingsPath`(=/v1/embeddings) | **保存即触发全量重嵌入**（先真实探测维度，通过才 DROP 索引重建；期间向量路降级关键词） |
| `retrieval.*` | `vecThreshold`(0.3)、`vectorTopK`(15)、`keywordLimit`(20)、`keywordMaxTerms`(6)、`keywordMaxTotal`(12)、`fusionMode`(sum)、`positionBonus`(0.03)、`sectionBonus`(0.01)、`strength`(balanced)、`searchTimeoutMs`(8000)、`keywordTimeoutMs`(800)、`rewriteTimeoutMs`(5000)、`rewriteFallbackMinHits`(2)、`rewriteFallbackWeakScore`(0.2)、`maxRefsPerBlock`(8)、`relatedCount`(3)、`refDetectEnabled`(true)、`refDetectMention`(true)、`refExpand*`（扩散开关 / 上限 3 块·800 token / 父章节 summary×2 级·200 字 / 弱匹配，共 9 项） | 多数保存即生效；`refDetectEnabled`/`refDetectMention` **需重解析** |
| `chunk.*` | `maxSize`(800)、`headingDepth`(4)、`structural`(true)、`structuralRatio`(0.8)、`maxChunks`(3000)、`maxImages`(100) | **需重解析** |
| `parse.*` | `concurrency`(2)、`embedBatchSize`(10)、`embedRetryCount`(1)、`ocrMinText`(20)、`ocrDpi`(200)、`recoverStuckOnStartup`(true) | 对后续解析生效；`recoverStuckOnStartup` 多副本应置 false |
| `vision.*` | `prompt`、`concurrency`(2)、`userImageConcurrency`(2)、`retryCount`(1)、`think`(false)、`keepAliveMinutes`(30)、`numCtx`(16384)、`descCacheVersion`(1)、`descCacheTtlDays`(180) | 保存即生效；`descCacheVersion` bump 触发全量重新描述；**`vision.timeoutMillis` 需重启**（DB 默认 30000，yml 为 180000） |
| `context.*` | `dedupEnabled`(true)、`dedupThreshold`(0.45)、`dedupPathThreshold`(0.28)（其余上下文参数同上方 yml 值） | 保存即生效 |
| `atRef.*` | `maxChunksPerDoc`(3)、`maxTotal`(6) | 保存即生效 |
| `rerank.*` | `minHits`(6)、`maxHits`(15)、`failCooldownMs`(60000) | 保存即生效 |
| `keyword.*` | `failCooldownMs`(60000)、`reconcileOnStartup`(true)、`reconcileIntervalMs`(3600000) | 保存即生效；`reconcileOnStartup` 多副本应置 false |
| `semanticCache.*` | `enabled`(true)、`threshold`(0.96)、`maxEntries`(500) | 保存即生效 |
| `eval.*` | `judgeEnabled`(false)、`judgeModel`(空=回落 `chat.model`)、`autoIntervalMs`(86400000)、`autoThresholdPct`(10) | 保存即生效 |
| `cleanup.*` | `sessionRetentionDays`(30)、`sessionCleanupIntervalMs`(86400000) | 保存即生效（≤0 停用） |
| `images.*` | `maxWidth`(1280)、`quality`(0.9)、`authEnabled`(Java/DB 默认 false，**yml/env 默认 true**)、`authExpireSeconds`(3600)、`chatRetentionMillis`(604800000=7 天)、`chatCleanupIntervalMs`(86400000) | 保存即生效（`dir`/`urlPrefix` 仅 yml） |
| `upload.*` | `maxFileSize`(209715200=200MB) | 保存即生效 |
| `cache.*` | `docMetaTtlSeconds`(600) | 保存即生效 |
| `session.*` | `maxHistory`(10)、`expireMinutes`(30)、`anonymousShared`(true) | 保存即生效 |
| `queryRewrite.*` / `imageFilter.*` / `intent.*` / `ratelimit.*` | 与上方 yml 同值；另有 `imageFilter.enabled/minHits/preContextChars`、`intent.model/prompt/chatPrompt`、`ratelimit.windowSeconds`(60) | 保存即生效 |
| `tool.*` | `enabled`(**false**)、`knowledgeRetrieval.enabled`(false)/`maxHits`(5)、`artifact.enabled`(false)、`builtin.enabled`(false) | 保存即生效——**默认全关，需显式开启** |
| `skill.*` | `enabled`(**false**)、`dir`(./data/skills)、`injectEnabled`(true)、`toolEnabled`(true)、`injectMaxChars`(1200)、`maxFileChars`(20000)、`disabledNames`(系统写入) | 保存即生效 |
| `agent.*` | `enabled`(**false**)、`subAgents`(2，范围 2~4)、`topKPerAgent`(3)、`digestEnabled`(true) | 保存即生效 |
| `mcp.*` | `enabled`(**false**)、`servers`(JSON 数组 `[{name,url,type}]`，type=streamable\|sse) | 保存后需 `POST /api/ai/mcp/reload` 重连 |

> **只读 / 特殊项**：`embedding.dimensions`（当前向量索引维度，重嵌入成功后由系统回写，设置页只读、不在白名单）；`keyword.index` 永不入库（只从 yml/env 读）；密钥类 `chat.apiKey`/`vision.apiKey`/`embedding.apiKey`/`keyword.apiKey` **写入即 RSA 加密**，页面仅回显 `****后4位`，掩码原样提交不会覆盖真实 key（存量明文密钥启动自动迁移为密文）。

> **System Prompt 外置边界**：仅"角色与回答风格"段可编辑（设置页）；引用 `[N]` / 图片 `[图片N]` / 追问 `<related>` 规则与后端解析器强耦合，保留代码固定，避免改坏导致解析失效。

## 已知注意事项

- **智能体 / 工具 / 技能 / MCP 默认全关**：`agent.enabled`、`tool.enabled`（含 `knowledgeRetrieval`/`artifact`/`builtin` 三个子开关）、`skill.enabled`、`mcp.enabled` 默认均为 false——多智能体编排、工具调用、技能包、MCP 外部工具这些能力**开箱不生效**，需在设置页显式开启（子开关还需与总开关同时开启）。开启工具后模型可能调用工具，调用过程与失败均经 `tool_status` 事件可观
- **需重解析才生效的配置**：`chunk.maxSize` / `chunk.headingDepth` / `chunk.structural` / `chunk.structuralRatio`、`retrieval.refDetectEnabled` / `retrieval.refDetectMention`、`vision.descCacheVersion`。只改配置不重解析，存量知识块与引用边不会重建（`refExpand*` 扩散类参数保存即生效，无需重解析）
- **`vision.timeoutMillis` 三处默认值不一致，且改动需重启**：`application.yml` 为 `180000`（实际生效），Java 默认 `30000`、DB 默认 `30000`；视觉客户端在启动时构建，**该键需重启才生效**（其余 `vision.*` 键保存即生效）
- **`.nvmrc` 固定 Node 18.19.0**：前端以仓库 `.nvmrc` 为准（Node ≥18 均可构建；早前文档写的 22 是错的）
- **聊天模型**：当前使用 `qwen3.8-27b`。该 MaaS 网关对部分模型（如 `qwen-max`）返回 DashScope 原生格式（`{"text":...}`），Spring AI 无法解析（表现为 0 token 无回答）；需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- **结构切分/分词升级需重解析**：jieba 分词（关键词路即时生效）与结构感知切分（docx）需对存量文档**重解析**才重建知识块；切分后 `c_ai_message.sources` 的 knowledgeId 失效，**评估集需重新生成**（检索评估页"从历史问答重新生成"）
- **引用识别需重解析**：交叉引用/提及识别在解析时建立 `c_ai_knowledge_ref`，修改 `refDetectEnabled/refDetectMention` 后需重解析；引用扩散/父章节带出（`refExpand*`）保存即生效
- **无编号标题文档的编号引用**：正文标题不带编号（WPS 自动编号只在目录）的文档，"见 4.1.2 节"这类编号引用匹配不到正文标题，走章节名匹配或丢弃（当前实现边界）；标题文本自带编号（如"4.1.2 数值Api类型"）的文档编号引用可精确命中
- **切换向量模型必然触发全量重嵌入**：向量跨模型不可迁移，改 `embedding.*` 保存后会 DROP 向量索引并按新维度重建、全库重算向量，**耗时与知识块数和 embedding 吞吐成正比**（万级块可达数十分钟），期间向量检索降级关键词路（召回质量下降但服务不中断）、语义缓存被清空。因此**避免在业务高峰切换**；切换前确认 `spring.ai.vectorstore.redis.initialize-schema=true`（否则护栏直接拒绝执行）；完成后核对设置页"索引内块数"与成功写入块数是否一致，并抽查几个问题验证召回质量
- **重嵌入与并发解析撞车会丢块**：DROP 索引的瞬间若有文档正在解析写向量，那批向量会落进已删除的索引。任务末尾的**索引对账**（`FT.INFO num_docs` vs 成功写入数）会暴露差异并告警，**解析空闲时再手动触发一次即可补齐**（重嵌是幂等重建操作，按块 id 覆盖写入）。多副本部署时由保存配置的实例单点执行，其余副本重建客户端后与新索引自然对齐
- **多副本重嵌入期间的维度窗口**：Redis 索引共享，重建 schema 与各副本重建 embedding 客户端之间存在秒级窗口，个别请求可能以旧维度向量查新索引 → 该次向量召回失败并降级关键词路（`HybridRetrievalService` 已捕获标记 `vectorFailed`），不会返回错误结果
- **base-url 不含 `/v1`**：Spring AI 与 VisionService 都会自动补 `/v1`；视觉 base-url 以 `/v1` 结尾时也不会重复拼接
- **Spring AI 版本**：1.1.8 起 starter 更名（`spring-ai-starter-model-openai` / `spring-ai-starter-vector-store-redis`），由 `spring-ai-bom` 统一管理；RedisVectorStore 配置属性 `index` → `index-name`，`initialize-schema` 默认 false 需显式开启。**升级 1.1 后旧向量数据建议重新上传/重解析文档**（序列化结构可能变化）
- **图片访问路径**：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时需去掉 `/ai` 前缀（vite 代理 target 已含 context-path），否则双重 `/ai` 404
- **图片描述失败降级**：视觉模型调用失败时图片仍会提取保存，描述降级为 `[图片]` 占位，不影响上传与问答；此时回答图片相关性校验对无描述图自动放行
- **存量库升级**：`c_ai_config.config_value` 需为 TEXT（容纳长 prompt）；**schema 演进自动补列**——启动时 `SchemaMigrator` 解析 schema.sql 与 information_schema 比对，缺失列自动 ALTER 补上（幂等，失败仅告警不阻塞启动；新表 `c_ai_document_version` 仍由启动自动创建）；`c_ai_document.category` 字段保留（前端分类 UI 已移除，存量值已清空）
- **视觉模型思考模式**：qwen3 系列 `max_tokens` 限制会导致内容为空（思考耗尽 token），VisionService 不发送 max_tokens、改用 `think: false`
- **多图描述性能**：图片描述耗时与并发强相关，Ollama 需设 `OLLAMA_NUM_PARALLEL` 才能真正并行；文档重传会重新生成全部图片描述（77 图约 5-10 分钟）
- **解析增强需重解析存量文档**：Excel 公式求值/日期按格式输出、CSV 引号感知解析、docx 文本框旁注提取均只对**重新解析**的文档生效（解析后 `sources` 的 knowledgeId 不变、无需重生成评估集，但块内容会变，建议顺手跑一次自动体检）
- **向量阈值行为变化**：`retrieval.vecThreshold` 现为唯一真源（原受 yml `similarity-threshold: 0.5` 上限钳制）。若此前把阈值配到 0.5 以上而当时未生效，升级后**会真实生效**——召回更严格（recall 可能下降、准确率上升），请用检索评估页确认是否符合预期；0.5 及以下无行为变化
- **rrf 融合为实验模式**：`retrieval.fusionMode=rrf`（倒数排名融合，忽略标题/位置奖励的分值加分）保存即生效，建议先用检索评估页"对比调优"验证对 recall/命中率的影响再决定是否留用；默认 `sum` 与历史行为一致
- **过期会话/消息自动清理**：默认每日物理清除逻辑删除超过 30 天的会话与消息（间隔/保留期经 `cleanup.sessionCleanupIntervalMs`、`cleanup.sessionRetentionDays` DB 键调整，≤0 停用）。**保留期即"撤销删除"窗口**——被清理的消息无法再撤销。存量大库建议手工补索引 `ALTER TABLE c_ai_message ADD KEY idx_deleted_create (deleted, create_time)`（新库已内置），否则清理首轮会全表扫描
- **图片描述含 ASCII 方括号自动转全角**：描述写入 `[图片：…]` 标记时 `[]` 统一替换为全角 `［］`（标记内不允许出现半角 ]，否则下游正则截断错配）；对中文描述几乎无感知
