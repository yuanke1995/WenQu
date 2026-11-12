-- chatflow 绑定退役：智能体不再有「回答由工作流产出」的运行方式。
-- 新库由 V1 建列、本迁移删除；存量库（baseline 到 V1）执行本次 ALTER 一次。
ALTER TABLE `c_ai_agent` DROP COLUMN `workflow_id`;
