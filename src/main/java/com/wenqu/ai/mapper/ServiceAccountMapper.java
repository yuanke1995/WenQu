package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ServiceAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 服务账号 Mapper（c_ai_service_account，批次 C3）。
 *
 * @author yuanke
 */
@Mapper
public interface ServiceAccountMapper extends BaseMapper<ServiceAccount> {
}
