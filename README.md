# 问渠 WenQu · AI 文档问答助手

> **问渠那得清如许，为有源头活水来。** —— 答案，自有源头。
>
> 中文名：**问渠** ｜ 英文名：**WenQu** ｜ 英文 slogan：*Ask the source.*

独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答。支持 Word/PDF/Excel/TXT/Markdown 文档解析（含扫描 PDF OCR、大文件流式解析）、混合检索 + 查询改写、**知识块关联检索（交叉引用 1-hop 扩散 + 父章节上下文带出）**、价值驱动上下文控制、**语义缓存加速**、回答中位置级展示文档原图（识别用压缩图、展示用原图的双图策略 + 相关性预筛防错配）、解析进度实时展示（含图片识别逐张进度）、引用溯源与**检索状态行（来源可点击弹原文）**、**差评回流闭环**、会话管理（搜索/置顶/收藏/按组删除）、文档版本管理、数据看板与知识缺口闭环、检索量化评估（一键体检 + 预设参数对比 + 一键应用），是面向"操作手册问答"场景的完整智能助手。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8 |
| ORM | MyBatis-Plus 3.5.12 |
| 数据库 | OceanBase（MySQL 协议，库 `ai_doc_assistant`，可按环境调整） |
| 向量库 | Redis Stack（RediSearch，docker 映射端口 **6380**，Jedis 客户端） |
| LLM | 阿里云 MaaS 网关（OpenAI 兼容：chat=`qwen3.8-27b`，embedding=`qwen3.7-text-embedding-flash`，base-url 不含 `/v1`）；图片理解/OCR 走本地 Ollama `qwen3-vl:2b` |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ 原生流（txt/md/csv）+ jieba-analysis 1.0.2（中文分词） |
| 前端 | Vue 3 + Vite 5 + Ant Design Vue 4 + markdown-it/DOMPurify/highlight.js（Node ≥ 18，建议 20/22） |

## 目录结构

```
ai-doc-assistant/
├── pom.xml                          # 后端 Maven 项目（spring-ai-bom 统一管理版本）
├── src/main/java/.../ai/
│   ├── AiApplication.java           # 入口
│   ├── config/                      # AiAppProperties / SecurityConfig / ImageWebConfig / GlobalExceptionHandler / ImageAuthInterceptor
│   ├── controller/                  # Chat(SSE+会话+消息组) / Document / Qa(反馈+看板) / Config(模型配置) / AiKnowledge(知识块+缺口回流) / RetrievalDebug(检索调试) / Evaluation(检索评估) / AnswerCache(语义缓存) / SearchIndex(关键词索引)
│   ├── service/                     # RagService / HybridRetrievalService / RerankService / KeywordExtractor / ImageFilterService
│   │                                # KnowledgeRefService（引用识别+关联扩散）/ DocumentService / VisionService / UserImageService / SessionService / QaLogService / ConfigService / DocumentMetaCache / RateLimitService(限流) / AnswerCacheService(语义缓存) / RetrievalEvaluationService(检索评估) ...
│   ├── parser/                      # DocumentParser 接口 + DocxParser / PdfParser(OCR) / ExcelParser / TextParser(txt/md/csv)
│   ├── util/                        # TokenCounter（分语言 token 估算，上下文预算用）
│   ├── model/ + mapper/ + dto/      # 实体（含 AiDocumentVersion）/ MyBatis-Plus Mapper / 传输对象
├── src/main/resources/
├── config/
│   └── application-local.yml        # 本地开发私有配置（含密钥/数据目录，.gitignore 忽略；位于 Spring Boot 外部配置目录，不打进构建产物）
├── src/main/resources/
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD/AI_TRUSTED_TOKEN 缺失 fail-fast）
│   └── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/{docId}/ 提取图 + images/chat/ 用户图
├── deploy/nginx.conf                # 生产 nginx 参考配置
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 22）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    └── src/views/                   # Chat(智能问答) / Documents(文档管理) / Dashboard(数据看板) / Settings(系统设置) / Evaluation(检索评估)
```

## 启动方式

### 1. 环境准备

**Redis Stack**（docker，端口映射 6380）：
```bash
docker run -d --name redis-stack -p 6380:6379 redis/redis-stack-server:latest
```
> RedisVectorStore 自动配置使用 **Jedis** 客户端，项目已引入 `redis.clients:jedis` 且 `spring.data.redis.client-type: jedis`。

