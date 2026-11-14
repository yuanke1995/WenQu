-- 分支执行方式（digest/full）退役（2026-11-14）：P5 派遣收口后所有咨询分支都跑完整问答轮，
-- 该档位在运行时没有消费点。平台参数与智能体级选择一并退役。
-- 平台键残留行必须删除：留着设置页虽有行可看（schema 已无定义），恢复默认/对账都会再碰它。
DELETE FROM `c_ai_config` WHERE `config_key` = 'agent.subagentMode';

-- 智能体级存量值随字段一起退役（运行时不读）。V1 基线仍建列（新库 V1 建列、本迁移删除，
-- 与 V2 同一口径），SchemaMigrator 的 RETIRED_COLUMNS 已把它列入不补回清单。
ALTER TABLE `c_ai_agent` DROP COLUMN `subagent_mode`;
