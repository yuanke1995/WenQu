-- 个人按模型的思考档位记忆（原来只在前端 localStorage：换浏览器/清缓存即丢，
-- 且历史台账里只有 deep_think 开关、分不出「中/高/超高/极致」）。
-- 归属键是 (uid, model_ref)：每个模型各一行，互不覆盖；模型被停用/移出后行保留
-- （重新启用时用户的选择还在），读路径只按 ref 取，不 JOIN 模型库。
CREATE TABLE IF NOT EXISTS `c_ai_user_model_think` (
    `tenant_id`   VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID（Phase 4 多租户：本行归属的租户）',
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `uid`         VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid）',
    `model_ref`   VARCHAR(190) NOT NULL COMMENT '模型引用 providerId/modelId（与 c_ai_message.model 同口径）',
    `think_level` VARCHAR(16)  NOT NULL COMMENT '思考档位：off=关闭思考 / on=开启思考但不指定强度（该模型未登记档位）/ low|medium|high|xhigh|max=具体强度',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（最后一次选择该模型的档位）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_uid_model_ref` (`uid`, `model_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人按模型的思考档位记忆（跨设备同步；未设置=没有行，按模型登记默认值）';