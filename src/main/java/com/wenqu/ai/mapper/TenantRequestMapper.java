package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.TenantRequest;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 租户开通申请：CRUD（BaseMapper）+ 待审计数（铃铛角标用） */
public interface TenantRequestMapper extends BaseMapper<TenantRequest> {

    /** 待审申请的编号清单（管理员页默认只看这些） */
    @Select("select id from c_ai_tenant_request where status = 'pending' order by create_time asc")
    List<String> pendingIds();
}
