package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 调用审计日志：每次工具执行落一行，覆盖两个方向——
 * <ul>
 *   <li><b>入站</b>：外部客户端（Claude / Cursor / 其他 Agent）调 wenqu 暴露的工具
 *       —— per-agent 端点 {@code /ai/mcp/{token}}（channel=agent，凭据=分享 token，身份=发布者）；
 *       平台级入口 {@code /ai/mcp}（channel=platform，凭据=API Key，身份=Key 创建者）；</li>
 *   <li><b>出站</b>：模型调用用户自己接入的外部 MCP 工具（channel=outbound，
 *       凭据指代=服务名，身份=服务归属人，智能体来自问答装配；工作流节点为空）。</li>
 * </ul>
 * <p>
 * 安全约定：凭据只存<b>脱敏指代</b>（token 前 6 位 / Key ID / {@code mcp:uid/服务名}），不存明文；
 * 出站不落参数与返回正文。查询入口在 {@code McpController}（仅管理员）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_mcp_call_log")
public class McpCallLog {

    /** 调用记录 ID（业务生成 UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 渠道: agent=智能体端点 /ai/mcp/{token}，platform=平台级 /ai/mcp，outbound=平台调用外部工具 */
    private String channel;

    /** 被调用的工具名（入站 ask-{slug} / wenqu_*；出站为带 server 前缀的外部工具名） */
    private String toolName;

    /** 身份归属：入站=端点发布者/Key 创建者 uid；出站=服务归属人 uid */
    private String ownerUid;

    /** 凭据指代（脱敏）：token:前6位 / key:{id} / mcp:{uid}/{服务名}，不存明文 */
    private String credentialRef;

    /** 平台级入口的 API Key ID（agent / outbound 渠道为空） */
    private String apiKeyId;

    /** 智能体 ID（入站 agent 渠道必填；出站问答装配时有值，工作流节点为空） */
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
