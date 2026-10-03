-- 清理已删除的「深度思考」平台开关（2026-10-03）
--
-- 背景：管理员侧的「深度思考总开关」与「自动路由」已删除。
--   · 总开关：管理员一刀切关闭深度思考，等于替所有用户决定要不要花他们的 token；
--   · 自动路由：用户显式关闭深度思考后，只要问题命中「对比/为什么/如何」等触发词，
--     后端又把它改回开启——覆盖用户选择、强制消耗用户 token，且用户无感知。
-- 是否深度思考现在完全由「用户自己的开关（对话页 per-model）」+「模型思考能力位」决定。
--
-- 本脚本只删存量库里的孤儿配置行（改完代码后跑一次即可；新库不会写入这些键）。
-- 用法：mysql -u root -p ai_doc_assistant < scripts/cleanup-deep-reasoning-switches-2026-10-03.sql

-- 1) 全局层：五个已删除的键（enabled / autoRoute / autoRouteMinChars / autoRouteLongChars / autoRouteKeywords）
DELETE FROM `c_ai_config` WHERE `config_key` IN
    ('deepReasoning.enabled',
     'deepReasoning.autoRoute',
     'deepReasoning.autoRouteMinChars',
     'deepReasoning.autoRouteLongChars',
     'deepReasoning.autoRouteKeywords',
     -- 思考入口统一为「等级下拉」后，「深度思考默认开启」失去了载体：
     -- 等级下拉自带 per-model 记忆（前端本地），不再需要服务端兜底默认值
     'chat.deepThinkDefault');

-- 2) 个人层：同步清掉同名个人覆盖行（这些键曾被 personal 字段下发过，属孤儿行）
DELETE FROM `c_ai_user_config` WHERE `config_key` IN
    ('deepReasoning.enabled',
     'deepReasoning.autoRoute',
     'deepReasoning.autoRouteMinChars',
     'deepReasoning.autoRouteLongChars',
     'deepReasoning.autoRouteKeywords',
     'chat.deepThinkDefault');

-- 说明：schema 已不再声明这些字段，ConfigService.defaults() 也不再注册它们，
-- 因此删完后不会再被重新写入（defaults 只做 insert，不会复活已删键）。
