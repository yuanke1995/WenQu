-- 深度执行（长任务）设置整体退役（2026-11-14 拍板）：四项预算旋钮下线，
-- 自动推进轮数上限固化为代码常量 GoalSettings.MAX_ROUNDS = 20；
-- Token / 机器时长 / 工具调用三项硬预算整个机制随设置退役（不再有读取点）。
-- 配置行必须删除：库里旧值已无读取点，留着会让对账/恢复默认再碰没有字段定义的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    'chat.goal.maxRounds', 'chat.goal.maxTokens', 'chat.goal.maxMs', 'chat.goal.maxToolCalls');
DELETE FROM `c_ai_user_config` WHERE `config_key` IN (
    'chat.goal.maxRounds', 'chat.goal.maxTokens', 'chat.goal.maxMs', 'chat.goal.maxToolCalls');

-- 目标行的四列预算快照（创建时从设置抄一份）随机制一起退役：
-- 建目标时不再写快照，读取点全部改为代码常量/不再读取，列留着只会让存量行携带已经失效的旧限额。
ALTER TABLE `c_ai_goal` DROP COLUMN `budget_tokens`;
ALTER TABLE `c_ai_goal` DROP COLUMN `budget_ms`;
ALTER TABLE `c_ai_goal` DROP COLUMN `budget_tool_calls`;
ALTER TABLE `c_ai_goal` DROP COLUMN `budget_rounds`;
