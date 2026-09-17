-- 问渠：初始化管理员账号 + 存量数据归属 admin（2026-09-17）
-- 用法：mysql -uroot -p ai_doc_assistant < scripts/init-admin-2026-09-17.sql
-- 前置：先执行 src/main/resources/schema.sql（CREATE TABLE IF NOT EXISTS，幂等建表）
--       mysql -uroot -p ai_doc_assistant < src/main/resources/schema.sql
-- 说明：本脚本幂等，可重复执行；ADD COLUMN 若列已存在会报 1060，可忽略。

-- ========== 1) 补字段（存量表：CREATE TABLE IF NOT EXISTS 不会补列，需显式 ALTER） ==========
-- c_ai_user 的登录字段由 schema.sql 建表时自带；c_ai_api_key.created_by 已存在，均无需 ALTER
ALTER TABLE `c_ai_document`  ADD COLUMN `created_by`       VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid）';
ALTER TABLE `c_ai_agent`     ADD COLUMN `created_by`       VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户 uid）';

-- ========== 2) 管理员账号 admin / admin@2026 ==========
-- 哈希由 com.wisesoft.ai.util.AuthCrypto（PBKDF2WithHmacSHA256 / 120000 轮 / 16 字节盐）生成，已自校验通过
INSERT INTO `c_ai_user` (`uid`, `username`, `role`, `status`, `password_hash`, `login_fail_count`)
VALUES ('admin', 'admin', 'superadmin', 1,
        'pbkdf2$120000$hyXgs2nNAlTjBqqiei2Z2g$MWqzWkXruSmYjO90vRwtJ3mZTAoENM0umk6lM59m3I8', 0)
ON DUPLICATE KEY UPDATE
    `username`         = 'admin',
    `role`             = 'superadmin',
    `status`           = 1,
    `password_hash`    = VALUES(`password_hash`),
    `login_fail_count` = 0,
    `locked_until`     = NULL;

-- ========== 3) 存量数据全部归属 admin ==========
-- 库里原有 anonymous 池 + 10 种历史 localStorage 随机 uid（4e8c8ab7…/rt test/feat-test 等），一并归拢到 admin
UPDATE `c_ai_session`  SET `user_id`    = 'admin';
UPDATE `c_ai_document` SET `created_by` = 'admin';
UPDATE `c_ai_agent`    SET `created_by` = 'admin';
UPDATE `c_ai_api_key`  SET `created_by` = 'admin';

-- ========== 4) 校验（看结果，确认是否还有其它 uid 需要归拢） ==========
SELECT '--- 用户表 ---' AS `检查项`;
SELECT `uid`, `username`, `role`, `status`, LEFT(`password_hash`, 24) AS `hash前缀` FROM `c_ai_user`;

SELECT '--- 会话归属分布 ---' AS `检查项`;
SELECT `user_id`, COUNT(*) AS `会话数` FROM `c_ai_session` WHERE `deleted` = 0 GROUP BY `user_id`;

SELECT '--- 文档/智能体/Key 归属分布 ---' AS `检查项`;
SELECT 'document' AS `表`, IFNULL(`created_by`, '(null)') AS `创建人`, COUNT(*) AS `条数` FROM `c_ai_document` GROUP BY `created_by`
UNION ALL SELECT 'agent',    IFNULL(`created_by`, '(null)'), COUNT(*) FROM `c_ai_agent`   GROUP BY `created_by`
UNION ALL SELECT 'api_key',  IFNULL(`created_by`, '(null)'), COUNT(*) FROM `c_ai_api_key` GROUP BY `created_by`;
