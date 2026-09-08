package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * AI 消息 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /** 按 ID 查询（忽略逻辑删除标记，撤销删除时定位已软删消息用；自定义 SQL 不经 @TableLogic 改写） */
    @Select("SELECT * FROM c_ai_message WHERE id = #{id}")
    AiMessage selectByIdIgnoreDeleted(@Param("id") String id);

    /** 按会话+序号+角色查询（忽略逻辑删除，撤销删除时配对同组用户问题用） */
    @Select("SELECT * FROM c_ai_message WHERE session_id = #{sessionId} AND sequence = #{seq} AND role = #{role} " +
            "ORDER BY create_time DESC LIMIT 1")
    AiMessage selectBySeqIgnoreDeleted(@Param("sessionId") String sessionId,
                                       @Param("seq") int seq,
                                       @Param("role") String role);

    /** 恢复单条软删除消息（撤销删除用） */
    @Update("UPDATE c_ai_message SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") String id);

    /** 会话内最大序号（物理查询：含已软删行）。删除轮次后序号不复用，撤销按 seq-1 精确配对依赖序号单调唯一 */
    @Select("SELECT COALESCE(MAX(sequence), 0) FROM c_ai_message WHERE session_id = #{sessionId}")
    int maxSequencePhysical(@Param("sessionId") String sessionId);

    /** 批量查询已存在消息 ID（忽略逻辑删除：软删消息仍在撤销窗口内，降级补写不得用同 ID 重插撞主键） */
    @Select("<script>SELECT id FROM c_ai_message WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<String> selectExistingIdsIncludingDeleted(@Param("ids") java.util.Collection<String> ids);

    /** 物理删除逻辑删除标记且创建时间早于截止时间的消息（过期数据清理；物理清除即"撤销删除"窗口终点，幂等） */
    @Delete("DELETE FROM c_ai_message WHERE deleted = 1 AND create_time < #{cutoff}")
    int purgeDeletedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}