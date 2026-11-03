package com.wenqu.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 授权点目录 Mapper（c_ai_permission，纯目录表：扫描器从 {@code @Permission} 注解自动登记，
 * 管理员在权限页只读展示；授权点不是手工配置项——它的事实源在代码注解里）。
 *
 * @author yuanke
 */
@Mapper
public interface PermissionCatalogMapper {

    /** 登记一个授权点（幂等；code 唯一，已存在时保留管理员改过的名称） */
    @Insert("INSERT IGNORE INTO c_ai_permission(code, name, module) VALUES(#{code}, #{name}, #{module})")
    int register(@Param("code") String code, @Param("name") String name, @Param("module") String module);

    /** 全部授权点（权限页展示） */
    @Select("SELECT code, name, module, create_time AS createTime FROM c_ai_permission ORDER BY module, code")
    List<Map<String, Object>> listAll();

    /** 全部授权点编码（判定侧预载） */
    @Select("SELECT code FROM c_ai_permission")
    List<String> allCodes();
}
