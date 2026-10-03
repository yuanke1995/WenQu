package com.wenqu.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 菜单-接口归属 Mapper（纯关系表，无实体：MyBatis-Plus 不便处理复合主键，直接注解 SQL）。
 * <p>
 * 多归属：同一接口可挂多个菜单（如部门接口同时归属「成员管理」与「权限管理」）。
 * 仅用于权限配置页的分组展示与勾选联动，**不参与鉴权**（鉴权只认 c_ai_role_api 绑定）。
 *
 * @author yuanke
 */
@Mapper
public interface MenuApiMapper {

    @Select("SELECT api_id FROM c_ai_menu_api WHERE menu_id = #{menuId}")
    List<String> apiIdsOfMenu(@Param("menuId") String menuId);

    @Select("SELECT menu_id FROM c_ai_menu_api WHERE api_id = #{apiId}")
    List<String> menuIdsOfApi(@Param("apiId") String apiId);

    /** 全量归属关系（表小，权限页列表聚合回填用；列：api_id / menu_id） */
    @Select("SELECT api_id, menu_id FROM c_ai_menu_api")
    List<Map<String, Object>> all();

    /** 已有任一归属的接口 id 集合（扫描器幂等补齐用） */
    @Select("SELECT DISTINCT api_id FROM c_ai_menu_api")
    List<String> boundApiIds();

    @Insert("INSERT IGNORE INTO c_ai_menu_api(menu_id, api_id) VALUES(#{menuId}, #{apiId})")
    int bind(@Param("menuId") String menuId, @Param("apiId") String apiId);

    /** 菜单删除时清理引用 */
    @Delete("DELETE FROM c_ai_menu_api WHERE menu_id = #{menuId}")
    int unbindByMenu(@Param("menuId") String menuId);

    /** 接口删除时清理引用 */
    @Delete("DELETE FROM c_ai_menu_api WHERE api_id = #{apiId}")
    int unbindByApi(@Param("apiId") String apiId);
}
