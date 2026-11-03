package com.wenqu.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 角色-授权点绑定 Mapper（c_ai_role_permission，纯关系表：MyBatis-Plus 不便处理复合主键，直接注解 SQL）。
 * <p>
 * 判定口径（方案 D3/B1）：allow 并集放大权限；deny 优先于 allow（任一角色 deny 即 deny），
 * 除非该 deny 行带 {@code exempt=1}（只在该角色内部生效，不压其它角色给的 allow）。
 * 内置管理员级（admin_flag=1）不写绑定行，判定时展开为「除被 deny 外全部 allow」（方案 D5）。
 *
 * @author yuanke
 */
@Mapper
public interface RolePermissionMapper {

    /** 某角色的全部绑定行（permission_code + effect + exempt） */
    @Select("SELECT permission_code AS permissionCode, effect, exempt FROM c_ai_role_permission WHERE role_code = #{roleCode}")
    List<Map<String, Object>> rowsOfRole(@Param("roleCode") String roleCode);

    /** 某授权点被哪些角色绑定（反查：接口页「已授权角色」列，方案 D-1） */
    @Select("SELECT DISTINCT role_code FROM c_ai_role_permission WHERE permission_code = #{permissionCode}")
    List<String> roleCodesOfPermission(@Param("permissionCode") String permissionCode);

    /** 绑定 allow（幂等；同角色同授权点已有 deny 行时由服务层裁决，不在 SQL 层静默覆盖） */
    @Insert("INSERT IGNORE INTO c_ai_role_permission(role_code, permission_code, effect, exempt) VALUES(#{roleCode}, #{permissionCode}, 'allow', 0)")
    int bindAllow(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode);

    /** 绑定 deny（exempt=1 表示仅该角色内部生效，方案 D3 豁免开关） */
    @Insert("INSERT IGNORE INTO c_ai_role_permission(role_code, permission_code, effect, exempt) VALUES(#{roleCode}, #{permissionCode}, 'deny', #{exempt})")
    int bindDeny(@Param("roleCode") String roleCode, @Param("permissionCode") String permissionCode,
                 @Param("exempt") int exempt);

    /** 清空某角色的全部绑定（保存为「全量替换」语义） */
    @Delete("DELETE FROM c_ai_role_permission WHERE role_code = #{roleCode}")
    int unbindAllOfRole(@Param("roleCode") String roleCode);

    /** 授权点退役时清理引用 */
    @Delete("DELETE FROM c_ai_role_permission WHERE permission_code = #{permissionCode}")
    int unbindByPermission(@Param("permissionCode") String permissionCode);

    /** 持有某授权点的角色数（授权点占用守门） */
    @Select("SELECT COUNT(DISTINCT role_code) FROM c_ai_role_permission WHERE permission_code = #{permissionCode}")
    long countRolesOfPermission(@Param("permissionCode") String permissionCode);
}
