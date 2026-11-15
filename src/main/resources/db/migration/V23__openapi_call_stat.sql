-- B5 接入漏斗：开放 API 调用台账（按日聚合，一次调用 = 一次 upsert 计数）
--
-- 为什么是聚合表而不是明细表：漏斗只回答「签发的 Key 有没有被用起来、卡在哪一步」。
-- 逐条留痕会把外部调用的每一次重试都变成一行，等于在问渠里再建一套请求日志库；
-- 单次失败排查走 request_id + 应用日志，台账只负责趋势与归因。
--
-- 归因（reason）必须由拒绝发生的现场写下：只看 403 分不出「这把 Key 没开通 runs.read」
-- 和「这个端点根本没登记进契约」，而两者的解法完全不同（改 Key 授权 / 换端点）。
-- 状态码只能说明「失败了」，所以 reason 是这张表存在的主要理由。
--
-- api_key_id 为空串 = 未通过认证的调用（没带 Key / Key 无效 / 已停用 / 已过期）。
-- 保留这类行是刻意的：它统计的正是「有人在拿不对的 Key 调」——不留这一行，集成方
-- 卡在认证这一步时平台侧完全看不见。

CREATE TABLE IF NOT EXISTS `c_ai_openapi_call_stat` (
    `stat_date`  DATE         NOT NULL COMMENT '统计日（应用服务器本地时区切天，与用量台账同口径）',
    `api_key_id` VARCHAR(50)  NOT NULL DEFAULT '' COMMENT 'API Key id；空串=未通过认证的调用（没带 Key / 无效 / 停用 / 过期）',
    `endpoint`   VARCHAR(160) NOT NULL DEFAULT '' COMMENT '端点：v1 已登记时写契约模板（如 GET /v1/runs/{runId}），未登记时写实际路径',
    `status`     INT          NOT NULL DEFAULT 0 COMMENT 'HTTP 状态码',
    `reason`     VARCHAR(48)  NOT NULL DEFAULT '' COMMENT '归因：ok / no_credential / key_invalid / key_disabled / key_expired / endpoint_not_registered / key_denied / scope_denied:<scope> / rate_limited / error_<status>',
    `calls`      BIGINT       NOT NULL DEFAULT 0 COMMENT '当日计数',
    PRIMARY KEY (`stat_date`, `api_key_id`, `endpoint`, `status`, `reason`),
    KEY `idx_date_reason` (`stat_date`, `reason`),
    KEY `idx_key_date` (`api_key_id`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放 API 调用台账（按日聚合，B5 接入漏斗）';