**本地 Ollama**（图片描述 / 扫描 PDF OCR）：安装 Ollama 并拉取视觉模型：
```bash
ollama pull qwen3-vl:2b
```
> 建议设置环境变量 `OLLAMA_NUM_PARALLEL=4`（否则多图描述串行排队）；4GB 显存机器并发度建议 `vision.concurrency=2`。

**数据库**：现有 OceanBase 库 `ai_doc_assistant`（库需预先存在，库名按实际环境配置）。表结构（`c_ai_document`/`c_ai_knowledge`/`c_ai_session`/`c_ai_message`/`c_ai_qa_log`/`c_ai_qa_feedback`/`c_ai_config`/`c_ai_document_version`/`c_ai_knowledge_ref`/`c_ai_answer_cache`）由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全）；也可手动执行：
```bash
mysql -h172.168.10.65 -P2881 -uroot -p ai_doc_assistant < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码
export AI_TRUSTED_TOKEN=xxx               # 内部鉴权 token（与平台网关一致，SecurityConfig 校验）

# ===== 可选 =====
export AI_DEEPSEEK_KEY=sk-xxxx            # chat 模型密钥（MaaS 网关；有默认空值，缺失不启动失败，但聊天不可用）
export DB_HOST=127.0.0.1                  # 数据库主机（容器部署默认 mysql；外部 OceanBase 改为实际地址）
export DB_PORT=3306                       # 数据库端口
export DB_NAME=ai_doc_assistant           # 库名
export DB_USERNAME=root                   # 用户名
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379               # Redis 端口（本机环境覆盖为 6380：docker 映射）
export AI_VISION_MODEL=qwen3-vl:2b        # 图片描述/OCR 模型（本地 Ollama）
export AI_VISION_BASE_URL=http://localhost:11434  # 视觉地址（不含 /v1，代码自动拼）
export AI_VISION_THINK=false              # 关闭 qwen3 思考模式（提速且输出稳定）
export AI_IMAGES_DIR=./data               # 数据落盘目录（跨平台兜底；生产容器内为 /app/data）
export AI_IMAGES_AUTH_ENABLED=true        # 图片访问鉴权（HMAC 签名 URL；默认已开启，本地开发在 config/application-local.yml 设 false）
export AI_QUERY_REWRITE_ENABLED=true      # 查询改写开关（默认开启）
export AI_IMAGE_FILTER_ENABLED=true       # 回答图片相关性校验开关（默认开启）
export AI_RATELIMIT_ENABLED=true          # 接口限流开关（Redis 固定窗口，按用户/IP；也可设置页改）
export AI_RATELIMIT_CHAT=10               # 问答限频：次/分钟/用户（0=不限）
export AI_RATELIMIT_UPLOAD=10             # 上传限频：次/分钟/用户（0=不限）
export LOG_LEVEL_APP=info                 # 应用日志级别
export AI_ADMIN_USERS=""                  # 管理员用户白名单（逗号分隔 X-User-Id；"*"=全员管理员，单机自用）
export AI_ADMIN_TOKEN=""                  # 管理员口令（请求头 X-Admin-Token；无网关/本地部署与前端 VITE_ADMIN_TOKEN 一致）
                                          # 两者任一命中即管理员；均未配置则管理端点默认 403（只问答可用的最小开放）
export SPRINGDOC_ENABLED=false            # Swagger/OpenAPI 开关（生产默认关，本地开发在 application-local.yml 已开启）
```

**本地开发**：无需 export，把真实值直接写入项目根 `config/application-local.yml`（私有文件，已加入 .gitignore；Spring Boot 自动从外部 `config/` 目录加载，**不打进构建产物**——密钥不会随 jar 分发；**数据目录 `ai-app.images.dir` 也在此配置**，Windows 机器改成 `D:/workspace/ai-doc-assistant/data` 即可，天然区分平台。该文件同时可关闭图片鉴权 `auth-enabled: false` 保持本地开发便利），然后以 `local` profile 启动（application.yml 已默认激活 local）：
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
接口文档（Swagger UI）：`http://localhost:8090/ai/swagger-ui/index.html`（springdoc 自动生成；Try-it-out 在线调试需在请求头携带 `X-Trusted-Token`）。**默认仅本地打开**：本地开发在 `config/application-local.yml` 已启用；生产 `SPRINGDOC_ENABLED` 缺省为 false（接口契约不外泄），如需临时开启 export `SPRINGDOC_ENABLED=true`。同理 docker-compose 默认仅把 8090 绑定到回环地址（`127.0.0.1`），对外直连需 `APP_PUBLISH=8090` 或由 nginx/网关注入鉴权。

