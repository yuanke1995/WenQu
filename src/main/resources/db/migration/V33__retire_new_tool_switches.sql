-- 工具族开关收口（2026-11-20）：新增「文档处理 / 桌面操作 / 历史对话检索 / MCP 资源」四族时，
-- 曾经各配了一把平台开关 + 一个 office.sofficePath 设置项。按仓库既有口径
-- 「低风险工具族不再各留平台开关，靠 tool.enabled 总闸、权限档位与停用名单收口」，
-- 这五把键已从 config-schema.json 与 defaults() 移除：
--   · tool.office.enabled / tool.sessionHistory.enabled / mcp.resource.enabled → 无平台开关
--   · tool.computerUse.enabled → 改由会话档位当闸门（只在「完全权限」档挂载）
--   · office.sofficePath → 降为部署级（环境变量 WENQU_SOFFICE / JVM 属性 office.sofficePath）
--   · tool.readImage.enabled → 读图也是只读能力，跟随视觉模型能力与文件系统可用性，不再单设开关
-- 存量库里若已播种过这几行（本地起过一次应用就会种），留着就是 driftReport 之外的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    'tool.office.enabled', 'tool.computerUse.enabled', 'tool.sessionHistory.enabled',
    'mcp.resource.enabled', 'office.sofficePath', 'tool.readImage.enabled');
DELETE FROM `c_ai_user_config` WHERE `config_key` IN (
    'tool.office.enabled', 'tool.computerUse.enabled', 'tool.sessionHistory.enabled',
    'mcp.resource.enabled', 'office.sofficePath', 'tool.readImage.enabled');
