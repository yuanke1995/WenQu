package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.MailDelivery;

/** 邮件外发投递台账：行级过滤由 {@code WenquTenantLineHandler} 负责（本表带 tenant_id） */
public interface MailDeliveryMapper extends BaseMapper<MailDelivery> {
}
