package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户（Phase 4 · P9）。
 *
 * <p>只有注册信息，没有配置项：租户的隔离是<b>行级归属</b>（全表 {@code tenant_id}），
 * 不是独立实例、不是独立库。平台参数（供应商、模型、菜单与角色定义）仍是平台级共享，
 * 而内容安全词表、配额、审计与用量按租户各算各的；边界与理由见 {@code WenquTenantLineHandler}
 * 与手册「多租户」。
 *
 * <p>{@code id} 直接就是各行 {@code tenant_id} 的取值（不再另设一个 code 列），
 * 少一次映射就少一处能写错的地方。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_tenant")
public class Tenant {

    /** 租户标识（= 业务行里的 tenant_id）；default 是单租户部署的那个 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 显示名 */
    private String name;

    /** 1=启用；0=停用（停用后该租户成员无法登录，数据保留） */
    private Integer status;

    /** 联系人/对接人 */
    private String contact;

    /** 备注：这个租户是谁、怎么开通的 */
    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
