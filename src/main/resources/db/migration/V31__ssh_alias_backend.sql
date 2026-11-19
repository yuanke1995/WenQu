-- ============================================
-- 2026-11-19: 本机 OpenSSH 别名工作区（DSH dsh-ssh 同形态）
--
-- 连接发起端是用户本机执行器（wenqu-agent），使用本机 ssh 与 ~/.ssh/config 里的 Host alias；
-- 问渠只存 alias 名与远端绝对路径，不保存任何私钥/密码。
-- ============================================

ALTER TABLE `c_ai_project`
    ADD COLUMN `ssh_alias` VARCHAR(128) DEFAULT NULL
        COMMENT '本机 OpenSSH 别名（Host 名，如 wenqu）；backend_type=ssh_alias 时必填' AFTER `ssh_host_id`;
