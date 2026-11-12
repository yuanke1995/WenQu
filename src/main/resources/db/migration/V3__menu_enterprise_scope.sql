-- Flyway V3：菜单表加「企业面专属」标记。
-- 背景：S3 引入两档部署 profile（standard / enterprise）。standard 档企业端点直接 404，
-- 但菜单表仍向管理员下发 /tenants、/security 等入口，侧栏与路由守卫只能按路径硬挡——
-- 菜单数据源与权限事实源不一致。本列把「属于哪个档位」写进菜单数据：
--   enterprise = 1：仅 enterprise 档下发、且只出现在管理列表（权限管理页）里；
--   standard 档下既不进侧栏，也不出现在菜单管理页，无法被误勾选授权。
ALTER TABLE `c_ai_menu`
    ADD COLUMN `enterprise_only` INT DEFAULT 0 COMMENT '企业面专属: 1=仅 enterprise 档下发/可管理' AFTER `builtin`;

UPDATE `c_ai_menu`
   SET `enterprise_only` = 1
 WHERE `id` IN ('menu-tenants', 'menu-security', 'menu-audit-events', 'menu-service-accounts');
