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
    /**
     * 智能体团队（会话级开关，不进系统设置）：成员发现、成员消息、等待消息。
     * <p>只在开启团队模式的会话/成员轮挂载；无系统级 gateKey（与 TODO 同款随总闸）。
     */
    TEAM("team", "智能体团队"),
    /**
     * Office 文档处理：格式转换 / 公式重算 / 渲染预览。
     * <p>只读写工作区或沙盒内的文件（服务端不落用户原件），属 MUTATE，只读档由档位裁剪；
     * 审批「禁用」档也整族不挂（它确实会产出/改写文件）。
     */
    OFFICE("office", "文档处理", true),
    /**
     * 桌面操作（Computer Use）：经用户本机执行器在 macOS 上列出应用/窗口、截图、点击、输入。
     * <p>与 SANDBOX/SSH 同口径：真在用户电脑上动手，审批 off 档整族不挂，只读档由档位裁剪。
     */
    COMPUTER_USE("computer_use", "桌面操作", true),
    /**
     * 历史对话检索：按关键词检索<b>当前用户自己</b>的问答记录并读取某次对话的往来。
     * <p>只读、个人资产，游客与子智能体分支都不挂（见 provider 的 exposes）。
     */
    SESSION_RETRIEVAL("session_retrieval", "历史对话检索"),
    /**
     * MCP 资源：读取外部 MCP server 暴露的 resources / resource templates（与「MCP 工具」是两条协议面）。
     * <p>出网读外部数据，属 EXTERNAL；只读档不给（与联网搜索同一口径）。
     */
    MCP_RESOURCE("mcp_resource", "MCP 资源"),
    /** 沙盒执行（隔离容器里的命令与文件读写）；审批 off 档整族不挂 */
    SANDBOX("sandbox", "沙盒执行", true),
    /**
     * SSH 远程命令（经用户本机执行器调用本机 OpenSSH 连远端）。
     * <p>与 SANDBOX 同口径：真在远端跑命令，审批 off 档整族不挂；
     * 只读档由 {@code ExecutionPolicy#allowsTool} 按族裁剪，能力面就不给。
     */
    SSH("ssh", "SSH 远程命令", true),
    /** MCP 外部工具：按用户登记动态合入，没有静态清单；审批 off 档整族不挂 */
    MCP("mcp", "MCP 外部工具", true),
    /**
     * 浏览器操作（统一族：本机 headed / 本机 headless / 平台云端三种执行位置共用同一套工具名）。
     * <p>替换原 LOCAL_BROWSER 族：模型永远只看到一套 browser_* 工具，执行位置由个人设置决定，
     * 不再有「两套浏览器给模型两种结论」的问题。审批 off 档整族不挂。
     */
    BROWSER("browser", "浏览器操作", true),
    /** 本机浏览器：已并入 BROWSER 族，保留枚举值仅为历史配置/日志兼容，不再有 provider */
    @Deprecated
    LOCAL_BROWSER("local_browser", "本机浏览器（已并入浏览器操作）", true);

    private final String code;
    private final String familyLabel;
    /**
     * 「工具执行确认=off（禁用）」时整族不挂。
     * <p>这是族级事实而不是工具级事实：
     * 沙盒与 MCP 在 off 档下整族不给（前者真在容器里跑命令、后者是外部 server 的任意工具），
     * 而联网搜索<b>刻意不受 off 管辖</b>——它启停由自身开关决定，审批档位与它无关
     * （requiresApproval=false，ask 档也不弹卡）。
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
