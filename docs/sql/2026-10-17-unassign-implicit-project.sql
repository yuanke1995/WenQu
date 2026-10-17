-- ============================================================================
-- 2026-10-17  把 implicit「兜底容器」里的数据挪到「会话」栏
-- ============================================================================
--
-- 背景：侧栏改成「会话 / 工作区」后，兜底容器不再作为一个工作区展示；
--       不属于任何真实工作区的会话统一进顶部「会话」。
--       新接口已经用 __nospace__（project_id IS NULL OR implicit 容器）把两者并在一起，
--       本脚本负责把**已有数据**真正迁到 project_id = NULL，避免库里还留着
--       「兜底容器」的归属、产物页也还冒出这个分组。
--
-- 影响：c_ai_session / c_ai_artifact / c_ai_scheduled_job / c_ai_workflow / c_ai_workflow_run
--       中 project_id 指向 implicit 容器的行 → project_id = NULL。
--       implicit 容器行本身保留（老客户端 / ensureSession / 内部派发仍要它兜底），UI 不再展示。
--
-- 执行前：
--   1) 备份这五张表（示例）：
--      mysqldump -uroot --single-transaction --no-tablespaces <db> \
--        c_ai_session c_ai_artifact c_ai_scheduled_job c_ai_workflow c_ai_workflow_run \
--        > before-unassign-implicit.sql
--   2) 低峰执行；迁移后重启应用（在途轮次的沙盒作用域可能还持有旧 projectId）
--
-- 幂等：重复执行时 UPDATE 命中 0 行。
-- ============================================================================

START TRANSACTION;

-- 1) 会话
UPDATE c_ai_session s
  JOIN c_ai_project p ON s.project_id = p.id
   SET s.project_id = NULL
 WHERE p.selection_status = 'implicit';

-- 2) 产物
UPDATE c_ai_artifact a
  JOIN c_ai_project p ON a.project_id = p.id
   SET a.project_id = NULL
 WHERE p.selection_status = 'implicit';

-- 3) 定时任务
UPDATE c_ai_scheduled_job j
  JOIN c_ai_project p ON j.project_id = p.id
   SET j.project_id = NULL
 WHERE p.selection_status = 'implicit';

-- 4) 工作流
UPDATE c_ai_workflow w
  JOIN c_ai_project p ON w.project_id = p.id
   SET w.project_id = NULL
 WHERE p.selection_status = 'implicit';

-- 5) 工作流运行记录
UPDATE c_ai_workflow_run r
  JOIN c_ai_project p ON r.project_id = p.id
   SET r.project_id = NULL
 WHERE p.selection_status = 'implicit';

COMMIT;

-- 验证：以下各值应为 0（implicit 下不再有数据）；没挂工作区桶里能看到迁移过来的行
SELECT 'implicit_sessions' AS k, COUNT(*) AS c
  FROM c_ai_session s JOIN c_ai_project p ON s.project_id = p.id
 WHERE p.selection_status = 'implicit'
UNION ALL
SELECT 'implicit_artifacts', COUNT(*)
  FROM c_ai_artifact a JOIN c_ai_project p ON a.project_id = p.id
 WHERE p.selection_status = 'implicit'
UNION ALL
SELECT 'implicit_jobs', COUNT(*)
  FROM c_ai_scheduled_job j JOIN c_ai_project p ON j.project_id = p.id
 WHERE p.selection_status = 'implicit'
UNION ALL
SELECT 'implicit_workflows', COUNT(*)
  FROM c_ai_workflow w JOIN c_ai_project p ON w.project_id = p.id
 WHERE p.selection_status = 'implicit'
UNION ALL
SELECT 'implicit_workflow_runs', COUNT(*)
  FROM c_ai_workflow_run r JOIN c_ai_project p ON r.project_id = p.id
 WHERE p.selection_status = 'implicit';
