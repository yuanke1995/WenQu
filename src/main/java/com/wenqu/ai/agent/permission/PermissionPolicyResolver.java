package com.wenqu.ai.agent.permission;

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
    /** 平台上限档位（智能体选得比它高就被钳下来） */
    public static final String KEY_MAX = "agent.permissionPreset.max";

    private final ConfigService configService;

    public PermissionPolicyResolver(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 本轮生效策略。
     *
     * @param agentPresetCode   智能体存的档位码（null/空=未设置，跟随平台默认）
     * @param agentApprovalMode 智能体的工具执行确认设置（auto/ask/off；空白=未设置，用档位的建议值）
     * @param guestMode         免登录分享会话
     * @param onClamp           档位被平台上限钳制时的上报出口（问答侧传本轮降级列表；不需要就传 no-op）
     */
    public ExecutionPolicy resolve(String agentPresetCode, String agentApprovalMode, boolean guestMode,
                                   BiConsumer<String, String> onClamp) {
        PermissionPreset preset = requestedPreset(agentPresetCode);
        PermissionPreset ceiling = ceilingPreset();
        if (guestMode) {
            // 游客会话强制只读档。今天游客拿不到沙盒/MCP 靠的是各族描述符的 guestAllowed 与 provider 自查，
            // 那是「谁能看到什么」的可见性闸；本行管的是「这一轮允许干什么」的能力上限闸。两道闸都要成立：
            // 少了这条，将来任何一处按档位放宽的改动都会顺带把游客会话一起放开。
            preset = PermissionPreset.RESEARCH;
        } else if (preset.rank() > ceiling.rank()) {
            PermissionPreset clamped = ceiling;
            BiConsumer<String, String> sink = onClamp == null ? (c, m) -> { } : onClamp;
            log.info("[PERM] 档位被平台上限钳制: 请求={} 上限={} 生效={}",
                    preset.code(), clamped.code(), clamped.code());
            sink.accept("PERMISSION_PRESET_CLAMPED",
                    "该智能体的权限档位「" + preset.label() + "」高于平台上限「" + clamped.label()
                            + "」，本轮按上限执行（沙盒" + clamped.sandboxMode().label() + "）");
            preset = clamped;
        }
        return ExecutionPolicy.of(preset, agentApprovalMode, configService.get("sandbox.virtualPathPrefix"));
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
    private PermissionPreset ceilingPreset() {
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
     * <p>被上限禁掉的档位<b>照样返回</b>并带 {@code available=false} 与原因：配置页上「这一档管理员没开」
     * 与「这一档不存在」对用户是两件事，藏掉前者会让人以为平台没这能力。
     */
    public List<Map<String, Object>> catalog() {
        PermissionPreset ceiling = ceilingPreset();
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
            m.put("unavailableReason", available ? null : "平台上限为「" + ceiling.label() + "」，本档需管理员放开");
            out.add(m);
        }
        return out;
    }
}
