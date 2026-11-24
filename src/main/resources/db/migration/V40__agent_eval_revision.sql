-- 评测运行记录：保存实际执行的智能体版本快照与 override 指纹，支持历史版本重测/复现。
ALTER TABLE `c_ai_agent_eval_run`
    ADD COLUMN `agent_revision` INT DEFAULT NULL COMMENT '实际执行的智能体版本快照号（c_ai_agent_version.version；NULL=当前配置）' AFTER `agent_version`,
    ADD COLUMN `override_fingerprint` VARCHAR(255) DEFAULT NULL COMMENT '本轮 AgentTurnOverride 指纹（NULL=无覆盖）' AFTER `agent_revision`;