### 4. 启动前端

```bash
cd web
nvm use            # 或使用 Node 20/22（.nvmrc 已固定 22）
npm install
npm run dev        # 访问 http://localhost:5800（端口被占直接报错，不会跳号）
```

Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 使用流程

1. **文档管理**：上传 `.docx` / `.pdf` / `.xlsx` / `.txt` / `.md` / `.csv`（默认 ≤200MB，可多文件 + **拖拽/粘贴上传**，全页拖拽高亮；上限在设置页调整，物理上限 1GB；**大文件流式解析**不整载内存）→ 异步解析（解析中状态轮询，进度条实时展示：图片识别阶段逐张显示"识别图片 k/total"）→ 生效/弃用/重解析/**批量重解析**/批量操作/命中次数统计；**解析中删除文档立即中断**（线程中断 + 产物自动清理，无孤儿数据）；点击"知识块"可预览该文档的分块与图片（支持**编辑/删除单个知识块**——编辑走 Markdown 工具栏 + 左写右看实时预览 + 图片点选插入 + 未保存关闭提醒，保存后自动重新向量化；**知识块级启停用**——停用块不参与召回；**跨文档全局搜索**——按关键词搜全部知识块）；**版本管理**（每次解析/重解析自动存快照，可查看历史版本并一键回滚）；**结构感知切分**（docx 默认开启：按标题层级开新块、段落边界断块、表格独立成块；**正文只存净内容**，章节路径 `【上下文】章节 > 小节` 独立存储，向量化与检索回答时再拼入上下文，需重解析生效）；**交叉引用识别**（解析时自动扫描块内"详见/参见/见 X 节/《章节名》/XX章节"等引用表达，建立块间引用关系 `c_ai_knowledge_ref`，需重解析生效）
2. **智能问答**：提问（支持上传图片+问题）→ 混合检索 + 查询改写 + **知识块关联扩散**（命中块自动带出被引用/父章节关联块）→ 流式回答，位置级插入文档原图、[N] 引用角标（点击弹窗看来源全文与图片，扩散块标注"关联引用/父章节上下文"）、末尾相关追问；**回答下方检索状态行**（"搜索 N 个关键词，参考 M 段资料"）展开后逐条列出来源与命中摘要，**点击条目弹原文**（与角标同弹窗）；检索阶段有进度提示（理解问题→检索中→生成回答）；头部"AI 回答可能有误"可点击查看完整免责声明；回答下方操作区（检索调试/重新生成/复制 Markdown/导出 .md/👍👎 反馈）；回答顶部可能展示降级提示（未命中/超时截断/深度思考降级/图片剔除等，调试级提示需设置页开启）；断连自动重试（内联提示）；**语义缓存**：与历史问题高度相似时直接复用回答（秒回）；**引用语义一致性自检**（回答生成后校验每条 `[N]` 是否被引用内容直接支撑，剔除“编号存在但语义不符”的引用并重编编号，提升引用可信度，可在设置页关闭）
3. **会话管理**：侧边栏搜索（标题/内容模糊匹配）、**置顶/收藏/重命名**（重命名为条目旁悬浮卡片、回车保存，置顶排最前，收藏 tab 筛选）、拖拽伸缩、删除/清空；**按消息组删除问答**（豆包式：进入多选模式，勾选回答自动勾选同组问题，支持撤销）+ 消息时间戳显示；**快捷键**：Esc 停止生成/清空输入、⌘/Ctrl+N 新建会话、⌘/Ctrl+K 聚焦会话搜索；输入区支持**拖入/粘贴发图**（与文档页一致）；生成中**上翻回看历史不被打断**（暂停自动滚动，可一键回底）
4. **数据看板**：统计卡片（问答量/满意率/引用率/无命中率，加载骨架不闪 0）+ 热门问题/无命中 TOP10 + **知识库缺口管理**（无命中问题一键入库，含向量召回）+ **差评样本回流**（看板还原差评问题与引用块，一键加入检索评估集）+ **检索质量自动体检**（定时按线上参数跑评估集并与上期对比，指标下滑即红黄灯预警，可手动触发；**评估集变更自动重置基线**——重新生成评估集后样本变化，本期记为新的基线，不再跨样本误报下滑）
5. **系统设置**：**问答/视觉/向量三类模型均可跨厂商热切换**（网关地址/API Key/模型名，API Key 以 RSA 加密入库）+ 温度/System Prompt（角色段）/检索权重与行为参数/重排区间/解析并发与结构切分/上下文参数，**保存即生效**（DB 存储，折叠分组展示，顶部**锚点导航**快速定位 + 滚动高亮，参数带 `?` 说明，**调试/排障用开关以橙色「调试」徽标区别**——检索调试入口、降级提示、体检 LLM 评判）；其中**向量模型切换会先探测新配置**（不可达/维度非法一律拒绝保存），通过后自动全量重嵌入，面板内展示进度/维度变化/耗时/索引对账并可手动重试；问答输入框左侧深度思考开关（图标按钮，localStorage 记忆）；深度思考增强：**思考链注入最终回答**（推理过程截断作参考注入生成 prompt）、**思考关键词增强检索**（从思考全文提取词元补充召回，失败降级也复用）、**思考长度护栏**（默认 3000 字截断保留）、思考完成自动折叠、**自动路由**（autoRoute 可开：长问/多条件/对比类自动启用）
6. **检索评估**（顶栏入口）：评估集从历史问答引用回放生成（问题→期望知识块，差评案例自动补充）→ **一键体检**（当前配置跑全量评估，红绿灯结论 + 落空/低分题目清单，零参数门槛）→ **对比调优**（+ 添加参数组选预设：关键词优先/向量优先/向量+重排/多路模拟，或自定义；空参数框灰色占位回显当前值）→ 结果表 **recall@k/MRR/命中率** 相对基线 ↑↓ 对比 + 自动结论，好的组**一键「应用此组」直接写入配置生效**（不污染评估，应用前可看逐问题明细）

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
- **会话**：MySQL + Redis 双层存储，历史恢复（含图片/引用来源/messageId/检索状态行）、删除/清空、**搜索/置顶/收藏**、侧边栏拖拽伸缩（宽度记忆）、**按消息组删除问答**（多选模式：勾回答默认勾同组问题，支持撤销）、消息时间戳、推荐问题池（`chat.suggestedQuestions`，设置页编辑 + 看板热门问题一键加入，欢迎页展示）
- **前端体验**：markdown-it + DOMPurify + highlight.js 安全渲染（代码块复制按钮、**长行自动折行 + 限高滚动**、**表格渲染容错**：LLM 在标题/列表后未留空行的表格自动补空行独立渲染、结尾孤立竖线清理）、重新生成/编辑重问（编辑图标悬浮气泡下方）、图片灯箱（**滚轮按幅度平滑缩放**：每 100 单位滚轮量 8%、单次 clamp ±30% 防惯性跳变）、发送后自动滚动到底部（不等首个 token）、问答 👍👎 反馈（**单选锁定**：评价后两按钮禁用不可再点，刷新/重进会话仍保持——历史消息回带既有评价）、断连自动重试内联提示、全局错误边界（渲染异常友好提示防白屏、401 统一提示）、图片加载失败占位图、上传进度条
- **深度思考（生产级）**：思考流式展示（`model` 透传 reasoning_content / `prompt` 引导双模式）→ 提取 `<search>` 检索计划（精化 query + 子问题）→ 多路并行检索合并 → 复用上下文构建与回答流。增强：**思考链注入最终回答**（`injectThinking` 默认开：推理过程截断后作为参考注入生成 prompt，明确"以参考资料为准"，让"想过的拆解"作用于"答"）；**思考关键词增强检索**（`injectKeywords` 默认开：从思考全文提取词元补充多路检索，思考失败时也用于增强降级检索，思考不白费）；**失败细化降级**（超时/异常时已收集内容若含检索计划仍走多路，否则"原问题 + 思考词元"增强检索）；**思考长度护栏**（`maxThinkingChars` 默认 3000：超限截断思考流并保留已想内容，防刷爆上下文/token）；**前端思考完成自动折叠**（避免超长思维链刷屏，想看再点开）；**自动路由**（`autoRoute` 默认关：长问 ≥25 字或含多条件/对比/递进词自动启用深度思考，保守启发式避免常见问题全量思考成本翻倍）。全部参数在设置页"深度思考设置"（含"思考增强·护栏与路由"子分组）

