package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.SteerItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 插话待发队列的数据访问。状态流转全部用 CAS（{@code WHERE status=…}）：
 * 两个副本同时 drain 时只有一个能把同一行从 PENDING 抢走，另一个拿 0 行即跳过。
 */
public interface SteerItemMapper extends BaseMapper<SteerItem> {

    @Select("SELECT * FROM c_ai_steer_item WHERE session_id = #{sessionId} AND status IN ('PENDING','STEERED') "
            + "ORDER BY created_at ASC LIMIT 200")
    List<SteerItem> pendingOf(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM c_ai_steer_item WHERE id = #{id} LIMIT 1")
    SteerItem byId(@Param("id") String id);

    /** 抢占一行（PENDING→STEERED）。返回 0 表示已被别人抢走或已不在排队。 */
    @Update("UPDATE c_ai_steer_item SET status='STEERED', turn_id=#{turnId}, "
            + "attempts=attempts+1, last_error=NULL WHERE id=#{id} AND status='PENDING'")
    int claim(@Param("id") String id, @Param("turnId") String turnId);

    /** 送达成功：把框架收据号与实际送达的那一轮补上（inbox/applied 据此置 APPLIED）。 */
    @Update("UPDATE c_ai_steer_item SET receipt_id=#{receiptId}, turn_id=#{turnId} WHERE id=#{id} AND status='STEERED'")
    int setReceipt(@Param("id") String id, @Param("receiptId") String receiptId, @Param("turnId") String turnId);

    /** 送达失败：退回排队，把原因留在行上（可见、不静默吞）。 */
    @Update("UPDATE c_ai_steer_item SET status='PENDING', receipt_id=NULL, last_error=#{error} "
            + "WHERE id=#{id} AND status='STEERED'")
    int release(@Param("id") String id, @Param("error") String error);

    /** 框架确认应用：按收据号置 APPLIED（幂等，跨副本也能命中）。 */
    @Update("UPDATE c_ai_steer_item SET status='APPLIED' WHERE receipt_id=#{receiptId} AND status='STEERED'")
    int applied(@Param("receiptId") String receiptId);

    /** 用户丢弃：只允许丢掉还在排队的项；已送出的等模型听到，不能假装撤回了。 */
    @Update("UPDATE c_ai_steer_item SET status='DROPPED' WHERE id=#{id} AND user_id=#{userId} "
            + "AND session_id=#{sessionId} AND status='PENDING'")
    int drop(@Param("id") String id, @Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 会话/目标终态收敛：该会话还排着的项不能再假装「等轮开跑」。 */
    @Update("UPDATE c_ai_steer_item SET status='DROPPED', last_error='会话已结束，未送达' "
            + "WHERE session_id=#{sessionId} AND status='PENDING'")
    int dropAllPending(@Param("sessionId") String sessionId);

    /** 崩溃窗口兜底：claim 之后进程没了，长于 TTL 的 STEERED 行退回排队（可能就是重复一句的窗口）。 */
    @Update("UPDATE c_ai_steer_item SET status='PENDING', receipt_id=NULL, last_error='送出去后没有回执，重新排队' "
            + "WHERE session_id=#{sessionId} AND status='STEERED' AND updated_at < NOW(3) - INTERVAL #{seconds} SECOND")
    int reviveStale(@Param("sessionId") String sessionId, @Param("seconds") int seconds);
}
