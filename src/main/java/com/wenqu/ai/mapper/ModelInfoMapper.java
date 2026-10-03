package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ModelInfo;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型库 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ModelInfoMapper extends BaseMapper<ModelInfo> {
}
