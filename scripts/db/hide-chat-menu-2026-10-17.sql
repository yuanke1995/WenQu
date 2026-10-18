-- 问渠 WenQu —— 存量库把「对话」菜单收成默认隐藏（2026-10-17）
-- 背景：侧栏顶部已有「新建对话」，会话本身从工作区树进入，「对话」这一项不再占侧栏位。
--        这件事原先由前端硬过滤（AppLayout.vue 的 HIDDEN_NAV_PATHS）实现——管理员在
--        「权限管理」里把菜单改回显示也没用，只能改代码。现改为菜单数据 visible=0：
--        服务端不下发该行（MenuService.visibleMenusFor 按 visible=1 过滤），侧栏自然不渲染，
--        而**行与它的接口归属、角色绑定一行都不动**（鉴权只认 c_ai_role_api）。
--        新装库由 RbacSeedRunner.BUILTIN_MENUS 的第 6 列直接种成 0，本脚本只服务存量库。
--
-- 判据：只按 id='menu-chat' 命中，不依赖中文条件（本机 mysql 客户端对中文条件会静默不匹配）。
-- 幂等：重复执行命中 0 行。可逆：在「权限管理 → 编辑菜单 → 对话 → 可见」打开即可，无需 SQL。
--
-- 用法（生产库同样适用）：
--   mysql -u root -p wenqu_ai < scripts/db/hide-chat-menu-2026-10-17.sql

-- 1) 命中预检：visible 应为 1（存量库）。若已是 0，下面的 UPDATE 空跑，属正常
SELECT `id`, `path`, `visible`, `render_as`, `builtin` FROM `c_ai_menu` WHERE `id` = 'menu-chat';

-- 2) 执行：只改这一行的可见性（不动 render_as，它仍是 sidebar 型，便于随时改回显示）
UPDATE `c_ai_menu` SET `visible` = 0 WHERE `id` = 'menu-chat' AND `visible` = 1;

-- 3) 验证：visible 应为 0，render_as 仍为 sidebar
SELECT `id`, `path`, `visible`, `render_as` FROM `c_ai_menu` WHERE `id` = 'menu-chat';

-- 4) 回滚（仅在需要时执行；等价于在权限管理里把「显示」打开）：
-- UPDATE `c_ai_menu` SET `visible` = 1 WHERE `id` = 'menu-chat';
