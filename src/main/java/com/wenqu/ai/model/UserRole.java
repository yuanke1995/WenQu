package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色多对多（取代 {@code c_ai_user.role} 单值列）：一个用户可持多个角色，
 * 授权判定取并集（任一角色为管理员级即管理员；接口/菜单权限各角色授权结果取并集）。
 * <p>
 * 复合主键 {@code (uid, role_code)}——实体不声明 @TableId（MyBatis-Plus 不支持复合主键），
 * 一律走 Wrapper 查询/删除，不用 selectById/updateById。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_role")
public class UserRole {

    /** 本地账号 (c_ai_user.uid) */
    private String uid;

    /** 角色编码 (c_ai_role.code) */
    private String roleCode;

    /** 授权来源: idp | manual（idp=组映射/目录同步授予，IdP 权威口径下不被手动改动静默覆盖） */
    private String grantedBy;

    private LocalDateTime createTime;
}
