-- 能力绑定表：把 c_ai_agent 的能力列迁出，Agent 行只保留身份/人设/策略。
-- 一行 = 某智能体的某一种能力绑定；flag 三态与 selection 具体项分开存，
-- 与旧列的语义一一对应（flag NULL=跟随全局，selection NULL=全挂、''=显式不用）。
-- 存量数据回填由 AgentBindingBackfill（启动任务）用同一份 codec 完成，不在 SQL 里重复一遍映射。
CREATE TABLE IF NOT EXISTS `c_ai_agent_binding` (
    `tenant_id`   VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `id`          VARCHAR(50)  NOT NULL COMMENT '主键ID',
    `agent_id`    VARCHAR(50)  NOT NULL COMMENT '智能体ID',
    `capability`  VARCHAR(32)  NOT NULL COMMENT '能力标识：knowledge/skill/mcp/http/workflow/builtin/artifact/browser/websearch/deny',
    `flag`        TINYINT      DEFAULT NULL COMMENT '三态开关：1=开 0=关 NULL=跟随全局',
    `selection`   MEDIUMTEXT   DEFAULT NULL COMMENT '具体项：逗号串或 JSON；NULL=未指定，空串=显式不用',
    `config`      MEDIUMTEXT   DEFAULT NULL COMMENT '能力私有配置（如知识库范围/标签/过滤条件的 JSON）',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_capability` (`agent_id`, `capability`),
    KEY `idx_binding_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体能力绑定（c_ai_agent 能力列的权威存储）';
