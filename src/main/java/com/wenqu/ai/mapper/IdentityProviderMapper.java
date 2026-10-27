package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.IdentityProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 统一身份认证源（c_ai_identity_provider） */
@Mapper
public interface IdentityProviderMapper extends BaseMapper<IdentityProvider> {

    /**
     * 认证源换归属时，把由它决定归属的从属行一起带走（Phase 4 多租户）。
     * <p>身份绑定 / 组映射 / 组成员 / 同步日志一律按 {@code provider_id}（同步日志是 source_id）存取，
     * 它们的租户本就该跟着认证源走。留在旧租户的症状是「规则仍在界面上列着，裁决却再也不生效」
     * ——同步与登录都在新租户里跑，按租户过滤就读不到自己认证源的规则。这类静默失配必须一次做对。
     * <p>四条语句都在平台视图里调用（调用方 {@code IdentityProviderService.update} 已切好），
     * 否则框架会给 UPDATE 再拼一道本租户条件，改不动刚被挪走的行。
     */
    @Update("update c_ai_user_identity set tenant_id = #{tenant} where provider_id = #{providerId}")
    int restampBindings(@Param("providerId") String providerId, @Param("tenant") String tenant);

    @Update("update c_ai_group_role_mapping set tenant_id = #{tenant} where provider_id = #{providerId}")
    int restampGroupMappings(@Param("providerId") String providerId, @Param("tenant") String tenant);

    @Update("update c_ai_group_member set tenant_id = #{tenant} where provider_id = #{providerId}")
    int restampGroupMembers(@Param("providerId") String providerId, @Param("tenant") String tenant);

    @Update("update c_ai_sync_job_log set tenant_id = #{tenant} where source_id = #{providerId}")
    int restampSyncLogs(@Param("providerId") String providerId, @Param("tenant") String tenant);
}
