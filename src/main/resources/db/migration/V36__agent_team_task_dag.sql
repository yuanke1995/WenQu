-- 智能体团队任务板（DAG）：任务、依赖边、事件账本
-- 会话级团队的一部分；不做系统配置，不做跨用户协作（P0 口径）

CREATE TABLE IF NOT EXISTS `c_ai_team_task` (
    `tenant_id`       VARCHAR(32)   NOT NULL DEFAULT 'default' COMMENT '租户ID（行级隔离）',
    `id`              VARCHAR(64)   NOT NULL COMMENT '任务ID（UUID）',
    `root_session_id` VARCHAR(50)   NOT NULL COMMENT '所属团队根会话',
    `subject`         VARCHAR(500)  NOT NULL COMMENT '任务标题',
    `description`     TEXT          DEFAULT NULL COMMENT '任务说明',
    `state`           VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/READY/IN_PROGRESS/COMPLETED/FAILED/CANCELLED',
    `owner_session_id` VARCHAR(50)  DEFAULT NULL COMMENT '认领它的成员会话（根会话也可认领）',
    `owner_name`      VARCHAR(64)   DEFAULT NULL COMMENT '认领者展示名',
    `blocked_count`   INT           NOT NULL DEFAULT 0 COMMENT '未完成前置数；0 才可认领',
    `result`          TEXT          DEFAULT NULL COMMENT '完成/失败结果摘要',
    `version`         BIGINT        NOT NULL DEFAULT 0 COMMENT 'CAS 修订号',
    `created_at`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`         INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tt_root` (`tenant_id`, `root_session_id`, `deleted`, `state`),
    KEY `idx_tt_owner` (`tenant_id`, `owner_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体团队任务（DAG 节点）';

CREATE TABLE IF NOT EXISTS `c_ai_team_task_edge` (
    `tenant_id`     VARCHAR(32)  NOT NULL DEFAULT 'default',
    `id`            VARCHAR(64)  NOT NULL,
    `root_session_id` VARCHAR(50) NOT NULL,
    `from_task_id`  VARCHAR(64)  NOT NULL COMMENT '前置任务',
    `to_task_id`    VARCHAR(64)  NOT NULL COMMENT '后继任务',
    `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tte` (`tenant_id`, `from_task_id`, `to_task_id`),
    KEY `idx_tte_to` (`tenant_id`, `to_task_id`),
    KEY `idx_tte_root` (`tenant_id`, `root_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队任务依赖边';

CREATE TABLE IF NOT EXISTS `c_ai_team_task_event` (
    `tenant_id`  VARCHAR(32)  NOT NULL DEFAULT 'default',
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`    VARCHAR(64)  NOT NULL,
    `kind`       VARCHAR(24)  NOT NULL COMMENT 'created/depended/ready/claimed/completed/failed/cancelled',
    `detail`     VARCHAR(500) DEFAULT NULL,
    `created_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_tte_task` (`tenant_id`, `task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团队任务事件时间线';
