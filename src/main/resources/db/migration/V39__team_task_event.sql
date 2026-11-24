-- 智能体团队：任务事件账本（框架任务板不保留逐条事件，这里按 TeamClient 变更投影一份）
-- 只写不读框架内部数据：WenquTeamClient 在 create/assign/claim/unclaim/complete/fail 与
-- team_task_depend 时各记一行，TeamController 按 (root, taskId) 读出「创建→依赖→认领→完成」时间线。
-- 用 JdbcTemplate 直连，不经 MyBatis 租户拦截器；tenant_id 显式落列，查询按它过滤。
CREATE TABLE IF NOT EXISTS `c_ai_team_task_event` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `tenant_id`       VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户（TenantContext 当前值）',
    `root_session_id` VARCHAR(50)  NOT NULL COMMENT '团队根会话 id（一棵树一个团队）',
    `task_id`         VARCHAR(64)  NOT NULL COMMENT '框架任务 id',
    `kind`            VARCHAR(24)  NOT NULL COMMENT 'created/assigned/claimed/unclaimed/completed/failed/depended',
    `detail`          VARCHAR(600) DEFAULT NULL COMMENT '事件摘要（标题 / 认领人 / 结果 / 依赖）',
    `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_team_task_event` (`tenant_id`, `root_session_id`, `task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体团队任务事件时间线';
