package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * API Key Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiApiKeyMapper extends BaseMapper<AiApiKey> {

    /** 记录使用时间（鉴权热路径只做一次 update，失败不影响放行） */
    @Update("UPDATE c_ai_api_key SET last_used_at = #{ts} WHERE id = #{id}")
    int touchLastUsed(@Param("id") String id, @Param("ts") LocalDateTime ts);
}
