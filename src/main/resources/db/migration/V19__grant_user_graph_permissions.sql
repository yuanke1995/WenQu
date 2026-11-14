-- 图谱能力下放到库主（2026-11-14）：普通用户自建自管知识库（含个人默认库「问渠」），
-- 但 user 角色从没有过任何 graph:* 授权点，界面上能点进「图谱」却每个 /graph/** 都 403。
-- 端点侧 GraphController 已按 kbId 补归属校验（谁建归谁 / 内置库仅管理员级），
-- 授权点只决定「能不能进端点」，两者配合才不会让普通用户读到别人的库图谱。
-- 按所有现存租户的启用 user 角色补行：自定义角色由管理员在权限管理里自行绑定，不在本迁移范围。
INSERT INTO `c_ai_role_permission`
    (`role_code`, `permission_code`, `effect`, `exempt`, `create_time`, `tenant_id`)
SELECT r.`code`, p.`permission_code`, 'allow', 0, NOW(), r.`tenant_id`
FROM `c_ai_role` r
JOIN (
    SELECT 'graph:read'   AS `permission_code`
    UNION ALL SELECT 'graph:build'
    UNION ALL SELECT 'graph:delete'
) p
WHERE r.`code` = 'user' AND r.`status` = 1
  AND NOT EXISTS (
      SELECT 1 FROM `c_ai_role_permission` rp
      WHERE rp.`role_code` = r.`code`
        AND rp.`tenant_id` = r.`tenant_id`
        AND rp.`permission_code` = p.`permission_code`
  );
