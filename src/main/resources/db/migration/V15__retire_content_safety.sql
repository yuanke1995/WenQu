-- 内容安全整体退役（2026-11-14 拍板）：输入 / 输出双闸、规则清单（c_ai_content_rule）、
-- 外部检查服务与设置面板 9 项一并下线，运行时不再有任何内容判定。
-- 配置键残留行必须删除：库里旧值已无读取点，留着会让对账/恢复默认再碰没有字段定义的孤儿行。
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    'contentSafety.enabled', 'contentSafety.provider', 'contentSafety.baseUrl',
    'contentSafety.apiKey', 'contentSafety.remoteOnInput', 'contentSafety.remoteOnOutput',
    'contentSafety.scanThinking', 'contentSafety.inputRefusal', 'contentSafety.outputSuffix');
DELETE FROM `c_ai_user_config` WHERE `config_key` IN (
    'contentSafety.enabled', 'contentSafety.provider', 'contentSafety.baseUrl',
    'contentSafety.apiKey', 'contentSafety.remoteOnInput', 'contentSafety.remoteOnOutput',
    'contentSafety.scanThinking', 'contentSafety.inputRefusal', 'contentSafety.outputSuffix');

-- 规则清单（含首批只告警的身份证号/卡号示例）随功能一起退役：
-- V1 基线仍建表（新库 V1 建表、本迁移删除，与 V2 同一口径）。
DROP TABLE IF EXISTS `c_ai_content_rule`;

-- 菜单行（menu-content-safety）不在迁移里删：RbacSeedRunner 的 RETIRED_MENUS
-- 会在启动时连行清理（含角色绑定与接口归属），且新库本就不会再种。
