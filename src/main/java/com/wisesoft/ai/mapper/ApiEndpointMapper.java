package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.ApiEndpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * AI 接口 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ApiEndpointMapper extends BaseMapper<ApiEndpoint> {

    /** 按模块改名（仅展示/分组字段，不参与鉴权）：用于启动期把存量英文模块键归一成中文 */
    @Update("UPDATE c_ai_api SET module = #{zh} WHERE module = #{en}")
    int renameModule(@Param("en") String en, @Param("zh") String zh);
}
