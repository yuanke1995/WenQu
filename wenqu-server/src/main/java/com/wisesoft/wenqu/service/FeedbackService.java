package com.wisesoft.wenqu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.MessageFeedback;
import com.wisesoft.wenqu.repository.port.ConversationMapper;
import com.wisesoft.wenqu.repository.port.MessageFeedbackMapper;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repositories.RepoValues;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 消息反馈业务用例。
 *
 * <p>由参考实现的 services/feedback_service.py 逐方法翻译。
 *
 * <p>必要替换：
 * <ul>
 *   <li>{@code HTTPException(status, detail)} → {@link BizException}(code, message)，
 *       由全局异常处理器统一转为 JSON 响应（状态码语义一致：422/404/403/409）。
 *   <li>参考实现先 {@code db.commit()} 落库、再把 Langfuse 上报丢线程池执行（本地反馈已落库，
 *       上传失败不影响主流程）。本工程在事务提交后回调（afterCommit）执行上报，顺序一致；
 *       上报失败由端口实现内部吞掉（对应参考实现 submit_user_feedback_score 捕获异常返回 False）。
 *   <li>Langfuse 客户端属于外部集成，SDK 尚未照搬（能力差异）：以 {@link LangfusePort} 端口
 *       承载，未装配时跳过上报（对应参考实现 get_langfuse_client() 返回 None 的分支）。
 *   <li>{@code created_at.isoformat()} → 固定 {@code yyyy-MM-dd'T'HH:mm:ss}（Python 对零秒
 *       也输出秒位）。
 * </ul>
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private static final DateTimeFormatter NAIVE_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * Langfuse 上报端口（对应 submit_user_feedback_score；实现随 Langfuse 集成照搬时提供，
     * 合约：内部吞掉异常并返回 False，不得向上抛出）。
     */
    public interface LangfusePort {
        boolean submitUserFeedbackScore(
                String traceId, int feedbackId, int messageId, int conversationId, String uid, String rating, String reason);
    }

    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackMapper feedbackMapper;
    private final ObjectProvider<LangfusePort> langfusePort;

    public FeedbackService(
            MessageMapper messageMapper,
            ConversationMapper conversationMapper,
            MessageFeedbackMapper feedbackMapper,
            ObjectProvider<LangfusePort> langfusePort) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.feedbackMapper = feedbackMapper;
        this.langfusePort = langfusePort;
    }

    @Transactional
    public Map<String, Object> submitMessageFeedbackView(
            Integer messageId, String rating, String reason, String currentUid) {
        if (!"like".equals(rating) && !"dislike".equals(rating)) {
            throw new BizException(422, "Rating must be 'like' or 'dislike'");
        }

        Message message = messageMapper.selectOne(
                new LambdaQueryWrapper<Message>().eq(Message::getId, messageId));
        if (message == null) {
            throw new BizException(404, "Message not found");
        }

        Conversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getId, message.getConversationId()));
        if (conversation == null || !String.valueOf(currentUid).equals(conversation.getUid())) {
            throw new BizException(403, "Access denied");
        }

        MessageFeedback existingFeedback = feedbackMapper.selectOne(
                new LambdaQueryWrapper<MessageFeedback>()
                        .eq(MessageFeedback::getMessageId, messageId)
                        .eq(MessageFeedback::getUid, String.valueOf(currentUid)));
        if (existingFeedback != null) {
            throw new BizException(409, "Feedback already submitted for this message");
        }

        LocalDateTime now = DateTimeUtils.utcNowNaive();
        MessageFeedback newFeedback = new MessageFeedback();
        newFeedback.setMessageId(messageId);
        newFeedback.setUid(String.valueOf(currentUid));
        newFeedback.setRating(rating);
        newFeedback.setReason(reason);
        newFeedback.setCreatedAt(now);
        feedbackMapper.insert(newFeedback);

        String traceId = RepoValues.parseObject(message.getExtraMetadata()).getString("langfuse_trace_id");
        if (traceId != null && !traceId.isEmpty()) {
            LangfusePort port = langfusePort.getIfAvailable();
            if (port != null) {
                Integer feedbackId = newFeedback.getId();
                Integer conversationId = message.getConversationId();
                String uid = String.valueOf(currentUid);
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                port.submitUserFeedbackScore(
                                        traceId, feedbackId, messageId, conversationId, uid, rating, reason);
                            }
                        });
            }
        }

        log.info("User {} submitted {} feedback for message {}", currentUid, rating, messageId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", newFeedback.getId());
        result.put("message_id", newFeedback.getMessageId());
        result.put("rating", newFeedback.getRating());
        result.put("reason", newFeedback.getReason());
        result.put("created_at", newFeedback.getCreatedAt().format(NAIVE_ISO));
        return result;
    }

    public Map<String, Object> getMessageFeedbackView(Integer messageId, String currentUid) {
        MessageFeedback feedback = feedbackMapper.selectOne(
                new LambdaQueryWrapper<MessageFeedback>()
                        .eq(MessageFeedback::getMessageId, messageId)
                        .eq(MessageFeedback::getUid, String.valueOf(currentUid)));

        Map<String, Object> result = new LinkedHashMap<>();
        if (feedback == null) {
            result.put("has_feedback", false);
            result.put("feedback", null);
            return result;
        }

        Map<String, Object> feedbackData = new LinkedHashMap<>();
        feedbackData.put("id", feedback.getId());
        feedbackData.put("rating", feedback.getRating());
        feedbackData.put("reason", feedback.getReason());
        feedbackData.put("created_at", feedback.getCreatedAt().format(NAIVE_ISO));
        result.put("has_feedback", true);
        result.put("feedback", feedbackData);
        return result;
    }
}
