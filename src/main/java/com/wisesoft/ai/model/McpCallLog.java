package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 服务端调用审计日志：外部客户端（Claude / Cursor / 其他 Agent）每次调用
 * wenqu 暴露的 MCP 工具落一行，覆盖两类入口——
 * <ul>
 *   <li>per-agent 端点 {@code /ai/mcp/{token}}（channel=agent，凭据=分享 token，身份=发布者）；</li>
 *   <li>平台级入口 {@code /ai/mcp}（channel=platform，凭据=API Key，身份=Key 创建者）。</li>
 * </ul>
 * <p>
 * 安全约定：凭据只存<b>脱敏指代</b>（token 前 6 位 / Key ID），不存明文——
 * token 即凭据，明文入库等于把门钥匙锁在门垫下。审计字段由
 * {@code McpServerService} 的统一埋点写入，查询入口在 {@code McpController}（仅管理员）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_mcp_call_log")
public class McpCallLog {

    /** 调用记录 ID（业务生成 UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 入口渠道: agent=智能体端点 /ai/mcp/{token}，platform=平台级 /ai/mcp */
    private String channel;

    /** 被调用的工具名（ask-{slug} / wenqu_*） */
    private String toolName;

    /** 身份归属：端点=发布者 uid，平台级=Key 创建者 uid */
    private String ownerUid;

    /** 凭据指代（脱敏）：token:前6位 / key:{id}，不存明文 */
    private String credentialRef;

    /** 平台级入口的 API Key ID（agent 渠道为空） */
    private String apiKeyId;

    /** 智能体 ID（agent 渠道必填，platform 指定时有值） */
    private String agentId;

    /** 调用者 IP（X-Forwarded-For 首段） */
    private String callerIp;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 结果: 1=成功 0=失败（isError 或抛异常） */
    private Integer success;

    /** 失败原因（截断 500 字符） */
    private String errorMsg;

    /** 调用时刻 */
    private LocalDateTime createdAt;
}
