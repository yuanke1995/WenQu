package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.QaFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
