-- 思考强度档位迁移（2026-10-03）：统一到「思考等级下拉」入口
--
-- 背景：聊天框此前有「灯泡」开关（开/关深度思考）+ 个人设置里的「深度思考默认开启」。
-- 现改为单个「思考等级下拉」（关闭思考 / 低 / 中 / 高 / 超高 / 极致），需先给存量
-- 可思考模型补上档位登记，否则这些模型在下拉里无档位可选、思考入口消失。
--
-- 迁移范围：thinking = switchable（可开关）与 always（恒思考）的聊天类模型。
--   · switchable → 补 low,medium,high，默认档位 medium
--   · always     → 同上（恒思考模型强制开思考，档位只影响强度）
-- 不迁移 thinking = none（不支持思考）：前端 THINK_CAPS.none 的 visible=false，
-- 本来就不显示任何思考入口，删灯泡对它零影响。
--
-- 幂等：只处理 reasoning_levels 为空的行；已登记档位的模型保持管理员配置不动。
-- 用法：mysql -u root -p ai_doc_assistant < scripts/migrate-reasoning-levels-2026-10-03.sql

-- 1) 补档位（仅 reasoning_levels 为空时）
UPDATE `c_ai_model`
SET `reasoning_levels` = 'low,medium,high'
WHERE `model_type` IN ('chat', 'omni')
  AND `thinking` IN ('switchable', 'always')
  AND (`reasoning_levels` IS NULL OR TRIM(`reasoning_levels`) = '');

-- 1b) 思考能力名判定补齐（2026 国产主力）：旧名单只认 deepseek-v3 / glm-4/5 等，
--     V4、doubao-seed-2、kimi、minimax、hunyuan、spark 等被判成「不支持思考」，
--     导致用户连思考都开不了。按名补成「可开关」，不覆盖已显式配置的 none/always。
UPDATE `c_ai_model`
SET `thinking` = 'switchable'
WHERE `model_type` IN ('chat', 'omni')
  AND `thinking` = 'auto'
  AND (
       LOWER(`model_id`) LIKE '%deepseek-v4%'
    OR LOWER(`model_id`) LIKE '%deepseek-flash%'
    OR LOWER(`model_id`) LIKE '%doubao-seed%'
    OR LOWER(`model_id`) LIKE '%kimi%'
    OR LOWER(`model_id`) LIKE '%minimax%'
    OR LOWER(`model_id`) LIKE '%hunyuan%'
    OR LOWER(`model_id`) LIKE '%spark%'
    OR LOWER(`model_id`) LIKE '%qwen3%'
  );

-- 2) 补默认档位（仅 default_reasoning_level 为空时；须与上面的档位一致）
UPDATE `c_ai_model`
SET `default_reasoning_level` = 'medium'
WHERE `model_type` IN ('chat', 'omni')
  AND `thinking` IN ('switchable', 'always')
  AND `reasoning_levels` IS NOT NULL AND TRIM(`reasoning_levels`) <> ''
  AND (`default_reasoning_level` IS NULL OR TRIM(`default_reasoning_level`) = '');

-- 3) 二次补档位：第 1 步执行时 thinking 还是 auto，第 1b 步才改成 switchable，
--    这里再补一次档位（DeepSeek V4 等新认出的模型）
UPDATE `c_ai_model`
SET `reasoning_levels` = 'low,medium,high'
WHERE `model_type` IN ('chat', 'omni')
  AND `thinking` IN ('switchable', 'always')
  AND (`reasoning_levels` IS NULL OR TRIM(`reasoning_levels`) = '');

UPDATE `c_ai_model`
SET `default_reasoning_level` = 'medium'
WHERE `model_type` IN ('chat', 'omni')
  AND `thinking` IN ('switchable', 'always')
  AND `reasoning_levels` IS NOT NULL AND TRIM(`reasoning_levels`) <> ''
  AND (`default_reasoning_level` IS NULL OR TRIM(`default_reasoning_level`) = '');

-- 核对：应看到可思考模型都有档位与默认档位
SELECT `thinking`, `reasoning_levels`, `default_reasoning_level`, COUNT(*) AS c,
       GROUP_CONCAT(DISTINCT `model_id` SEPARATOR ', ') AS models
FROM `c_ai_model`
WHERE `enabled` = 1 AND `model_type` IN ('chat', 'omni')
GROUP BY `thinking`, `reasoning_levels`, `default_reasoning_level`;
