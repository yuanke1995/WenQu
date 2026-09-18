package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.ConfigOption;
import org.apache.ibatis.annotations.Mapper;

/**
 * config_options Mapper
 */
@Mapper
public interface ConfigOptionMapper extends BaseMapper<ConfigOption> {
}
