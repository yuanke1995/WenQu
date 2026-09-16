package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AnswerCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 相似问题答案缓存 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AnswerCacheMapper extends BaseMapper<AnswerCache> {

    /**
     * 原子自增命中数并校验行仍存在（二合一）：
     * 返回受影响行数 = 1 表示命中且入库条目有效；= 0 表示该缓存行已被其它实例清除/淘汰
     * （本实例内存索引滞后），调用方据此整体失效本地索引，避免返回基于旧知识库的过期答案。
     */
    @Update("UPDATE c_ai_answer_cache SET hit_count = hit_count + 1 WHERE id = #{id}")
    int incrHitCount(String id);
}