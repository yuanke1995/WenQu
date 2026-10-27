package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.UsageLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 推理用量台账：写入（BaseMapper）+ 统计聚合。<p>
 * 所有账目口径均取自网关 usage（spring 侧累加前的 native 原始值），
 * 与供应商侧计费总量一致。
 */
public interface UsageLogMapper extends BaseMapper<UsageLog> {

    /** 每日总量清单（uid=null 视为系统侧不入个人统计；since 为空则全量） */
    @Select("<script>SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, "
            + "SUM(total_tokens) AS t, COUNT(*) AS c "
            + "FROM c_ai_usage_log "
            + "WHERE uid = #{uid} "
            + "<if test='since != null'>AND create_time &gt;= #{since}</if> "
            + "GROUP BY d ORDER BY d</script>")
    List<Map<String, Object>> statDailyTokens(@Param("uid") String uid,
                                              @Param("since") LocalDateTime since);

    /** 每日×模型（近 N 日趋势与模型占比共用；模型为 NULL 归入 NULL key，聚合层映射为「未记录」） */
    @Select("<script>SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, model AS model, "
            + "SUM(total_tokens) AS t FROM c_ai_usage_log "
            + "WHERE uid = #{uid} "
            + "<if test='since != null'>AND create_time &gt;= #{since}</if> "
            + "GROUP BY d, model ORDER BY d</script>")
    List<Map<String, Object>> statModelDaily(@Param("uid") String uid,
                                             @Param("since") LocalDateTime since);

    /** 活跃日期清单（统计卡连续天数：以「当天有用量」为准，比「当天有回答」更贴合实际使用） */
    @Select("SELECT DISTINCT DATE_FORMAT(create_time, '%Y-%m-%d') AS d FROM c_ai_usage_log "
            + "WHERE uid = #{uid} ORDER BY d")
    List<String> statActiveDates(@Param("uid") String uid);

    /**
     * 按用途（kind）聚合：回答之外的每一次辅助调用（深度思考、改写、压缩、引用自检、记忆提取、
     * 按需派遣、子智能体分支）都单独记账，这里就是它们的出口。
     * <p>kind 为空按 chat 归：那是引入归属字段之前的存量行，不该变成一个「未知用途」桶让人以为有问题。
     */
    @Select("<script>SELECT COALESCE(NULLIF(kind, ''), 'chat') AS k, SUM(total_tokens) AS t, COUNT(*) AS c "
            + "FROM c_ai_usage_log "
            + "WHERE uid = #{uid} "
            + "<if test='since != null'>AND create_time &gt;= #{since}</if> "
            + "GROUP BY k ORDER BY t DESC</script>")
    List<Map<String, Object>> statKindTokens(@Param("uid") String uid,
                                             @Param("since") LocalDateTime since);

    /**
     * 用户×模型×日 token 明细（费用报表唯一数据源）：输入/输出分开聚合，
     * 费用 = Σ(输入×输入单价 + 输出×输出单价)，价格按模型登记在 Java 侧换算——
     * 不在 SQL 里 join c_ai_model：单价随登记随时可改，SQL 端固化快照会让历史费用跟着漂移。
     * uid=null 跨全部用户（管理侧），since=null 全量（个人费用卡全时段口径）。
     */
    @Select("<script>SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, uid AS uid, model AS model, "
            + "tenant_id AS tenant, "
            + "SUM(prompt_tokens) AS p, SUM(completion_tokens) AS c, SUM(total_tokens) AS t "
            + "FROM c_ai_usage_log "
            + "<where>"
            + "<if test='uid != null'>uid = #{uid}</if>"
            + "<if test='since != null'>AND create_time &gt;= #{since}</if>"
            + "</where> "
            + "GROUP BY d, uid, model, tenant ORDER BY d</script>")
    List<Map<String, Object>> statCostDetail(@Param("uid") String uid, @Param("since") LocalDateTime since);

    /**
     * 配额用量的<b>对账口径</b>（按模型聚合输入/输出，费用在 Java 侧按现行单价现算）：
     * 周期计数器走 Redis 图快，但 Redis 会被清、会换实例，账必须能从台账重算出来——
     * 重算不出来的配额等于「谁也不知道现在到底用了多少」。
     *
     * @param uid    成员档：非空按单人
     * @param deptId 部门档：非空按该部门全体成员（join c_ai_user.department_id）
     * @param kinds  受限用途清单（空=全部）；kind 为空的存量行按 chat 归，与费用报表同一口径
     */
    @Select("<script>SELECT u.model AS model, SUM(u.prompt_tokens) AS p, SUM(u.completion_tokens) AS c "
            + "FROM c_ai_usage_log u "
            + "<if test='deptId != null'>JOIN c_ai_user m ON m.uid = u.uid AND m.department_id = #{deptId} </if>"
            + "WHERE u.create_time &gt;= #{since} "
            + "<if test='uid != null'>AND u.uid = #{uid}</if> "
            + "<if test='kinds != null and kinds.size() > 0'>AND COALESCE(NULLIF(u.kind, ''), 'chat') "
            + "IN <foreach item='k' collection='kinds' open='(' separator=',' close=')'>#{k}</foreach></if> "
            + "GROUP BY u.model</script>")
    List<Map<String, Object>> sumForQuota(@Param("uid") String uid,
                                          @Param("deptId") String deptId,
                                          @Param("since") LocalDateTime since,
                                          @Param("kinds") List<String> kinds);
}
