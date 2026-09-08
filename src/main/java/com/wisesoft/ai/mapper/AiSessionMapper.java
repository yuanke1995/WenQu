package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * AI 会话 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {

    /** 行级锁读取会话（消息序号分配路径用：锁行串行化同会话并发写）。
     *  自定义 SQL 不过滤逻辑删除——软删行同样需要可锁，避免补建占位后仍串行失败。 */
    @Select("SELECT * FROM c_ai_session WHERE id = #{id} FOR UPDATE")
    AiSession selectForUpdate(@Param("id") String id);

    /** 物理删除逻辑删除标记且更新时间早于截止时间的会话（过期数据清理；撤销窗口由保留期界定，幂等） */
    @Delete("DELETE FROM c_ai_session WHERE deleted = 1 AND update_time < #{cutoff}")
    int purgeDeletedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
