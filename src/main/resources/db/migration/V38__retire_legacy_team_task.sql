-- 智能体团队框架化：退役自研任务板（c_ai_team_task / _edge / _event）。
-- 新链路用框架 TeamClient + c_ai_team_store（V37）。开发阶段口径：旧表数据可弃，直接 DROP。
DROP TABLE IF EXISTS `c_ai_team_task_event`;
DROP TABLE IF EXISTS `c_ai_team_task_edge`;
DROP TABLE IF EXISTS `c_ai_team_task`;
