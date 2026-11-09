package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.TenantOpenInvite;

/** 邀请式开通：CRUD（BaseMapper），token 校验按 token_hash 唯一键查 */
public interface TenantOpenInviteMapper extends BaseMapper<TenantOpenInvite> {
}