## API 一览

> 完整接口文档见 **Swagger UI**（启动后访问 `/ai/swagger-ui/index.html`，随代码自动更新；接口按"智能问答/文档管理/知识库/反馈与看板/系统配置/检索调试/检索评估/关键词索引/图片描述缓存"分组，Try-it-out 需携带 `X-Trusted-Token`）。下表为核心端点速查：

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（token/image/done/error 事件） |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}`、`PUT /api/ai/session/{id}/rename` | 会话列表（支持关键词搜索）/ 新建 / 历史恢复 / 重命名（≤50 字，校验归属） |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏（`{pinned:true}` 或 `{favorite:true}`） |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions` | 删除单会话（软删除会话+消息）/ 清空全部会话 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /api/ai/message-group/undo` | 按消息组删除问答（问题+回答）/ 撤销删除 |
| `GET /api/ai/suggested`、`POST /api/ai/suggested` | 推荐问题池读取 / 追加（去重上限 8 条） |
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
6. **权限模型（用户问答 / 管理员运维）**：普通用户仅开放问答链路——`/chat`、会话管理、反馈提交、引用溯源（GET 单个知识块）、`/config/public`、`/suggested`、`/auth/me`。**其余端点（文档上传/删除、模型与系统配置、评估、看板、检索调试、索引重建、语义缓存/图片描述缓存运维等）仅管理员可访问**（403，fail-closed）。管理员判定：网关透传的 `X-User-Id` ∈ `AI_ADMIN_USERS` 白名单，或请求携带 `X-Admin-Token` == `AI_ADMIN_TOKEN`。前端在页面右上角提供"管理员验证"入口（口令仅存本机 localStorage）；生产多用户建议用账号白名单而非共享口令
7. **普通用户 UI 收敛**：前端 `/documents`、`/dashboard`、`/evaluation`、`/settings` 路由带管理员守卫（`router.beforeEach` + `/auth/me`），非管理员菜单不展示、直达 URL 自动跳回智能问答

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

- **安全**：关键密钥零默认值（`DB_PASSWORD`/`AI_TRUSTED_TOKEN` 缺失 fail-fast，模型密钥允许空默认仅功能不可用）、token 恒定时间比较、图片访问 HMAC 签名 URL（`AI_IMAGES_AUTH_ENABLED=true`）、统一异常+参数校验（`@Valid`）、错误信息不泄露内部细节、**上传魔数校验**（文件头字节须与扩展名匹配，docx/xlsx=PK、pdf=%PDF，防伪造扩展名）、**接口限流**（问答/上传按用户/IP 固定窗口限频，超限 429，Redis 不可用自动放行）、**管理操作审计**（上传/删除/批量删除/回滚记录操作者 `[AUDIT]` 日志）
- **可靠性**：上传失败自动补偿清理（删向量+MySQL+图片）、脏解析记录清理、解析异步化（不阻塞上传）、**解析中删除文档立即中断**（内存标志 + 线程 interrupt + 阶段检查点，清理本次产物）、SSE 异步订阅支持停止生成、查询改写专用线程池（超时隔离 + daemon + PreDestroy 回收）
- **可配置**：**问答/视觉/向量三类模型跨厂商热切换**（网关地址/API Key/模型名/接口路径，API Key RSA 加密入库）+ 温度/System Prompt 角色段/视觉提示词/检索权重与行为参数/重排区间/解析并发/上下文参数/关联扩散参数/限频/语义缓存/推荐问题池 **数据库存储、保存即生效**（`c_ai_config`，存量升级自动补默认项；检索 topK/向量阈值/关键词上限/重排区间等键支持在线修改，检索评估"应用此组"即写入这些键）；prompt 调整无需重启；检索/重排/解析/问答/关联扩散 5 组 30+ 项行为参数收口配置化（原硬编码移除）；**危险配置有前置护栏**——切 Meilisearch 先探可用性、切向量模型先探可达性与维度合法性，探测失败一律拒绝保存而非存下坏配置
- **可观测性**：`/actuator/health` 健康检查、日志级别环境变量化、MyBatis 日志走 slf4j、检索调试 API、Swagger UI 接口文档（springdoc 自动生成，随代码实时更新）；**降级提示统一开关**（fail-loud：所有回答降级事件——无命中/改写失败/图片剔除/未标注引用/缓存命中——默认不展示，全部写 `[FAIL-LOUD]` 日志；排障时开 `chat.showDebugDegradations` 才在回答下方显示）
- **多用户与部署**：**会话按用户隔离**（网关透传 `X-User-Id`，列表/历史/删除/清空均校验归属；anonymous 为存量兼容池）、multi-stage Dockerfile（非 root 运行 + HEALTHCHECK + `JAVA_OPTS` 内存注入）、docker-compose（redis-stack + meilisearch + **内置 MySQL**，亦可经 `DB_HOST` 等指向外部 OceanBase）、nginx 参考配置（`deploy/nginx.conf`，SPA fallback + SSE 关缓冲 + 图片缓存）

## 配置说明

关键配置项（`application.yml`，完整默认值见 [AiAppProperties.java](src/main/java/com/wisesoft/ai/config/AiAppProperties.java)）：

```yaml
ai-app:
  chunk: { max-size: 800, overlap: 100, structural: true, structural-ratio: 0.8 }   # 分块(重叠仅进向量化文本) + 结构感知切分（docx，需重解析生效）
  retrieval:
    top-k: 5                               # 上下文用命中块数（重排候选另算）
    vector-weight: 0.6                     # 混合检索：向量权重（DB c_ai_config 可覆盖，设置页保存即生效）
    keyword-weight: 0.4                    # 混合检索：关键词权重
    title-bonus: 0.1                       # 混合检索：标题命中奖励
    # ---- 检索行为参数（DB c_ai_config 可覆盖，设置页保存即生效）----
    # vecThreshold: 0.3                    # 向量相似度下限/归一化基准（0~1，唯一真源，评估"应用此组"可写）
    # fusionMode: sum                      # 双路融合：sum=加权和(默认，含标题/位置奖励) / rrf=倒数排名融合(实验，按名次融合，用评估页对比验证)
    rewrite-timeout-ms: 5000               # 查询改写超时（DB 可覆盖：retrieval.rewriteTimeoutMs，设置页可调）
    # ---- 知识块关联检索（DB c_ai_config 可覆盖，设置页"知识块关联检索"小节） ----
    # ref-detect-enabled: true             # 解析时引用识别（改后需重解析）
    # ref-detect-mention: true             # 无动词章节提及识别（如 4.1.2 所述/《数据字典》/XX章节，仅精确匹配）
    # ref-expand-enabled: true             # 检索时关联扩散+父章节带出总开关（保存即生效）
    # ref-expand-max-hits: 3               # 扩散块数量上限
    # ref-expand-max-tokens: 800           # 扩散块 token 汇总上限
    # ref-expand-include-incoming: false   # 是否扩散入边（引用本块的块，默认关）
    # ref-expand-parent-enabled: true      # 命中子章节时带出父章节上下文
    # ref-expand-parent-mode: summary      # 父章节内容模式 title_only/summary/full
    # ref-expand-parent-max-levels: 2      # 父章节向上带出级数
    # ref-expand-parent-summary-chars: 200 # summary 模式截取字符数
    # ref-expand-fuzzy-name: true          # 章节名弱匹配（contains）开关
  context:                                 # 上下文与长度控制（设置页可调，保存即生效）
    model-windows: "qwen-plus=131072,qwen3=131072,deepseek=65536,..."  # 模型窗口映射（按 chat.model 子串匹配）
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
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（开发在 application-local.yml 配绝对路径；生产 /app/data）
    max-width: 1280                         # 识别用压缩图最长边（qwen3-vl 最佳清晰度档，视觉 token 约 1600-2500；展示用原图不受限）
    quality: 0.9                            # JPEG 压缩质量（识别用；带透明通道自动转 PNG）
    url-prefix: /ai/images
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:true}   # 图片访问鉴权默认开启（防漏配裸奔；本地开发在 config/application-local.yml 设 false）
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
  query-rewrite:                           # 查询改写（默认开启）
    enabled: ${AI_QUERY_REWRITE_ENABLED:true}
    timeout-millis: 5000
    # history-rounds / prompt / prompt-multi-turn 为代码默认值（AiAppProperties.QueryRewrite），yml 不覆盖
  system-prompt: "你是\"问渠\"..."          # 回答角色段默认值（DB c_ai_config 可覆盖，保存即生效）
  trusted-token: ${AI_TRUSTED_TOKEN}

