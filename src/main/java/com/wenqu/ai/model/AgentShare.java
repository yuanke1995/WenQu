package com.wenqu.ai.model;

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
 * - 能力：ChatService 游客模式只暴露知识精确检索与内置工具（计算器/时间），
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

    /**
     * 游客能否发图：1=允许，0=关闭（默认）。
     * <p><b>为什么默认关而不是跟开</b>：游客模型由本行的 {@link #modelRef} 决定，而图片要送到模型才谈得上"看懂"——
     * 非视觉模型收到图片只会报错或被静默忽略，视觉 token 的成本却记在发布者账上。这既不是偏好也不是体验问题，
     * 是"发布者有没有明确同意"的问题，所以由发布者在发布弹窗里显式打开。
     */
    private Integer guestImagesEnabled;

    /**
     * 游客能否上传文档附件（PDF/Word/代码等）：1=允许，0=关闭（默认）。
     * <p>附件与图片走的是两条不同的上游链路（附件要 multipart 上传换 fileId、按访客隔离落盘），
     * 所以与 {@link #guestImagesEnabled} 分开两列、两个开关——一个模型可能是纯文本的，
     * 但读 PDF 恰恰是它最擅长的事，合成一个开关就会逼发布者做无谓的取舍。
     */
    private Integer guestFilesEnabled;

    /** 创建人（游客对话以该用户身份执行检索可见性） */
    private String createdBy;

    /** 游客对话累计访问次数（发布访问统计；每次成功发起一次游客对话 +1） */
    private Integer visitCount;

    /** 最近一次游客对话时间 */
    private LocalDateTime lastVisitAt;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
