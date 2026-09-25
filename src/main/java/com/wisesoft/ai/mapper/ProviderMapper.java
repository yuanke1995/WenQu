package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.Provider;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型供应商 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ProviderMapper extends BaseMapper<Provider> {
}
