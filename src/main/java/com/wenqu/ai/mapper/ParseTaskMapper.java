package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ParseTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 文档解析任务队列 Mapper
 * <p>
 * 抢占语义全部下推到 SQL：单行 UPDATE 自带原子性（两个实例并发抢同一行时只有一方 affected rows=1），
 * 所以不需要 FOR UPDATE 事务包裹，也不依赖 Redis 分布式锁。
 *
 * @author yuanke
 */
@Mapper
public interface ParseTaskMapper extends BaseMapper<ParseTask> {

    /**
     * 抢占待执行的任务（原子 CAS）：把满足条件的行置为 running 并刷新租约。
     * <p>
     * 子查询必须包一层派生表 t —— MySQL 禁止 UPDATE 直接引用同表子查询（错误 1093），加派生表绕开。
     * 按 (priority, create_time) 取最该跑的一批；limit 由调用方按并发度放大（多捞几个，抢不到留下一轮）。
     */
    @Update("update c_ai_parse_task set status = 1, worker = #{worker}, "
            + "start_time = now(), lease_until = date_add(now(), interval #{leaseSec} second), "
            + "attempt = attempt + 1 where id in (select id from (select id from c_ai_parse_task "
            + "where status in (0, 3) and next_run_at <= now() "
            + "order by priority desc, create_time asc limit #{limit}) t)")
    int claimDueTasks(@Param("limit") int limit, @Param("leaseSec") int leaseSec, @Param("worker") String worker);

    /** 取本实例持有中的任务（含超时/lease 已过仍未被回收的异常态，交给看门狗或执行壳处理） */
    @Select("select id, doc_id, kb_id, status, attempt, max_attempt, next_run_at, lease_until, "
            + "priority, error, worker, create_time, start_time, finish_time "
            + "from c_ai_parse_task where worker = #{worker} and status = 1 order by start_time asc")
    List<ParseTask> selectMine(@Param("worker") String worker);

    /** 租约回收：把 lease 已过期的 running 任务收回 queued（覆盖进程崩溃/线程卡死，取代旧版"重启一律判失败"） */
    @Update("update c_ai_parse_task set status = 0, lease_until = null, worker = null, "
            + "next_run_at = now() where status = 1 and lease_until is not null and lease_until <= now()")
    int revokeExpiredLeases();

    /** 任务成功 */
    @Update("update c_ai_parse_task set status = 2, finish_time = now(), lease_until = null, worker = null "
            + "where id = #{id}")
    int markSucceeded(@Param("id") String id);

    /** 任务终态失败（不可重试） */
    @Update("update c_ai_parse_task set status = 4, finish_time = now(), lease_until = null, worker = null, "
            + "error = #{error} where id = #{id}")
    int markDead(@Param("id") String id, @Param("error") String error);

    /** 任务可重试：attempt 未达上限则退避后回到 queued，否则转 dead */
    @Update({"<script>",
            "update c_ai_parse_task set ",
            "status = case when attempt >= max_attempt then 4 else 0 end, ",
            "finish_time = now(), lease_until = null, worker = null, ",
            "next_run_at = case when attempt >= max_attempt then next_run_at else date_add(now(), interval #{backoffSec} second) end, ",
            "error = #{error} ",
            "where id = #{id}",
            "</script>"})
    int markRetryable(@Param("id") String id, @Param("error") String error, @Param("backoffSec") int backoffSec);

    /** 本实例重解析时：把同文档尚未结束的旧任务作废（避免同一文档两个任务抢跑） */
    @Update({"<script>",
            "update c_ai_parse_task set status = 4, finish_time = now(), lease_until = null, worker = null, ",
            "error = '被新的解析任务取代' where doc_id = #{docId} and status in (0, 1, 3)",
            "</script>"})
    int killActiveByDoc(@Param("docId") String docId);

    /**
     * 队列统计（前端展示「排队 N / 执行 M」）。
     * 计数用 SUM：information_schema.table_rows 是 InnoDB 估算值（会骗人），一律不许用它判有没有数据。
     */
    @Select("select sum(case when status = 0 or status = 3 then 1 else 0 end) as queued, "
            + "sum(case when status = 1 then 1 else 0 end) as running, "
            + "sum(case when status = 4 then 1 else 0 end) as dead from c_ai_parse_task")
    java.util.Map<String, Object> queueStats();

    /**
     * 单库队列统计（文档管理页页头指标）：只看当前库的任务——全局口径会把别的库在跑的任务
     * 显示在本库页头，用户读成「我上传的卡住了」。
     *
     * @param includeNullKb 默认库并上 kb_id 为空的历史任务（口径与文档列表 list(kbId) 的默认库语义一致）
     */
    @Select({"<script>",
            "select sum(case when status = 0 or status = 3 then 1 else 0 end) as queued, ",
            "sum(case when status = 1 then 1 else 0 end) as running, ",
            "sum(case when status = 4 then 1 else 0 end) as dead ",
            "from c_ai_parse_task where ",
            "<choose>",
            "<when test='includeNullKb'> (kb_id = #{kbId} or kb_id is null) </when>",
            "<otherwise> kb_id = #{kbId} </otherwise>",
            "</choose>",
            "</script>"})
    java.util.Map<String, Object> queueStatsByKb(@Param("kbId") String kbId, @Param("includeNullKb") boolean includeNullKb);
}
