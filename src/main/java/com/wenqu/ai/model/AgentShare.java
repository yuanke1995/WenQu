package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 游客对话模型引用（providerId/modelId；空=回退创建者个人默认模型）。
     * <p>{@code updateStrategy=ALWAYS}：切回「跟随发布者个人默认模型」是把这一列清空，
     * 而 updateById 默认忽略 null 字段——不加它，弹窗里选「跟随」会保存成功、界面也显示成「跟随」，
     * 库里却仍留着上一个模型，重开弹窗它就又回来了，游客也照旧用被换掉的那个模型作答。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
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

    /**
     * 游客请求能否携带调用变量（variables）：1=允许注入本轮上下文，0=关闭（默认，fail-closed）。
     * <p>变量由<b>宿主页面的 JS</b> 传入（不是匿名访客手打的），注入形态是「调用方上下文」数据段
     * （{@code UntrustedContent} 信封消毒）。默认关与 guestImages/guestFiles 同口径：发布者没显式
     * 同意的能力不该默认生效——变量直进模型上下文，等于给了宿主页面向提示词塞上下文的通道。
     */
    private Integer variablesEnabled;

    /**
     * 游客可用的 MCP server 名单（逗号分隔的 server 名，空/NULL=全部关闭，默认关）。
     * <p>为什么是名单而不是布尔：MCP server 是发布者的<b>个人资产</b>，一个发布者名下往往
     * 挂着内网数据库查询这类绝不给匿名访客碰的服务——一刀切的「游客可用 MCP」等于把
     * 全部个人 server 都亮给访客。按 server 粒度勾选，发布者只亮特定用途的那几个
     * （如表单设计助手勾「动态表单服务」），其余照旧不可见。
     * <p>名单里的名字必须是发布者<b>自己登记且启用</b>的 server（发布时按 uid 校验过滤）；
     * 游客轮的 MCP 连接池本来就按发布者 uid 解析，这里只是可见性的收窄，不产生新凭据。
     * 发布者后续删掉某 server 后，名单里的残留名在装配期自然解析不到，等于该项静默失效。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String guestMcpServers;

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
