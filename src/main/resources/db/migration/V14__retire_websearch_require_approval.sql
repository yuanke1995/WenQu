-- 联网搜索「纳入执行审批」设置项退役（2026-11-14 拍板）：审批恒开，联网是唯一会向外部
-- 第三方发请求、可能对用户计费的能力，让模型自主决定花谁的钱不该是默认态。
-- 运行时不再读 webSearch.requireApproval（WebSearchToolProvider 声明 requiresApproval 恒真）。
-- 残留配置行必须删除：库里旧值（即便有人关过）已无读取点，
-- 留着会让对账/恢复默认再碰一个没有字段定义的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` = 'webSearch.requireApproval';
DELETE FROM `c_ai_user_config` WHERE `config_key` = 'webSearch.requireApproval';
