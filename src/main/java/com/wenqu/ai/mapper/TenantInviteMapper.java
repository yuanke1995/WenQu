package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.TenantInvite;

/** 租户邀请：行级过滤由 {@code WenquTenantLineHandler} 负责（本表带 tenant_id） */
public interface TenantInviteMapper extends BaseMapper<TenantInvite> {
}
