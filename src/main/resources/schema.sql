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
    `query_params` TEXT         DEFAULT NULL COMMENT '检索参数(JSON: {"retrieval.vecThreshold":"0.3",...}; 空=全部继承全局检索设置)',
    `parse_params` TEXT         DEFAULT NULL COMMENT '解析参数(JSON: chunk.maxSize/overlap/maxChunks/maxImages/structural/structuralRatio/headingDepth + visionRef; 空=全部继承全局解析设置)',
    `embedding_ref` VARCHAR(255) DEFAULT NULL COMMENT '本库绑定向量模型（引用 providerId/modelId；必填，迁移工具会把历史空值回填为退役前的全局 embedding.model）',
    `embedding_dimensions` INT DEFAULT NULL COMMENT '本库向量索引维度（绑定/切换向量模型重嵌后回写）',
    `graph_enabled` INT DEFAULT 0 COMMENT 'GraphRAG 开关（库级，默认关）: 1=解析后抽实体关系三元组，检索时一跳图扩展',
    `is_default`   INT          DEFAULT 0 COMMENT '是否默认库: 1=默认（新建文档默认归属、未指定库时的兜底）',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)',
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
    `category`     VARCHAR(100) DEFAULT NULL COMMENT '分类（前端 UI 已移除，字段保留兼容）',
    `kb_id`        VARCHAR(50)  DEFAULT NULL COMMENT '所属知识库ID（必填；启动迁移会把历史空值归入默认库）',
    `version`      INT          DEFAULT 0 COMMENT '版本号（每次解析+1，用于版本管理）',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `auto_refresh`    INT          DEFAULT 0 COMMENT '网页源自动刷新: 0=关闭 1=开启（仅 file_type=url 生效）',
    `refresh_cron`    VARCHAR(60)  DEFAULT NULL COMMENT '自动刷新 cron（5段: 分 时 日 月 周，如 0 3 * * * 每日3点）',
    `last_refresh_at` DATETIME     DEFAULT NULL COMMENT '上次自动刷新时刻',
    `next_refresh_at` DATETIME     DEFAULT NULL COMMENT '下次自动刷新时刻（ScheduleCenter 扫描据此触发）',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
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
    `created_by`  VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；游客对话与 MCP 调用以该用户身份执行检索可见性与工具）',
    `visit_count`  INT         DEFAULT 0 COMMENT '游客对话累计访问次数（每次成功发起一次游客对话 +1；发布访问统计）',
    `last_visit_at` DATETIME   DEFAULT NULL COMMENT '最近一次游客对话时间',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_token` (`token`),
    KEY `idx_share_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体公开分享配置（一个智能体一条；/s/{token} 免登录对话）';

