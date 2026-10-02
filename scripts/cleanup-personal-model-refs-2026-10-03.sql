-- 问渠 WenQu —— 个人专属模型键的全局遗留行清理（2026-10-03）
-- 背景：rerank.model / memory.embeddingRef / graphrag.modelRef / parse.qaModel 已改为
--       「个人设置项」（schema 标记 personalOnly）：值 = 某个用户登记的私有模型，按使用身份
--       （问答按提问者、解析/抽取按库主）解析，全局层（c_ai_config）不参与读取——
--       从根上消除"管理员把自己的模型配成全局默认、被全平台静默借用"的隔离漏洞。
--
-- 现状：存量全局行对代码已不可见（ConfigService.get 对 personalOnly 直接跳过全局缓存/默认值），
--       删除供应商时这类残留也只告警不阻挡；这里物理清掉，保持库面干净、守门日志不再念到它们。
--
-- 影响：清理后相关能力按个人设置解析；未设置的用户按各自口径降级——
--       重排回落本地 rerank.baseUrl 服务；记忆去重降级精确匹配、语义注入关闭；
--       GraphRAG 抽取 / 问答对生成跳过并在解析终态告警。
--       请引导使用者在「个人设置 → 聊天模型与偏好 → 模型默认」补配自己登记的模型。
--
-- 用法：mysql -u root -p ai_doc_assistant < scripts/cleanup-personal-model-refs-2026-10-03.sql

-- 1) 清理四个个人专属键的历史全局行
DELETE FROM `c_ai_config` WHERE `config_key` IN
  ('rerank.model', 'memory.embeddingRef', 'graphrag.modelRef', 'parse.qaModel');

-- 2) 验证：应返回空集
SELECT `config_key`, `config_value` FROM `c_ai_config`
WHERE `config_key` IN ('rerank.model', 'memory.embeddingRef', 'graphrag.modelRef', 'parse.qaModel');

-- 3) 提醒：改库后需让实例重载配置缓存（任选其一）
--    * 重启实例；或
--    * redis-cli PUBLISH ai:config:changed changed（多实例/在线刷新）
