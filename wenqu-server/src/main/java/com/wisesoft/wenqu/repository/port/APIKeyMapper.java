package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.APIKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * api_keys Mapper
 */
@Mapper
public interface APIKeyMapper extends BaseMapper<APIKey> {
}
