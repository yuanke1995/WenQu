package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.UserIdentity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-外部身份关联 Mapper（统一身份模型）
 *
 * @author yuanke
 */
@Mapper
public interface UserIdentityMapper extends BaseMapper<UserIdentity> {
}
