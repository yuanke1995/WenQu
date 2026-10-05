package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.SessionShare;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 会话只读分享 Mapper
 *
 * @author yuanke
 */
public interface SessionShareMapper extends BaseMapper<SessionShare> {

    /**
     * 物理删除**已停用**且更新时间早于截止时间的分享记录（过期数据清理；幂等）。
     *
     * <p>条件里 {@code enabled = 0} 是硬约束：停用即"我关闭了这件事"，但停用后仍生效的
     * 链接随时可能被访客打开，任何情况下都不能被清理任务删掉。保留期与
     * {@code cleanup.sessionRetentionDays}（会话撤销窗口）对齐——窗口期内用户还能反悔，
     * 窗口过后没有留着的理由。
     *
     * <p>用 {@code update_time} 而非 {@code create_time}：一条分享可以被停用又被重新开启
     * 多次（每次开启换新 token 并把 update_time 推到当下），按创建时间算会把它当成
     * "很老的记录"清掉，可它其实是刚刚才发出去了。
     */
    @Delete("DELETE FROM c_ai_session_share WHERE enabled = 0 AND update_time < #{cutoff}")
    int purgeDisabledOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
