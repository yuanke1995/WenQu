package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 角色表（RBAC）。
 * <p>
 * {@code c_ai_user.role} 的值域即本表 {@code code}（单角色模型）。
 * 内置三角色：superadmin / admin（管理员级，直通全部接口与菜单）、user（普通）；
 * 自定义角色可标记 {@code adminFlag=1} 升为管理员级，否则按
 * {@code c_ai_role_menu}（侧边栏菜单）与 {@code c_ai_role_api}（可调接口）绑定授权。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_role")
public class Role {

    /** 角色编码（小写字母开头，≤20 字符，与 c_ai_user.role VARCHAR(20) 对齐） */
    @TableId(type = IdType.INPUT)
    private String code;

    /** 角色名称 */
    private String name;

    /** 角色描述 */
    private String description;

    /** 管理员级: 1=视同管理员（放行全部接口与菜单） */
    private Integer adminFlag;

    /** 内置角色: 1=预置不可删除（superadmin/admin/user） */
    private Integer builtin;

    /** 状态: 1=启用, 0=停用（停用后仅保留问答白名单能力） */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
