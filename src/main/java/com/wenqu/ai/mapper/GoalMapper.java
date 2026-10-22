package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.Goal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 目标模式数据访问。
 *
 * <p>除 BaseMapper 外只加两类查询：用量聚合（预算账）与任务运行时的租约/就绪查询。
 * 租约全部走<b>条件 UPDATE</b>（affected rows 即抢到/没抢到），不先查后写——多副本下
 * 「先 SELECT 再 UPDATE」的窗口足够两个 worker 各起一轮。
 *
 * <p>本仓 mapper 无 XML，与 {@code UsageLogMapper} 同一形状。
 */
public interface GoalMapper extends BaseMapper<Goal> {

    /** 可被调度的目标（有下一轮时间、租约空闲或已过期）。 */
    String READY_WHERE = "status IN ('QUEUED','RUNNING','VERIFYING','WAITING_APPROVAL','WAITING_EVENT') "
            + "AND next_run_at IS NOT NULL AND next_run_at <= NOW(3) "
            + "AND (lease_until IS NULL OR lease_until < NOW(3))";

    /**
     * 一个会话在目标运行期内的模型用量（按 kind 聚合，含 chat/compress/subagent）。
     * <p>为什么必须含 compress 与 subagent：压缩摘要与子智能体分支都花的是这个目标的 token，
     * 只数 chat 会让预算账比真实成本小一大截（deepseek-flash 恒思考那条对账纪律同源）。
     *
     * @param sessionId 会话
     * @param since     起始时刻（目标 startedAt；NULL=不限）
     * @return [{kind, tokens}]；空列表=还没有用量
     */
    @Select({
            "<script>",
            "SELECT kind, COALESCE(SUM(total_tokens), 0) AS tokens",
            "FROM c_ai_usage_log",
            "WHERE session_id = #{sessionId}",
            "  AND kind IN ('chat', 'compress', 'subagent')",
            "<if test='since != null'> AND create_time &gt;= #{since}</if>",
            "GROUP BY kind",
            "</script>"
    })
    List<Map<String, Object>> sumSessionTokens(@Param("sessionId") String sessionId,
                                               @Param("since") LocalDateTime since);

    /**
     * 某一轮时间窗内的 token（轮次记账用）。目标轮之间可能夹着用户轮，但目标轮收尾时会先
     * 让位/暂停，窗口取 {@code [round.startedAt, now]} 足够贴近这一轮的成本；比按目标
     * {@code startedAt} 聚合（会把用户轮也算进去）更准。
     */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM c_ai_usage_log "
            + "WHERE session_id = #{sessionId} AND kind IN ('chat','compress','subagent') "
            + "AND create_time >= #{from} AND create_time <= #{to}")
    long sumTokensBetween(@Param("sessionId") String sessionId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

    /** 取一批可调度的目标（后续逐条条件 UPDATE 抢租约）。 */
    @Select("SELECT * FROM c_ai_goal WHERE " + READY_WHERE + " ORDER BY next_run_at ASC LIMIT #{limit}")
    List<Goal> ready(@Param("limit") int limit);

    /**
     * 抢租约：affected=1 才算抢到。条件里再次校验状态与可调度时间，
     * 防止「SELECT 之后被另一个 worker 先抢走」。
     *
     * @param seconds 租约时长；调用方按轮次/工具耗时可续租
     */
    @Update("UPDATE c_ai_goal SET lease_owner = #{owner}, "
            + "lease_until = TIMESTAMPADD(SECOND, #{seconds}, NOW(3)), heartbeat_at = NOW(3), "
            + "row_version = row_version + 1 "
            + "WHERE id = #{id} AND " + READY_WHERE)
    int claim(@Param("id") String id, @Param("owner") String owner, @Param("seconds") int seconds);

    /**
     * 接管过期租约（恢复用）：与 {@link #claim} 的差别是不要求 {@code next_run_at <= NOW}——
     * 执行中的轮 next_run_at 本来就是 NULL，恢复恰恰要接管这种「有人拿着租约但心跳断了」的行。
     */
    @Update("UPDATE c_ai_goal SET lease_owner = #{owner}, "
            + "lease_until = TIMESTAMPADD(SECOND, #{seconds}, NOW(3)), heartbeat_at = NOW(3), "
            + "row_version = row_version + 1 "
            + "WHERE id = #{id} AND status IN "
            + "('QUEUED','RUNNING','VERIFYING','WAITING_APPROVAL','WAITING_EVENT') "
            + "AND lease_until IS NOT NULL AND lease_until < NOW(3)")
    int claimExpired(@Param("id") String id, @Param("owner") String owner, @Param("seconds") int seconds);

    /** 续租：只续自己持有的那些目标；进程卡住时租约自然过期。 */
    @Update("UPDATE c_ai_goal SET lease_until = TIMESTAMPADD(SECOND, #{seconds}, NOW(3)), "
            + "heartbeat_at = NOW(3) "
            + "WHERE lease_owner = #{owner} AND status IN "
            + "('QUEUED','RUNNING','VERIFYING','WAITING_APPROVAL','WAITING_EVENT')")
    int renewLeases(@Param("owner") String owner, @Param("seconds") int seconds);

    /** 释放租约：只释放自己持有的，避免把新 worker 刚抢到的租约清掉。 */
    @Update("UPDATE c_ai_goal SET lease_owner = NULL, lease_until = NULL "
            + "WHERE id = #{id} AND lease_owner = #{owner}")
    int releaseLease(@Param("id") String id, @Param("owner") String owner);

    /** 只更新事件游标：目标态帧下发时用，避免整行 update 把调度器刚抢到的租约覆盖掉。 */
    @Update("UPDATE c_ai_goal SET last_event_id = #{eventId} WHERE id = #{id}")
    int updateLastEventId(@Param("id") String id, @Param("eventId") long eventId);

    /** 租约过期、仍处于运行/等待态的目标（启动恢复与周期回收共用）。 */
    @Select("SELECT * FROM c_ai_goal WHERE status IN "
            + "('QUEUED','RUNNING','VERIFYING','WAITING_APPROVAL','WAITING_EVENT') "
            + "AND lease_until IS NOT NULL AND lease_until < NOW(3) "
            + "ORDER BY lease_until ASC LIMIT #{limit}")
    List<Goal> expiredLeases(@Param("limit") int limit);

    /**
     * 卡在 RUNNING/QUEUED 但 next_run_at 为空、租约也空的目标（上一次收尾没写完状态）。
     * 启动恢复与周期回收共用：把它们重新排到「现在」。
     */
    @Select("SELECT * FROM c_ai_goal WHERE status IN ('QUEUED','RUNNING') "
            + "AND next_run_at IS NULL AND lease_owner IS NULL "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<Goal> stranded(@Param("limit") int limit);
}
