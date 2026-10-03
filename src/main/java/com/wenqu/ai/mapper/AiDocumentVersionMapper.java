package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.AiDocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 文档版本快照 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiDocumentVersionMapper extends BaseMapper<AiDocumentVersion> {
}
