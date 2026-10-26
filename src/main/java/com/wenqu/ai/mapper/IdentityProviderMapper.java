package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.IdentityProvider;
import org.apache.ibatis.annotations.Mapper;

/** 统一身份认证源（c_ai_identity_provider） */
@Mapper
public interface IdentityProviderMapper extends BaseMapper<IdentityProvider> {
}
