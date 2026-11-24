-- 能力绑定收尾：c_ai_agent 的能力列正式退役。
-- 前置：V41 建表 + AgentBindingBackfill 回填 + 双写已跑通；读侧默认已切到绑定表
-- （wenqu.agent.binding-read=true），本迁移删除旧列后不会再有读点落空。
--
-- 保留在 c_ai_agent 上的列（身份 / 人设 / 策略）：
--   name/icon/description/system_prompt/is_builtin/is_default/disabled/is_subagent/sub_agent_ids/
--   permission_preset/tool_approval_mode/memory_mode/goal_enabled/callback_url/callback_secret/
--   query_params/published/created_by/create_time/update_time/tenant_id
ALTER TABLE `c_ai_agent`
    DROP COLUMN `knowledge_scope`,
    DROP COLUMN `knowledge_base_ids`,
    DROP COLUMN `knowledge_tags`,
    DROP COLUMN `knowledge_filter`,
    DROP COLUMN `knowledge_disabled`,
    DROP COLUMN `tool_knowledge`,
    DROP COLUMN `tool_builtin`,
    DROP COLUMN `tool_skill`,
    DROP COLUMN `tool_artifact`,
    DROP COLUMN `tool_mcp`,
    DROP COLUMN `tool_browser`,
    DROP COLUMN `tool_websearch`,
    DROP COLUMN `tool_http`,
    DROP COLUMN `tool_workflow`,
    DROP COLUMN `tool_deny`,
    DROP COLUMN `skills`,
    DROP COLUMN `mcps`,
    DROP COLUMN `builtin_tools`,
    DROP COLUMN `http_tools`,
    DROP COLUMN `workflow_tools`;
