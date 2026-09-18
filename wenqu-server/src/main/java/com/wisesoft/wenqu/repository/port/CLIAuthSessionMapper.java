package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.CLIAuthSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * cli_auth_sessions Mapper
 */
@Mapper
public interface CLIAuthSessionMapper extends BaseMapper<CLIAuthSession> {
}
