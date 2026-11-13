-- 定时任务升级：任务级运行配置 + 执行记录耗时/尝试次数
--
-- 背景：定时任务长期只有「cron + 启停」，失败只能靠全局通知、超时只能跟全局值、
-- 执行记录只增不减。本次把运行配置下沉到任务级，并给执行记录补上可观测字段。

ALTER TABLE `c_ai_scheduled_job`
    ADD COLUMN `notify_on` VARCHAR(16) NOT NULL DEFAULT 'all'
        COMMENT '通知偏好: all=完成和失败 failure=仅失败 none=不通知' AFTER `model_ref`,
    ADD COLUMN `max_retries` INT NOT NULL DEFAULT 0
        COMMENT '失败后最多重试次数（0=不重试；重试仍在同一任务串行）' AFTER `notify_on`,
    ADD COLUMN `retry_interval_sec` INT NOT NULL DEFAULT 60
        COMMENT '两次尝试之间的等待秒数（10~3600）' AFTER `max_retries`,
    ADD COLUMN `timeout_sec` INT DEFAULT NULL
        COMMENT '单轮超时秒数（NULL=跟随全局 scheduled.timeoutMs）' AFTER `retry_interval_sec`,
    ADD COLUMN `end_at` DATETIME DEFAULT NULL
        COMMENT '任务到期时刻（到点自动停用并记一条 skipped）' AFTER `timeout_sec`;

ALTER TABLE `c_ai_scheduled_run`
    ADD COLUMN `attempt` INT NOT NULL DEFAULT 1
        COMMENT '第几次尝试（失败重试时递增）' AFTER `trigger_type`,
    ADD COLUMN `duration_ms` INT DEFAULT NULL
        COMMENT '本轮执行耗时毫秒（finished_at - started_at，落库避免前端重算）' AFTER `finished_at`;

-- 执行记录保留清理按 create_time 裁剪；已有 idx_uid_time 不能覆盖全表清理
ALTER TABLE `c_ai_scheduled_run`
    ADD KEY `idx_create_time` (`create_time`);
