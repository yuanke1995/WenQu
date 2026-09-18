package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.Department;
import org.apache.ibatis.annotations.Mapper;

/**
 * departments Mapper
 */
@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {
}
