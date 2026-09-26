-- ============================================
-- 问渠 WenQu —— AI 文档问答助手 数据库表结构
-- 数据库: ai_doc_assistant（库名保持不变，改名需数据迁移）
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
    `query_params` TEXT         DEFAULT NULL COMMENT '检索参数(JSON: {"retrieval.vecThreshold":"0.3",...}; 空=全部继承全局检索设置)',
    `parse_params` TEXT         DEFAULT NULL COMMENT '解析参数(JSON: chunk.maxSize/overlap/maxChunks/maxImages/structural/structuralRatio/headingDepth + visionRef; 空=全部继承全局解析设置)',
    `embedding_ref` VARCHAR(255) DEFAULT NULL COMMENT '本库绑定向量模型（引用 providerId/modelId；必填，迁移工具会把历史空值回填为退役前的全局 embedding.model）',
    `embedding_dimensions` INT DEFAULT NULL COMMENT '本库向量索引维度（绑定/切换向量模型重嵌后回写）',
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
    `file_type`    VARCHAR(20)  DEFAULT NULL COMMENT '文件类型: docx/pdf/xlsx',
    `chunk_count`  INT          DEFAULT 0 COMMENT '分块数量',
    `status`       INT          DEFAULT 0 COMMENT '状态: 0=生效, 1=已弃用, 2=解析中, 3=解析失败',
    `fail_reason`  VARCHAR(500) DEFAULT NULL COMMENT '解析失败原因(status=3)',
    `parse_progress` INT        DEFAULT 0 COMMENT '解析进度0-100',
    `parse_desc`   VARCHAR(64)  DEFAULT '' COMMENT '解析阶段描述',
    `file_size`    BIGINT       DEFAULT 0 COMMENT '文件大小(字节)',
    `description`  VARCHAR(500) DEFAULT NULL COMMENT '文档描述',
    `category`     VARCHAR(100) DEFAULT NULL COMMENT '分类（前端 UI 已移除，字段保留兼容）',
    `kb_id`        VARCHAR(50)  DEFAULT NULL COMMENT '所属知识库ID（必填；启动迁移会把历史空值归入默认库）',
    `version`      INT          DEFAULT 0 COMMENT '版本号（每次解析+1，用于版本管理）',
    `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid；未登录为 anonymous）',
    `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)',
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
    `user_id`       VARCHAR(64)  NOT NULL DEFAULT 'anonymous' COMMENT '归属用户（登录用户 uid；anonymous=历史兼容池全局可见）',
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
    `attachments` TEXT         DEFAULT NULL COMMENT '附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体)',
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
    `skills`          VARCHAR(1000) DEFAULT NULL COMMENT '技能范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `mcps`            VARCHAR(1000) DEFAULT NULL COMMENT 'MCP Server 范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `builtin_tools`   VARCHAR(500)  DEFAULT NULL COMMENT '内置工具范围: NULL=跟随全局 空串=不使用 逗号分隔=仅用这些',
    `is_subagent`     INT           DEFAULT 0 COMMENT '是否子智能体: 0=主智能体（对话页可选） 1=子智能体（供主智能体委派）',
    `sub_agent_ids`   VARCHAR(1000) DEFAULT NULL COMMENT '主智能体可委派的子智能体ID: NULL/空=走默认多视角策略 逗号分隔=用这些',
    `knowledge_disabled` INT        DEFAULT 0 COMMENT '不使用知识库: 0=使用（按 knowledge_scope 约束） 1=纯角色智能体（整条跳过检索链路；@ 引用不受影响）',
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
    -- default_rerank_model 已随「重排归知识库检索设置」退役（存量库该列无害保留）
    `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`uid`),
    UNIQUE KEY `uk_username` (`username`),
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
    `create_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型供应商表';

CREATE TABLE IF NOT EXISTS `c_ai_model` (
    `id`           VARCHAR(50)  NOT NULL COMMENT '主键ID (UUID)',
    `provider_id`  VARCHAR(50)  NOT NULL COMMENT '所属供应商（c_ai_provider.id）',
    `model_id`     VARCHAR(255) NOT NULL COMMENT '模型名（调用 API 时 model 参数原样透传）',
    `display_name` VARCHAR(255) DEFAULT NULL COMMENT '展示名（空=同 model_id）',
    `model_type`   VARCHAR(16)  DEFAULT 'chat' COMMENT '类型: chat=聊天 vision=视觉 embedding=向量 rerank=重排 other=其他',
    `thinking`     VARCHAR(16)  DEFAULT 'auto' COMMENT '思考能力(仅聊天模型有意义): auto=按模型名判定 none=不支持 switchable=可开关 always=恒思考',
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