CREATE TABLE IF NOT EXISTS `c_ai_user_memory` (
    `id`                VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `uid`               VARCHAR(64)  NOT NULL COMMENT '所属用户（记忆按用户隔离，只注入本人会话）',
    `content`           VARCHAR(500) NOT NULL COMMENT '记忆内容（一句独立可读的事实/偏好，如"用户负责XX项目的运维"）',
    `category`          VARCHAR(20)  DEFAULT 'fact' COMMENT '类别: fact=事实偏好 instruction=指令约定 project=项目背景',
    `source`            VARCHAR(20)  DEFAULT 'manual' COMMENT '来源: auto=问答后自动提取 manual=用户手动添加',
    `source_session_id` VARCHAR(50)  DEFAULT NULL COMMENT '来源会话ID（auto 时记录，便于溯源删除）',
    `hit_count`         INT          DEFAULT 0 COMMENT '被注入后续问答的次数（使用度）',
    `last_hit_at`       DATETIME     DEFAULT NULL COMMENT '最近一次被注入时间',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    `embedding`      TEXT         DEFAULT NULL COMMENT '记忆向量（JSON float 数组；语义去重与注入检索用）',
    KEY `idx_mem_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆（跨会话事实/偏好；注入本人后续问答的 system prompt）';
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
    `agent_id`      VARCHAR(50)  DEFAULT NULL COMMENT '会话级绑定的智能体（首问时锁定：显式选择或自动派遣结果；NULL=尚未绑定，空串=显式不用智能体走全局配置）',
    `agent_name`    VARCHAR(100) DEFAULT NULL COMMENT '绑定时的智能体名称快照（智能体改名/删除后会话仍可展示原名称）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_deleted_update` (`deleted`, `update_time` DESC),
    KEY `idx_user_update` (`user_id`, `deleted`, `update_time` DESC)
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
    `artifacts`   TEXT         DEFAULT NULL COMMENT '产物交付 (JSON数组: [{url,filename,size,description}])',
    `tool_calls`  MEDIUMTEXT   DEFAULT NULL COMMENT '工具调用过程 (JSON数组: [{name,status,elapsedMs,args,result|error}]；入参/输出存≤8KB全文，供前端卡片展开)',
    `timeline`    TEXT         DEFAULT NULL COMMENT '回答时间线 (JSON数组：正文区间段 / 过程区间段 / 工具下标段 / 产物下标段，记录交错顺序，刷新后还原过程视图)',
    `process_text` TEXT        DEFAULT NULL COMMENT '过程独白全文 (<process> 标签内的模型思考叙述，与正文分流；时间线 process 段区间指向本字段)',
    `tokens`      TEXT         DEFAULT NULL COMMENT 'Token 用量 (JSON: context/budget/hits/output/prompt/outputIsReal/total)',
    `attachments` TEXT         DEFAULT NULL COMMENT '附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体)',
    `agent_id`    VARCHAR(50)  DEFAULT NULL COMMENT '本轮生效的智能体（取自会话级绑定的当轮快照；NULL=未使用智能体）',
    `agent_name`  VARCHAR(100) DEFAULT NULL COMMENT '本轮智能体名称快照（智能体改名/删除后历史消息仍可展示原名称）',
    `sequence`    INT          NOT NULL DEFAULT 0 COMMENT '消息序号 (会话内递增)',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted`     INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`, `sequence`),
    KEY `idx_deleted_create` (`deleted`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI消息表';

-- ============================================
-- 2026-08-11: 问答数据闭环（日志 + 反馈）
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_qa_log` (
    `id`              VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `session_id`      VARCHAR(50)  DEFAULT NULL COMMENT '会话ID',
    `question`        TEXT         COMMENT '用户问题',
    `rewritten_query` TEXT         DEFAULT NULL COMMENT '改写后的检索用问题',
    `deep_think`      INT          DEFAULT 0 COMMENT '是否深度思考: 0=否,1=是',
    `answer_summary`  VARCHAR(1000) DEFAULT NULL COMMENT '回答摘要(前500字)',
    `hit_doc_ids`     VARCHAR(1000) DEFAULT NULL COMMENT '命中文档ID列表(逗号分隔)',
    `has_citation`    INT          DEFAULT 0 COMMENT '是否有引用标注',
    `elapsed_ms`      INT          DEFAULT 0 COMMENT '回答耗时(ms)',
    `stage_ms`        VARCHAR(500) DEFAULT NULL COMMENT '分段耗时JSON(rewrite/retrieve/generate/citation,距开始的累计ms)',
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
CREATE TABLE IF NOT EXISTS `c_ai_answer_cache` (
    `id`          VARCHAR(50)   NOT NULL COMMENT '主键ID',
    `question`    TEXT          NOT NULL COMMENT '原始问题',
    `embedding`   MEDIUMTEXT    NOT NULL COMMENT '问题向量 (JSON float 数组)',
    `answer`      MEDIUMTEXT    NOT NULL COMMENT '完整回答 (含 [N] 引用与 [图片N] 标记)',
    `sources`     TEXT          DEFAULT NULL COMMENT '引用来源 (JSON)',
    `images`      TEXT          DEFAULT NULL COMMENT '关联图片 URL (JSON 数组)',
    `related`     TEXT          DEFAULT NULL COMMENT '相关追问 (JSON 数组)',
    `message_id`  VARCHAR(50)   DEFAULT NULL COMMENT '关联消息ID (反馈用)',
    `hit_count`   INT           DEFAULT 0 COMMENT '命中次数',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_create` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='相似问题答案缓存 (知识库变更时整体清空)';

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
-- 2026-08-29: 知识块引用关系（交叉引用 1-hop 扩散 + 结构上下文扩展）
-- 纯派生数据：生命周期完全跟随知识块/文档，重解析按 doc_id 全量重建，不做逻辑删除
-- ============================================
CREATE TABLE IF NOT EXISTS `c_ai_knowledge_ref` (
    `id`                VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `doc_id`            VARCHAR(50)  NOT NULL COMMENT '所属文档ID（引用只在同文档内有效）',
    `from_knowledge_id` VARCHAR(50)  NOT NULL COMMENT '引用来源知识块ID（A）',
    `to_knowledge_id`   VARCHAR(50)  NOT NULL COMMENT '被引用目标知识块ID（B）',
    `ref_text`          VARCHAR(255) DEFAULT NULL COMMENT '原文引用表达（如"详见 4.1.2 节"/"参见「数据字典」"）',
    `create_time`       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_from_doc` (`from_knowledge_id`, `doc_id`),
    KEY `idx_to_doc` (`to_knowledge_id`, `doc_id`),
    KEY `idx_doc` (`doc_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI知识块引用关系表';

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
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)',
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
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)',
    `query_params` TEXT         DEFAULT NULL COMMENT '检索参数覆盖(JSON: {"retrieval.vectorWeight":"0.8",...}; 空=全部继承全局检索设置)',
    `system_prompt`   TEXT         DEFAULT NULL COMMENT '系统提示词覆盖（空=继承全局）',
    `knowledge_scope` VARCHAR(2000) DEFAULT NULL COMMENT '知识库范围：all 或 文档ID逗号分隔（空=all）',
    `knowledge_base_ids` VARCHAR(1000) DEFAULT NULL COMMENT '关联的知识库ID: NULL/空=默认知识库 逗号分隔=用这些库（检索作用域主路径）',
    `tool_knowledge`  INT          DEFAULT NULL COMMENT '知识库检索工具: 1=开 0=关 NULL=继承',
    `tool_builtin`    INT          DEFAULT NULL COMMENT '内置工具: 1=开 0=关 NULL=继承',
    `tool_skill`      INT          DEFAULT NULL COMMENT '技能工具(readSkill): 1=开 0=关 NULL=继承',
    `tool_artifact`   INT          DEFAULT NULL COMMENT '产物交付工具: 1=开 0=关 NULL=继承',
    `tool_mcp`        INT          DEFAULT NULL COMMENT 'MCP 工具: 1=开 0=关 NULL=继承',
    `tool_websearch`  INT          DEFAULT NULL COMMENT '联网搜索工具(webSearch): 1=开 0=关 NULL=继承（结果为网页来源，进引用体系）',
    `tool_approval_mode` VARCHAR(10) DEFAULT NULL COMMENT '有副作用工具(沙盒/MCP)执行审批: auto=自动执行 ask=执行前需用户确认(off=禁用) NULL=auto',
    `max_tool_steps`  INT          DEFAULT NULL COMMENT '单轮工具调用步数上限(全部工具合计; NULL=用全局 agent.maxToolSteps; 0=不限制)',
    `skills`          VARCHAR(1000) DEFAULT NULL COMMENT '技能范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `mcps`            VARCHAR(1000) DEFAULT NULL COMMENT 'MCP Server 范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `builtin_tools`   VARCHAR(500)  DEFAULT NULL COMMENT '内置工具范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `is_subagent`     INT           DEFAULT 0 COMMENT '是否子智能体: 0=主智能体（对话页可选） 1=子智能体（供主智能体委派）',
    `sub_agent_ids`   VARCHAR(1000) DEFAULT NULL COMMENT '主智能体可委派的子智能体ID: NULL/空=走默认多视角策略 逗号分隔=用这些',
    `knowledge_disabled` INT        DEFAULT 0 COMMENT '不使用知识库: 0=使用（按 knowledge_scope 约束） 1=纯角色智能体（整条跳过检索链路；@ 引用不受影响）',
    `workflow_id`     VARCHAR(50)  DEFAULT NULL COMMENT 'M4：绑定的工作流ID——非空时该智能体的回答由工作流产出（chatflow 语义，跑已发布版本）',
    `is_builtin`      INT           DEFAULT 0 COMMENT '内置标记: 1=系统内置（禁止删除） 0=普通',
    `is_default`      INT          DEFAULT 0 COMMENT '是否默认智能体: 0=否 1=是',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能体配置表';

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
    `default_model` VARCHAR(255) DEFAULT NULL COMMENT '个人默认聊天模型（引用 providerId/modelId；空=不设默认，对话时手动选择）',
    `default_vision_model` VARCHAR(255) DEFAULT NULL COMMENT '个人默认视觉模型（引用 providerId/modelId；空=不设默认，聊天上传图片理解用）',
    `memory_enabled` INT DEFAULT 1 COMMENT '用户级长期记忆自动提炼开关（个人设置可改）: 1=开, 0=关；历史行加列时按默认 1（保持原行为）',
    -- default_rerank_model 已随「重排归知识库检索设置」退役（存量库该列无害保留）
    `oidc_sub`     VARCHAR(255) DEFAULT NULL COMMENT 'OIDC 身份标识（IdP 的 sub；空=未绑定单点登录。唯一索引：一个 sub 只能绑一个账号，防冒用）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`uid`),
    UNIQUE KEY `uk_username` (`username`),
    -- MySQL 唯一索引允许多行 NULL ⇒ 未绑定 OIDC 的本地账号不受影响
    UNIQUE KEY `uk_oidc_sub` (`oidc_sub`),
    KEY `idx_department` (`department_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 用户表（画像/归属 + 本地登录凭据）';

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
    `thinking`     VARCHAR(16)  DEFAULT 'auto' COMMENT '思考能力(仅聊天模型有意义): auto=按模型名判定 none=不支持 switchable=可开关 always=恒思考',
    `context_window` INT        DEFAULT NULL COMMENT '上下文窗口 token（NULL=未声明，回落全局默认窗口；上下文预算=窗口×安全系数−输出限制）',
    `max_output`   INT          DEFAULT NULL COMMENT '最大输出 token（NULL=未声明，回落全局输出限制；作为 max_tokens 随请求下发）',
    `enabled`      INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注（如上下文窗口说明）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_model` (`provider_id`, `model_id`),
    KEY `idx_provider` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型库表（按类型登记供应商可用模型）';

-- ============================================
-- 2026-09-26: 技能（Skills）与 MCP Server 下沉为「个人资产」
-- 变更背景：这两类资源原先是管理员在系统设置里维护的**全局**资源——技能落在服务器目录
--   skill.dir（{技能名}/SKILL.md）+ 全局停用名单 skill.disabledNames，MCP 落在全局配置
--   mcp.servers（JSON 数组）+ mcp.enabled；普通用户既看不见也改不了（/api/ai/skill/** 与
--   /api/ai/mcp/** 对非管理员返回 403）。现改为按 uid 归属，每人各管各的。
--   系统设置只保留与"内容无关"的预算类参数（skill.injectMaxChars / skill.maxFileChars）
--   与工具调用总开关 tool.enabled；技能是否使用、MCP 连哪些服务器由用户自己决定。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_user_skill` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `uid`         VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `dir_name`    VARCHAR(64)  NOT NULL COMMENT '技能标识（详情/停用/删除的主键；沿用原「技能目录名」口径：中英文/数字/下划线/连字符）',
    `name`        VARCHAR(200) NOT NULL COMMENT '技能显示名（frontmatter name，注入系统提示用）',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '一句话描述（模型据此判断要不要读取该技能）',
    `version`     VARCHAR(32)  DEFAULT NULL COMMENT '版本（frontmatter version）',
    `content`     MEDIUMTEXT   NOT NULL COMMENT 'SKILL.md 全文（含 YAML frontmatter；只作纯文本读取，不执行其中任何内容）',
    `source`      VARCHAR(20)  DEFAULT 'user' COMMENT '来源: user=自建 url=URL 安装',
    `disabled`    INT          DEFAULT 0 COMMENT '停用: 0=生效 1=停用（停用后不注入清单、readSkill 也拒绝读取）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid_dir` (`uid`, `dir_name`),
    KEY `idx_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人技能表（ Skills：能力包正文，按用户隔离，替代原服务器技能目录）';

CREATE TABLE IF NOT EXISTS `c_ai_skill_disabled` (
    `uid`         VARCHAR(64) NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `dir_name`    VARCHAR(64) NOT NULL COMMENT '内置技能标识（classpath skills/{dirName}/SKILL.md 的目录名）',
    `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`uid`, `dir_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内置技能的个人停用标记（内置技能随版本分发、所有人可见，是否停用由各人自定）';

CREATE TABLE IF NOT EXISTS `c_ai_user_mcp` (
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `uid`         VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `name`        VARCHAR(60)  NOT NULL COMMENT '服务显示名（个人维度唯一；同时作为工具名前缀防冲突）',
    `url`         VARCHAR(512) NOT NULL COMMENT '服务地址（可含路径；streamable 默认端点 /mcp、sse 默认 /sse）',
    `type`        VARCHAR(16)  DEFAULT 'streamable' COMMENT '传输类型: streamable | sse',
    `enabled`     INT          DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用后不建立连接、不暴露其工具）',
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
    `path`        VARCHAR(200) DEFAULT NULL COMMENT '前端路由路径（如 /members）',
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
    `filename`    VARCHAR(120) NOT NULL COMMENT '产物文件名（已净化，含扩展名）',
    `ext`         VARCHAR(10)  DEFAULT NULL COMMENT '扩展名（小写，无点）',
    `size`        INT          DEFAULT 0 COMMENT '字节数',
    `object_key`  VARCHAR(255) NOT NULL COMMENT '存储相对路径 artifacts/{uid}/{yyyyMM}/{id}_{name}',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '给用户的产物说明',
    `deleted`     INT          DEFAULT 0 COMMENT '软删: 0=正常 1=已删除（文件同时删除）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_uid_time` (`uid`, `create_time`),
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产物交付表（模型生成的可下载文件）';

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
    `timezone`     VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai' COMMENT 'cron 的时区解释',
    `deep_think`   INT           DEFAULT 0 COMMENT '本轮是否深度思考: 0=否 1=是',
    `model_ref`    VARCHAR(255)  DEFAULT NULL COMMENT '模型覆盖（{providerId}/{modelId}；空=个人默认）',
    `enabled`      INT           DEFAULT 1 COMMENT '启用: 1=启用 0=停用（停用时 next_run_at 清空）',
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
    `tool_name`    VARCHAR(100)  DEFAULT NULL COMMENT '待审批工具名（沙盒/MCP）',
    `status`       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/TIMEOUT',
    `request_args` VARCHAR(2000) DEFAULT NULL COMMENT '工具入参摘要（截断 2000 字符，审计回溯用）',
    `created_at`   DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '挂起时刻',
    `resolved_at`  DATETIME      DEFAULT NULL COMMENT '裁决/超时时刻',
    PRIMARY KEY (`id`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具执行审批记录（人在回路：持久化 + 审计 + 进程重启后可见）';

CREATE TABLE IF NOT EXISTS `c_ai_mcp_call_log` (
    `id`             VARCHAR(50)  NOT NULL COMMENT '调用记录ID（UUID）',
    `channel`        VARCHAR(16)  NOT NULL COMMENT '入口渠道: agent=智能体端点/ai/mcp/{token}，platform=平台级/ai/mcp',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 服务端调用审计日志（外部客户端调用 wenqu MCP 端点的每次工具执行）';

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
    `create_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_uid_time` (`uid`, `update_time`)
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务执行日志（ScheduleCenter 每次触发完成后落一行；按保留期定期清理）';
