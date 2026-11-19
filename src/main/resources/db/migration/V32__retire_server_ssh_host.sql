-- ============================================
-- 2026-11-19: 退役「服务端 SSH 主机」形态（保留本机 OpenSSH 别名 ssh_alias）
--
-- 服务端 JSch + 托管凭据 + 主机管理界面已移除；工作区只保留：
--   backend_type=local  /  backend_type=ssh_alias（本机 OpenSSH 别名）
-- 审计表 c_ai_executor_audit 继续服务 ssh_alias，不删。
-- ============================================

DROP TABLE IF EXISTS `c_ai_ssh_host`;

ALTER TABLE `c_ai_project`
    DROP COLUMN `ssh_host_id`;
