package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.Tenant;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 租户注册表：只有 CRUD（行级过滤由 {@code WenquTenantLineHandler} 负责，本表自身豁免） */
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 各租户的成员数（管理页一览）。
     * <p>裸 SQL 聚合走的是 {@code c_ai_user}——只在平台视图里调用（跨租户统计本就是平台动作），
     * 否则租户拦截器会给它加上当前的 {@code tenant_id} 条件，数出来只剩自己一家。
     */
    @Select("select tenant_id as tenantId, count(*) as members from c_ai_user group by tenant_id")
    List<Map<String, Object>> memberCounts();
}
