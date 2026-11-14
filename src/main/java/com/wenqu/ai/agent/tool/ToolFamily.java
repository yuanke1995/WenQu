package com.wenqu.ai.agent.tool;

/**
 * 工具族：工具归属的第一层。
 * <p>
 * 为什么要有它：一个工具的「挂不挂、要不要审批、游客能不能用、开关在哪、卡片叫什么」
 * 若散在问答侧的 if 链、{@code ToolInventoryService.LABELS}、硬编码的 sensitive 集合与前端映射里，
 * 同一族工具的属性就会被抄几份，改一处漏三处。
 * 族是收口的单位：每个 {@link ToolFamilyProvider} 一次把本族全部 {@link ToolDescriptor} 声明清楚，
 * 其余地方一律来查它。
 * <p>
 * 刻意用枚举而不是自由字符串：族名同时是配置前缀口径（{@code tool.*} / {@code webSearch.*}）
 * 与提示词能力段的分组依据，写错族名不该静默变成「又冒出一个新族」。
 *
 * @author yuanke
 */
public enum ToolFamily {

    /** 提问工具：只剩 askUser（人在回路） */
    ASK_USER("ask_user", "提问工具"),
    /** 知识库精确检索 */
    KNOWLEDGE("knowledge", "知识库精确检索"),
    /** 联网搜索（Provider 壳，外部计费与配额都在这族） */
    WEBSEARCH("websearch", "联网搜索"),
    /**
     * HTTP 工具（OpenAPI 导入的宿主 API 工具集，按智能体 {@code http_tools} 配置动态合入）。
     * <p>与 WEBSEARCH 同口径：<b>刻意不受审批 off 档管辖</b>——嵌入场景要的正是无人值守
     * （审批=off 时照常自动调用）；ask 档仍会逐次问（动态工具名未登记注册中心，
     * {@code ToolRegistry.requiresApproval} fail-closed 返回 true，与 MCP 同一兜底）。
     */
    HTTP_API("http_api", "HTTP 工具"),
    /**
     * 工作流工具（已发布工作流按智能体 {@code workflow_tools} 配置动态合入）。
     * <p>与 HTTP_API 同口径：<b>刻意不受审批 off 档管辖</b>——工作流当工具的典型场景正是嵌入方
     * 无人值守地调一条确定性流程；ask 档仍会逐次问（动态工具名未登记注册中心，
     * {@code ToolRegistry.requiresApproval} fail-closed 返回 true，与 MCP / HTTP 同一兜底）。
     */
    WORKFLOW("workflow", "工作流"),
    /** 产物交付（模型生成的可下载文件） */
    ARTIFACT("artifact", "产物交付"),
    /** 技能（Skills 渐进披露的取回端，个人资产）；智能体级开关 tool_skill 控制它开不开 */
    SKILL("skill", "技能（Skills）"),
    /** 任务清单（writeTodo） */
    TODO("todo", "任务清单"),
    /** 沙盒执行（隔离容器里的命令与文件读写）；审批 off 档整族不挂 */
    SANDBOX("sandbox", "沙盒执行", true),
    /** MCP 外部工具：按用户登记动态合入，没有静态清单；审批 off 档整族不挂 */
    MCP("mcp", "MCP 外部工具", true),
    /** 本机浏览器：经本机执行器驱动用户电脑上的浏览器（仅执行器在线时挂载）；审批 off 档整族不挂 */
    LOCAL_BROWSER("local_browser", "本机浏览器", true);

    private final String code;
    private final String familyLabel;
    /**
     * 「工具执行确认=off（禁用）」时整族不挂。
     * <p>这是族级事实而不是工具级事实：
     * 沙盒与 MCP 在 off 档下整族不给（前者真在容器里跑命令、后者是外部 server 的任意工具），
     * 而联网搜索<b>刻意不受 off 管辖</b>——它启停由自身开关决定；纳入执行审批是恒定事实
     * （2026-11-14 设置项退役，原默认开），off 档不会把联网整个关掉，只有 ask 档逐项确认。
     */
    private final boolean blockedWhenApprovalOff;

    ToolFamily(String code, String familyLabel, boolean blockedWhenApprovalOff) {
        this.code = code;
        this.familyLabel = familyLabel;
        this.blockedWhenApprovalOff = blockedWhenApprovalOff;
    }

    ToolFamily(String code, String familyLabel) {
        this(code, familyLabel, false);
    }

    public String code() {
        return code;
    }

    public String familyLabel() {
        return familyLabel;
    }

    public boolean blockedWhenApprovalOff() {
        return blockedWhenApprovalOff;
    }

    /** MCP 工具名由外部 server 自定，按名归属族时的兜底判定（前缀由 McpClientService 生成） */
    public static boolean looksLikeMcpTool(String name) {
        return name != null && name.startsWith("wen_qu");
    }
}
