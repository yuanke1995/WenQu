package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * operation_logs Mapper
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
