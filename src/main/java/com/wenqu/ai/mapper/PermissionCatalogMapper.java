package com.wenqu.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 全部授权点（权限页展示）。
     * <p>{@code readonly}：该授权点名下端点是否全为 GET/ALL（或 {@code self:*} 个人资产点）。
     * 与 {@code RbacSeedRunner} 建「只读管理员」预设的口径一致，权限页的「只读管理员」模板直接用它，
     * 避免前后端各写一套判断。没有登记任何端点的授权点视为非只读。</p>
     */
    @Select("SELECT p.code, p.name, p.module, p.create_time AS createTime, "
            + "CASE WHEN p.code LIKE 'self:%' THEN 1 "
            + "WHEN COUNT(a.id) > 0 AND SUM(CASE WHEN a.method IN ('GET','ALL') THEN 0 ELSE 1 END) = 0 THEN 1 "
            + "ELSE 0 END AS `readonly` "
            + "FROM c_ai_permission p LEFT JOIN c_ai_api a ON a.permission_code = p.code "
            + "GROUP BY p.code, p.name, p.module, p.create_time "
            + "ORDER BY p.module, p.code")
    List<Map<String, Object>> listAll();

    /** 全部授权点编码（判定侧预载） */
    @Select("SELECT code FROM c_ai_permission")
    List<String> allCodes();

    /**
     * 存量行展示名回填：仅当库里的 name 仍等于 code（未被管理员手工改名）时更新。
     * <p>管理员改过的名称不会被覆盖。</p>
     */
    @Update("UPDATE c_ai_permission SET name = #{name} WHERE code = #{code} AND name = code")
    int backfillNameIfSame(@Param("code") String code, @Param("name") String name);
}
