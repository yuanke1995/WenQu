package com.wisesoft.ai.service;

import com.wisesoft.ai.mapper.UsageLogMapper;
import com.wisesoft.ai.model.UsageLog;
import com.wisesoft.ai.util.RequestUser;
import com.wisesoft.ai.util.UsageAccumulator;
import com.wisesoft.ai.util.UsageAttr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 推理用量台账：全公司唯一的记账口。
 * <p>
 * 记账点下沉在 {@link DynamicOpenAiChatModel}（多供应商路由出口）——任何经 platform
 * 发出的 LLM 请求都不可能绕过它，因此新增调用方（工作流节点、未来工具）无需改动即可
 * 被记账，避免"逐处补漏"必然漏的现实。
 * <p>
 * <b>归属</b>：优先用调用方显式给的上下文（流式走 Reactor Context、同步走 ThreadLocal），
 * 其次回落到 {@link RequestUser}；都取不到就是无用户上下文的系统调用（索引/评测/探测），
 * uid 落 NULL——宁可如实留空，也不猜 migrate 归属。
 * <p>
 * <b>幂等</b>：一次模型请求一行。工具调用循环中每一轮是一次真实请求，逐轮各记一行。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageLedgerService {

    private final UsageLogMapper usageLogMapper;

    /**
     * 非流式调用记账（即刻浏览器式：usage 随响应一次返回）。
     *
     * @param attr        显式归属（可为 null）
     * @param modelRef    模型引用 providerId/modelId
     * @param usage       Spring AI 响应携带的 usage（可为 null）
     */
    public void recordCall(UsageAttr.Attr attr, String modelRef, org.springframework.ai.chat.metadata.Usage usage) {
        if (usage == null) return;
        long prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        if (prompt <= 0 && completion <= 0) return;
        record(attr, modelRef, prompt, completion, UsageAccumulator.cachedTokensOf(usage));
    }

    /**
     * 流式调用收尾记账：把该次订阅内逐轮累加的用量落一行（多次工具轮一并包含）。
     * 中途报错也照记——钱已经花了，账不能丢。
     */
    public void recordStream(UsageAttr.Attr attr, String modelRef, UsageAccumulator acc) {
        if (acc == null || acc.isEmpty()) return;
        record(attr, modelRef, acc.promptTotal(), acc.completionTotal(), acc.cachedTotal());
    }

    private void record(UsageAttr.Attr attr, String modelRef, long prompt, long completion, long cached) {
        String uid = attr == null ? null : attr.uid();
        if (uid == null || uid.isBlank()) {
            // RequestUser 兜底：同步路径（MVC 线程）通常可见，异步池/工具线程不可见时不强行归属
            try {
                uid = RequestUser.uid();
            } catch (Exception ignored) {
                uid = null;
            }
        }
        UsageLog row = new UsageLog();
        row.setUid(uid == null || uid.isBlank() || "anonymous".equals(uid) ? null : uid);
        row.setSessionId(attr == null ? null : attr.sessionId());
        row.setMessageId(attr == null ? null : attr.messageId());
        row.setModel(modelRef);
        row.setKind(attr == null || attr.kind() == null ? "chat" : attr.kind());
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setCachedTokens(cached);
        row.setTotalTokens(prompt + completion);
        try {
            usageLogMapper.insert(row);
        } catch (Exception e) {
            // 记账失败不能拖垮问答（该花的钱已花），但必须显形——静默失败会让台账与账单对不上却无从察觉
            log.error("[UsageLedger] 用量台账写入失败（该笔用量不会进入统计）: uid={}, model={}, tokens={}, err={}",
                    uid, modelRef, prompt + completion, e.getMessage());
        }
    }
}
