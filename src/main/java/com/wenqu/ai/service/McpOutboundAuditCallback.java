package com.wenqu.ai.service;

import com.wenqu.ai.mapper.McpCallLogMapper;
import com.wenqu.ai.model.McpCallLog;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 出站 MCP 工具调用的审计包装：模型调用用户接入的外部 MCP 工具时落一行
 * {@code c_ai_mcp_call_log}（channel=outbound）。
 * <p>
 * 为什么是 {@link SyncMcpToolCallback} 的<b>子类</b>而不是普通 delegate：
 * 工作流 mcp 节点按裸名找工具，判据是 {@code instanceof SyncMcpToolCallback}
 * 再取 {@code getOriginalToolName()}（见 {@code WorkflowEngine.originalToolName}）。
 * 换成普通包装类，所有真实工具都会「找不到」，且错误文案把人引向「服务未启用」。
 * <p>
 * 不落参数与返回正文（只落工具名/耗时/成败/错误摘要），与入站审计同口径；
 * 审计是旁路，落库失败只告警，绝不影响工具调用本身。
 *
 * @author yuanke
 */
@Slf4j
public class McpOutboundAuditCallback extends SyncMcpToolCallback {

    private final McpCallLogMapper callLogMapper;
    private final String ownerUid;
    private final String serverName;
    private final String agentId;

    public McpOutboundAuditCallback(McpSyncClient client, McpSchema.Tool tool, McpCallLogMapper callLogMapper,
                                    String ownerUid, String serverName, String agentId) {
        super(client, tool);
        this.callLogMapper = callLogMapper;
        this.ownerUid = ownerUid;
        this.serverName = serverName;
        this.agentId = agentId;
    }

    /** 基类 {@code call(String)} 会虚调用到本方法，故只覆写这一个入口即覆盖两种调用形态 */
    @Override
    public String call(String toolCallInput, ToolContext toolContext) {
        long start = System.currentTimeMillis();
        boolean success = false;
        String error = null;
        try {
            String result = super.call(toolCallInput, toolContext);
            success = true;
            return result;
        } catch (RuntimeException e) {
            // 远端 isError 与传输异常在 SDK 里都抛 ToolExecutionException
            error = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        } finally {
            record(start, success, error);
        }
    }

    private void record(long start, boolean success, String error) {
        try {
            McpCallLog row = new McpCallLog();
            row.setId(UUID.randomUUID().toString());
            row.setChannel("outbound");
            // 与模型/服务卡片看到的一致：带 server 前缀的工具名
            row.setToolName(cut(getToolDefinition().name(), 128));
            row.setOwnerUid(ownerUid);
            row.setCredentialRef(cut("mcp:" + ownerUid + "/" + serverName, 128));
            row.setAgentId(agentId == null || agentId.isBlank() ? null : cut(agentId, 64));
            row.setDurationMs(System.currentTimeMillis() - start);
            row.setSuccess(success ? 1 : 0);
            row.setErrorMsg(error == null ? null : cut(error, 500));
            row.setCreatedAt(LocalDateTime.now());
            callLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[MCP-AUDIT] 出站调用日志落库失败（不影响本次调用）: {}", e.getMessage());
        }
    }

    /** 列宽兜底：审计失败不能反过来打断调用（工具名/服务名都由外部输入决定） */
    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
