-- 智能体停用开关：与「删除」区分——停用只是不可用，配置与版本历史都保留，随时可恢复。
-- 停用后：对话页不可选、@/委派不可用、公开分享链接与 MCP 端点立即失效；
-- 管理列表仍可见（否则停用后就没人能把它启用回来）。
-- 默认智能体不允许停用：会话未显式指定 agentId 时由 defaultAgent() 作答，
-- 停掉默认会让所有人静默掉回全局配置（见 AgentService.setDefault / doUpdate 的 fail-loud 校验）。

ALTER TABLE `c_ai_agent`
    ADD COLUMN `disabled` INT NOT NULL DEFAULT 0
        COMMENT '停用标记: 0=启用 1=停用（对话页/委派/分享不可用，配置与版本保留）' AFTER `is_default`;
