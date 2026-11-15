package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.OpenRun;
import org.apache.ibatis.annotations.Mapper;

/** 对外开放运行映射表（写入 + 归属查询） */
@Mapper
public interface OpenRunMapper extends BaseMapper<OpenRun> {
}
