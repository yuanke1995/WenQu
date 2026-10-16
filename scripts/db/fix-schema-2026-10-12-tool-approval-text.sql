-- 问渠 WenQu —— 存量库列宽修正脚本（2026-10-12）
-- 背景：c_ai_tool_approval 的 request_args / answer 一直是 VARCHAR(2000)，但人在回路三类记录往里写的
--        是 JSON 载荷：计划卡写 {plan: 整份计划}（多步计划常态 2–6K 字，且写侧根本没截），
--        提问卡写 {questions: [...]}（写侧截到 8000 字）。两种后果都是坏的：
--          · 严格模式（生产 MySQL 默认带 STRICT_TRANS_TABLES）→ 插入报 1406，被落库处的 try/catch 吞成
--            一行 warn，卡片没进库 → 刷新/换设备后 /plan-approval/pending 查不到，那张正在倒计时的卡就地消失；
--          · 非严格模式 → 静默截到 2000 字，恢复出来的是半截 JSON，同样重建不出卡片。
--        审计回溯要的正是「当时那张卡写了什么」，所以这里改列宽而不是把载荷截短。
-- 已同步写入 src/main/resources/schema.sql（新装库直接是 TEXT）；
-- SchemaMigrator 刻意只做「补列/补索引」不改列类型，所以存量库要跑本脚本。
--
-- 用法（MODIFY 到 TEXT 是放宽，不动数据，可重复执行）：
--   mysql -u root -h 127.0.0.1 -p wenqu_ai --force < scripts/db/fix-schema-2026-10-12-tool-approval-text.sql
-- （库名按你本地实际调整）

ALTER TABLE `c_ai_tool_approval`
    MODIFY COLUMN `request_args` TEXT DEFAULT NULL
    COMMENT '工具入参摘要（审批类截 2000 字符；计划类存 {plan} JSON、提问类存 {questions} JSON，一份多步计划常态超 2000 字，故整列放宽成 TEXT）';

ALTER TABLE `c_ai_tool_approval`
    MODIFY COLUMN `answer` TEXT DEFAULT NULL
    COMMENT '裁决内容：提问类（askUser）记录存用户所选/输入的答案；超时/忽略不代答时落给模型的「未作答」说明';

-- 验一眼：两列都该是 text，status 仍 varchar(16)
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'c_ai_tool_approval'
  AND COLUMN_NAME IN ('status', 'request_args', 'answer');
