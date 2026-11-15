-- A 期 run 原语：对外运行映射表。
-- run_id 直接取 turnId（消息 / 审批 / 事件账本 / webhook 的共同幂等键），不另造第二套身份。
-- 这张表只做「快速归属 + 对外状态查询」，不承载执行状态机；不动任何现有表的归属键。

CREATE TABLE IF NOT EXISTS `c_ai_open_run` (
    `tenant_id`       VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `run_id`          VARCHAR(64)  NOT NULL COMMENT '对外 runId，等于 turnId',
    `session_id`      VARCHAR(50)  NOT NULL COMMENT '承载本轮的会话',
    `uid`             VARCHAR(64)  NOT NULL COMMENT '归属用户（API Key 恒为签发人本人）',
    `agent_id`        VARCHAR(50)  DEFAULT NULL COMMENT '发起时指定的智能体',
    `api_key_id`      VARCHAR(50)  DEFAULT NULL COMMENT '发起该 run 的 API Key',
    `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT 'Idempotency-Key；NULL=未指定',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'running' COMMENT 'running | succeeded | failed | stopped | interrupted',
    `error_code`      VARCHAR(64)  DEFAULT NULL COMMENT '稳定错误码（不暴露堆栈）',
    `error_message`   VARCHAR(500) DEFAULT NULL COMMENT '可读错误原因',
    `answer`          VARCHAR(4000) DEFAULT NULL COMMENT '终态回答摘录（正文在结果会话里）',
    `idempotent_hit`  TINYINT      NOT NULL DEFAULT 0 COMMENT '本次是否命中幂等重放',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finish_time`     DATETIME     DEFAULT NULL,
    PRIMARY KEY (`run_id`),
    UNIQUE KEY `uk_open_run_idem` (`uid`, `idempotency_key`),
    KEY `idx_open_run_uid_time` (`uid`, `create_time`),
    KEY `idx_open_run_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对外开放运行（run）映射：turnId 投影给集成方的句柄';
