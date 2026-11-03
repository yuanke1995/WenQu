package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色数据范围（批次 C2，方案 docs/rbac-upgrade-plan.md §2/§3 批次 C）：
 * 角色自带数据范围——资源侧不再有"共享给谁"入口（share_config 已退役），
 * 用户能看什么由其角色集合推导。判定 = 角色数据范围 ∪ 创建者（创建者恒 MANAGE）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_role_data_scope")
public class RoleDataScope {

    /** 角色编码（c_ai_role.code，主键——每角色一行） */
    @TableId
    private String roleCode;

    /**
     * 范围类型: all=全部资源 | own=仅本人创建（默认） |
     * department=本部门创建的资源可读 | department_and_children=含下级部门
     */
    private String scopeType;

    /** 生效部门（scope_type=department/department_and_children 时必填；空=取成员自己的部门） */
    private String departmentId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
