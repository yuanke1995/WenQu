package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.AuditEvent;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 审计事件：写入（BaseMapper）+ 链校验取数 + 留存清理 */
public interface AuditEventMapper extends BaseMapper<AuditEvent> {

    /** 本副本已落库的最大序号（重启后接着排，链不断） */
    @Select("select coalesce(max(seq), 0) from c_ai_audit_event where replica = #{replica}")
    long maxSeqOfReplica(@Param("replica") String replica);

    /** 按副本+序号区间取链（校验用，必须按 seq 升序且不能跳号；链跨租户，取数走平台视图，含 tenant_id 只为报断点位置） */
    @Select("select id, ts, level, action, actor_uid, actor_ip, target_type, target_id, session_id, "
            + "detail, replica, seq, prev_hash, row_hash, tenant_id from c_ai_audit_event "
            + "where replica = #{replica} and seq > #{afterSeq} order by seq asc limit #{limit}")
    List<AuditEvent> chainAfter(@Param("replica") String replica, @Param("afterSeq") long afterSeq,
                                @Param("limit") int limit);

    /** 链尾哈希（重启后续链用；null=该副本还没有事件） */
    @Select("select row_hash from c_ai_audit_event where replica = #{replica} order by seq desc limit 1")
    String tailHash(@Param("replica") String replica);

    /** 有链的副本清单（校验要逐副本各走各的链） */
    @Select("select distinct replica from c_ai_audit_event where replica is not null")
    List<String> distinctReplicas();

    /** 留存清理：删早于 cutoff 的事件（返回删除行数） */
    @Delete("delete from c_ai_audit_event where ts < #{cutoff} limit #{batch}")
    int purgeBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batch") int batch);

    /** 导出取数（按时间倒序分页，条件全可选） */
    @Select("<script>select id, ts, level, action, actor_uid, actor_ip, target_type, target_id, session_id, "
            + "detail, replica, seq, tenant_id as tenantId from c_ai_audit_event "
            + "<where>"
            + "<if test='from != null'>AND ts &gt;= #{from}</if>"
            + "<if test='to != null'>AND ts &lt;= #{to}</if>"
            + "<if test='actor != null'>AND actor_uid = #{actor}</if>"
            + "<if test='action != null'>AND action = #{action}</if>"
            + "<if test='keyword != null'>AND (action like concat('%',#{keyword},'%') or detail like concat('%',#{keyword},'%'))</if>"
            + "<if test='tenantId != null'>AND tenant_id = #{tenantId}</if>"
            + "</where> order by ts desc, id desc limit #{offset}, #{limit}</script>")
    List<Map<String, Object>> query(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("actor") String actor, @Param("action") String action,
                                    @Param("keyword") String keyword, @Param("tenantId") String tenantId,
                                    @Param("offset") int offset, @Param("limit") int limit);

    /** 同上条件的总数（分页与导出前确认量级） */
    @Select("<script>select count(*) from c_ai_audit_event "
            + "<where>"
            + "<if test='from != null'>AND ts &gt;= #{from}</if>"
            + "<if test='to != null'>AND ts &lt;= #{to}</if>"
            + "<if test='actor != null'>AND actor_uid = #{actor}</if>"
            + "<if test='action != null'>AND action = #{action}</if>"
            + "<if test='keyword != null'>AND (action like concat('%',#{keyword},'%') or detail like concat('%',#{keyword},'%'))</if>"
            + "<if test='tenantId != null'>AND tenant_id = #{tenantId}</if>"
            + "</where></script>")
    long countQuery(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                    @Param("actor") String actor, @Param("action") String action,
                    @Param("keyword") String keyword, @Param("tenantId") String tenantId);

    /** 出现过的动作名（导出/查询页的下拉选项，量级=动作数） */
    @Select("select distinct action from c_ai_audit_event order by action")
    List<String> distinctActions();
}
