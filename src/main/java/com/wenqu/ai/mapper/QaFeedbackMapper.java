package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.QaFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 问答反馈 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface QaFeedbackMapper extends BaseMapper<QaFeedback> {

    /** 反馈表聚合：总数 / 点赞数（rating=1） */
    @Select("SELECT COUNT(*) AS total, SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END) AS likes FROM c_ai_qa_feedback")
    Map<String, Object> feedbackStats();

    /** 区间反馈聚合（看板时间范围与上期对比；until 左闭右开） */
    @Select("<script>SELECT COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END), 0) AS likes" +
            " FROM c_ai_qa_feedback" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " </where></script>")
    Map<String, Object> feedbackStatsBetween(@Param("from") LocalDateTime from,
                                             @Param("until") LocalDateTime until);

    /** 区间按天反馈（满意度折线的数据源；与问答趋势按日期对齐） */
    @Select("<script>SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS d, COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END), 0) AS likes" +
            " FROM c_ai_qa_feedback" +
            " <where>" +
            " <if test='from != null'>created_at &gt;= #{from}</if>" +
            " <if test='until != null'>AND created_at &lt; #{until}</if>" +
            " </where>" +
            " GROUP BY d ORDER BY d</script>")
    List<Map<String, Object>> dailyBetween(@Param("from") LocalDateTime from,
                                           @Param("until") LocalDateTime until);
}
