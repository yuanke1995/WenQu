-- 智能体团队底座：持久成员会话 + 会话级团队开关
-- 不新增任何系统级配置项；开关只落在会话行上（team_enabled）

ALTER TABLE `c_ai_session`
    ADD COLUMN `team_enabled` INT NOT NULL DEFAULT 0 COMMENT '会话级团队开关：1=本会话开启智能体团队模式（仅根会话有意义，成员会话恒为0）；0=关闭。不是系统配置',
    ADD COLUMN `root_session_id` VARCHAR(50) DEFAULT NULL COMMENT '所属团队根会话 id（父子传递，便于成员发现与冷恢复；根会话自身为 NULL）',
    ADD COLUMN `parent_session_id` VARCHAR(50) DEFAULT NULL COMMENT '直接创建者会话 id（成员树边；根会话自身为 NULL）',
    ADD COLUMN `member_name` VARCHAR(64) DEFAULT NULL COMMENT '团队内模型可见名（短名 reviewer 或全路径 reviewer/checker）；仅 kind=team 有值',
    ADD COLUMN `member_state` VARCHAR(24) DEFAULT NULL COMMENT '成员在线快照：IDLE/RUNNING/WAITING/STOPPED/OFFLINE；仅 kind=team 有值，重启后按活实例重建',
    ADD COLUMN `last_read_event_id` BIGINT NOT NULL DEFAULT 0 COMMENT '团队成员已读消息的事件游标（c_ai_session_event.id）；仅 kind=team 有意义，避免未读消息重复注入',
    ADD KEY `idx_team_root` (`tenant_id`, `root_session_id`, `deleted`),
    ADD KEY `idx_team_parent` (`tenant_id`, `parent_session_id`);

-- kind 列注释更新：加 team
ALTER TABLE `c_ai_session`
    MODIFY COLUMN `kind` VARCHAR(16) NOT NULL DEFAULT 'chat'
    COMMENT '会话种类：chat=用户对话；temp=平台内部临时会话（跑完即删）；team=智能体团队的持久成员会话（可恢复、可被消息唤醒，不进普通会话列表）';
