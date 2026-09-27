package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体公开分享配置（/s/{token} 免登录对话）
 * <p>
 * 一个智能体一条（agent_id 唯一语义由服务层保证）；token 为 URL 路径段，
 * 删除行即撤销分享。游客对话的安全模型：
 * - 身份：检索可见性与个人默认模型按 created_by（发布者）执行——发布智能体即视为
 *   授予访客"以该智能体配置问答"的能力；
 * - 能力：RagService 游客模式只暴露知识精确检索与内置工具（计算器/时间），
 *   沙盒/产物/MCP/技能执行等身份敏感能力一律不对访客暴露。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent_share")
public class AgentShare {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 被分享的智能体ID */
    private String agentId;

    /** 分享令牌（URL 路径段） */
    private String token;

    /** 游客对话模型引用（providerId/modelId；空=回退创建者个人默认模型） */
    private String modelRef;

    /** 启用: 1=可访问, 0=暂停（保留 token） */
    private Integer enabled;

    /**
     * MCP 端点：1=对外提供 /ai/mcp/{token}（Streamable HTTP；token 即凭据，与网页分享同一条记录）
     * <p>与 {@link #enabled} 独立：可以只开网页分享不开 MCP；停用网页分享（enabled=0）时 MCP 一并失效。
     */
    private Integer mcpEnabled;

    /** 创建人（游客对话以该用户身份执行检索可见性） */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
