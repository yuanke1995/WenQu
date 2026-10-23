package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.OidcProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * OIDC 认证源 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface OidcProviderMapper extends BaseMapper<OidcProvider> {
}
