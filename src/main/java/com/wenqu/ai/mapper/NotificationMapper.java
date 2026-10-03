package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper（纯 BaseMapper，查询条件都在 Service 层用 LambdaWrapper 表达）。
 *
 * @author yuanke
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
