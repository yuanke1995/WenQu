-- ============================================
-- AI 文档助手 数据库表结构
-- 数据库: ai_doc_assistant
--
-- 存量库升级：无需手动执行 ALTER。启动时 SchemaMigrator 会解析本文件，
-- 自动为存量表补齐缺失的「列」与「索引」（幂等，失败仅告警不阻塞启动）；
-- 新表由 spring.sql.init 的 CREATE TABLE IF NOT EXISTS 创建。
-- 大表补索引耗时较久时，可用 ai-app.schema-auto-index=false 关闭索引自动补齐，改由运维在窗口期执行。
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_document` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `file_name`    VARCHAR(200) DEFAULT NULL COMMENT '文件名',
    `file_type`    VARCHAR(20)  DEFAULT NULL COMMENT '文件类型: docx/pdf/xlsx',
    `chunk_count`  INT          DEFAULT 0 COMMENT '分块数量',
    `status`       INT          DEFAULT 0 COMMENT '状态: 0=生效, 1=已弃用, 2=解析中, 3=解析失败',
    `fail_reason`  VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因(status=3)',
    `parse_progress` INT        DEFAULT 0 COMMENT '解析进度0-100',
    `parse_desc`   VARCHAR(64)  DEFAULT '' COMMENT '解析阶段描述',
    `file_size`    BIGINT       DEFAULT 0 COMMENT '文件大小(字节)',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '文档描述',
    `category`     VARCHAR(100) DEFAULT NULL COMMENT '分类（前端 UI 已移除，字段保留兼容）',
    `version`      INT          DEFAULT 0 COMMENT '版本号（每次解析+1，用于版本管理）',
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      INT          DEFAULT 0 COMMENT '逻辑删除: 0=未删除, 1=已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_deleted` (`status`, `deleted`),
    KEY `idx_file_status` (`file_name`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI文档表';

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
-- 说明：idx_doc_deleted 覆盖按文档取块 + 逻辑删除过滤（增量 diff/孤儿清扫/快照/关键词路 doc 过滤）；
-- idx_doc_id 为其最左前缀、已冗余，可在窗口期手动 DROP（SchemaMigrator 不会自动删索引）。
-- 关键词检索：默认走 MySQL content/title LIKE（全表扫描，知识块量大时慢）；
-- 建议切换到 Meilisearch 引擎（keyword.engine=meilisearch + POST /api/ai/search-index/reindex），根治 LIKE 扫描。

-- ============================================
-- 2026-08-10: 会话持久化 & 历史回放
-- ============================================

CREATE TABLE IF NOT EXISTS `c_ai_session` (
    `id`            VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID无横线)',
    `user_id`       VARCHAR(64)  NOT NULL DEFAULT 'anonymous' COMMENT '归属用户（网关透传X-User-Id；anonymous=历史兼容池全局可见）',
    `title`         VARCHAR(200) DEFAULT NULL COMMENT '会话标题 (取自首条用户问题)',
    `message_count` INT          DEFAULT 0 COMMENT '消息条数',
    `is_pinned`     INT          DEFAULT 0 COMMENT '置顶: 0=否, 1=是',
    `is_favorite`   INT          DEFAULT 0 COMMENT '收藏: 0=否, 1=是',
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
    `tool_calls`  TEXT         DEFAULT NULL COMMENT '工具调用过程 (JSON数组: [{name,status,elapsedMs,args,result|error}])',
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
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_created` (`created_at`)
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
    `expire_at`    DATETIME     DEFAULT NULL COMMENT '过期时间（NULL=长期有效）',
    `last_used_at` DATETIME     DEFAULT NULL COMMENT '最近使用时间',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
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
    `description`     VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `model`           VARCHAR(255) DEFAULT NULL COMMENT '模型覆盖（空=继承全局 chat.model）',
    `system_prompt`   TEXT         DEFAULT NULL COMMENT '系统提示词覆盖（空=继承全局）',
    `knowledge_scope` VARCHAR(2000) DEFAULT NULL COMMENT '知识库范围：all 或 文档ID逗号分隔（空=all）',
    `tool_knowledge`  INT          DEFAULT NULL COMMENT '知识库检索工具: 1=开 0=关 NULL=继承',
    `tool_builtin`    INT          DEFAULT NULL COMMENT '内置工具: 1=开 0=关 NULL=继承',
    `tool_skill`      INT          DEFAULT NULL COMMENT '技能工具(readSkill): 1=开 0=关 NULL=继承',
    `tool_artifact`   INT          DEFAULT NULL COMMENT '产物交付工具: 1=开 0=关 NULL=继承',
    `tool_mcp`        INT          DEFAULT NULL COMMENT 'MCP 工具: 1=开 0=关 NULL=继承',
    `skills`          VARCHAR(1000) DEFAULT NULL COMMENT '技能范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `mcps`            VARCHAR(1000) DEFAULT NULL COMMENT 'MCP Server 范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `builtin_tools`   VARCHAR(500)  DEFAULT NULL COMMENT '内置工具范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `is_default`      INT          DEFAULT 0 COMMENT '是否默认智能体: 0=否 1=是',
    `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_default` (`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能体配置表';
