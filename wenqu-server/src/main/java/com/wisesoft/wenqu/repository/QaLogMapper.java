package com.wisesoft.wenqu.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.model.QaLog;
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

    /** 最近 limit 条日志中热门问题 TOP10（先取最近 N 条再过滤聚合，保留原 LIMIT 2000 语义） */
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
}
