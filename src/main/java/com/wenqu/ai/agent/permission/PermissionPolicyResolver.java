package com.wenqu.ai.agent.permission;

// [保留] 问渠权限与沙箱模型 —— 框架引擎(AgentxPermissions/WenquAgentxTools)与共享服务仍依赖，勿删。

import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 权限档位解析：把「智能体存的档位 + 智能体自己的审批设置 + 平台默认与上限 + 是否游客」算成本轮那份
 * {@link ExecutionPolicy}。
 * <p>
 * 归属沿用仓库既有的<b>配置四档</b>口径：
 * <ul>
 *   <li>平台全局——{@code agent.permissionPreset.default}（没设过档位的智能体用哪一档）与
 *       {@code agent.permissionPreset.max}（管理员允许的最高档，即「收紧后模型连这个能力都看不到」那条）；</li>
 *   <li>资源级绑定——{@code c_ai_agent.permission_preset}（每个智能体自己选）；</li>
 *   <li>审批旋钮不新建载体，继续用 {@code c_ai_agent.tool_approval_mode}（见 {@link PermissionPreset#approvalDefault()}）。</li>
 * </ul>
 * 刻意<b>不做</b>的事：不建第二套授权体系。角色/菜单/接口权限在 {@code RoleService}，资源可见性在
 * {@code ResourceVisibilityService}，本类只管「这一轮允许模型干什么」，两者不同层——一个人能不能改
 * 某个智能体的档位，与改完之后档位拦不拦得住工具，是两条独立的判定。
 * <p>
 * 解析失败的方向选择（每一处都 fail-loud）：认不出的档位码一律回落到<b>更低</b>的一侧，
 * 绝不因为「配置项写错了」就把会话升到全自主。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class PermissionPolicyResolver {

    /** 平台默认档位（智能体未选档时用它） */
    public static final String KEY_DEFAULT = "agent.permissionPreset.default";
    /** 平台上限档位（管理员收紧处；智能体选得比它高就被钳下来） */
    public static final String KEY_MAX = "agent.permissionPreset.max";
    /**
     * 平台级工具黑名单（逗号分隔工具名）：管理员一次性停掉某一个工具用的。
     * <p>为什么不是族级开关就够：{@code tool.websearch.enabled} 那一档关的是整族，
     * 想停掉其中一个工具会连带关掉同族其余能力；而智能体级黑名单又要把每个智能体都改一遍。
     * <p>刻意<b>不</b>做成个人可覆盖：个人能放宽自己的黑名单，就等于绕过管理员的停用。
     */
    public static final String KEY_DENY = "tool.deny";

    private final ConfigService configService;
    /** 工具注册中心：仅用于启动期核对平台停用名单里的名字存在（见 {@link #checkPlatformDeny()}） */
    private final com.wenqu.ai.agent.tool.ToolRegistry toolRegistry;

    public PermissionPolicyResolver(ConfigService configService,
                                    com.wenqu.ai.agent.tool.ToolRegistry toolRegistry) {
        this.configService = configService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 本轮生效策略。
     *
     * @param agentPresetCode   智能体存的档位码（null/空=未设置，跟随平台默认）
     * @param agentApprovalMode 智能体的工具执行确认设置（auto/ask/off；空白=未设置，用档位的建议值）
     * @param guestMode         免登录分享会话
     * @param agentToolDeny     智能体的按名工具黑名单（逗号串；null/空=不禁用任何具体工具）
     * @param onClamp           档位被上限钳制时的上报出口（问答侧传本轮降级列表；不需要就传 no-op）
     */
    public ExecutionPolicy resolve(String agentPresetCode, String agentApprovalMode, boolean guestMode,
                                   String agentToolDeny, BiConsumer<String, String> onClamp) {
        PermissionPreset preset = requestedPreset(agentPresetCode);
        PermissionPreset ceiling = platformCeiling();
        if (guestMode) {
            // 游客会话强制只读档。今天游客拿不到沙盒/MCP 靠的是各族描述符的 guestAllowed 与 provider 自查，
            // 那是「谁能看到什么」的可见性闸；本行管的是「这一轮允许干什么」的能力上限闸。两道闸都要成立：
            // 少了这条，将来任何一处按档位放宽的改动都会顺带把游客会话一起放开。
            preset = PermissionPreset.RESEARCH;
        } else if (preset.rank() > ceiling.rank()) {
            BiConsumer<String, String> sink = onClamp == null ? (c, m) -> { } : onClamp;
            log.info("[PERM] 档位被平台上限钳制: 请求={} 平台上限={} 生效={}",
                    preset.code(), ceiling.code(), ceiling.code());
            sink.accept("PERMISSION_PRESET_CLAMPED",
                    "该智能体的权限档位「" + preset.label() + "」高于平台上限「" + ceiling.label()
                            + "」，本轮按上限执行（沙盒" + ceiling.sandboxMode().label() + "）");
            preset = ceiling;
        }
        return ExecutionPolicy.of(preset, agentApprovalMode,
                configService.get("sandbox.virtualPathPrefix"),
                // 平台停用名单与智能体自己的黑名单取并集：前者是"这个工具平台先停一停"，
                // 后者是"这个智能体不用它"，两条互不覆盖——成员不能靠删自己那条把平台停用的召回来
                mergeDeny(configService.get(KEY_DENY), agentToolDeny));
    }

    /** 两份逗号名单取并集（保序去重：平台在前，便于日志里看出哪条是平台停的） */
    static String mergeDeny(String platformDeny, String agentDeny) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String csv : new String[]{platformDeny, agentDeny}) {
            if (csv == null) continue;
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return String.join(",", out);
    }

    /**
     * 启动期核对平台停用名单里的名字真存在。
     * <p>设置页那一项是自由文本，写错一个字母的表现是"我明明停了它，模型还在调"——没有任何报错。
     * 智能体级黑名单在保存时就拒（{@code AgentService.toEntity}），平台级没有那个入口，所以在启动补一道。
     * 只喊不拦：一个笔误不该让整个平台起不来。
     */
    @jakarta.annotation.PostConstruct
    void checkPlatformDeny() {
        String csv = configService.get(KEY_DENY);
        if (csv == null || csv.isBlank()) return;
        List<String> unknown = new ArrayList<>();
        for (String n : csv.split(",")) {
            String t = n.trim();
            if (t.isEmpty()) continue;
            if (toolRegistry.find(t).isEmpty() && !com.wenqu.ai.agent.tool.ToolFamily.looksLikeMcpTool(t)) {
                unknown.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            log.error("[PERM] 配置项 {} 里有平台不认识的工具名（这些停用动作不会生效，请改正）: {}",
                    KEY_DENY, String.join("、", unknown));
        }
    }

    /** 智能体请求的档位：未设置→平台默认；写了认不出的值→回落平台默认并报警（绝不回落到最高档） */
    private PermissionPreset requestedPreset(String code) {
        if (code == null || code.isBlank()) return defaultPreset();
        PermissionPreset p = PermissionPreset.fromCode(code);
        if (p == null) {
            log.error("[PERM] 智能体权限档位无法识别（按平台默认档执行，请修正配置）: code={} 默认={}",
                    code, defaultPreset().code());
            return defaultPreset();
        }
        return p;
    }

    private PermissionPreset defaultPreset() {
        String code = configService.get(KEY_DEFAULT);
        PermissionPreset p = PermissionPreset.fromCode(code);
        if (p != null) return p;
        if (code != null && !code.isBlank()) {
            log.error("[PERM] 配置项 {} 的值无法识别（回落 standard）: {}", KEY_DEFAULT, code);
        }
        return PermissionPreset.STANDARD;
    }

    /**
     * 平台上限。认不出时取<b>平台默认档</b>而不是「不设上限」：这一项是管理员用来收紧的，
     * 一个笔误把它读成「放开到全自主」等于把收紧动作反转为放权。
     */
    private PermissionPreset platformCeiling() {
        String code = configService.get(KEY_MAX);
        PermissionPreset p = PermissionPreset.fromCode(code);
        if (p != null) return p;
        if (code != null && !code.isBlank()) {
            log.error("[PERM] 配置项 {} 的值无法识别（按平台默认档 {} 收口，不放开）: {}",
                    KEY_MAX, defaultPreset().code(), code);
            return defaultPreset();
        }
        return PermissionPreset.AUTONOMOUS;
    }

    /**
     * 档位名册（智能体配置页的下拉就从这里来）。
     * <p>手工组装 Map：本工程用 fastjson 序列化，它不认 record（{@code JSON.toJSONString(record)}
     * 得到 {@code {}}），任何要出参的对象都得走 Map。
     * <p>被上限禁掉的档位<b>照样返回</b>并带 {@code available=false} 与原因：配置页上「这一档没开」
     * 与「平台没这档」对用户是两件事，藏掉前者会让人以为平台没这能力。
     */
    public List<Map<String, Object>> catalog() {
        PermissionPreset ceiling = platformCeiling();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PermissionPreset p : PermissionPreset.values()) {
            boolean available = p.rank() <= ceiling.rank();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.code());
            m.put("label", p.label());
            m.put("summary", p.summary());
            m.put("sandboxMode", p.sandboxMode().code());
            m.put("sandboxLabel", p.sandboxMode().label());
            m.put("approvalDefault", p.approvalDefault());
            m.put("approvalFollowsAgent", p.approvalDefault() == null);
            m.put("available", available);
            m.put("unavailableReason", available
                    ? null : "平台上限为「" + ceiling.label() + "」，本档不可选（需管理员放开）");
            out.add(m);
        }
        return out;
    }
}
