package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.UserConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * user_config Mapper
 */
@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfig> {
}
