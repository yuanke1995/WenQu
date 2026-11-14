-- V7: 重排平台层退役（2026-11-14）
-- 重排模型与窗口下放知识库检索设置（c_ai_knowledge.query_params 的 rerank.model/minHits/maxHits，
-- 见 KbParamCodec），rerank.enabled 仅剩智能体级「强制关」（显式 false 经线程覆盖下发）。
-- 全局键残留行必须删除：rerank.enabled=false 会把全平台重排暗中压死，
-- 旧窗口值会盖过库级未配置时的内置默认（ConfigDefaults.RERANK_MIN_HITS/MAX_HITS）。
DELETE FROM `c_ai_config` WHERE `config_key` IN (
    'rerank.enabled',
    'rerank.model',
    'rerank.platformRef',
    'rerank.baseUrl',
    'rerank.minHits',
    'rerank.maxHits'
);

-- 个人层默认重排模型一并退役：存量个人值不再被线程装载（ChatService 个人键集已移除）
DELETE FROM `c_ai_user_config` WHERE `config_key` = 'rerank.model';
