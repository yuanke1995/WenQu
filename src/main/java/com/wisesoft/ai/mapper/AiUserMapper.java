package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 用户 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiUserMapper extends BaseMapper<AiUser> {
}
