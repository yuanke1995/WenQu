-- 智能体团队：AgentScope 框架 TeamClient(LocalTeamClient) 的持久 BaseStore 后端
-- 表只被 WenquMysqlBaseStore 用 JdbcTemplate 直连访问，不经 MyBatis 租户拦截器；
-- 租户信息由 namespace 段携带（teams/{tenant}:{rootSessionId}/{teamName}/...）。
CREATE TABLE IF NOT EXISTS `c_ai_team_store` (
    `namespace`  VARCHAR(255) NOT NULL COMMENT '命名空间路径：teams/{ns}/{team}[/tasks|members|messages]',
    `item_key`   VARCHAR(191) NOT NULL COMMENT '命名空间内键（任务id / 成员名 / 消息序号）',
    `seq`        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '全局插入序，search 按它排序（消息/任务的时间顺序依赖它）',
    `version`    BIGINT       NOT NULL DEFAULT 1 COMMENT 'CAS 修订号，putIfVersion 的比较依据',
    `value_json` LONGTEXT     NOT NULL COMMENT '值（JSON；TeamTask/TeamMemberInfo/TeamMessage 的 toMap 结果）',
    `updated_at` DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`namespace`, `item_key`),
    UNIQUE KEY `uk_team_store_seq` (`seq`),
    KEY `idx_team_store_ns` (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体团队框架 BaseStore（任务板/邮箱/名册）';
