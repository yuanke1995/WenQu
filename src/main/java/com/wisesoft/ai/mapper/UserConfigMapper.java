package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.UserConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 个人配置覆盖 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfig> {
}
