package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.UserConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 个人配置覆盖 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfig> {
}
