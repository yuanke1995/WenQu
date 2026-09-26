package com.wisesoft.ai.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-接口绑定 Mapper（纯关系表，无实体：MyBatis-Plus 不便处理复合主键，直接注解 SQL）
 *
 * @author yuanke
 */
@Mapper
public interface RoleApiMapper {

    @Select("SELECT api_id FROM c_ai_role_api WHERE role_code = #{roleCode}")
    List<String> apiIdsOfRole(@Param("roleCode") String roleCode);

    @Select("SELECT role_code FROM c_ai_role_api WHERE api_id = #{apiId}")
    List<String> roleCodesOfApi(@Param("apiId") String apiId);

    @Insert("INSERT IGNORE INTO c_ai_role_api(role_code, api_id) VALUES(#{roleCode}, #{apiId})")
    int bind(@Param("roleCode") String roleCode, @Param("apiId") String apiId);

    /** 清空某角色的全部接口绑定（绑定保存为「全量替换」语义） */
    @Delete("DELETE FROM c_ai_role_api WHERE role_code = #{roleCode}")
    int unbindAllOfRole(@Param("roleCode") String roleCode);

    /** 接口删除时清理引用 */
    @Delete("DELETE FROM c_ai_role_api WHERE api_id = #{apiId}")
    int unbindByApi(@Param("apiId") String apiId);
}
