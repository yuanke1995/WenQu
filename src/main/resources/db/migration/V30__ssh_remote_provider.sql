-- ============================================
-- 2026-11-19: SSH 远程主机一等 provider
--
-- 目标：工作区可以绑定一台远端 POSIX 主机（SFTP 文件系统 + SSH 命令 + PTY 终端），
-- 与本机执行器目录同构；凭据托管、指纹校验、路径围栏、审计。
--
-- 设计见 design/plans/SSH远程主机一等provider落地方案.md
-- ============================================

-- 工作区后端类型：local=本机执行器目录（存量行默认）/ ssh=远端主机
ALTER TABLE `c_ai_project`
    ADD COLUMN `backend_type` VARCHAR(16) NOT NULL DEFAULT 'local'
        COMMENT '执行后端：local=本机执行器目录 / ssh=远端 SSH 主机（implicit 容器无实际后端）' AFTER `executor_id`,
    ADD COLUMN `ssh_host_id` VARCHAR(64) DEFAULT NULL
        COMMENT 'SSH 主机 id（c_ai_ssh_host.id）；backend_type=ssh 时必填' AFTER `backend_type`;

-- SSH 主机（个人资产，密文凭据落库；出参一律脱敏）
CREATE TABLE IF NOT EXISTS `c_ai_ssh_host` (
    `tenant_id`          VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `id`                 VARCHAR(64)  NOT NULL COMMENT '主键（ssh- 前缀）',
    `uid`                VARCHAR(64)  NOT NULL COMMENT '归属用户（c_ai_user.uid；个人资产，不共享）',
    `name`               VARCHAR(128) NOT NULL COMMENT '显示名（如“生产应用服务器”）',
    `host`               VARCHAR(255) NOT NULL COMMENT '域名或 IP',
    `port`               INT          NOT NULL DEFAULT 22 COMMENT 'SSH 端口',
    `username`           VARCHAR(128) NOT NULL COMMENT '登录用户',
    `auth_type`          VARCHAR(16)  NOT NULL DEFAULT 'password' COMMENT 'password / private_key',
    `password_cipher`    TEXT         DEFAULT NULL COMMENT '密码 JWE 密文（仅 password）',
    `private_key_cipher` MEDIUMTEXT   DEFAULT NULL COMMENT '私钥 JWE 密文（仅 private_key；OpenSSH/PEM）',
    `passphrase_cipher`  TEXT         DEFAULT NULL COMMENT '私钥口令 JWE 密文（可空）',
    `host_key_fingerprint` VARCHAR(128) DEFAULT NULL COMMENT 'SSH 主机公钥指纹 SHA256:...（首次连接 TOFU 记录，此后严格比对）',
    `host_key_algorithm` VARCHAR(64)  DEFAULT NULL COMMENT '主机公钥算法（ssh-ed25519 / rsa-sha2-256 …）',
    `status`             VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active / disabled',
    `last_probe_at`      DATETIME     DEFAULT NULL COMMENT '最近一次连通性探测时间',
    `last_probe_ok`      INT          DEFAULT NULL COMMENT '最近一次探测结果：1=通 0=失败',
    `last_error`         VARCHAR(500) DEFAULT NULL COMMENT '最近一次探测失败原因（已脱敏）',
    `deleted`            INT          NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`        DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_uid` (`uid`, `deleted`),
    KEY `idx_uid_name` (`uid`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SSH 远程主机（工作区的远端执行后端）';

-- 执行审计：远端文件/命令/终端动作逐条留痕（异步落库，不阻塞工具调用）
CREATE TABLE IF NOT EXISTS `c_ai_executor_audit` (
    `tenant_id`     VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID',
    `id`            VARCHAR(64)  NOT NULL COMMENT '主键',
    `uid`           VARCHAR(64)  NOT NULL COMMENT '归属用户',
    `executor_kind` VARCHAR(16)  NOT NULL DEFAULT 'ssh' COMMENT 'ssh / local',
    `executor_id`   VARCHAR(64)  DEFAULT NULL COMMENT '执行器 id（ssh:<hostId> 或设备 id）',
    `host_id`       VARCHAR(64)  DEFAULT NULL COMMENT 'SSH 主机 id',
    `project_id`    VARCHAR(64)  DEFAULT NULL COMMENT '工作区 id',
    `session_id`    VARCHAR(64)  DEFAULT NULL COMMENT '会话 id',
    `turn_id`       VARCHAR(64)  DEFAULT NULL COMMENT '轮次 id',
    `op`            VARCHAR(32)  NOT NULL COMMENT 'exec/read/write/edit/grep/find/list/stat/mkdir/move/remove/writeBytes/readBytes/terminal',
    `path`          VARCHAR(1024) DEFAULT NULL COMMENT '相对源文件夹路径（无法相对化时存绝对路径）',
    `ok`            INT          NOT NULL DEFAULT 1 COMMENT '1=成功 0=失败',
    `error_code`    VARCHAR(64)  DEFAULT NULL COMMENT '失败错误码',
    `duration_ms`   INT          NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `bytes_in`      BIGINT       NOT NULL DEFAULT 0 COMMENT '读入字节数（近似）',
    `bytes_out`     BIGINT       NOT NULL DEFAULT 0 COMMENT '写出字节数（近似）',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_uid_time` (`uid`, `create_time`),
    KEY `idx_host_time` (`host_id`, `create_time`),
    KEY `idx_session_time` (`session_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行器审计（SSH 一等 provider）';
