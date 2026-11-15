-- 【口径更正】V20 的注释写「run_id 直接取 turnId」，但实现并非如此：
-- OpenRunService.create 自己生成对外 runId，ChatService.chat 另生成框架 turnId，两者不相等。
-- V20 已执行，改文件会破坏 Flyway checksum，故在此更正口径：run_id 是【对外独立句柄】，
-- 框架 turnId 不暴露给集成方；run 与轮次的关联一律经 session_id。A1 第二批的审批 / 产物 /
-- 插话 / 停止都已验证走这条路径，不需要补 turn_id 映射列。
--
-- A 期 run 原语（第二批）：出站投递行挂 runId。
-- run.finished 的幂等要有数据库兜底，不能只靠「调用方记得去重」：
-- 同一 run 只允许一条投递行，重入/并发/扫描补发都插不进第二条。
-- MySQL 唯一键允许多个 NULL，存量 agent.turn.finished 行（run_id=NULL）不受影响。

ALTER TABLE `c_ai_webhook_delivery`
    ADD COLUMN `run_id` VARCHAR(64) DEFAULT NULL COMMENT 'v1 runId（run.finished 幂等键；轮终回调为 NULL）' AFTER `turn_id`,
    ADD UNIQUE KEY `uk_wh_run_id` (`run_id`);
