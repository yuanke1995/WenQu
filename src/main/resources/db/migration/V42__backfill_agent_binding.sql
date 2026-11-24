-- 能力绑定回填：必须在「建表(V41)」之后、「删列(V43)」之前执行。
-- 为什么不能用启动任务回填：Flyway 在应用启动任务之前跑完，V43 删列时数据还没搬走，
-- 演练库上实测「绑定表 0 行、能力列已删」= 真实数据丢失。
--
-- 语义与 AgentBindingCodec 一致：
--   flag      三态（1/0/NULL）
--   selection 具体项（NULL=未指定、''=显式不用、非空=具体项）
--   config    知识库能力的私有配置（JSON）
-- 只搬「有值」的能力；全 NULL 的智能体不产生绑定行（保持「未配置」语义）。

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'knowledge', a.`tool_knowledge`, NULL,
       JSON_OBJECT(
           'knowledgeBaseIds', a.`knowledge_base_ids`,
           'knowledgeTags',    a.`knowledge_tags`,
           'knowledgeFilter',  a.`knowledge_filter`,
           'knowledgeScope',   a.`knowledge_scope`,
           'knowledgeDisabled', a.`knowledge_disabled`),
       NOW()
FROM `c_ai_agent` a
WHERE a.`tool_knowledge` IS NOT NULL
   OR a.`knowledge_base_ids` IS NOT NULL
   OR a.`knowledge_tags` IS NOT NULL
   OR a.`knowledge_filter` IS NOT NULL
   OR a.`knowledge_scope` IS NOT NULL
   OR a.`knowledge_disabled` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'skill', a.`tool_skill`, a.`skills`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_skill` IS NOT NULL OR a.`skills` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'mcp', a.`tool_mcp`, a.`mcps`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_mcp` IS NOT NULL OR a.`mcps` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'http', a.`tool_http`, a.`http_tools`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_http` IS NOT NULL OR a.`http_tools` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'workflow', a.`tool_workflow`, a.`workflow_tools`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_workflow` IS NOT NULL OR a.`workflow_tools` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'builtin', a.`tool_builtin`, a.`builtin_tools`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_builtin` IS NOT NULL OR a.`builtin_tools` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'artifact', a.`tool_artifact`, NULL, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_artifact` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'browser', a.`tool_browser`, NULL, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_browser` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'websearch', a.`tool_websearch`, NULL, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_websearch` IS NOT NULL;

INSERT INTO `c_ai_agent_binding`
    (`id`, `tenant_id`, `agent_id`, `capability`, `flag`, `selection`, `config`, `update_time`)
SELECT REPLACE(UUID(), '-', ''), a.`tenant_id`, a.`id`, 'deny', NULL, a.`tool_deny`, NULL, NOW()
FROM `c_ai_agent` a
WHERE a.`tool_deny` IS NOT NULL;
