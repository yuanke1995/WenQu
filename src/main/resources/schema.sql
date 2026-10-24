-- ============================================
-- 问渠 WenQu —— AI 智能体工作台 数据库表结构
-- 数据库: wenqu_ai（库名经 DB_NAME 按环境配置；已有存量库改名需数据迁移）
--
-- 存量库升级：无需手动执行 ALTER。启动时 SchemaMigrator 会解析本文件，
-- 自动为存量表补齐缺失的「列」与「索引」（幂等，失败仅告警不阻塞启动）；
-- 新表由 spring.sql.init 的 CREATE TABLE IF NOT EXISTS 创建。
-- 大表补索引耗时较久时，可用 ai-app.schema-auto-index=false 关闭索引自动补齐，改由运维在窗口期执行。
-- ============================================

-- 知识库：文档的容器，检索按库隔离、检索参数随库（对齐成熟同类产品的知识库模型）。
-- 一个知识库 = 一套检索作用域（含自己的检索参数）；文档归属某个库，智能体关联若干库。
CREATE TABLE IF NOT EXISTS `c_ai_knowledge_base` (
    -- 主键列为 kb_id：c_ai_document.kb_id 的外键指向它
    `kb_id`        VARCHAR(50)  NOT NULL COMMENT '知识库ID（c_ai_document.kb_id 的外键）',
    `name`         VARCHAR(200) NOT NULL COMMENT '知识库名称',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `icon`         VARCHAR(32)  DEFAULT NULL COMMENT '图标: wenqu=问渠品牌标 / emoji 字符; NULL=默认库图标（默认库恒为 wenqu 且不可修改，其余库空=默认库图标）',
    `query_params` TEXT         DEFAULT NULL COMMENT '检索参数(库级召回池: retrieval.vecThreshold/vectorWeight/keywordWeight + rerank.model; 空=继承全局)',
    `parse_params` TEXT         DEFAULT NULL COMMENT '解析参数(JSON: chunk.maxSize/overlap/maxChunks/maxImages/structural/structuralRatio/headingDepth + visionRef; 空=全部继承全局解析设置)',
    `embedding_ref` VARCHAR(255) DEFAULT NULL COMMENT '本库绑定向量模型（引用 providerId/modelId；必填，迁移工具会把历史空值回填为退役前的全局 embedding.model）',
    `embedding_dimensions` INT DEFAULT NULL COMMENT '本库向量索引维度（绑定/切换向量模型重嵌后回写）',
    `graph_enabled` INT DEFAULT 0 COMMENT 'GraphRAG 开关（库级，默认关）: 1=解析后抽实体关系三元组，检索时一跳图扩展',
    `graph_model_ref` VARCHAR(255) DEFAULT NULL COMMENT 'GraphRAG 抽取模型（引用 providerId/modelId；归库主，判权按库主可用；空=回落库主个人默认聊天模型，再空则抽取 fail-loud）',
    `is_default`   INT          DEFAULT 0 COMMENT '是否个人默认库: 1=该归属人的默认库（问渠，每人一张、系统懒创建；未指定库的文档兜底归属）',
    `builtin`      INT          DEFAULT 0 COMMENT '官方内置库: 1=系统随版本同步的内置知识库（如问渠使用手册；全员只读、不接受上传/编辑/删除，内容按源文件指纹增量重建）',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=私有：仅创建者与管理员级可见（谁建归谁，2026-10）)',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`kb_id`),
    KEY `idx_deleted_default` (`deleted`, `is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表（文档容器，检索按库隔离，检索参数随库）';

CREATE TABLE IF NOT EXISTS `c_ai_document` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `file_name`    VARCHAR(200) DEFAULT NULL COMMENT '文件名',
    `file_type`    VARCHAR(20)  DEFAULT NULL COMMENT '文件类型: docx/pdf/xlsx/url(url=网页导入,源文件为HTML快照)',
    `chunk_count`  INT          DEFAULT 0 COMMENT '分块数量',
    `status`       INT          DEFAULT 0 COMMENT '状态: 0=生效, 1=已弃用, 2=解析中, 3=解析失败',
    `fail_reason`  VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因(status=3)',
    `parse_progress` INT        DEFAULT 0 COMMENT '解析进度0-100',
    `parse_desc`   VARCHAR(64)  DEFAULT '' COMMENT '解析阶段描述',
    `file_size`    BIGINT       DEFAULT 0 COMMENT '文件大小(字节)',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '文档描述',
    `source_url`   VARCHAR(1024) DEFAULT NULL COMMENT '网页导入的源URL（file_type=url 时记录；普通上传为空）',
    `source_hash`  VARCHAR(64)  DEFAULT NULL COMMENT '源内容指纹(SHA-256 hex；官方内置库手册同步用：指纹不变跳过重建，普通上传为空)',
    `kb_id`        VARCHAR(50)  DEFAULT NULL COMMENT '所属知识库ID（必填；启动迁移会把历史空值归入默认库）',
    `vision_ref`   VARCHAR(255) DEFAULT NULL COMMENT '文档级视觉模型覆盖（上传时指定，引用 providerId/modelId；空=跟随知识库。对本文档所有图片理解生效）',
    `tags`         VARCHAR(500) DEFAULT NULL COMMENT '文档标签（逗号分隔，如 部门A,合同,2026；空=无标签。供智能体检索标签收窄与文档筛选用）',
    `version`      INT          DEFAULT 0 COMMENT '版本号（每次解析+1，用于版本管理）',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=私有：仅创建者与管理员级可见（谁建归谁，2026-10）)',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `auto_refresh`    INT          DEFAULT 0 COMMENT '网页源自动刷新: 0=关闭 1=开启（仅 file_type=url 生效）',
    `refresh_cron`    VARCHAR(60)  DEFAULT NULL COMMENT '自动刷新 cron（5段: 分 时 日 月 周，如 0 3 * * * 每日3点）',
    `last_refresh_at` DATETIME     DEFAULT NULL COMMENT '上次自动刷新时刻',
    `next_refresh_at` DATETIME     DEFAULT NULL COMMENT '下次自动刷新时刻（ScheduleCenter 扫描据此触发）',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    `deleted_at`   DATETIME     DEFAULT NULL COMMENT '进入回收站时刻（软删保留内容，可恢复/彻底删除）；NULL=非回收站删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_deleted` (`status`, `deleted`),
    KEY `idx_file_status` (`file_name`, `status`),
    KEY `idx_next_refresh` (`next_refresh_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文档表';

CREATE TABLE IF NOT EXISTS `c_ai_parse_task` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`        VARCHAR(50)  NOT NULL COMMENT '所属文档ID（与 c_ai_document.id 对应）',
    `kb_id`         VARCHAR(50)  DEFAULT NULL COMMENT '所属知识库ID',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0=queued待解析, 1=running执行中, 2=succeeded成功, 3=retryable可重试, 4=dead终态失败',
    `attempt`       INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `max_attempt`   INT          NOT NULL DEFAULT 3 COMMENT '最大尝试次数（超过转 dead）',
    `next_run_at`   DATETIME     NOT NULL COMMENT '最早可执行时刻（退避重试由此推迟）',
    `lease_until`   DATETIME     DEFAULT NULL COMMENT '租约到期时刻：进程崩溃或任务卡死超时后由扫描器回收回 queued',
    `priority`      TINYINT      NOT NULL DEFAULT 0 COMMENT '优先级（大者优先，如用户手动重解析优先）',
    `error`         VARCHAR(500) DEFAULT NULL COMMENT '失败原因（status=3/4 时可见）',
    `worker`        VARCHAR(128) DEFAULT NULL COMMENT '执行者标识（实例+线程，仅本实例执行自己抢到的任务）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `start_time`    DATETIME     DEFAULT NULL COMMENT '开始执行时刻',
    `finish_time`   DATETIME     DEFAULT NULL COMMENT '结束时刻',
    PRIMARY KEY (`id`),
    KEY `idx_due` (`status`, `next_run_at`),
    KEY `idx_doc` (`doc_id`),
    KEY `idx_worker` (`worker`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档解析持久化任务队列（上传只登记，解析由扫描器抢占执行，队列满不丢任务）';

CREATE TABLE IF NOT EXISTS `c_ai_knowledge` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`       VARCHAR(50)  DEFAULT NULL COMMENT '所属文档ID',
    `title`        VARCHAR(200) DEFAULT NULL COMMENT '片段标题',
    `content`      TEXT         DEFAULT NULL COMMENT '片段正文（净内容：不含章节路径前缀与分块重叠）',
    `images`       TEXT         DEFAULT NULL COMMENT '关联图片URL(JSON数组)',
    `chunk_index`  INT          DEFAULT 0 COMMENT '片段序号',
    `status`       INT          DEFAULT 0 COMMENT '启停用: 0=生效, 1=停用（停用块不参与召回，块级诊断用）',
    `vector_id`    VARCHAR(50)  DEFAULT NULL COMMENT 'Redis向量库中的文档ID',
    `content_hash` VARCHAR(64)  DEFAULT NULL COMMENT '内容指纹(SHA-256: title+title_path+净content+images)，重解析增量对比用',
    `title_path`   VARCHAR(500) DEFAULT NULL COMMENT '章节路径(如 一级/二级/三级)，检索与向量化时拼装上下文，正文不含前缀',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_doc_id` (`doc_id`),
    KEY `idx_doc_deleted` (`doc_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI知识片段表';

CREATE TABLE IF NOT EXISTS `c_ai_knowledge_qa` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID（同时作为问答对向量的 vector id）',
    `doc_id`        VARCHAR(50)  DEFAULT NULL COMMENT '所属文档ID',
    `knowledge_id`  VARCHAR(50)  NOT NULL COMMENT '来源知识块ID（问答对命中后取回该块正文）',
    `question`      VARCHAR(500) NOT NULL COMMENT '生成的问法（向量化索引文本）',
    `answer`        TEXT         DEFAULT NULL COMMENT '生成的答案（排查展示用，不参与召回）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_qa_doc` (`doc_id`),
    KEY `idx_qa_knowledge` (`knowledge_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识块问答对索引表（QA增强检索：按问法向量化，命中后返回来源块）';

CREATE TABLE IF NOT EXISTS `c_ai_knowledge_child` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID（同时作为子块向量的 vector id）',
    `doc_id`        VARCHAR(50)  DEFAULT NULL COMMENT '所属文档ID',
    `knowledge_id`  VARCHAR(50)  NOT NULL COMMENT '父块ID（命中子块后取回父块完整正文进上下文）',
    `content`       TEXT         NOT NULL COMMENT '子块正文（向量化索引文本；父块完整内容仍存 c_ai_knowledge）',
    `chunk_seq`     INT          DEFAULT 0 COMMENT '在父块内的切片序号',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_child_doc` (`doc_id`),
    KEY `idx_child_knowledge` (`knowledge_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='子块索引表（父子分块：小块向量化检索，命中后返回父块完整正文）';

CREATE TABLE IF NOT EXISTS `c_ai_agent_share` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `agent_id`    VARCHAR(50)  NOT NULL COMMENT '被分享的智能体ID',
    `token`       VARCHAR(64)  NOT NULL COMMENT '分享令牌（/s/{token} 免登录访问；唯一）',
    `model_ref`   VARCHAR(255) DEFAULT NULL COMMENT '游客对话模型引用（providerId/modelId，须为平台级供应商或创建者可用；空=回退创建者个人默认模型）',
    `enabled`     INT          DEFAULT 1 COMMENT '启用: 1=可访问, 0=暂停（保留 token，恢复即用）',
    `mcp_enabled` INT          DEFAULT 0 COMMENT 'MCP 端点: 1=对外提供 /ai/mcp/{token}（Streamable HTTP，Token 即凭据），0=关闭',
    `guest_images_enabled` INT DEFAULT 0 COMMENT '游客发图: 1=允许访客在分享页发图，0=关闭（默认）。默认关而非跟开：游客模型由发布者选定，非视觉模型收到图片只会报错或静默忽略，视觉 token 成本却记在发布者账上',
    `guest_files_enabled`  INT DEFAULT 0 COMMENT '游客文档附件: 1=允许访客上传 PDF/Word/代码等附件（逐访客隔离落盘 + 24h 清理 + 按发布者身份解析），0=关闭（默认）',
    `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；游客对话与 MCP 调用以该用户身份执行检索可见性与工具）',
    `visit_count`  INT         DEFAULT 0 COMMENT '游客对话累计访问次数（每次成功发起一次游客对话 +1；发布访问统计）',
    `last_visit_at` DATETIME   DEFAULT NULL COMMENT '最近一次游客对话时间',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_token` (`token`),
    KEY `idx_share_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体公开分享配置（一个智能体一条；/s/{token} 免登录对话）';

-- ============================================
-- 2026-10-02: 个人配置覆盖（schema personal 字段）
-- 键限定为 config-schema.json 中标记 personal 的字段：个人值覆盖系统全局值
-- （个人设置页保存），仅对该用户本人的
-- 问答生效；清空 = 删除本行 = 回落全局。存储与全局配置同构（配置键 + 值），加参数只改 schema。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_user_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `uid`          VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键（config-schema.json 的 backendKey，须标记 personal）',
    `config_value` TEXT         DEFAULT NULL COMMENT '个人覆盖值（与全局配置同格式；空=未设置）',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid_config_key` (`uid`, `config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人配置覆盖（personal 字段；覆盖全局值，仅本人问答生效）';
-- 说明：idx_doc_deleted 覆盖按文档取块 + 逻辑删除过滤（增量 diff/孤儿清扫/快照/关键词路 doc 过滤）；
-- idx_doc_id 为其最左前缀、已冗余，可在窗口期手动 DROP（SchemaMigrator 不会自动删索引）。
-- 关键词检索：默认走 MySQL content/title LIKE（全表扫描，知识块量大时慢）；
-- 建议切换到 Meilisearch 引擎（keyword.engine=meilisearch + POST /api/ai/search-index/reindex），根治 LIKE 扫描。

-- ============================================
-- 2026-08-10: 会话持久化 & 历史回放
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_session` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `user_id`       VARCHAR(64)  NOT NULL DEFAULT 'anonymous' COMMENT '归属用户（登录用户 uid；anonymous=历史兼容池全局可见）',
    `title`         VARCHAR(200) DEFAULT NULL COMMENT '会话标题 (取自首条用户问题)',
    `message_count` INT          DEFAULT 0 COMMENT '消息条数',
    `is_pinned`     INT          DEFAULT 0 COMMENT '置顶: 0=否, 1=是',
    `is_favorite`   INT          DEFAULT 0 COMMENT '收藏: 0=否, 1=是',
    `agent_id`      VARCHAR(50)  DEFAULT NULL COMMENT '最近一次作答的智能体（P5：不再是会话锁定值，每轮按请求重新解析；供分享页按智能体列会话与访客会话回收使用）',
    `agent_name`    VARCHAR(100) DEFAULT NULL COMMENT '绑定时的智能体名称快照（智能体改名/删除后会话仍可展示原名称）',
    `model`         VARCHAR(190) DEFAULT NULL COMMENT '会话级模型覆盖（聊天页手动切换模型时写入，引用或遗留名；NULL=跟随个人默认）',
    `history_summary`   MEDIUMTEXT DEFAULT NULL COMMENT '滚动历史摘要（上下文压缩：更早轮次压缩后的摘要，注入 prompt 的「早期对话摘要」段；NULL=未压缩过）',
    `summary_until_seq` BIGINT     DEFAULT 0 COMMENT '摘要已覆盖的消息条数（压缩由 AgentScope 框架执行，问渠只在压缩完成后把摘要与这一计数落一份，供「已压缩上下文」那条线的「查看摘要」读；0=本会话从没压过）',
    `todos`        TEXT         DEFAULT NULL COMMENT '会话当前任务清单 (JSON: {turnId,items:[{content,status}],updatedAt})；整表覆盖，只留最新一份——清单是"现在还剩几步"的状态，不是历史；历史写入由 c_ai_session_event 留痕；NULL=本会话没有清单',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    `kind`          VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT '会话种类：chat=用户发起的对话（进会话列表）；temp=平台内部临时会话（子智能体分支、工作流子智能体节点各跑一整轮，跑完即删），既不出现在会话列表也不参与「新建对话」的空会话复用',
    `project_id`    VARCHAR(64)  DEFAULT NULL COMMENT '所属工作区（c_ai_project.id；NULL=没挂工作区，存量行为此值。可空是一等状态：所有读路径都必须容忍 NULL，不按时间/文件名猜测回填）',
    PRIMARY KEY (`id`),
    KEY `idx_deleted_update` (`deleted`, `update_time` DESC),
    KEY `idx_user_update` (`user_id`, `deleted`, `update_time` DESC),
    KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI会话表';

CREATE TABLE IF NOT EXISTS `c_ai_message` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `session_id`  VARCHAR(50)  NOT NULL COMMENT '所属会话ID',
    `role`        VARCHAR(20)  NOT NULL COMMENT '角色: user / assistant',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `thinking`    TEXT         DEFAULT NULL COMMENT '思考过程全文(深度思考)',
    `images`      TEXT         DEFAULT NULL COMMENT '关联图片URL (JSON数组字符串)',
    `sources`     TEXT         DEFAULT NULL COMMENT '引用来源 (JSON数组字符串)',
    `retrieved`   TEXT         DEFAULT NULL COMMENT '检索状态行数据 (JSON: keywords/refs/terms)',
    `related`     TEXT         DEFAULT NULL COMMENT '相关推荐问句 (JSON数组字符串)；不落库则刷新后"接下来可以"整块消失',
    `degradations` TEXT        DEFAULT NULL COMMENT '本轮降级事件 (JSON数组: [{level,code,msg}])；只随 done 事件下发时刷新即消失，而"当时降了什么级"是这一轮的事实，回头看不见不等于没发生过。空列表不写=NULL',
    `warn_msg`     TEXT        DEFAULT NULL COMMENT '本轮警示条（最后一条 warn 帧原文，如沙盒基建异常/权限档位拒绝）；只随流下发一次时刷新即消失，用户当场看见的异常回头读成"没发生过"。NULL=本轮没有警示',
    `artifacts`   TEXT         DEFAULT NULL COMMENT '产物交付 (JSON数组: [{url,filename,size,description}])',
    `tool_calls`  MEDIUMTEXT   DEFAULT NULL COMMENT '工具调用过程 (JSON数组: [{name,status,startedAtMs,elapsedMs,waitedMs,args,result|error}]；startedAtMs 是服务端墙上开始时刻、elapsedMs 是这一次实际跑掉的时长、waitedMs 是其中被等你读卡答题盖住的时长（整段跨度要减它），三者缺位表示没计时、前端不显示；入参/输出存≤8KB全文，供前端卡片展开)',
    `timeline`    TEXT         DEFAULT NULL COMMENT '回答时间线 (JSON数组：正文区间段 / 过程区间段 / 工具下标段 / 产物下标段，记录交错顺序，刷新后还原过程视图)',
    `process_text` TEXT        DEFAULT NULL COMMENT '过程独白全文 (<process> 标签内的模型思考叙述，与正文分流；时间线 process 段区间指向本字段)',
    `tokens`      TEXT         DEFAULT NULL COMMENT '本轮运行统计 (JSON: context/budget/hits/output/prompt/outputIsReal/total/thinkingChars; compacted=这一轮内框架自动压缩过上下文，「已压缩上下文」分隔线只认这一个键; turnElapsedMs=这一轮机器耗时即墙钟扣掉人在回路、humanWaitMs=其中被等你读卡答题盖住的时长且只在真等过时才写) —— 气泡顶部那条整轮耗时读这两个键，不落库则刷新即没数',
    `model`       VARCHAR(190) DEFAULT NULL COMMENT '本轮生效模型引用（助手消息落库；用量统计按模型分组的依据，存量行为 NULL 归入未知）',
    `attachments` TEXT         DEFAULT NULL COMMENT '附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体)',
    `agent_id`    VARCHAR(50)  DEFAULT NULL COMMENT '本轮生效的智能体（取自会话级绑定的当轮快照；NULL=未使用智能体）',
    `agent_name`  VARCHAR(100) DEFAULT NULL COMMENT '本轮智能体名称快照（智能体改名/删除后历史消息仍可展示原名称）',
    `variant_group` VARCHAR(64) DEFAULT NULL COMMENT '分支版本组键（编辑重发/重新生成的同一位置各版本共用一个组；NULL=不属于任何版本组）',
    `variant_tail` TEXT         DEFAULT NULL COMMENT '该版本被替换时刻的可见尾部消息ID快照（JSON数组，含本条），切回分支时按此恢复；sessionRetentionDays 物理清理后恢复返回 0',
    `mentions`     TEXT         DEFAULT NULL COMMENT '@ 引用 (JSON数组字符串: [{type,id,name,kbId}])；用户消息常驻标注，刷新/历史回显均保留',
    `history_refs` TEXT         DEFAULT NULL COMMENT '# 历史引用 (JSON数组字符串: [{messageId,role,content}])；用户消息常驻标注，刷新/历史回显均保留',
    `plan`        TEXT         DEFAULT NULL COMMENT '计划批准卡 (JSON: {plan,status=approved|rejected})；助手消息落库，刷新/历史按轮重建执行计划卡；NULL=本轮未走计划模式',
    `goal_command` VARCHAR(64) DEFAULT NULL COMMENT '目标指令回声（值=目标 id）：这条**用户提问**是把一件事设为目标，气泡上挂命令标注、不挂普通消息动作。目标行只留「什么时候定的什么目标」；NULL=普通提问',
    `turn_id`     VARCHAR(64)  DEFAULT NULL COMMENT '本轮身份（一问一答共用一个 turnId，与 c_ai_session_event.turn_id 同源）；消息→事件账本的关联键，逐气泡「过程回放」靠它。存量行为 NULL=该轮没有过程记录可回放',
    `end_reason`  VARCHAR(16)  DEFAULT NULL COMMENT '本轮终止原因（记在**用户提问**上）：stopped=用户叫停且本轮没产出任何回答。刻意不落一条「已停止」的空回答——空的回答留在对话里比不留更碍眼；「这一问没有回答」本就是那一问的事实',
    `sequence`    INT          NOT NULL DEFAULT 0 COMMENT '消息序号 (会话内递增)',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`, `sequence`),
    KEY `idx_deleted_create` (`deleted`, `create_time`),
    KEY `idx_variant_group` (`variant_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI消息表';

-- ============================================
-- 2026-08-11: 问答数据闭环（日志 + 反馈）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_qa_log` (
    `id`              VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `session_id`      VARCHAR(50)  DEFAULT NULL COMMENT '会话ID',
    `question`        TEXT         COMMENT '用户问题',
    `deep_think`      INT          DEFAULT 0 COMMENT '是否深度思考: 0=否,1=是',
    `answer_summary`  VARCHAR(1000) DEFAULT NULL COMMENT '回答摘要(前500字)',
    `hit_doc_ids`     VARCHAR(1000) DEFAULT NULL COMMENT '命中文档ID列表(逗号分隔)',
    `has_citation`    INT          DEFAULT 0 COMMENT '是否有引用标注',
    `elapsed_ms`      INT          DEFAULT 0 COMMENT '回答耗时(ms)',
    `message_id`      VARCHAR(50)  DEFAULT NULL COMMENT 'P1：本轮回答消息ID（trace 详情还原 toolCalls/sources/tokens 的关联键；存量行为 NULL）',
    `agent_id`        VARCHAR(50)  DEFAULT NULL COMMENT 'P1：本轮生效智能体ID（trace 按智能体筛选；未用智能体为 NULL）',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_created` (`created_at`),
    KEY `idx_agent_time` (`agent_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI问答日志表';

CREATE TABLE IF NOT EXISTS `c_ai_qa_feedback` (
    `id`             VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `message_id`     VARCHAR(50)  NOT NULL COMMENT '关联消息ID',
    `rating`         INT          DEFAULT 0 COMMENT '1=有帮助 0=没帮助',
    `feedback_text`  VARCHAR(500) DEFAULT NULL COMMENT '反馈文本',
    `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message` (`message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI问答反馈表';

CREATE TABLE IF NOT EXISTS `c_ai_image_desc` (
    `cache_key`    VARCHAR(128) NOT NULL COMMENT '缓存键(v{版本}_{sha256: model+prompt+图片字节})',
    `description`  TEXT         NOT NULL COMMENT '图片描述',
    `model`        VARCHAR(128) DEFAULT NULL COMMENT '视觉模型名',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近命中时间(TTL基准)',
    PRIMARY KEY (`cache_key`),
    KEY `idx_update` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片描述缓存(内容寻址,持久化)';

CREATE TABLE IF NOT EXISTS `c_ai_config` (
    `config_key`    VARCHAR(64)   NOT NULL COMMENT '配置键（chat.model/chat.temperature/vision.model/...）',
    `config_value`  TEXT          NOT NULL COMMENT '配置值（TEXT 以容纳长 system prompt）',
    `remark`        VARCHAR(255)  DEFAULT NULL COMMENT '说明',
    `update_time`   DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型配置表';

-- ============================================
-- 2026-08-13: 产品功能补强（会话置顶收藏 / 文档分类 / 文档版本）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_document_version` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`        VARCHAR(50)  NOT NULL COMMENT '文档ID',
    `version`       INT          DEFAULT 0 COMMENT '版本号',
    `chunk_count`   INT          DEFAULT 0 COMMENT '该版本知识块数量',
    `snapshot_json` MEDIUMTEXT   COMMENT '知识块快照(JSON数组:[{id,title,content,titlePath,images}])',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`       INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除,1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_doc_version` (`doc_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文档版本快照表';


-- ============================================
-- 2026-09-14: API Key（对外开放问答能力）
-- 库里只存 SHA-256 哈希 + 前 8 位前缀（列表可辨识、不泄露全文）；明文仅签发时返回一次
-- key_hash 建唯一索引：鉴权热路径按哈希等值查询，且防重复签发同一 Key
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_api_key` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `name`         VARCHAR(200) DEFAULT NULL COMMENT '用途名称（如"报表系统集成"）',
    `key_hash`     VARCHAR(64)  NOT NULL COMMENT 'Key 的 SHA-256 哈希（十六进制小写）',
    `key_prefix`   VARCHAR(32)  DEFAULT NULL COMMENT '明文前缀（列表展示用）',
    `disabled`     INT          DEFAULT 0 COMMENT '是否停用: 0=启用, 1=停用（吊销）',
    `mcp_enabled`  INT          DEFAULT 0 COMMENT 'MCP 端点: 1=该 Key 可访问平台级 MCP 入口 /ai/mcp（元工具集），0=不可',
    `expire_at`    DATETIME     DEFAULT NULL COMMENT '过期时间（NULL=长期有效）',
    `last_used_at` DATETIME     DEFAULT NULL COMMENT '最近使用时间',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=私有：仅创建者与管理员级可见（谁建归谁，2026-10）)',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key_hash` (`key_hash`),
    KEY `idx_disabled` (`disabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI API Key 表';

-- ============================================
-- 2026-09-15: 智能体配置（P3：4.1 Agent 配置——模型/知识库/工具/提示词）
-- 智能体 = 命名预设，把「模型/系统提示词/知识库范围/工具开关」打包；对话页下拉切换，
-- 选中后该轮问答按智能体覆盖全局配置（未填维度继承全局）。工具开关三态：1=开 0=关 NULL=继承。
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_agent` (
    `id`              VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `name`            VARCHAR(200) NOT NULL COMMENT '智能体名称',
    `icon`            VARCHAR(32)  DEFAULT NULL COMMENT '图标: wenqu=问渠品牌标 / emoji 字符; NULL=默认展示（内置「问渠」默认用问渠品牌标，其余用机器人图标）',
    `description`     VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=私有：仅创建者与管理员级可见（谁建归谁，2026-10）)',
    `query_params` TEXT         DEFAULT NULL COMMENT '检索开关覆盖(JSON: {"rerank.enabled":"false"}; 空=继承全局; 阈值/权重/召回量归知识库或全局)',
    `system_prompt`   TEXT         DEFAULT NULL COMMENT '系统提示词覆盖（空=继承全局）',
    `knowledge_scope` VARCHAR(2000) DEFAULT NULL COMMENT '知识库范围：all 或 文档ID逗号分隔（空=all）',
    `knowledge_base_ids` VARCHAR(1000) DEFAULT NULL COMMENT '关联的知识库ID: NULL/空=默认知识库 逗号分隔=用这些库（检索作用域主路径）',
    `knowledge_tags`  VARCHAR(500) DEFAULT NULL COMMENT '检索标签收窄: 逗号分隔文档标签，检索只收命中其一的文档（空=不按标签过滤，与 knowledge_base_ids 叠加生效）',
    `tool_knowledge`  INT          DEFAULT NULL COMMENT '知识库检索工具: 1=开 0=关 NULL=继承',
    `tool_builtin`    INT          DEFAULT NULL COMMENT '内置工具: 1=开 0=关 NULL=继承',
    `tool_skill`      INT          DEFAULT NULL COMMENT '技能工具(readSkill): 1=开 0=关 NULL=继承',
    `tool_artifact`   INT          DEFAULT NULL COMMENT '产物交付工具: 1=开 0=关 NULL=继承',
    `tool_mcp`        INT          DEFAULT NULL COMMENT 'MCP 工具: 1=开 0=关 NULL=继承',
    `tool_websearch`  INT          DEFAULT NULL COMMENT '联网搜索工具(webSearch): 1=开 0=关 NULL=继承（结果为网页来源，进引用体系）',
    `goal_enabled`    INT          DEFAULT NULL COMMENT '目标模式开关: 1=开 0=关 NULL=开（平台级总闸已去掉，这一格是唯一判据）。关=界面无入口、引擎不接目标上下文、续跑不接管',
    `tool_approval_mode` VARCHAR(10) DEFAULT NULL COMMENT '有副作用工具(沙盒/MCP)执行审批: auto=自动执行 ask=执行前需用户确认 off=禁用 NULL=跟随权限档位建议值(只读→确认/全自主→自动)',
    `permission_preset` VARCHAR(32) DEFAULT NULL COMMENT '权限档位(P2,捆沙盒执行档+审批建议值): research=只读调研 standard=常规 autonomous=全自主; NULL=跟随平台默认 agent.permissionPreset.default,高于 agent.permissionPreset.max 时按上限执行',
    `tool_deny` VARCHAR(1000) DEFAULT NULL COMMENT '工具黑名单(P2): 逗号分隔工具名,本轮这些工具不进模型工具表、被调到也拒; 与族级三态/具体项白名单并列(其余照旧、只拿掉点名这几个),也是 MCP 动态工具唯一的粒度; NULL/空=不禁',
    `max_tool_steps`  INT          DEFAULT NULL COMMENT '单轮工具调用步数上限(全部工具合计; NULL=用全局 agent.maxToolSteps; 0=不限制)',
    `skills`          VARCHAR(1000) DEFAULT NULL COMMENT '技能范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `mcps`            VARCHAR(1000) DEFAULT NULL COMMENT 'MCP Server 范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `builtin_tools`   VARCHAR(500)  DEFAULT NULL COMMENT '内置工具范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `is_subagent`     INT           DEFAULT 0 COMMENT '是否子智能体: 0=主智能体（对话页可选） 1=子智能体（供主智能体委派）',
    `sub_agent_ids`   VARCHAR(1000) DEFAULT NULL COMMENT '主智能体可委派的子智能体ID: NULL/空=走默认多视角策略 逗号分隔=用这些',
    `subagent_mode`   VARCHAR(16)  DEFAULT NULL COMMENT '被当作子智能体咨询时怎么跑(P3a): digest=只并行检索+提炼要点(原有行为) full=跑完整问答管线(自己的知识库/工具/技能/沙盒/产物生效); NULL=跟随平台默认 agent.subagentMode',
    `knowledge_disabled` INT        DEFAULT 0 COMMENT '不使用知识库: 0=使用（按 knowledge_scope 约束） 1=纯角色智能体（整条跳过检索链路；@ 引用不受影响）',
    `workflow_id`     VARCHAR(50)  DEFAULT NULL COMMENT 'M4：绑定的工作流ID——非空时该智能体的回答由工作流产出（chatflow 语义，跑已发布版本）',
    `is_builtin`      INT           DEFAULT 0 COMMENT '内置标记: 1=系统内置问渠（全局唯一，禁止删除，仅管理员可配置） 0=普通',
    `is_default`      INT          DEFAULT 0 COMMENT '是否默认智能体: 0=否 1=是',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能体配置表';

-- ============================================
-- 2026-10-05: 智能体配置版本快照——每次保存落一版（人设/知识范围/工具集调优字段），
-- 回滚 = 把历史版本配置再存一版（历史不改写、可审计，对齐工作流版本口径）。
-- 最新版本恒等于当前配置；保留份数 agent.versionKeep（默认 20），超出按版本号最小先删。
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_agent_version` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '版本记录ID（UUID）',
    `agent_id`    VARCHAR(50)  NOT NULL COMMENT '智能体ID',
    `version`     INT          NOT NULL COMMENT '版本号（同一智能体内递增，从 1 起）',
    `config`      TEXT         NOT NULL COMMENT '配置快照(JSON：人设/知识范围/工具集等调优字段白名单)',
    `operator`    VARCHAR(64)  DEFAULT NULL COMMENT '产生该版本的操作人 uid',
    `reason`      VARCHAR(100) DEFAULT NULL COMMENT '来源说明：创建 / 保存 / 回滚自 vX',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_version` (`agent_id`, `version`),
    KEY `idx_agent_version_time` (`agent_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体配置版本快照（保存即落一版，回滚=再存一版）';

-- ============================================
-- 2026-09-16: 多用户协作（用户 + 部门 + 共享范围）
-- c_ai_user 存画像/归属与登录凭据（密码哈希加盐存储）；鉴权由本服务本地登录（JWT）完成。
-- 用户由管理员在「成员管理」建档；未登录请求归属 anonymous（与会话兼容池同理）。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_department` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `parent_id`    VARCHAR(50)  DEFAULT NULL COMMENT '父部门ID（c_ai_department.id，空=根节点；2026-09-26 升级树形）',
    `name`         VARCHAR(100) NOT NULL COMMENT '部门名称',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT '部门描述',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 部门表（树形：parent_id 空为根）';

CREATE TABLE IF NOT EXISTS `c_ai_user` (
    `uid`          VARCHAR(64)  NOT NULL COMMENT '用户标识（登录账号，与鉴权一致）',
    `username`     VARCHAR(100) DEFAULT NULL COMMENT '显示名称',
    `department_id` VARCHAR(50) DEFAULT NULL COMMENT '所属部门ID（外键 c_ai_department.id，可空=未分配）',
    `role`         VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '角色: superadmin | admin | user',
    `status`       INT          DEFAULT 1 COMMENT '状态: 1=启用, 0=禁用',
    `password_hash` VARCHAR(255) DEFAULT NULL COMMENT '密码哈希（PBKDF2；空=未设置，不能本地登录）',
    `login_fail_count` INT      DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until` DATETIME     DEFAULT NULL COMMENT '锁定至（失败过多时；空=未锁定）',
    `last_login_time` DATETIME  DEFAULT NULL COMMENT '最近一次登录成功时间（本地登录与 OIDC 回调均记；空=从未登录）',
    `default_model` VARCHAR(255) DEFAULT NULL COMMENT '个人默认聊天模型（引用 providerId/modelId；空=不设默认，对话时手动选择）',
    `memory_enabled` INT DEFAULT 1 COMMENT '用户级长期记忆自动提炼开关（个人设置可改）: 1=开, 0=关；历史行加列时按默认 1（保持原行为）',
    -- default_rerank_model 已随「重排归知识库检索设置」退役（存量库该列无害保留）
    -- default_vision_model 已随「聊天图片理解只看模型能力位」下线（存量库该列无害保留）
    `oidc_sub`     VARCHAR(255) DEFAULT NULL COMMENT 'OIDC 身份标识（IdP 的 sub；空=未绑定单点登录）',
    `oidc_provider` VARCHAR(50) DEFAULT NULL COMMENT 'OIDC 认证源（c_ai_oidc_provider.id）。与 oidc_sub 组成复合唯一：一个 sub 在同一认证源下只能绑一个账号，防冒用；两个不同 IdP 签发相同 sub 不再冲突（空=未绑定）',
    `menu_pref`    TEXT        DEFAULT NULL COMMENT '个人侧栏偏好 JSON：{"order":[menuId...],"hidden":[menuId...]}。仅重排/隐藏「本人有权看到」的菜单，不越权；空=未自定义（走菜单表默认顺序与显隐）',
    `avatar`       VARCHAR(512) DEFAULT NULL COMMENT '用户头像：空=昵称首字；emoji=表情头像；以 / 开头=上传图片 URL（/ai/images/avatar/...）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`uid`),
    UNIQUE KEY `uk_username` (`username`),
    -- MySQL 唯一索引允许多行 NULL ⇒ 未绑定 OIDC 的本地账号不受影响
    UNIQUE KEY `uk_oidc_provider_sub` (`oidc_provider`, `oidc_sub`),
    KEY `idx_department` (`department_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 用户表（画像/归属 + 本地登录凭据）';

-- ============================================
-- 2026-10：OIDC 多认证源（多 IdP）
-- 把原先 c_ai_config 里 20 个扁平 oidc.* 键搬成「每行一个认证源」的表，
-- 照搬模型供应商 c_ai_provider 的形状（逐行 RSA 加密 client_secret + 掩码脱敏 + 管理页 CRUD）。
-- 存量库由 OidcMigrationRunner 自动组装 default 行；新库直接建表。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_oidc_provider` (
    `id`                    VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `name`                  VARCHAR(100) NOT NULL COMMENT '认证源名称（登录按钮文案，如「企业微信登录」）',
    `enabled`               INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用后登录页不显示该入口）',
    `issuer_url`            VARCHAR(512) DEFAULT NULL COMMENT 'IdP issuer（discovery 源：/.well-known/openid-configuration）',
    `client_id`             VARCHAR(255) DEFAULT NULL COMMENT '客户端 ID',
    `client_secret`         TEXT         DEFAULT NULL COMMENT '客户端密钥（RSA 加密存储，RSA: 前缀）',
    `redirect_uri`          VARCHAR(512) DEFAULT NULL COMMENT '回调地址（空=按访问来源推导 /api/ai/auth/oidc/callback）',
    `frontend_base_url`     VARCHAR(512) DEFAULT NULL COMMENT '登录后前端跳转基地址（空=同源相对路径）',
    `authorization_endpoint` VARCHAR(512) DEFAULT NULL COMMENT '授权端点（空=discovery）',
    `token_endpoint`        VARCHAR(512) DEFAULT NULL COMMENT '令牌端点（空=discovery）',
    `userinfo_endpoint`     VARCHAR(512) DEFAULT NULL COMMENT '用户信息端点（空=discovery）',
    `scopes`                VARCHAR(255) DEFAULT 'openid profile email' COMMENT '请求 scope（空格分隔）',
    `force_prompt_login`    INT          DEFAULT 0 COMMENT '授权请求带 prompt=login（强制重新认证）',
    `username_claim`        VARCHAR(100) DEFAULT 'preferred_username' COMMENT '用户名字段',
    `name_claim`            VARCHAR(100) DEFAULT 'name' COMMENT '显示名字段',
    `use_raw_username`      INT          DEFAULT 0 COMMENT '用 IdP 用户名当账号标识（便于与本地账号对齐）',
    `auto_create_user`      INT          DEFAULT 1 COMMENT '首次登录自动建号（关闭则须管理员预先建好并绑定）',
    `default_role`          VARCHAR(64)  DEFAULT 'user' COMMENT '自动建档默认角色（须是权限管理中已启用的角色）',
    `default_department`    VARCHAR(50)  DEFAULT NULL COMMENT '自动建档默认部门（不存在则按名称创建）',
    `fetch_department_info` INT          DEFAULT 0 COMMENT '从 userinfo 同步部门',
    `department_claim`      VARCHAR(100) DEFAULT 'department' COMMENT '部门映射字段',
    `sort_order`            INT          DEFAULT 0 COMMENT '排序（小在前，决定登录页按钮顺序）',
    `create_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OIDC 认证源表（多 IdP）';

-- ============================================
-- 2026-09-25: 模型供应商管理（多供应商 + 模型库分类型）
-- 供应商 = OpenAI 兼容网关（baseUrl + apiKey + 路径覆盖）；模型库按类型（chat/vision/embedding/rerank/other）
-- 分类登记。模型引用格式 `{providerId}/{modelId}` 贯穿 chat.model / agent.model / 用户偏好等所有存模型处；
-- 遗留纯模型名仍按全局 chat.* 网关解析（兼容存量数据）。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_provider` (
    `id`               VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `name`             VARCHAR(100) NOT NULL COMMENT '供应商显示名（如 DeepSeek / 智谱GLM）',
    `icon`             VARCHAR(255) DEFAULT NULL COMMENT '图标：内置图标key（deepseek/zhipu/...）或 http(s) 图片URL（空=前端字母头像）',
    `base_url`         VARCHAR(512) NOT NULL COMMENT '网关地址（OpenAI 兼容）',
    `api_key`          TEXT         DEFAULT NULL COMMENT 'API Key（RSA 加密存储，RSA: 前缀）',
    `completions_path` VARCHAR(255) DEFAULT NULL COMMENT '聊天补全路径（空=默认 /v1/chat/completions）',
    `embeddings_path`  VARCHAR(255) DEFAULT NULL COMMENT '向量路径（空=默认 /v1/embeddings）',
    `api_type`         VARCHAR(32)  DEFAULT 'openai' COMMENT '协议类型（预留: openai=OpenAI 兼容）',
    `enabled`          INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用后其模型不可选）',
    `remark`           VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `sort_order`       INT          DEFAULT 0 COMMENT '排序（小在前）',
    `created_by`       VARCHAR(64)  DEFAULT NULL COMMENT '创建人 uid',
    `owner_uid`        VARCHAR(64)  DEFAULT NULL COMMENT '归属用户：谁建归谁（仅归属人可见可用，2026-10，不存在平台共享）；历史遗留空值=无主行，启动回填',
    `quota_status`     VARCHAR(16)  DEFAULT NULL COMMENT '额度状态: NULL=正常 exhausted=额度不足（余额耗尽/配额用尽/套餐到期）。纯被动：真实调用失败时登记，任一次成功调用即清除',
    `quota_message`    VARCHAR(512) DEFAULT NULL COMMENT '额度异常原因（网关返回原文摘要，供界面与通知展示；正常时 NULL）',
    `quota_model_id`   VARCHAR(255) DEFAULT NULL COMMENT '触发额度不足的模型名（裸模型名，不含 providerId 前缀；正常时 NULL）',
    `quota_checked_at` DATETIME     DEFAULT NULL COMMENT '额度状态最近判定时刻（正常时 NULL；超时效未复现视为已自愈）',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型供应商表（谁建归谁，无平台共享）';

CREATE TABLE IF NOT EXISTS `c_ai_model` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `provider_id`  VARCHAR(50)  NOT NULL COMMENT '所属供应商（c_ai_provider.id）',
    `model_id`     VARCHAR(255) NOT NULL COMMENT '模型名（调用 API 时 model 参数原样透传）',
    `display_name` VARCHAR(255) DEFAULT NULL COMMENT '展示名（空=同 model_id）',
    `model_type`   VARCHAR(16)  DEFAULT 'chat' COMMENT '类型: chat=聊天 vision=视觉 embedding=向量 rerank=重排 audio=语音 omni=全模态 other=其他',
    `vision_capable` INT        DEFAULT NULL COMMENT '图片理解能力(可与聊天并存): NULL=按类型与模型名自动判定 1=支持 0=不支持',
    `tool_capable`  INT        DEFAULT NULL COMMENT '工具调用/Function Calling 能力: NULL=按类型与模型名自动判定 1=支持 0=不支持(下发 tools 会 400)',
    `thinking`     VARCHAR(16)  DEFAULT 'auto' COMMENT '思考能力(仅聊天模型有意义): auto=按模型名判定 none=不支持 switchable=可开关 always=恒思考',
    `reasoning_levels` VARCHAR(64) DEFAULT NULL COMMENT '该模型支持的思考强度档位(逗号分隔: low,medium,high,xhigh,max；空=不支持强度调节，仅开关思考)',
    `default_reasoning_level` VARCHAR(16) DEFAULT NULL COMMENT '默认思考强度档位(须在本模型 reasoning_levels 内；空=不指定，由请求层/网关默认决定)',
    `context_window` INT        DEFAULT NULL COMMENT '上下文窗口上限 token（对话类模型必填，无全局兜底；即用户可选区间的最大值/默认值；上下文预算=窗口×安全系数−输出限制）',
    `context_window_min` INT    DEFAULT NULL COMMENT '上下文窗口下限 token（NULL=不可调；与 context_window 构成用户在聊天页可选区间 [min,max]，默认取 max）',
    `max_output`   INT          DEFAULT NULL COMMENT '最大输出 token（NULL=未声明，不下发 max_tokens 交由厂商默认；作为 max_tokens 随请求下发，同时从窗口预算中预留）',
    `input_price`  DECIMAL(10,2) DEFAULT NULL COMMENT '输入单价（元/百万 tokens；NULL=未配置，费用报表对该模型显示未计价；0=免费模型）',
    `output_price` DECIMAL(10,2) DEFAULT NULL COMMENT '输出单价（元/百万 tokens；NULL=未配置；输出通常比输入贵，两价独立登记）',
    `enabled`      INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注（如上下文窗口说明）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_model` (`provider_id`, `model_id`),
    KEY `idx_provider` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型库表（按类型登记供应商可用模型）';

-- ============================================
-- 2026-09-26: MCP Server 下沉为「个人资产」
-- 变更背景：这类资源原先是管理员在系统设置里维护的**全局**配置——MCP 落在全局配置
--   mcp.servers（JSON 数组）+ mcp.enabled；普通用户既看不见也改不了
--   （/api/ai/mcp/** 对非管理员返回 403）。现改为按 uid 归属，每人各管各的。
-- 技能三张表（个人技能 / 内置停用 / 内置改写）与用户长期记忆表已随「全框架化 P3」退役：
--   技能正文与记忆都改由文件系统承载，增删改交给框架的技能与记忆工具。
--   存量库的清理见文件末尾「退役清理」段。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_user_mcp` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `uid`         VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `name`        VARCHAR(60)  NOT NULL COMMENT '服务显示名（个人维度唯一；同时作为工具名前缀防冲突）',
    `url`         VARCHAR(512) NOT NULL COMMENT '服务地址（可含路径；streamable 默认端点 /mcp、sse 默认 /sse）',
    `type`        VARCHAR(16)  DEFAULT 'streamable' COMMENT '传输类型: streamable | sse',
    `headers`     TEXT         COMMENT '自定义请求头（JSON 对象，整块 RSA 加密存储；RSA: 前缀）',
    `enabled`     INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用后不建立连接、不暴露其工具）',
    `tool_labels` TEXT COMMENT '工具中文名（JSON）：{items:{裸工具名:中文名}}。用户在 MCP 页逐条手工起名，没起名的展示原名',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid_name` (`uid`, `name`),
    KEY `idx_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人 MCP Server 表（替代原全局配置 mcp.servers）';

-- ============================================
-- 2026-09-26: RBAC 权限管理（角色 / 菜单 / 接口）
-- 模型：c_ai_user.role 引用 c_ai_role.code（单角色）；
-- 角色（非管理员级）按 c_ai_role_menu 控制侧边栏菜单、按 c_ai_role_api 控制可调接口
-- （拦截器 AntPathMatcher 匹配 method+path，未绑定一律 403 fail-closed；
--   superadmin 与 admin_flag=1 的角色直通全部接口与菜单）。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_role` (
    `code`        VARCHAR(20)  NOT NULL COMMENT '角色编码（c_ai_user.role 的值域，小写字母开头）',
    `name`        VARCHAR(50)  NOT NULL COMMENT '角色名称',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    `admin_flag`  INT          DEFAULT 0 COMMENT '管理员级: 1=视同管理员（放行全部接口与菜单；仅自定义角色可改）',
    `builtin`     INT          DEFAULT 0 COMMENT '内置角色: 1=预置（superadmin/admin/user，不可删、admin_flag/状态不可改）',
    `status`      INT          DEFAULT 1 COMMENT '状态: 1=启用, 0=停用（停用后该角色仅保留问答白名单能力）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 角色表（RBAC：单角色，绑定菜单与接口）';

CREATE TABLE IF NOT EXISTS `c_ai_menu` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键（内置菜单固定 id 如 menu-chat，自定义为 UUID）',
    `parent_id`   VARCHAR(50)  DEFAULT NULL COMMENT '父菜单ID（空=顶级）',
    `name`        VARCHAR(50)  NOT NULL COMMENT '菜单名称',
    `icon`        VARCHAR(50)  DEFAULT NULL COMMENT '图标名（@ant-design/icons-vue 组件名，如 SettingOutlined）',
    `path`        VARCHAR(200) DEFAULT NULL COMMENT '前端路由路径（如 /members）。tab 型可空（跳转由父页 ?tab= 承担）；group 型必须为空',
    `render_as`   VARCHAR(10)  DEFAULT 'sidebar' COMMENT '渲染位置: sidebar=侧栏入口(默认) | tab=父页内Tab(须有parent_id) | group=侧栏分组标题(不可点) | hidden=纯权限容器(任何UI不渲染)',
    `sort_order`  INT          DEFAULT 0 COMMENT '排序（小在前）',
    `visible`     INT          DEFAULT 1 COMMENT '是否显示: 1=显示, 0=隐藏（隐藏后不进侧边栏，仍可作权限归属）',
    `builtin`     INT          DEFAULT 0 COMMENT '内置菜单: 1=预置不可删除',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 菜单表（侧边栏数据源，按角色绑定下发）';

CREATE TABLE IF NOT EXISTS `c_ai_api` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `method`      VARCHAR(10)  NOT NULL COMMENT 'HTTP 方法（大写；ALL=任意方法）',
    `path`        VARCHAR(200) NOT NULL COMMENT '接口路径（应用内路径，如 /api/ai/agent/{id}）',
    `name`        VARCHAR(100) DEFAULT NULL COMMENT '接口名称（展示用）',
    `module`      VARCHAR(50)  DEFAULT NULL COMMENT '所属模块（Controller 分组，可编辑）',
    `builtin`     INT          DEFAULT 0 COMMENT '扫描登记: 1=启动期自动登记（路径删除后重启会重新登记）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_method_path` (`method`, `path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 接口表（RBAC 鉴权数据源）';

CREATE TABLE IF NOT EXISTS `c_ai_role_menu` (
    `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码（c_ai_role.code）',
    `menu_id`   VARCHAR(50) NOT NULL COMMENT '菜单ID（c_ai_menu.id）',
    PRIMARY KEY (`role_code`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单绑定';

CREATE TABLE IF NOT EXISTS `c_ai_role_api` (
    `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码（c_ai_role.code）',
    `api_id`    VARCHAR(50) NOT NULL COMMENT '接口ID（c_ai_api.id）',
    PRIMARY KEY (`role_code`, `api_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-接口绑定';

CREATE TABLE IF NOT EXISTS `c_ai_menu_api` (
    `menu_id` VARCHAR(50) NOT NULL COMMENT '菜单ID（c_ai_menu.id）',
    `api_id`  VARCHAR(50) NOT NULL COMMENT '接口ID（c_ai_api.id）',
    PRIMARY KEY (`menu_id`, `api_id`),
    KEY `idx_api` (`api_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单-接口归属（多归属；仅分组/展示与勾选联动，不参与鉴权）';

-- ============================================
-- 2026-09-26: 产物交付（present_artifacts）落库
-- 模型在回答中生成的文件产物（md/txt/csv/json/html）按**用户**归属持久化，
-- 供「我的产物」列表/下载/删除与超期清理；文件本体落在 {images.dir}/artifacts 下按
-- {uid}/{yyyyMM}/{id}_{名称} 组织——id 前缀同时解决"同一会话同名产物互相覆盖"。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_artifact` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `uid`         VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `session_id`  VARCHAR(64)  DEFAULT NULL COMMENT '产生该产物的会话（可空：会话清理后成果仍归人）',
    `project_id`  VARCHAR(64)  DEFAULT NULL COMMENT '所属工作区（c_ai_project.id；由 session_id 反推写入，NULL=没挂工作区。删工作区时置 NULL 而不删行——"成果归人"这条设计意图不许被级联删除破坏）',
    `filename`    VARCHAR(120) NOT NULL COMMENT '产物文件名（已净化，含扩展名）',
    `ext`         VARCHAR(10)  DEFAULT NULL COMMENT '扩展名（小写，无点）',
    `size`        INT          DEFAULT 0 COMMENT '字节数',
    `object_key`  VARCHAR(255) NOT NULL COMMENT '存储相对路径 artifacts/{uid}/{yyyyMM}/{id}_{name}',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '给用户的产物说明',
    `deleted`     INT          DEFAULT 0 COMMENT '软删: 0=正常 1=已删除（文件同时删除）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_uid_time` (`uid`, `create_time`),
    KEY `idx_session` (`session_id`),
    KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产物交付表（模型生成的可下载文件）';

-- ============================================
-- 2026-10-17: 工作区容器（Project）
-- 把「一个工作目录 + 若干次会话 + 若干份产物」收在一个可持续的根下。
--
-- 为什么加这张表：执行能力早就是工作区级的（沙盒、多步、产物），容器却停在会话级
-- （沙盒目录按 sessions/{thread_id} 切分、跨会话互不可见），成果停在用户级（artifact 一张
-- 扁平表）。换个会话 = 换一个世界。本表补的是中间那个聚合根。
--
-- 三条设计边界（写下来是为了挡住下一轮"顺手也做了吧"）：
--   1. 归属 uid，**不是租户也不是部门**：问渠是单公司私有化产品（tenantId 全仓 0 命中），
--      引入共享工作区等于引入一套新权限模型，不在本表范围；
--   2. **不是权限边界**：project_id 只做归属与过滤，任何检索/可见性判定都不读它
--      （walled 口径维持 P5 的解绑决定，别顺手加回来）；
--   3. **不是必填概念**：每人一个 implicit 兜底容器由后台懒创建，用户不建工作区也能照常问答，
--      只是产物开始有归属。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_project` (
    `id`               VARCHAR(64)  NOT NULL COMMENT '主键ID (UUID)',
    `uid`              VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid；单用户容器，不做共享）',
    `name`             VARCHAR(255) DEFAULT NULL COMMENT '工作区名；implicit 容器可为空（不给它起名，界面只体现为侧栏「会话」栏）',
    `selection_status` VARCHAR(20)  NOT NULL DEFAULT 'selectable' COMMENT 'implicit=每人一个自动默认（不可删、不进切换器） / selectable=用户显式创建可选中',
    `workdir_path`     VARCHAR(512) NOT NULL COMMENT '工作区目录（相对 user-data 根的 POSIX 相对路径，形如 projects/{id}；provisioner 侧 normalize_workdir_path 会拒绝对路径与非法分量）',
    `directory_mode`   VARCHAR(20)  NOT NULL DEFAULT 'managed' COMMENT 'managed=平台托管目录 / linked=关联用户已有目录（暂未实现，保留位）',
    `instructions`     TEXT         DEFAULT NULL COMMENT '工作区约定：写一次即对该工作区**所有会话**生效（每轮注入 system prompt 的工作背景段）。这是「工作区」与「分类标签」的分界——没有它，每条对话都得重新交代背景',
    `default_agent_id` VARCHAR(50)  DEFAULT NULL COMMENT '工作区默认助手：会话归属该工作区且用户没显式选助手时用它作答，其知识库范围随助手生效（工作区因此有自己的资料域）',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active=正常 / archived=归档（只读保留，二期）',
    `deleted`          INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0=正常, 1=已删除（删工作区不删其下会话与产物，只把它们的 project_id 置 NULL）',
    `idempotency_key`  VARCHAR(128) DEFAULT NULL COMMENT '幂等创建键（自动建 implicit 容器时防并发重复插）',
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid_idem` (`uid`, `idempotency_key`),
    KEY `idx_uid_status` (`uid`, `selection_status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作区容器（把工作目录/会话/产物收在一个可持续的根下）';

-- ============================================
-- 2026-09-26: 定时执行智能体（用户自建的计划任务）
-- 每轮到点后按「智能体 + 提示词」跑一次完整问答，结果落进该任务的**专属会话**
-- （一个任务一个会话，历史执行追加在里面，不刷屏会话列表；与平台版"每轮新建会话"不同，
-- 差异见 ScheduledJobService 注释）。
-- 调度：ScheduleCenter 的节拍任务扫描 next_run_at ≤ now 的启用任务；扫描时先推进
-- next_run_at 再执行 ⇒ 天然防同一轮重复触发。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_scheduled_job` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '主键ID (UUID)',
    `uid`          VARCHAR(64)   NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `name`         VARCHAR(100)  NOT NULL COMMENT '任务名（同时作为结果会话标题）',
    `agent_id`     VARCHAR(50)   DEFAULT NULL COMMENT '执行的智能体（空=当轮生效的默认智能体）',
    `prompt`       VARCHAR(2000) NOT NULL COMMENT '每轮发送给智能体的问题/指令',
    `cron`         VARCHAR(100)  NOT NULL COMMENT 'cron 表达式（5 段：分 时 日 月 周）',
    `timezone`     VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai',
    `project_id`   VARCHAR(64)   DEFAULT NULL COMMENT '归属工作区（c_ai_project.id；空=不属于任何工作区）。挂工作区的任务：每轮结果会话落进该工作区，产物与工作目录也跟着进去' COMMENT 'cron 的时区解释',
    `deep_think`   INT           DEFAULT 0 COMMENT '本轮是否深度思考: 0=否 1=是',
    `model_ref`    VARCHAR(255)  DEFAULT NULL COMMENT '模型覆盖（{providerId}/{modelId}；空=个人默认）',
    `enabled`      INT           DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用时 next_run_at 清空）',
    `paused_by_archive` INT      DEFAULT 0 COMMENT '归档联动停用快照: 1=因工作区归档被自动停用（恢复工作区时只还原这些，用户自己停用的不动）',
    `next_run_at`  DATETIME      DEFAULT NULL COMMENT '下次执行时刻（按 timezone 计算得出）',
    `last_run_at`  DATETIME      DEFAULT NULL COMMENT '上次触发时刻',
    `session_id`   VARCHAR(50)   DEFAULT NULL COMMENT '该任务的结果会话（懒创建，之后每轮追加）',
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT           DEFAULT 0 COMMENT '软删: 0=正常 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_uid` (`uid`),
    KEY `idx_due` (`enabled`, `next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时执行智能体任务表';

CREATE TABLE IF NOT EXISTS `c_ai_scheduled_run` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '主键ID (UUID)',
    `job_id`       VARCHAR(50)   NOT NULL COMMENT '任务ID（c_ai_scheduled_job.id）',
    `uid`          VARCHAR(64)   NOT NULL COMMENT '归属用户',
    `session_id`   VARCHAR(50)   DEFAULT NULL COMMENT '执行结果会话',
    `trigger_type` VARCHAR(16)   NOT NULL DEFAULT 'scheduled' COMMENT '触发方式: scheduled | manual',
    `status`       VARCHAR(16)   NOT NULL COMMENT '状态: running | succeeded | failed',
    `answer`       VARCHAR(1000) DEFAULT NULL COMMENT '回答摘录（列表快速预览用）',
    `error`        VARCHAR(500)  DEFAULT NULL COMMENT '失败原因',
    `started_at`   DATETIME      NOT NULL COMMENT '开始时刻',
    `finished_at`  DATETIME      DEFAULT NULL COMMENT '结束时刻',
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_time` (`job_id`, `create_time`),
    KEY `idx_uid_time` (`uid`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行记录（每轮一行；回答正文在结果会话里）';

CREATE TABLE IF NOT EXISTS `c_ai_tool_approval` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '审批请求ID（UUID，与 SSE approval_required 事件一致）',
    `session_id`   VARCHAR(50)   DEFAULT NULL COMMENT '归属会话',
    `user_id`      VARCHAR(64)   DEFAULT NULL COMMENT '发起问答的用户（仅本人可裁决）',
    `turn_id`      VARCHAR(64)   DEFAULT NULL COMMENT '归属轮次（与 c_ai_message.turn_id 同源）：断线重连时把卡片挂回它自己那一轮；补列前的存量记录与工作流画布的审批为 NULL（没有问答轮次可言），前端按原口径处理不猜轮次',
    `status`       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/TIMEOUT/STOPPED(用户叫停本轮)/INTERRUPTED(无人查看宽限中止)/REVISED/SUPERSEDED(计划版本链)',
    `request_args` TEXT          DEFAULT NULL COMMENT '工具入参摘要（审批类截 2000 字符；计划类存 {plan} JSON、提问类存 {questions} JSON，一份多步计划常态超 2000 字，故整列放宽成 TEXT）',
    `answer`       TEXT          DEFAULT NULL COMMENT '裁决内容：提问类（askUser）记录存用户所选/输入的答案；超时/忽略不代答时落给模型的「未作答」说明',
    `created_at`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '挂起时刻',
    `resolved_at`  DATETIME      DEFAULT NULL COMMENT '裁决/超时时刻',
    PRIMARY KEY (`id`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具执行审批/智能体提问记录（人在回路：持久化 + 审计 + 进程重启后可见）';

-- ============================================
-- 2026-10-21: 目标模式（Goal Mode）
-- 2026-10-22 任务运行时重构：目标 = 一句跨轮的完成目标 + 一个阶段；控制面由
-- 「GoalScheduler 租约 + c_ai_goal_round 轮次 + GoalContinuation 续跑上下文 + GoalVerifier 证据核验」
-- 承载。用户只剩改目标/停止/继续/删除四个动作；同会话至多一条未结束目标。
-- 完成不再由回答最后一行直接决定：marker 只生成「完成申请」，证据核验通过才落 ACHIEVED。
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_goal` (
    `id`                VARCHAR(64)  NOT NULL COMMENT '目标ID（UUID，续跑幂等键 goal:{id}:{n} 用它，永不复用）',
    `session_id`        VARCHAR(50)  NOT NULL COMMENT '归属会话',
    `user_id`           VARCHAR(64)  DEFAULT NULL COMMENT '发起用户（仅本人可操作）',
    `agent_id`          VARCHAR(50)  DEFAULT NULL COMMENT '发起时绑定的智能体（换智能体即换实例，目标照旧）',
    `objective`         VARCHAR(4000) NOT NULL COMMENT '目标原文（续跑提示词逐字带上；产品上限 4000）',
    -- 下面四列是上一版「验收标准/验证」范式的遗留（2026-10-22 退役）：不再读写，存量库保留不删
    `criteria_json`     JSON         DEFAULT NULL COMMENT '【已退役】旧的验收标准快照',
    `constraints_json`  JSON         DEFAULT NULL COMMENT '【已退役】旧的约束快照',
    `subject_version`   VARCHAR(2000) DEFAULT NULL COMMENT '【已退役】旧的工件指纹',
    `verifications_json` JSON        DEFAULT NULL COMMENT '【已退役】旧的验证记录',
    `status`            VARCHAR(32)  NOT NULL DEFAULT 'PAUSED' COMMENT '阶段: RUNNING=自动推进/PAUSED=停下可继续/ACHIEVED=已完成/CANCELLED=已删除（老行其余状态读取时归一）',
    -- 预算四列（budget_turns/budget_tokens/budget_tool_calls/budget_minutes）已退役（2026-10-21）；
    -- 存量库里的列留着不删（无读取方，删列是不可逆动作，交给运维窗口）
    `used_turns`        INT          NOT NULL DEFAULT 0 COMMENT '目标区间内的问答轮数（含用户插话轮）',
    `used_tokens`       BIGINT       NOT NULL DEFAULT 0 COMMENT '已用 token（按 c_ai_usage_log 聚合）',
    `used_tool_calls`   INT          NOT NULL DEFAULT 0 COMMENT '已用工具调用（按目标区间累计）',
    `used_ms`           BIGINT       NOT NULL DEFAULT 0 COMMENT '已用机器毫秒（每轮 machineMs 累计）',
    `contract_version`  BIGINT       NOT NULL DEFAULT 0 COMMENT '【已退役】框架 contractVersion 水位',
    `state_revision`    BIGINT       NOT NULL DEFAULT 0 COMMENT '【已退役】框架 TaskContextState.revision 水位',
    `first_turn_id`     VARCHAR(64)  DEFAULT NULL COMMENT '目标覆盖的首轮（过程面板按它聚合）',
    `last_turn_id`      VARCHAR(64)  DEFAULT NULL COMMENT '目标覆盖的末轮',
    `turn_counter`      INT          NOT NULL DEFAULT 0 COMMENT '自动续跑已起的轮数（幂等键 goal:{id}:{n} 的 n）',
    `end_reason`        VARCHAR(32)  DEFAULT NULL COMMENT '停下的原因: achieved/paused/preempted/round-limit/blocked/turn-failed/budget/no-progress/orphaned/cleared',
    `blocked_reason`    VARCHAR(1000) DEFAULT NULL COMMENT '助手报告的受阻原因（end_reason=blocked 时有值）',
    -- 任务运行时（2026-10-22）：控制面从进程内回调链搬到「租约 + 调度器 + 轮次表」。
    -- phase 是内部阶段；status 仍是对外阶段（RUNNING/PAUSED/ACHIEVED/CANCELLED + FAILED/等待态）。
    `phase`             VARCHAR(32)  NOT NULL DEFAULT 'QUEUED' COMMENT '内部阶段: QUEUED/EXECUTING/VERIFYING/WAITING_APPROVAL/WAITING_EVENT/RECOVERING/DONE',
    `objective_version` INT          NOT NULL DEFAULT 0 COMMENT '目标原文版本（edit 递增；轮次输入快照按版本取）',
    `lease_owner`       VARCHAR(64)  DEFAULT NULL COMMENT '当前执行者（实例ID）：同一目标同一时刻只允许一个 worker',
    `lease_until`       DATETIME(3)  DEFAULT NULL COMMENT '执行租约到期时刻（崩溃后由调度器回收）',
    `heartbeat_at`      DATETIME(3)  DEFAULT NULL COMMENT '执行者心跳时刻',
    `next_run_at`       DATETIME(3)  DEFAULT NULL COMMENT '下一轮最早可开始时刻（NULL=有轮在飞，不可调度）',
    `continuation_json` JSON         DEFAULT NULL COMMENT '续跑上下文：目标/阶段/计划/事实/产物/失败尝试/缺口/预算/权限',
    `outcome_json`      JSON         DEFAULT NULL COMMENT '完成或失败的结构化结果（state/code/summary/evidence/remaining）',
    `last_event_id`     BIGINT       DEFAULT NULL COMMENT '最近一条 goal 事件的账本ID（前端断线重连游标）',
    `error_class`       VARCHAR(32)  DEFAULT NULL COMMENT '最近一次失败分类: TRANSIENT/CONFIG/ENVIRONMENT/PERMISSION/BUSINESS/UNKNOWN/ORPHANED',
    `attempt`           INT          NOT NULL DEFAULT 0 COMMENT '连续失败次数（成功一轮清零）',
    `no_progress_rounds` INT         NOT NULL DEFAULT 0 COMMENT '连续无进展轮数（产物/事实/计划/缺口都没变）',
    `recovery_count`    INT          NOT NULL DEFAULT 0 COMMENT '崩溃恢复次数（超过阈值转 PAUSED，避免无限重跑）',
    `last_progress_at`  DATETIME(3)  DEFAULT NULL COMMENT '最近一次有进展的时刻',
    `waiting_reason`    VARCHAR(500) DEFAULT NULL COMMENT 'WAITING_* 阶段在等什么（审批/外部事件/用户输入）',
    `budget_tokens`     BIGINT       NOT NULL DEFAULT 0 COMMENT 'Token 硬预算（0=用配置默认）',
    `budget_ms`         BIGINT       NOT NULL DEFAULT 0 COMMENT '机器毫秒硬预算（0=用配置默认）',
    `budget_tool_calls` INT          NOT NULL DEFAULT 0 COMMENT '工具调用硬预算（0=用配置默认）',
    `budget_rounds`     INT          NOT NULL DEFAULT 0 COMMENT '轮次硬预算（0=用配置默认）',
    `row_version`       BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本（状态迁移与调度竞争用）',
    `created_at`        DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    `started_at`        DATETIME(3)  DEFAULT NULL COMMENT '进入 RUNNING 时刻',
    `ended_at`          DATETIME(3)  DEFAULT NULL COMMENT '终态时刻',
    PRIMARY KEY (`id`),
    KEY `idx_goal_session_status` (`session_id`, `status`),
    KEY `idx_goal_user` (`user_id`),
    KEY `idx_goal_ready` (`status`, `next_run_at`),
    KEY `idx_goal_lease` (`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标模式：一个跨轮目标 + 阶段（任务运行时：权威状态/租约/续跑/证据）';

-- ============================================
-- 2026-10-22: 目标轮次表（每一轮是任务运行时的一次 attempt）
-- 为什么单独一张：续跑幂等键 goal:{id}:{n} 需要一个可审计的落点；崩溃恢复要判断
-- 「这一轮到底跑没跑、跑到了哪」，不能只看 goal 的 turn_counter。
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_goal_round` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `goal_id`         VARCHAR(64)  NOT NULL COMMENT '归属目标',
    `n`               INT          NOT NULL COMMENT '第几轮（从 1 起，幂等键 goal:{id}:{n} 的 n）',
    `status`          VARCHAR(32)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SETTLED/FAILED/INTERRUPTED',
    `turn_id`         VARCHAR(64)  DEFAULT NULL COMMENT '应用层轮号 goal:{id}:{n}',
    `lease_owner`     VARCHAR(64)  DEFAULT NULL COMMENT '起这一轮的执行者（fencing：租约被接管后旧回调不得写状态）',
    `input_hash`      VARCHAR(64)  DEFAULT NULL COMMENT '本轮输入快照的 sha256（幂等：同 key 不同输入即冲突）',
    `prompt`          TEXT         DEFAULT NULL COMMENT '本轮完整输入（审计/恢复用，不参与模型上下文）',
    `started_at`      DATETIME(3)  DEFAULT NULL COMMENT '开跑时刻',
    `ended_at`        DATETIME(3)  DEFAULT NULL COMMENT '收尾时刻',
    `error_class`     VARCHAR(32)  DEFAULT NULL COMMENT '失败分类',
    `used_tokens`     BIGINT       NOT NULL DEFAULT 0 COMMENT '本轮 token（按用量台账时间窗聚合）',
    `used_ms`         BIGINT       NOT NULL DEFAULT 0 COMMENT '本轮机器毫秒',
    `used_tool_calls` INT          NOT NULL DEFAULT 0 COMMENT '本轮工具调用数',
    `summary`         TEXT         DEFAULT NULL COMMENT '本轮交付说明（报告正文或 goal_submit.summary）',
    `proposal_json`   JSON         DEFAULT NULL COMMENT '模型提交的完成申请（goal_submit）',
    `evidence_json`   JSON         DEFAULT NULL COMMENT '本轮证据快照',
    `created_at`      DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_goal_round` (`goal_id`, `n`),
    KEY `idx_round_goal` (`goal_id`, `n`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标轮次：任务运行时的 attempt 记录（幂等/恢复/审计）';

-- ============================================
-- 2026-10-22: 目标证据表（Verifier 的输入与 Outcome 的引用）
-- 模型只能「申请完成」，证据落在这里，Verifier 逐条核验后才允许 COMPLETED。
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_goal_evidence` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `goal_id`    VARCHAR(64)  NOT NULL COMMENT '归属目标',
    `round_n`    INT          NOT NULL DEFAULT 0 COMMENT '产生/引用该证据的轮次',
    `kind`       VARCHAR(32)  NOT NULL COMMENT 'artifact/file/command/test/url/text',
    `ref`        VARCHAR(1000) DEFAULT NULL COMMENT '证据引用（产物ID/文件名/路径/URL/命令）',
    `note`       VARCHAR(2000) DEFAULT NULL COMMENT '说明',
    `hash`       VARCHAR(128) DEFAULT NULL COMMENT '内容指纹（可选）',
    `verified`   TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未核验 1=通过 2=失败',
    `created_at` DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时刻',
    PRIMARY KEY (`id`),
    KEY `idx_evidence_goal` (`goal_id`, `round_n`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标证据：Verifier 依据环境事实核验完成申请';

CREATE TABLE IF NOT EXISTS `c_ai_mcp_call_log` (
    `id`             VARCHAR(50)  NOT NULL COMMENT '调用记录ID（UUID）',
    `channel`        VARCHAR(16)  NOT NULL COMMENT '渠道: agent=智能体端点/ai/mcp/{token}，platform=平台级/ai/mcp，outbound=平台调用用户接入的外部工具',
    `tool_name`      VARCHAR(128) DEFAULT NULL COMMENT '被调用的工具名（ask-{slug} / wenqu_*）',
    `owner_uid`      VARCHAR(64)  DEFAULT NULL COMMENT '身份归属：端点=发布者uid，平台级=Key创建者uid',
    `credential_ref` VARCHAR(128) DEFAULT NULL COMMENT '凭据指代（脱敏）：token:前6位 / key:{id}，不存明文',
    `api_key_id`     VARCHAR(64)  DEFAULT NULL COMMENT '平台级入口的 API Key ID（agent 渠道为空）',
    `agent_id`       VARCHAR(64)  DEFAULT NULL COMMENT '智能体ID（agent 渠道必填，platform 指定时有值）',
    `caller_ip`      VARCHAR(64)  DEFAULT NULL COMMENT '调用者IP（X-Forwarded-For 首段）',
    `duration_ms`    BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `success`        TINYINT      NOT NULL DEFAULT 1 COMMENT '结果: 1=成功 0=失败（isError 或抛异常）',
    `error_msg`      VARCHAR(512) DEFAULT NULL COMMENT '失败原因（截断 500 字符）',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时刻',
    PRIMARY KEY (`id`),
    KEY `idx_created` (`created_at`),
    KEY `idx_channel_time` (`channel`, `created_at`),
    KEY `idx_tool` (`tool_name`),
    KEY `idx_owner` (`owner_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 调用审计日志（入站：外部客户端调 wenqu 端点；出站：模型调用户接入的外部 MCP 工具）';

CREATE TABLE IF NOT EXISTS `c_ai_workflow` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '工作流ID（UUID）',
    `uid`          VARCHAR(64)   NOT NULL COMMENT '创建者 uid',
    `name`         VARCHAR(100)  NOT NULL COMMENT '工作流名称',
    `description`  VARCHAR(500)  DEFAULT NULL COMMENT '描述',
    `dsl`          MEDIUMTEXT    NOT NULL COMMENT '工作流 DSL（JSON：version/nodes/edges，画布坐标存 position）',
    `status`       VARCHAR(16)   NOT NULL DEFAULT 'draft' COMMENT '状态: draft=草稿 published=已发布（M4 启用发布语义）',
    `share_config` VARCHAR(2000) DEFAULT NULL COMMENT '共享范围(JSON，与知识库/智能体同款两级可见性；空=仅本人，M4 前不启用)',
    `published_dsl` MEDIUMTEXT   DEFAULT NULL COMMENT 'M4：已发布版本锁定的 DSL（发布时从 dsl 拷贝；草稿继续改不影响已发布行为）',
    `published_version` INT      DEFAULT NULL COMMENT 'M4：当前发布版本号（对应 c_ai_workflow_version.version）',
    `published_at` DATETIME      DEFAULT NULL COMMENT 'M4：最近一次发布时间',
    `published_by` VARCHAR(64)   DEFAULT NULL COMMENT 'M4：最近一次发布者 uid',
    `schedule_cron` VARCHAR(64)  DEFAULT NULL COMMENT '定时触发 cron（5 段：分 时 日 月 周；NULL=未配置定时）',
    `project_id` VARCHAR(64) DEFAULT NULL COMMENT '归属工作区（c_ai_project.id；空=不属于任何工作区）。挂工作区的运行：运行记录与运行期临时会话都归到该工作区，产物随之前进',
    `schedule_timezone` VARCHAR(64) DEFAULT NULL COMMENT '定时 cron 解释时区（默认 Asia/Shanghai）',
    `schedule_enabled` TINYINT   NOT NULL DEFAULT 0 COMMENT '定时触发开关（1=启用；只跑已发布版本）',
    `schedule_next_run_at` DATETIME DEFAULT NULL COMMENT '下次定时执行时刻（扫描器据此触发；未启用为 NULL）',
    `callback_url` VARCHAR(500)  DEFAULT NULL COMMENT '运行终态回调地址（POST JSON；NULL=不回调）',
    `callback_secret` VARCHAR(128) DEFAULT NULL COMMENT '回调 HMAC-SHA256 签名密钥（NULL=不签名）',
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_uid_time` (`uid`, `update_time`),
    KEY `idx_schedule_next` (`schedule_next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义（DSL 是唯一真源，画布只是编辑器）';

CREATE TABLE IF NOT EXISTS `c_ai_workflow_run` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '运行ID（UUID）',
    `workflow_id`  VARCHAR(50)   NOT NULL COMMENT '工作流ID',
    `dsl_snapshot` MEDIUMTEXT    NOT NULL COMMENT '本次运行锁定的 DSL 快照（执行不可变，改画布不影响历史回放）',
    `trigger_type` VARCHAR(16)   NOT NULL DEFAULT 'manual' COMMENT '触发方式: manual=调试 agent=智能体绑定 api=外部Key',
    `triggered_by` VARCHAR(64)   DEFAULT NULL COMMENT '触发者 uid',
    `status`       VARCHAR(20)   NOT NULL DEFAULT 'running' COMMENT '状态: running/success/failed/timeout/waiting_approval',
    `inputs`       TEXT          DEFAULT NULL COMMENT '开始节点入参（JSON）',
    `outputs`      TEXT          DEFAULT NULL COMMENT '结束节点出参（JSON）',
    `node_traces`  MEDIUMTEXT    DEFAULT NULL COMMENT '节点级 trace（JSON 数组：nodeId/type/status/输入输出摘要/耗时）',
    `state_snapshot` MEDIUMTEXT  DEFAULT NULL COMMENT '人工审核挂起时的执行快照（已完成节点全量输出+挂起节点；恢复时短路重放用，终态运行置空）',
    `version`      INT           DEFAULT NULL COMMENT 'M4：本次运行基于的发布版本号（NULL=草稿调试运行）',
    `dsl_source`   VARCHAR(16)   NOT NULL DEFAULT 'draft' COMMENT 'M4：本次运行用的 DSL 来源: draft=草稿 published=已发布版本',
    `api_key_id`   VARCHAR(50)   DEFAULT NULL COMMENT 'M4：API 触发所用的 Key id（人工/智能体触发为 NULL）',
    `project_id` VARCHAR(64) DEFAULT NULL COMMENT '本次运行所属工作区（快照自工作流；空=无工作区）。供工作区页按工作区查运行记录',
    `error`        VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（截断 1000 字符）',
    `started_at`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '开始时刻',
    `finished_at`  DATETIME      DEFAULT NULL COMMENT '结束时刻',
    `duration_ms`  BIGINT        DEFAULT NULL COMMENT '总耗时（毫秒）',
    PRIMARY KEY (`id`),
    KEY `idx_wf_time` (`workflow_id`, `started_at`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流运行记录（每次运行锁 DSL 快照；trace 为运营闭环打地基）';

CREATE TABLE IF NOT EXISTS `c_ai_workflow_version` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '版本记录ID（UUID）',
    `workflow_id`  VARCHAR(50)   NOT NULL COMMENT '工作流ID',
    `version`      INT           NOT NULL COMMENT '版本号（同一工作流内递增，从 1 起）',
    `dsl`          MEDIUMTEXT    NOT NULL COMMENT '该版本锁定的 DSL 快照（回滚取用）',
    `note`         VARCHAR(500)  DEFAULT NULL COMMENT '发布说明',
    `published_by` VARCHAR(64)   DEFAULT NULL COMMENT '发布者 uid',
    `published_at` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wf_version` (`workflow_id`, `version`),
    KEY `idx_wf_time` (`workflow_id`, `published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流发布版本（M4：发布即落一版，回滚=以历史版本 DSL 再发一版）';

CREATE TABLE IF NOT EXISTS `c_ai_credential` (
    `id`          VARCHAR(50)   NOT NULL COMMENT '凭据ID（UUID）',
    `uid`         VARCHAR(64)   NOT NULL COMMENT '归属人 uid（个人资产，不共享）',
    `name`        VARCHAR(64)   NOT NULL COMMENT '凭据名称（工作流里以 {{credential:名称}} 引用；同一用户内唯一）',
    `value`       VARCHAR(4000) DEFAULT NULL COMMENT '凭据值（RSA 密文，复用 ConfigCryptoService；出参一律脱敏）',
    `remark`      VARCHAR(200)  DEFAULT NULL COMMENT '用途备注',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cred_uid_name` (`uid`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流凭据（http 节点引用；个人资产，值加密落库、出参脱敏）';

CREATE TABLE IF NOT EXISTS `c_ai_trace_sample` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '采样ID',
    `qa_log_id`    VARCHAR(50)  NOT NULL COMMENT '问答日志ID（唯一：一条日志只进一次池）',
    `message_id`   VARCHAR(50)  DEFAULT NULL COMMENT '回答消息ID（标注时还原全过程）',
    `source`       VARCHAR(16)  NOT NULL COMMENT '采样来源: bad=差评 nohit=无引用 random=随机',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态: pending=待标注 labeled=已回流评测集 dismissed=已忽略',
    `note`         VARCHAR(500) DEFAULT NULL COMMENT '差评原因/标注备注',
    `labeled_by`   VARCHAR(64)  DEFAULT NULL COMMENT '标注人 uid',
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '入池时间',
    `labeled_at`   DATETIME     DEFAULT NULL COMMENT '处理时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_qalog` (`qa_log_id`),
    KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P1 Trace 采样池（线上对话 → 标注 → 回流评测集）';

-- ============================================
-- P1 GraphRAG：实体关系三元组（库级开关，默认关；MySQL 起步不上图数据库）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_graph_entity` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '实体ID',
    `kb_id`         VARCHAR(50)  NOT NULL COMMENT '知识库ID（图按库隔离）',
    `name`          VARCHAR(128) NOT NULL COMMENT '实体显示名（首次见到的写法）',
    `name_norm`     VARCHAR(128) NOT NULL COMMENT '归一名（去空白/全角转半角/小写，匹配与唯一键用）',
    `aliases`       VARCHAR(1000) DEFAULT NULL COMMENT '别名(JSON数组，同一实体的其它写法)',
    `mention_count` INT          DEFAULT 0 COMMENT '提及次数（图扩展排序的弱权重）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kb_norm` (`kb_id`, `name_norm`),
    KEY `idx_kb_mention` (`kb_id`, `mention_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GraphRAG 实体表';

CREATE TABLE IF NOT EXISTS `c_ai_graph_triple` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '三元组ID',
    `kb_id`       VARCHAR(50)  NOT NULL COMMENT '知识库ID',
    `subject_id`  VARCHAR(50)  NOT NULL COMMENT '主体实体ID（c_ai_graph_entity.id）',
    `predicate`   VARCHAR(64)  NOT NULL COMMENT '关系谓词（如 母公司/位于/成立于）',
    `object_id`   VARCHAR(50)  NOT NULL COMMENT '客体实体ID',
    `chunk_id`    VARCHAR(50)  DEFAULT NULL COMMENT '溯源知识块ID（检索扩展反查真实块用）',
    `doc_id`      VARCHAR(50)  DEFAULT NULL COMMENT '溯源文档ID（按文档清图用）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_triple` (`kb_id`, `subject_id`, `predicate`, `object_id`),
    KEY `idx_kb_subj` (`kb_id`, `subject_id`),
    KEY `idx_kb_obj` (`kb_id`, `object_id`),
    KEY `idx_kb_doc` (`kb_id`, `doc_id`),
    KEY `idx_chunk` (`chunk_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GraphRAG 三元组表（LLM 从知识块抽取，仅原文陈述）';

CREATE TABLE IF NOT EXISTS `c_ai_graph_extract` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '抽取记录ID',
    `doc_id`       VARCHAR(50)  NOT NULL COMMENT '文档ID',
    `chunk_id`     VARCHAR(50)  NOT NULL COMMENT '知识块ID',
    `content_hash` VARCHAR(64)  NOT NULL COMMENT '抽取时的块内容哈希（哈希增量：一致即跳过）',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'done' COMMENT '状态: done=成功 failed=失败（失败不重试，计数可见）',
    `triple_count` INT          DEFAULT 0 COMMENT '抽出三元组条数',
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '抽取时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk` (`chunk_id`),
    KEY `idx_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GraphRAG 抽取记录（哈希增量与失败率的账本）';

CREATE TABLE IF NOT EXISTS `c_ai_session_share` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '分享ID（UUID）',
    `session_id`    VARCHAR(50)  NOT NULL COMMENT '被分享的会话ID',
    `token`         VARCHAR(64)  NOT NULL COMMENT '公开访问令牌（链接即凭据，重新开启会换新 token 使旧链接失效）',
    `enabled`       INT          NOT NULL DEFAULT 1 COMMENT '1=链接可访问 0=已停止',
    `title_snapshot` VARCHAR(200) DEFAULT NULL COMMENT '分享时刻的会话标题快照（会话改名/删除后仍能认出分享过哪一段）',
    `created_by`    VARCHAR(64)  DEFAULT NULL COMMENT '发起分享的 uid（仅会话所有者可分享）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '首次分享时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `visit_count`   INT          NOT NULL DEFAULT 0 COMMENT '链接访问次数',
    `last_visit_at` DATETIME     DEFAULT NULL COMMENT '最近访问时刻',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ss_token` (`token`),
    UNIQUE KEY `uk_ss_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话只读分享（链接持有者可看对话，不含工具过程与知识块全文）';

CREATE TABLE IF NOT EXISTS `c_ai_schedule_run` (
    `id`           VARCHAR(50)   NOT NULL COMMENT '执行记录ID（UUID）',
    `task_name`    VARCHAR(64)   NOT NULL COMMENT '任务名（ScheduleCenter 注册名，中文）',
    `trigger_type` VARCHAR(16)   NOT NULL DEFAULT 'auto' COMMENT '触发方式: startup=启动首轮 auto=周期触发 manual=手动触发',
    `success`      INT           NOT NULL DEFAULT 1 COMMENT '结果: 1=成功 0=失败',
    `error_msg`    VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（截断 1000 字符）',
    `duration_ms`  BIGINT        DEFAULT NULL COMMENT '耗时（毫秒）',
    `started_at`   DATETIME      NOT NULL COMMENT '开始时刻',
    `finished_at`  DATETIME      DEFAULT NULL COMMENT '结束时刻（与 started_at 同值附近，仅完成时落行）',
    PRIMARY KEY (`id`),
    KEY `idx_task_time` (`task_name`, `started_at`),
    KEY `idx_started` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行日志（只记有信息量的执行：失败/手动/有产出；高频任务空跑不记；按保留期定期清理）';

CREATE TABLE IF NOT EXISTS `c_ai_notification` (
    `id`          VARCHAR(50)   NOT NULL COMMENT '通知ID（UUID）',
    `uid`         VARCHAR(64)   NOT NULL COMMENT '接收人（uid；每人只看自己的通知）',
    `type`        VARCHAR(32)   NOT NULL COMMENT '类型: parse.done=解析完成 parse.failed=解析失败 parse.batch.failed=知识库批量解析失败 workflow.failed=运行失败 workflow.timeout=运行超时 workflow.approval=待人工审核 web.refresh.failed=网页源刷新失败 schedule.done=定时任务完成 schedule.failed=定时任务失败 eval.decline=检索评估下滑预警 tool.approval=工具审批待决 tool.ask=智能体提问待答 model.quota=模型供应商额度不足',
    `title`       VARCHAR(200)  NOT NULL COMMENT '一句话标题（列表主文本）',
    `content`     VARCHAR(1000) DEFAULT NULL COMMENT '详情（块数/失败原因等，落库前截断）',
    `ref_type`    VARCHAR(16)   DEFAULT NULL COMMENT '跳转目标类型: kb=知识库文档 workflow=工作流 session=会话（/chat?sid=） provider=模型供应商（/providers）',
    `ref_id`      VARCHAR(50)   DEFAULT NULL COMMENT '跳转目标 ID（kbId / workflowId / sessionId；空=不可跳转）',
    `ref_sub`     VARCHAR(64)   DEFAULT NULL COMMENT '二级跳转目标: 工作流审批=runId 工具审批=approvalId（前端据此直达可裁决位置）',
    `hit_count`   INT           NOT NULL DEFAULT 1 COMMENT '同类事件合并计数（同 dedup_key 未读期内重复发生 → +1 而非新增行；列表可显示「×N」）',
    `dedup_key`   VARCHAR(64)   DEFAULT NULL COMMENT '去重键（uid+type+dedup_key 未读期内合并；如 wf:<workflowId>/doc:<docId>/tool:<approvalId>）',
    `read_flag`   INT           NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读（read 是 MySQL 保留字，列名带 _flag 后缀）',
    `read_time`   DATETIME      DEFAULT NULL COMMENT '已读时刻',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产生时刻',
    PRIMARY KEY (`id`),
    KEY `idx_nt_uid_time` (`uid`, `create_time`),
    KEY `idx_nt_uid_unread` (`uid`, `read_flag`),
    KEY `idx_nt_uid_dedup` (`uid`, `dedup_key`, `read_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知（解析/工作流/网页源刷新等异步事件的用户可感知面；由 ScheduleCenter 按保留期定期清理）';

CREATE TABLE IF NOT EXISTS `c_ai_usage_log` (
    `id`                VARCHAR(50)  NOT NULL COMMENT '流水ID（UUID）',
    `uid`               VARCHAR(64)  DEFAULT NULL COMMENT '归属用户（NULL=无用户上下文的系统调用：索引/评测/探测等）',
    `session_id`        VARCHAR(64)  DEFAULT NULL COMMENT '触发该次调用的会话（可空）',
    `message_id`        VARCHAR(64)  DEFAULT NULL COMMENT '触发该次调用的回答消息（可空：调用先于消息落库或无归属回答）',
    `model`             VARCHAR(190) DEFAULT NULL COMMENT '模型引用 providerId/modelId（迁移前遗留名原样）',
    `kind`              VARCHAR(32)  NOT NULL DEFAULT 'chat' COMMENT '调用用途: chat=问答主链路 subagent=子智能体分支 memory=记忆提取 compress=历史压缩 citecheck=引用自检 workflow=工作流节点 graphrag=图谱 index=索引 probe=探测 eval=评测 other',
    `prompt_tokens`     BIGINT       NOT NULL DEFAULT 0 COMMENT '输入 token（含缓存命中部分，与网关 usage.prompt_tokens 同口径）',
    `completion_tokens` BIGINT       NOT NULL DEFAULT 0 COMMENT '输出 token（含思考过程与工具调用参数生成）',
    `cached_tokens`     BIGINT       NOT NULL DEFAULT 0 COMMENT '命中 prompt 缓存的输入 token',
    `total_tokens`      BIGINT       NOT NULL DEFAULT 0 COMMENT 'prompt+completion，与供应商计费总量同口径',
    `estimated`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=估算值（流被取消且网关 usage 末块未达：ESC停止/断开/护栏截断，按下发内容估算）；0=网关真实 usage',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时刻',
    PRIMARY KEY (`id`),
    KEY `idx_ul_uid_time` (`uid`, `create_time`),
    KEY `idx_ul_time` (`create_time`),
    KEY `idx_ul_model_time` (`model`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推理用量台账：所有经多供应商路由的 LLM 调用在唯一出口统一记账（个人统计/对账的唯一数据源）';

-- ============================================
-- 2026-10-09: 会话事件账本（agent 运行时的可回溯底座）
-- 只记「结构性事件」——工具开始/终态、人在回路三张卡、产物、子智能体编排、轮终态与用户停止。
-- 正文与 token/thinking/plan_delta 增量**不进本表**（那些仍在消息表，进表只会把账本写爆又没信息量）。
-- 一期只服务回放与审计，不参与模型输入；接入历史投影切换前，事件表必须继续保持这个边界。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_session_event` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID（自增即全局写入顺序，回放按它排序）',
    `session_id` VARCHAR(50)  NOT NULL COMMENT '归属会话',
    `user_id`    VARCHAR(64)  DEFAULT NULL COMMENT '发起本轮的用户（数据按人隔离，仅本人可见）',
    `turn_id`    VARCHAR(50)  DEFAULT NULL COMMENT '本轮唯一ID（一轮的多条事件按它聚成一条轨迹）',
    `goal_id`    VARCHAR(64)  DEFAULT NULL COMMENT '归属目标（c_ai_goal.id；目标态变更/验证/跨轮帧按它聚合，不新建表）',
    `type`       VARCHAR(32)  NOT NULL COMMENT '事件类型: tool/approval/ask_user/plan/artifact/subagent/agent/done/error/stop/goal_state/goal_turn/goal_verify…',
    `payload`    TEXT         DEFAULT NULL COMMENT '事件载荷摘要（工具名/状态/耗时/入参截断，上限 4000 字符）',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '发生时刻',
    PRIMARY KEY (`id`),
    KEY `idx_se_session_turn` (`session_id`, `turn_id`),
    KEY `idx_se_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话事件账本（结构性事件，服务过程回放与审计；旁路写入，失败不影响问答）';

-- ============================================
-- 退役清理（全框架化 P3：技能与记忆改由文件系统 + 框架工具承载）
-- 本文件由 spring.sql.init 在每次启动时逐条执行，故这四条 DROP 是幂等的：表不存在就是空操作。
-- 为什么必须写在这里而不是「删掉建表语句就完事」：schema.sql 只管建表，存量库里已经存在的表
--   不会因为语句被删而消失；而只要建表语句还在，删掉的表下次启动就被重建（实测踩过：16:45 手工
--   DROP，16:47 重启后四张表原样复活、0 行挂着当僵尸表）。写显式 DROP 才能让新老库一起干净。
-- 数据去向：技能正文 → 内置技能目录（classpath 随版本分发，启动时落盘到 user-data/skills-builtin）
--   与本人技能目录；长期记忆 → 工作区里的 MEMORY.md + memory/*.md。删表前的库备份是这一刀的回滚点。
-- ============================================
DROP TABLE IF EXISTS `c_ai_user_skill`;
DROP TABLE IF EXISTS `c_ai_skill_disabled`;
DROP TABLE IF EXISTS `c_ai_builtin_skill_override`;
DROP TABLE IF EXISTS `c_ai_user_memory`;

-- ============================================
-- 退役配置键清理（2026-10：全框架化收口 + 死键盘点）
-- 这些键已从 defaults() / config-schema.json 删除，代码里没有任何读取点（rerank.model 例外：
-- 它是 schema personalOnly 键，全局行按设计永远不会被读取）；存量库里的旧行只会让
-- 「改库能生效」的错觉继续存在，故每次启动幂等删除。新增键不要再往这里加——
-- 只有确认「无读取方」的退役键才进来。
-- ============================================
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    -- 聊天：全局模型/网关/提示词已退役；流式重试、计划生成超时、框架压缩三键无读取点
    'chat.model', 'chat.baseUrl', 'chat.apiKey', 'chat.completionsPath',
    'chat.systemPrompt', 'chat.userSystemPrompt', 'chat.historyRounds',
    'chat.remainTokenFloor', 'chat.truncateFallbackChars',
    'chat.agentscopeMaxContextTokens', 'chat.agentscopeTurnTimeoutSeconds',
    'chat.agentscopeCompactTriggerMessages', 'chat.planGenTimeoutMs', 'chat.streamRetryCount',
    'chat.planAutoIntent',
    -- 目标模式七个参数全部退役（2026-10-21「不设边界」）：平台总闸改由智能体级 goal_enabled 单点决定，
    -- 四条预算上限连同撞闸/预警/加预算一起删除，验收超时降为代码常量，候选提议恒开
    'chat.goalMode.enabled', 'chat.goalMode.defaultTurns', 'chat.goalMode.defaultTokens',
    'chat.goalMode.defaultToolCalls', 'chat.goalMode.defaultMinutes',
    'chat.goalMode.verifyTimeoutSeconds', 'chat.goalMode.requirementProposals',
    -- 目标确认卡等待时间随「对齐 DSH：没有确认步骤」退役（2026-10-22）：确认卡整族已删
    'chat.goalTimeoutMs',
    -- 框架状态存储恒走 Redis：开关已取消（本地文件方案退役，容器重建即丢会话上下文），
    -- 连接参数复用 spring.data.redis，不再单独保存 host/port/password
    'chat.agentscopeRedisEnabled',
    'chat.agentscopeRedisHost', 'chat.agentscopeRedisPort', 'chat.agentscopeRedisPassword',
    -- 视觉：网关与 Ollama 专属调参无读取点
    'vision.model', 'vision.baseUrl', 'vision.apiKey',
    'vision.keepAliveMinutes', 'vision.numCtx', 'vision.think',
    -- 向量：网关已归供应商表；全局维度记录无写入方也无读取方
    'embedding.model', 'embedding.baseUrl', 'embedding.apiKey',
    'embedding.embeddingsPath', 'embedding.dimensions',
    -- 上下文：历史压缩已由 AgentScope CompactionMiddleware 接管；去冗余/片段截取只服务未接线的 RetrievalPipeline
    'context.historyCompress', 'context.compressRatio', 'context.compressTimeoutMs',
    'context.safetyFactor', 'context.costCapTokens', 'context.maxOutputTokens',
    'context.snippetWindowChars', 'context.dedupEnabled', 'context.dedupThreshold',
    'context.dedupPathThreshold', 'context.adjacentMergeEnabled', 'context.adjacentMergeMaxChunks',
    -- 检索：查询改写、多路共识、检索-反思循环都是已删除的深度思考/手写运行时遗留；@ 文档块配额随 RetrievalPipeline 退役
    'retrieval.queryRewriteEnabled', 'retrieval.multiConsensusBonus', 'retrieval.reflectiveRetrieval',
    'retrieval.mentionDocChunks',
    -- 深度思考：整组由对话页按模型选择替代
    'deepReasoning.thinkingMode', 'deepReasoning.enableThinking', 'deepReasoning.prompt',
    'deepReasoning.searchTag', 'deepReasoning.maxSubQueries', 'deepReasoning.multiRetrieval',
    'deepReasoning.timeoutMillis', 'deepReasoning.maxThinkingTokens', 'deepReasoning.maxThinkingChars',
    'deepReasoning.injectThinking', 'deepReasoning.injectThinkingMaxChars',
    'deepReasoning.injectKeywords', 'deepReasoning.injectKeywordsMax',
    -- 解析/图谱：模型引用已回落库主默认聊天模型
    'parse.qaModel', 'graphrag.modelRef',
    -- 技能/记忆：面板与自研存储退役后留下的死配置
    -- 注意 skill.remoteAllowedHosts 不在此列：它是活配置（defaults() 登记、设置页可编辑，
    -- UserSkillStore/SkillController 真读它做 URL 安装白名单）。曾经误列进本名单，
    -- 而本脚本由 spring.sql.init 在建连接池时执行、早于 ConfigService.ensureDefaults()，
    -- 于是每次重启删行→ensureDefaults 按空默认值补回＝管理员白名单每次重启被清零（远程安装静默关闭）。
    'skill.injectMaxChars', 'skill.maxFileChars',
    'memory.maxPerUser', 'memory.maxInjectCount', 'memory.injectBudgetChars',
    'memory.dedupThreshold', 'memory.useSemanticInject', 'memory.platformEmbeddingRef',
    -- 2026-10-21 记忆参数收口：容量只留 memory.maxMdTokens（合并目标由它推导），日账保留期沿用框架默认 90 天
    'memory.consolidationMaxTokens', 'memory.dailyRetentionDays',
    -- 工具：低风险工具改为随总闸启用，按智能体三态 / tool.deny 收口，不再各留平台开关
    'tool.knowledgeRetrieval.enabled', 'tool.artifact.enabled',
    'tool.builtin.enabled', 'tool.subagentConsult.enabled',
    'tool.todo.enabled', 'tool.spillEnabled', 'tool.mcpCiteEnabled',
    -- 子智能体：旧并行编排器已删除，资料域改走派遣（框架子智能体）
    'agent.enabled', 'agent.subAgents', 'agent.topKPerAgent', 'agent.digestEnabled',
    'agent.aggregateMode', 'agent.digestMaxChars', 'agent.aggregateMarkFailed',
    'agent.autoRoute', 'agent.routeTimeoutMs', 'agent.maxParallelSubAgents',
    'agent.subagentMaxDepth', 'agent.subagentTimeoutMs', 'agent.subagentBriefChars',
    'agent.subagentBriefMode', 'agent.subagentBriefTurns',
    -- 2026-10-19 二次死键盘点：以下键在 defaults() / config-schema.json 中已无定义，
    -- 全仓库检索也确认零读取点；本地存量库实测仍有行（含部分 2026-09 前的旧键）。
    -- 旧问答引擎 / 意图识别 / 查询改写 / 语义缓存
    'chat.engineMode', 'chat.engineAbPercent', 'chat.showDebugDegradations',
    'chat.suggestedQuestions', 'chat.deepThinkDefault',
    'intent.enabled', 'intent.model', 'intent.prompt', 'intent.chatPrompt', 'intent.timeoutMillis',
    'queryRewrite.enabled', 'queryRewrite.historyRounds', 'queryRewrite.prompt', 'queryRewrite.promptMultiTurn',
    'semanticCache.enabled', 'semanticCache.maxEntries', 'semanticCache.threshold',
    -- 旧上下文预算（窗口已按模型声明）
    'context.defaultWindowTokens', 'context.historyMaxTokens',
    'context.historyPerMsgChars', 'context.modelWindows',
    -- 旧检索管线：融合/打分与 @ 引用扩展整组随 RetrievalPipeline 退役
    'atRef.maxChunksPerDoc', 'atRef.maxTotal',
    'retrieval.fusionMode', 'retrieval.strength', 'retrieval.positionBonus',
    'retrieval.sectionBonus', 'retrieval.titleBonus', 'retrieval.maxRefsPerBlock',
    'retrieval.refDetectEnabled', 'retrieval.refDetectMention',
    'retrieval.refExpandEnabled', 'retrieval.refExpandFuzzyName', 'retrieval.refExpandIncludeIncoming',
    'retrieval.refExpandMaxHits', 'retrieval.refExpandMaxTokens',
    'retrieval.refExpandParentEnabled', 'retrieval.refExpandParentMaxLevels',
    'retrieval.refExpandParentMode', 'retrieval.refExpandParentSummaryChars',
    'retrieval.rewriteFallbackMinHits', 'retrieval.rewriteFallbackWeakScore', 'retrieval.rewriteTimeoutMs',
    -- 深度思考自动路由（深度思考整组已退役）
    'deepReasoning.enabled', 'deepReasoning.autoRoute', 'deepReasoning.autoRouteKeywords',
    'deepReasoning.autoRouteLongChars', 'deepReasoning.autoRouteMinChars',
    -- MCP 客户端全局配置已下沉为按用户登记 c_ai_user_mcp
    'mcp.enabled', 'mcp.servers',
    -- 记忆向量化改为平台内置模型（个人层入口已下线）
    'memory.embeddingRef',
    -- rerank.model 是 personalOnly（无全局层）：历史全局行永远不会被读取，每次启动清掉；
    -- ensureDefaults 也已跳过 personalOnly，不会再种回来
    'rerank.model',
    -- 旧子智能体并行编排 / 访客共享会话
    'agent.autoDispatch', 'agent.dispatchNarrowScope', 'session.anonymousShared',
    -- 2026-10-19：「回答偏好」五项个人覆盖连同它们在系统设置里的全局那一份一起下线。
    -- 建议条数改为平台常量（RelatedPrompt.COUNT=3）、空态示例问题卡整块不再出、
    -- 个人默认助手取消（回落工作区默认→平台默认）、成员侧停用技能退场（skill.disabled 只留平台层，
    -- 故不进本清单）、个人执行上限退场（收紧只走 agent.permissionPreset.max）
    'retrieval.relatedCount', 'chat.sampleQuestionsEnabled', 'chat.sampleQuestions',
    'agent.personalDefault', 'agent.permissionPreset.personalMax'
);
-- 个人层遗留的模型引用键（parse.qaModel / graphrag.modelRef / memory.embeddingRef 曾允许个人覆盖；
-- rerank.model 的个人设置入口已下线，存量值不再被 UserConfigService 装载）
-- 2026-10-20：语义缓存与知识块交叉引用两条机制整体退场后，它们的表既无写入方也无读取方。
-- 建表定义已从本文件删除；这里对存量库补一次 DROP（幂等，口径同上面的 DELETE 名单：开发期直接覆盖，不留旧副本）。
DROP TABLE IF EXISTS `c_ai_answer_cache`;
DROP TABLE IF EXISTS `c_ai_knowledge_ref`;

DELETE FROM `c_ai_user_config` WHERE `config_key` IN (
    'parse.qaModel', 'graphrag.modelRef', 'memory.embeddingRef', 'rerank.model',
    'chat.deepThinkDefault', 'chat.historyRounds', 'chat.userSystemPrompt',
    -- 2026-10-19：这四项的个人覆盖已随字段定义一起删除，存量行永远不会再被读到
    'retrieval.relatedCount', 'chat.sampleQuestionsEnabled', 'chat.sampleQuestions',
    'agent.personalDefault', 'agent.permissionPreset.personalMax',
    -- 技能停用只剩平台一层：成员那份个人清单不再被 WenquSkillRepository 合并
    'skill.disabled'
);
