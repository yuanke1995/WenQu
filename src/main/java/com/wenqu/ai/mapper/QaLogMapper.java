package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.QaLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 问答日志 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface QaLogMapper extends BaseMapper<QaLog> {

    /** 最近 limit 条日志中热门问题 TOP10（先取最近 N 条再过滤聚合） */
    @Select("SELECT question, COUNT(*) AS cnt FROM (" +
            "  SELECT question FROM c_ai_qa_log ORDER BY created_at DESC LIMIT #{limit}" +
            ") t WHERE question IS NOT NULL AND question <> '' GROUP BY question ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> topQuestions(@Param("limit") int limit);

    /** 最近 limit 条日志中无命中问题 TOP10（过滤下推到子查询，外层只引用派生表列） */
    @Select("SELECT question, COUNT(*) AS cnt FROM (" +
            "  SELECT question FROM c_ai_qa_log WHERE (hit_doc_ids IS NULL OR hit_doc_ids = '') " +
            "  ORDER BY created_at DESC LIMIT #{limit}" +
            ") t WHERE question IS NOT NULL AND question <> '' " +
            "GROUP BY question ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> noHitQuestions(@Param("limit") int limit);

    /** 最近 limit 条日志聚合：总数 / 无命中数 / 引用数 */
    @Select("SELECT COUNT(*) AS total," +
            " SUM(CASE WHEN hit_doc_ids IS NULL OR hit_doc_ids = '' THEN 1 ELSE 0 END) AS no_hit," +
            " SUM(CASE WHEN has_citation = 1 THEN 1 ELSE 0 END) AS cited" +
            " FROM (SELECT hit_doc_ids, has_citation FROM c_ai_qa_log ORDER BY created_at DESC LIMIT #{limit}) t")
    Map<String, Object> summaryStats(@Param("limit") int limit);

    /** 指定时间窗口内无命中问题 TOP50（供看板一键入库） */
    @Select("SELECT question, COUNT(*) AS cnt, MAX(created_at) AS latest FROM c_ai_qa_log" +
            " WHERE created_at >= #{since} AND (hit_doc_ids IS NULL OR hit_doc_ids = '')" +
            " AND question IS NOT NULL AND question <> ''" +
            " GROUP BY question ORDER BY cnt DESC LIMIT 50")
    List<Map<String, Object>> noHitTop(@Param("since") LocalDateTime since);

    // ==================== 看板时间窗口聚合（range=7/30/90/0；until 用于上期窗口，左闭右开） ====================

    /** 区间汇总：总数 / 无命中数 / 引用数（from 为空=不限起点；until 为空=到此刻） */
    @Select("<script>SELECT COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN hit_doc_ids IS NULL OR hit_doc_ids = '' THEN 1 ELSE 0 END), 0) AS no_hit," +
            " COALESCE(SUM(CASE WHEN has_citation = 1 THEN 1 ELSE 0 END), 0) AS cited" +
            " FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " </where></script>")
    Map<String, Object> summaryBetween(@Param("from") LocalDateTime from,
                                       @Param("until") LocalDateTime until);

    /** 区间热门问题 TOP N */
    @Select("<script>SELECT question, COUNT(*) AS cnt FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " AND question IS NOT NULL AND question &lt;&gt; ''" +
            " </where>" +
            " GROUP BY question ORDER BY cnt DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> topQuestionsBetween(@Param("from") LocalDateTime from,
                                                  @Param("until") LocalDateTime until,
                                                  @Param("limit") int limit);

    /** 区间无命中问题 TOP N */
    @Select("<script>SELECT question, COUNT(*) AS cnt, MAX(created_at) AS latest FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " AND (hit_doc_ids IS NULL OR hit_doc_ids = '')" +
            " AND question IS NOT NULL AND question &lt;&gt; ''" +
            " </where>" +
            " GROUP BY question ORDER BY cnt DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> noHitQuestionsBetween(@Param("from") LocalDateTime from,
                                                    @Param("until") LocalDateTime until,
                                                    @Param("limit") int limit);

    /**
     * 区间知识缺口 TOP N：只统计本轮真调过检索却没命中的问题。
     * <p>与 {@link #noHitQuestionsBetween} 的差别只在 {@code retrieval_attempted = 1}——
     * 未挂知识库的纯角色智能体、模型没调检索工具的闲聊轮、工作流轮次都不算缺口。
     */
    @Select("<script>SELECT question, COUNT(*) AS cnt, MAX(created_at) AS latest FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " AND (hit_doc_ids IS NULL OR hit_doc_ids = '')" +
            " AND retrieval_attempted = 1" +
            " AND question IS NOT NULL AND question &lt;&gt; ''" +
            " </where>" +
            " GROUP BY question ORDER BY cnt DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> gapQuestionsBetween(@Param("from") LocalDateTime from,
                                                  @Param("until") LocalDateTime until,
                                                  @Param("limit") int limit);

    /** 区间按天聚合（趋势图；缺失日期由服务层补零） */
    @Select("<script>SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d, COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN hit_doc_ids IS NULL OR hit_doc_ids = '' THEN 1 ELSE 0 END), 0) AS no_hit," +
            " COALESCE(SUM(CASE WHEN has_citation = 1 THEN 1 ELSE 0 END), 0) AS cited" +
            " FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " </where>" +
            " GROUP BY d ORDER BY d</script>")
    List<Map<String, Object>> dailyBetween(@Param("from") LocalDateTime from,
                                           @Param("until") LocalDateTime until);

    /** 区间耗时样本（降序，用于 P95；limit 防止大区间拉全表） */
    @Select("<script>SELECT elapsed_ms AS ms FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " AND elapsed_ms IS NOT NULL" +
            " </where>" +
            " ORDER BY elapsed_ms DESC LIMIT #{limit}</script>")
    List<Integer> elapsedBetween(@Param("from") LocalDateTime from,
                                 @Param("until") LocalDateTime until,
                                 @Param("limit") int limit);

    /** 区间无命中问题去重计数（待办队列「知识缺口」计数，不是 TOP 条数；只认真调过检索的轮次） */
    @Select("<script>SELECT COUNT(DISTINCT question) AS c FROM c_ai_qa_log" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " AND (hit_doc_ids IS NULL OR hit_doc_ids = '')" +
            " AND retrieval_attempted = 1" +
            " AND question IS NOT NULL AND question &lt;&gt; ''" +
            " </where></script>")
    long distinctNoHitBetween(@Param("from") LocalDateTime from,
                              @Param("until") LocalDateTime until);

    /** 最近一条问答时间（页头「数据截至」） */
    @Select("SELECT MAX(created_at) FROM c_ai_qa_log")
    LocalDateTime lastCreatedAt();
}
