-- 问渠 WenQu —— 退役配置键遗留行清理脚本（2026-09-30）
-- 背景：全局键 chat.model / vision.model / embedding.model 已随「模型归属到使用者/知识库」重构退役
--        （不在 ConfigService.defaults()，设置页不渲染、无业务读取方）。
--        存量库中的旧行对用户不可见，但 deleteProvider 曾把它们计入「系统配置槽位」引用，
--        导致删供应商被看不见的配置挡住（报"请先在个人设置/知识库改用其他模型"却无处可改）。
--
-- 处理原则：
--   * chat.model / vision.model：代码无任何读取方（真孤儿），直接删除；
--   * embedding.model：DynamicEmbeddingModel.current() 仍会读取（用户记忆语义去重/注入的向量化
--     路由，best-effort），不能删——改绑到一个启用供应商下的在用向量模型（与本机实例同款：
--     阿里云百炼 qwen3.7-text-embedding-flash，即默认知识库所绑模型）。
--     改绑后注意：新旧向量模型维度/语义不同，存量记忆向量与新向量不再可比（记忆条目随轮转自然更新）。
--   * deleteProvider 同步改造：仅活跃键（defaults() 内）计入引用并带键名报错；退役键遗留引用只告警不阻挡。
--
-- 用法（生产库按实际改绑目标调整第一条 UPDATE 的值后再执行）：
--   mysql -u root -p ai_doc_assistant < scripts/cleanup-retired-config-2026-09-30.sql

-- 1) embedding.model：改绑到启用供应商的在用向量模型（{providerId}/{modelId}）。
--    ↓↓ 按你环境实际在用的向量模型调整（可查 SELECT kb_id, embedding_ref FROM c_ai_knowledge_base;）
UPDATE `c_ai_config`
SET `config_value` = '1fa5818a-bf31-4320-9850-3b1d23d57b6d/qwen3.7-text-embedding-flash',
    `remark`       = '记忆/全局向量化的遗留槽位（已退役不可见）；2026-09-30 清理时改绑至在用向量模型'
WHERE `config_key` = 'embedding.model';

-- 2) 真孤儿行：无任何代码读取方，直接删除
DELETE FROM `c_ai_config` WHERE `config_key` = 'chat.model';
DELETE FROM `c_ai_config` WHERE `config_key` = 'vision.model';

-- 3) 验证：应返回空集（不再有遗留键挡供应商删除）
SELECT `config_key`, `config_value` FROM `c_ai_config`
WHERE `config_key` IN ('chat.model', 'vision.model');

-- 4) 提醒：改库后需让实例重载配置缓存（任选其一）
--    * 重启实例；或
--    * redis-cli PUBLISH ai:config:changed changed（多实例/在线刷新）
