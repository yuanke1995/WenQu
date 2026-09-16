-- 问渠 WenQu —— 存量库 schema 补齐脚本（2026-09-16）
-- 背景：c_ai_document / c_ai_agent / c_ai_api_key 新增 created_by + share_config + create_time + update_time；
--        c_ai_user / c_ai_department 新增 create_time + update_time。
-- 这些列已写入 src/main/resources/schema.sql，由 SchemaMigrator 在启动期自动补齐；
-- 若你的运行实例启动期未成功补齐（被静默告警吞掉），可手动跑本脚本。
--
-- 用法（用 --force 保证幂等：列已存在时报错也不中断后续语句）：
--   mysql -u root -h 127.0.0.1 -p ai_doc_assistant --force < scripts/fix-schema-2026-09-16.sql
-- （库名按你本地实际调整；若连接带库名，可去掉末尾库名参数）

-- ---------- c_ai_document ----------
ALTER TABLE `c_ai_document` ADD COLUMN `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（网关透传 X-User-Id）';
ALTER TABLE `c_ai_document` ADD COLUMN `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)';
ALTER TABLE `c_ai_document` ADD COLUMN `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `c_ai_document` ADD COLUMN `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- c_ai_api_key ----------
ALTER TABLE `c_ai_api_key` ADD COLUMN `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人';
ALTER TABLE `c_ai_api_key` ADD COLUMN `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)';
ALTER TABLE `c_ai_api_key` ADD COLUMN `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `c_ai_api_key` ADD COLUMN `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- c_ai_agent ----------
ALTER TABLE `c_ai_agent` ADD COLUMN `created_by`   VARCHAR(64)  DEFAULT NULL COMMENT '创建人（网关透传 X-User-Id）';
ALTER TABLE `c_ai_agent` ADD COLUMN `share_config` TEXT         DEFAULT NULL COMMENT '共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见)';
ALTER TABLE `c_ai_agent` ADD COLUMN `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `c_ai_agent` ADD COLUMN `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- c_ai_department ----------
ALTER TABLE `c_ai_department` ADD COLUMN `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `c_ai_department` ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- c_ai_user ----------
ALTER TABLE `c_ai_user` ADD COLUMN `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `c_ai_user` ADD COLUMN `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
