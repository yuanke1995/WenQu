package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.OperationLog;
import com.wisesoft.wenqu.repository.port.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作审计日志。
 *
 * <p>由参考实现的 services/operation_log_service.py 翻译（模块级函数 → 服务方法）。
 *
 * <p>必要替换：参考实现的 {@code request.client.host}（FastAPI 对端地址）→
 * {@link HttpServletRequest#getRemoteAddr()}；其余语义不变。
 */
@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 把强制审计事实加入当前 owning transaction；失败必须阻止业务提交。
     *
     * <p>事务传播 REQUIRED：调用方有事务即加入，本方法抛出异常会使整个业务事务回滚——
     * 与参考实现「加入当前 owning transaction」的语义一致。
     */
    @Transactional
    public void logOperation(Integer userId, String operation, String details, HttpServletRequest request) {
        String ipAddress = null;
        if (request != null && request.getRemoteAddr() != null) {
            ipAddress = request.getRemoteAddr();
        }
        OperationLog record = new OperationLog();
        record.setUserId(userId);
        record.setOperation(operation);
        record.setDetails(details);
        record.setIpAddress(ipAddress);
        record.setTimestamp(DateTimeUtils.utcNowNaive());
        operationLogMapper.insert(record);
    }
}
