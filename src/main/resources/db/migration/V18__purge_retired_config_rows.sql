-- 僵尸配置行清理（2026-11-14）：132→90 字段收口时删掉的 42 个键里，V10~V16 只清了 22 个。
-- 下面 20 个键要么没有任何迁移写过 DELETE，要么只写在 V1__baseline.sql 的退役清单里——
-- baseline-on-migrate=true 时 V1 对存量库只标记不重放，等于没清。
-- 末尾 4 个是内容安全隐藏键：V15 只删了 9 个设置面板键，它们同样只出现在 V1 清单里。
-- 全部键在 Java 侧已零读取点、defaults() 也不再播种；留着就是 driftReport 不报的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    'chat.asyncWorkspaceUpgrade', 'chat.citationCheckEnabled', 'chat.mcpEnabled',
    'chat.prefixStabilizer', 'chat.profileAuto', 'chat.toolDescriptionCompact',
    'graphrag.expandTopK', 'httpTool.allowPrivateNetwork', 'httpTool.enabled',
    'images.authEnabled', 'keyword.reconcileOnStartup', 'workflowTool.enabled',
    'agent.permissionPreset.default', 'agent.permissionPreset.max',
    'agentEval.enabled', 'agentEval.gateMode', 'skill.disabled',
    'skill.remoteAllowedHosts', 'tool.deny', 'trace.sessionEventEnabled',
    'contentSafety.timeoutMs', 'contentSafety.remoteCheckChars',
    'contentSafety.outputHoldChars', 'contentSafety.maxScanChars');
DELETE FROM `c_ai_user_config` WHERE `config_key` IN (
    'chat.asyncWorkspaceUpgrade', 'chat.citationCheckEnabled', 'chat.mcpEnabled',
    'chat.prefixStabilizer', 'chat.profileAuto', 'chat.toolDescriptionCompact',
    'graphrag.expandTopK', 'httpTool.allowPrivateNetwork', 'httpTool.enabled',
    'images.authEnabled', 'keyword.reconcileOnStartup', 'workflowTool.enabled',
    'agent.permissionPreset.default', 'agent.permissionPreset.max',
    'agentEval.enabled', 'agentEval.gateMode', 'skill.disabled',
    'skill.remoteAllowedHosts', 'tool.deny', 'trace.sessionEventEnabled',
    'contentSafety.timeoutMs', 'contentSafety.remoteCheckChars',
    'contentSafety.outputHoldChars', 'contentSafety.maxScanChars');
