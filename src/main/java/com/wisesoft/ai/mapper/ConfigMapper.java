package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.Config;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置表 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ConfigMapper extends BaseMapper<Config> {
}
