-- 问渠 WenQu —— 用量台账孤儿引用清理脚本（2026-10-16）
-- 背景：100 路并发压测（scripts/loadtest/loadtest.mjs）与假上游探针各建了临时供应商，
--        台账 c_ai_usage_log.model 记的是 `providerId/modelId` 完整引用。压测收尾只删供应商和
--        压测用户（loadtest.mjs 的 cleanup 明确写着「残留会话请用 SQL 清理」），台账行不删；
--        供应商一删，这些 model 引用在 c_ai_provider / c_ai_model 里再也查不到对应记录，
--        单价永远无处登记 → 管理端「数据看板 → 费用」的「未计价模型」被 208 个
--        `<uuid>/mock-chat` 标签占满（合计仅 15,432 tokens，占区间 0.08%），
--        而「按用户」表 100 行是已删除的 loadtest_001..100 幽灵用户。
--
-- 判据（不用模型名白名单，按引用是否还指得通）：
--   model 形如 `providerId/modelId` 且 providerId 段不在 c_ai_provider 里 = 孤儿，删除。
--   这样一并覆盖 mock-chat 与探针自造的 fake-slow，也不依赖中文条件（本机 mysql 客户端
--   对中文条件会静默不匹配）。遗留「纯模型名」行（不含 /）不在判据内，保持原样。
--
-- 用法（生产库同样适用；备份表留在库里，确认无误后再手工 DROP）：
--   mysql -u root -p ai_doc_assistant < scripts/db/cleanup-usage-ledger-orphans-2026-10-16.sql

-- 1) 命中预检：先看要删多少行、多少 token，与预期不符就停手别往下执行
SELECT COUNT(*)                    AS rows_to_delete,
       COUNT(DISTINCT `uid`)       AS uids_touched,
       COUNT(DISTINCT `model`)     AS models_touched,
       COALESCE(SUM(`total_tokens`), 0) AS tokens_to_delete
FROM `c_ai_usage_log`
WHERE `model` LIKE '%/%'
  AND SUBSTRING_INDEX(`model`, '/', 1) NOT IN (SELECT `id` FROM `c_ai_provider`);

-- 2) 备份：整段落进带日期的备份表（重复执行会覆盖上一版备份，确认已不需要再跑）
DROP TABLE IF EXISTS `c_ai_usage_log_bak_20261016`;
CREATE TABLE `c_ai_usage_log_bak_20261016` AS
SELECT * FROM `c_ai_usage_log`
WHERE `model` LIKE '%/%'
  AND SUBSTRING_INDEX(`model`, '/', 1) NOT IN (SELECT `id` FROM `c_ai_provider`);

-- 3) 删除孤儿台账行
DELETE FROM `c_ai_usage_log`
WHERE `model` LIKE '%/%'
  AND SUBSTRING_INDEX(`model`, '/', 1) NOT IN (SELECT `id` FROM `c_ai_provider`);

-- 4) 验证：应返回 0 行；且「按用户」维度不再出现 loadtest_ 前缀
SELECT COUNT(*) AS remaining_orphans FROM `c_ai_usage_log`
WHERE `model` LIKE '%/%'
  AND SUBSTRING_INDEX(`model`, '/', 1) NOT IN (SELECT `id` FROM `c_ai_provider`);

SELECT COUNT(*) AS loadtest_ledger_rows FROM `c_ai_usage_log` WHERE `uid` LIKE 'loadtest\_%';

-- 5) 回滚（仅在需要时执行）：
-- INSERT INTO `c_ai_usage_log` SELECT * FROM `c_ai_usage_log_bak_20261016`;
-- 确认无需回滚后：DROP TABLE `c_ai_usage_log_bak_20261016`;
