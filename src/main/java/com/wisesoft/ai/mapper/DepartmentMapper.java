package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.Department;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 部门 Mapper
 * <p>
 * 注：{@link Department} 带 {@code @TableLogic}，{@code deleteById} 为逻辑删除；
 * 但唯一键 {@code uk_name} 只含 name 列，逻辑删除后的残留行仍占用该名称 → 同名部门无法重建。
 * 故删除部门走物理删除（删除前已由服务层校验无用户挂靠）。
 *
 * @author yuanke
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /** 物理删除（绕过 @TableLogic），避免软删残留行占用 uk_name 导致同名部门无法重建 */
    @Delete("DELETE FROM c_ai_department WHERE id = #{id}")
    int hardDeleteById(@Param("id") String id);
}
