package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import org.springframework.stereotype.Repository;

/**
 * 运行输出消息查询。
 *
 * <p>由参考实现的 repositories/agent_run_output_repository.py 逐方法翻译：
 * 只在指定 Run 的因果边界内读取输出消息；仅对历史 completed Run 启用"同 Run 取最后一条
 * assistant"的兼容读取。
 */
@Repository
public class AgentRunOutputRepository {

    /** 声明为常量以避免魔法值（参考实现为方法关键字参数）。 */
    private final MessageMapper messageMapper;

    public AgentRunOutputRepository(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /** 读取显式绑定消息；仅对历史 completed Run 启用同 Run 兼容读取。 */
    public Message getOutputMessage(
            String runId, Integer conversationId, Integer outputMessageId, boolean allowLegacyFallback) {
        if (outputMessageId == null && !allowLegacyFallback) {
            return null;
        }
        LambdaQueryWrapper<Message> wrapper =
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .eq(Message::getRunId, runId)
                        .eq(Message::getRole, "assistant");
        if (outputMessageId != null) {
            wrapper.eq(Message::getId, outputMessageId);
        } else {
            wrapper.orderByDesc(Message::getCreatedAt).orderByDesc(Message::getId).last("LIMIT 1");
        }
        return messageMapper.selectOne(wrapper);
    }
}
