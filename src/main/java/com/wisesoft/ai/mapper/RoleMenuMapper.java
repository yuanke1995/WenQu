package com.wisesoft.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-菜单绑定 Mapper（纯关系表，无实体：MyBatis-Plus 不便处理复合主键，直接注解 SQL）
 *
 * @author yuanke
 */
@Mapper
public interface RoleMenuMapper {

    @Select("SELECT menu_id FROM c_ai_role_menu WHERE role_code = #{roleCode}")
    List<String> menuIdsOfRole(@Param("roleCode") String roleCode);

    @Select("SELECT role_code FROM c_ai_role_menu WHERE menu_id = #{menuId}")
    List<String> roleCodesOfMenu(@Param("menuId") String menuId);

    @Insert("INSERT IGNORE INTO c_ai_role_menu(role_code, menu_id) VALUES(#{roleCode}, #{menuId})")
    int bind(@Param("roleCode") String roleCode, @Param("menuId") String menuId);

    /** 清空某角色的全部菜单绑定（绑定保存为「全量替换」语义） */
    @Delete("DELETE FROM c_ai_role_menu WHERE role_code = #{roleCode}")
    int unbindAllOfRole(@Param("roleCode") String roleCode);

    /** 菜单删除时清理引用 */
    @Delete("DELETE FROM c_ai_role_menu WHERE menu_id = #{menuId}")
    int unbindByMenu(@Param("menuId") String menuId);
}
