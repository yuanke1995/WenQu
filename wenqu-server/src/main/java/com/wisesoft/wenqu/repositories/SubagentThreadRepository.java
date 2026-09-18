package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.SubagentThread;
import com.wisesoft.wenqu.repository.port.SubagentThreadMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 子智能体线程关系仓储。
 *
 * <p>由参考实现的 repositories/subagent_thread_repository.py 逐方法翻译：全部查询都带
 * uid 归属条件（只返回当前用户可见的关系），创建时写入父/子对话、子线程、子智能体标识与
 * 发起运行 id。
 *
 * <p>说明：参考实现所有 uid 入参都经 {@code str(uid)} 归一，本工程同样按字符串比较。
 */
@Repository
public class SubagentThreadRepository {

    private final SubagentThreadMapper subagentThreadMapper;

    public SubagentThreadRepository(SubagentThreadMapper subagentThreadMapper) {
        this.subagentThreadMapper = subagentThreadMapper;
    }

    /** 按子线程 ID 查找当前用户可见的父子线程关系。 */
    public SubagentThread getByChildThreadForUser(String childThreadId, String uid) {
        return subagentThreadMapper.selectOne(
                new LambdaQueryWrapper<SubagentThread>()
                        .eq(SubagentThread::getChildThreadId, childThreadId)
                        .eq(SubagentThread::getUid, String.valueOf(uid)));
    }

    /** 按关系记录主键读取当前用户的子智能体线程关系。 */
    public SubagentThread getForUser(Integer relationId, String uid) {
        return subagentThreadMapper.selectOne(
                new LambdaQueryWrapper<SubagentThread>()
                        .eq(SubagentThread::getId, relationId)
                        .eq(SubagentThread::getUid, String.valueOf(uid)));
    }

    /** 按子对话 ID 查找父子线程关系，用于从对话反查父线程。 */
    public SubagentThread getByChildConversationForUser(Integer childConversationId, String uid) {
        return subagentThreadMapper.selectOne(
                new LambdaQueryWrapper<SubagentThread>()
                        .eq(SubagentThread::getChildConversationId, childConversationId)
                        .eq(SubagentThread::getUid, String.valueOf(uid)));
    }

    /** 创建一条父对话到子对话的线程关系记录。 */
    public SubagentThread create(
            String uid,
            Integer parentConversationId,
            Integer childConversationId,
            String childThreadId,
            String subagentSlug,
            String createdByRunId) {
        SubagentThread item = new SubagentThread();
        item.setUid(String.valueOf(uid));
        item.setParentConversationId(parentConversationId);
        item.setChildConversationId(childConversationId);
        item.setChildThreadId(childThreadId);
        item.setSubagentSlug(subagentSlug);
        item.setCreatedByRunId(createdByRunId);
        item.setCreatedAt(DateTimeUtils.utcNowNaive());
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        subagentThreadMapper.insert(item);
        return item;
    }

    /** 列出某用户的全部子线程关系（辅助查询，供后续服务层使用）。 */
    public List<SubagentThread> listForUser(String uid) {
        return subagentThreadMapper.selectList(
                new LambdaQueryWrapper<SubagentThread>().eq(SubagentThread::getUid, String.valueOf(uid)));
    }
}
