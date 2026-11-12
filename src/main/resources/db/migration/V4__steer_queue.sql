-- Flyway V4：运行中插话的待发队列（server-held projection）。
-- 背景：插话队列原来只活在前端内存里，刷新即丢；「等这一轮结束再发」靠前端 2.5s 重试。
-- 现在队列落库，前端读投影；drain 由「轮开跑」触发（只 steer 进活着的轮，绝不 submit）。
CREATE TABLE IF NOT EXISTS `c_ai_steer_item` (
    `id`         varchar(64)  NOT NULL COMMENT '队列项 ID',
    `tenant_id`  varchar(32)  NOT NULL DEFAULT 'default' COMMENT '租户ID（多租户拦截器按行归属注入/过滤）',
    `session_id` varchar(50)  NOT NULL COMMENT '所属会话',
    `user_id`    varchar(64)  NOT NULL COMMENT '发起人（只有本人能送出/删除）',
    `turn_id`    varchar(64)  DEFAULT NULL COMMENT '送达时那一刻的轮（goal:… 或用户轮 turnId）',
    `receipt_id` varchar(64)  DEFAULT NULL COMMENT '框架 steer 收据号；inbox/applied 据此置 APPLIED',
    `text`       text         NOT NULL COMMENT '用户原话',
    `images`     longtext     DEFAULT NULL COMMENT 'JSON 数组：原始 data URL（排队期间用于展示与重发；送达后由消息行存回显 URL）',
    `status`     varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/STEERED/APPLIED/DROPPED',
    `attempts`   int          NOT NULL DEFAULT 0 COMMENT '送达尝试次数',
    `last_error` varchar(200) DEFAULT NULL COMMENT '最近一次送达失败原因（可见，不静默吞）',
    `created_at` datetime(3)  DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` datetime(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_session_status` (`session_id`, `status`, `created_at`),
    KEY `idx_receipt` (`receipt_id`),
    KEY `idx_session_user` (`session_id`, `user_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运行中插话待发队列（刷新可恢复）';
