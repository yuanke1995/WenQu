package com.wenqu.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 开放 API 调用台账（B5 接入漏斗）：写入一次 = 一次 upsert 计数，查询全是按日聚合。
 *
 * <p>写入走 {@code ON DUPLICATE KEY UPDATE}：外部调用并发打同一条（同一天/同一 Key/同一端点/
 * 同一状态/同一原因）是常态，先查再插会漏计，计数交给数据库的原子自增。</p>
 *
 * <p>查询的 {@code keyIds} 一律由调用方传入<b>非空</b>列表（可见 Key + 空串），SQL 不做
 * 「参数为空就不加条件」的退化——那种写法一旦参数传空就变成全表口径，正是要避免的静默错位。</p>
 *
 * @author yuanke
 */
public interface OpenApiCallStatMapper {

    /** 记一次调用（同维度计数 +1） */
    @Insert("INSERT INTO c_ai_openapi_call_stat"
            + " (stat_date, api_key_id, endpoint, status, reason, calls)"
            + " VALUES (#{date}, #{keyId}, #{endpoint}, #{status}, #{reason}, 1)"
            + " ON DUPLICATE KEY UPDATE calls = calls + 1")
    int bump(@Param("date") LocalDate date,
             @Param("keyId") String keyId,
             @Param("endpoint") String endpoint,
             @Param("status") int status,
             @Param("reason") String reason);

    /** 每日序列：calls 总数 / ok 为 2xx */
    @Select("<script>SELECT stat_date AS d, SUM(calls) AS calls,"
            + " SUM(CASE WHEN status &gt;= 200 AND status &lt; 300 THEN calls ELSE 0 END) AS ok"
            + " FROM c_ai_openapi_call_stat"
            + " WHERE stat_date &gt;= #{since} AND api_key_id IN"
            + " <foreach collection='keyIds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + " GROUP BY stat_date ORDER BY stat_date</script>")
    List<Map<String, Object>> daily(@Param("keyIds") List<String> keyIds,
                                    @Param("since") LocalDate since);

    /** 失败原因分布（ok 不算失败，由调用方按 needsOk 决定是否带上） */
    @Select("<script>SELECT reason AS r, SUM(calls) AS calls"
            + " FROM c_ai_openapi_call_stat"
            + " WHERE stat_date &gt;= #{since} AND api_key_id IN"
            + " <foreach collection='keyIds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + " <if test='excludeOk'>AND reason &lt;&gt; 'ok'</if>"
            + " GROUP BY reason ORDER BY calls DESC</script>")
    List<Map<String, Object>> reasons(@Param("keyIds") List<String> keyIds,
                                      @Param("since") LocalDate since,
                                      @Param("excludeOk") boolean excludeOk);

    /** 状态码分布 */
    @Select("<script>SELECT status AS s, SUM(calls) AS calls"
            + " FROM c_ai_openapi_call_stat"
            + " WHERE stat_date &gt;= #{since} AND api_key_id IN"
            + " <foreach collection='keyIds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + " GROUP BY status ORDER BY status</script>")
    List<Map<String, Object>> statuses(@Param("keyIds") List<String> keyIds,
                                       @Param("since") LocalDate since);

    /** 端点热度（已登记端点写契约模板，多个 runId 不会拆成多行） */
    @Select("<script>SELECT endpoint AS e, SUM(calls) AS calls,"
            + " SUM(CASE WHEN status &gt;= 200 AND status &lt; 300 THEN calls ELSE 0 END) AS ok"
            + " FROM c_ai_openapi_call_stat"
            + " WHERE stat_date &gt;= #{since} AND api_key_id IN"
            + " <foreach collection='keyIds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + " GROUP BY endpoint ORDER BY calls DESC LIMIT 20</script>")
    List<Map<String, Object>> endpoints(@Param("keyIds") List<String> keyIds,
                                        @Param("since") LocalDate since);

    /**
     * 按 Key × 日明细：首调率与 7 日留存要在 Java 里跟 Key 的签发日对齐，
     * 放 SQL 里做就要 join c_ai_api_key（那张表带租户谓词），联动一多判定口径就藏进 SQL 了。
     */
    @Select("<script>SELECT api_key_id AS k, stat_date AS d, SUM(calls) AS calls,"
            + " SUM(CASE WHEN status &gt;= 200 AND status &lt; 300 THEN calls ELSE 0 END) AS ok"
            + " FROM c_ai_openapi_call_stat"
            + " WHERE stat_date &gt;= #{since} AND api_key_id IN"
            + " <foreach collection='keyIds' item='k' open='(' separator=',' close=')'>#{k}</foreach>"
            + " GROUP BY api_key_id, stat_date</script>")
    List<Map<String, Object>> keyDaily(@Param("keyIds") List<String> keyIds,
                                       @Param("since") LocalDate since);
}
