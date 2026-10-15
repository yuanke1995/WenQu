-- AgentScope 平移（agentx）在 c_ai_tool_approval 上新增 turn_id 列，并放宽 request_args 与 schema.sql 对齐。
--
-- 背景：框架引擎（AgentScope）的待办落库用 HitlDecisionService.recordFrameworkPending，会把 turnId 写进
-- c_ai_tool_approval.turn_id。schema.sql 与 ToolApproval 实体都已有该列，但 2026-10-10 之前的线上存量库没有，
-- 缺列时 INSERT 会失败（被代码 catch 成 warn、不阻塞本轮），后果是刷新/重启后这张确认卡、提问卡、计划卡
-- 取不回来——与手写侧一致的能力在框架引擎下缺了。
--
-- 幂等：列/类型已就位则跳过。在问渠业务库上执行一次即可。

-- 1) 补 turn_id（断线重连/刷新后把待办卡挂回它自己那一轮）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name   = 'c_ai_tool_approval'
                 AND column_name  = 'turn_id');
SET @sql = IF(@exists = 0,
    'ALTER TABLE c_ai_tool_approval ADD COLUMN turn_id VARCHAR(64) DEFAULT NULL COMMENT ''归属轮次（与 c_ai_message.turn_id 同源）：断线重连时把卡片挂回它自己那一轮；补列前的存量记录为 NULL，前端不猜轮次''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) request_args 放宽到 TEXT（与 schema.sql 对齐：一份多步计划常态超 2000 字，手写侧计划卡存整份 {plan} JSON）
SET @exists2 = (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name   = 'c_ai_tool_approval'
                  AND column_name  = 'request_args'
                  AND data_type    = 'varchar');
SET @sql2 = IF(@exists2 > 0,
    'ALTER TABLE c_ai_tool_approval MODIFY COLUMN request_args TEXT DEFAULT NULL COMMENT ''工具入参摘要（审批类截 2000 字符；计划类存 {plan} JSON、提问类存 {questions} JSON，整列放宽成 TEXT）''',
    'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