spring:
  servlet.multipart: { max-file-size: 1024MB, max-request-size: 1100MB }  # 物理上限 1GB；业务上限由 c_ai_config upload.maxFileSize 控制（默认 200MB，设置页可调）
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }  # 本机环境经 REDIS_PORT=6380 覆盖
  ai.openai:
    api-key: ${AI_DEEPSEEK_KEY}
    base-url: <MaaS 网关 /compatible-mode> # 不含 /v1（Spring AI 自动补）
    chat: { options: { model: qwen3.8-27b, temperature: 0.3 } }
    embedding: { base-url: ... , options: { model: qwen3.7-text-embedding-flash } }
  ai.vectorstore.redis: { initialize-schema: true, index-name: ai-doc-index, prefix: "ai:chunk:" }  # Spring AI 1.1 起属性为 index-name；initialize-schema 必须 true，否则全量重嵌入被护栏拒绝执行（DROP 后无法重建索引）
```

> **模型配置以 DB 为准**：`spring.ai.openai.*`（问答）与 `spring.ai.openai.embedding.*`（向量）仅作 `c_ai_config` 未配置时的**回退默认值**；设置页保存后一律以 DB 为准，改 yml 不再生效。另有系统内部记录项 `embedding.dimensions`（当前向量索引维度，重嵌入成功后自动回写，设置页只读展示，不可手工修改）。检索质量相关 DB 键：`retrieval.vecThreshold`（向量相似度下限/归一化基准，唯一真源——0.5 以上区间真实生效，评估"应用此组"可写）、`retrieval.fusionMode`（双路融合 sum/rrf，实验）、`context.dedupEnabled/dedupThreshold/dedupPathThreshold`（信息增益去冗余）、`chat.citationCheckEnabled`（引用语义一致性自检）、`eval.judgeEnabled`（自动体检 LLM 评判，调试级）、`eval.judgeModel`（评判模型名，留空回落 `chat.model`）均已开放白名单与设置页（judgeModel 为 DB 只读项，直接写库生效）。深度思考增强键：`deepReasoning.injectThinking`（思考链注入最终回答，默认 true）/ `injectThinkingMaxChars`（注入长度上限 800）/ `injectKeywords`（思考关键词增强检索，默认 true）/ `injectKeywordsMax`（增强词元数 5）/ `maxThinkingChars`（思考流长度护栏 3000，0=不限）/ `autoRoute`（自动路由，默认 false）。过期数据清理：`cleanup.sessionRetentionDays`（软删会话/消息物理保留天数，默认 30，≤0 停用）、`cleanup.sessionCleanupIntervalMs`（默认每日）经 DB 写入生效。

> **System Prompt 外置边界**：仅"角色与回答风格"段可编辑（设置页）；引用 `[N]` / 图片 `[图片N]` / 追问 `<related>` 规则与后端解析器强耦合，保留代码固定，避免改坏导致解析失效。

## 已知注意事项

- **聊天模型**：当前使用 `qwen3.8-27b`。该 MaaS 网关对部分模型（如 `qwen-max`）返回 DashScope 原生格式（`{"text":...}`），Spring AI 无法解析（表现为 0 token 无回答）；需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- **结构切分/分词升级需重解析**：jieba 分词（关键词路即时生效）与结构感知切分（docx）需对存量文档**重解析**才重建知识块；切分后 `c_ai_message.sources` 的 knowledgeId 失效，**评估集需重新生成**（检索评估页"从历史问答重新生成"）
- **引用识别需重解析**：交叉引用/提及识别在解析时建立 `c_ai_knowledge_ref`，修改 `refDetectEnabled/refDetectMention` 后需重解析；引用扩散/父章节带出（`refExpand*`）保存即生效
- **无编号标题文档的编号引用**：正文标题不带编号（WPS 自动编号只在目录）的文档，"见 4.1.2 节"这类编号引用匹配不到正文标题，走章节名匹配或丢弃（V1 边界）；标题文本自带编号（如"4.1.2 数值Api类型"）的文档编号引用可精确命中
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
