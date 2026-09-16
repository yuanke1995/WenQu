-- 存量库补列：本地登录鉴权所需（2026-09-16）
-- 背景：c_ai_user 原无密码字段（鉴权靠网关透传 X-User-Id）；引入本地登录后需补 password_hash 等。
-- 用法：mysql -u root -h 127.0.0.1 -p ai_doc_assistant --force < scripts/fix-schema-2026-09-16-auth.sql
--   --force 保证幂等：列/索引已存在时报错但不中断后续语句。

ALTER TABLE `c_ai_user` ADD COLUMN `password_hash` VARCHAR(255) DEFAULT NULL COMMENT '密码哈希（PBKDF2；空=未设置，不能本地登录）';
ALTER TABLE `c_ai_user` ADD COLUMN `login_fail_count` INT DEFAULT 0 COMMENT '连续登录失败次数';
ALTER TABLE `c_ai_user` ADD COLUMN `locked_until` DATETIME DEFAULT NULL COMMENT '锁定至（失败过多时；空=未锁定）';

-- 用户名唯一（登录可按用户名）；若存量有重复用户名，此语句会失败，请先清理重复后再执行。
ALTER TABLE `c_ai_user` ADD UNIQUE KEY `uk_username` (`username`);
