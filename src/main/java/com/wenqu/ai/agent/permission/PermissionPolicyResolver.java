package com.wenqu.ai.agent.permission;

// [保留] 问渠权限与沙箱模型 —— 框架引擎(AgentxPermissions/WenquAgentxTools)与共享服务仍依赖，勿删。

import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限档位解析：把「智能体存的档位 + 智能体自己的审批设置 + 是否游客」算成本轮那份
 * {@link ExecutionPolicy}。
 * <p>
 * 归属沿用仓库既有的<b>配置四档</b>口径：
 * <ul>
 *   <li>平台全局——默认档固定 {@code standard}（智能体没选档时用它；平台默认档设置项已退役）；</li>
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

    private final ConfigService configService;

    public PermissionPolicyResolver(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 本轮生效策略。
     *
     * @param agentPresetCode   智能体存的档位码（null/空=未设置，跟随平台默认 standard）
     * @param agentApprovalMode 智能体的工具执行确认设置（auto/ask/off；空白=未设置，用档位的建议值）
     * @param guestMode         免登录分享会话
     * @param agentToolDeny     智能体的按名工具黑名单（逗号串；null/空=不禁用任何具体工具）
     */
    public ExecutionPolicy resolve(String agentPresetCode, String agentApprovalMode, boolean guestMode,
                                   String agentToolDeny) {
        return resolve(null, agentPresetCode, agentApprovalMode, guestMode, agentToolDeny);
    }

    /**
     * 带会话级覆盖的解析：会话选的档位优先于智能体（DSH 的 {@code permission/preset} 是会话事件），
     * 仍受游客只读闸约束。
     *
     * @param sessionPresetCode 会话级档位码（null/空=没选过，跟随智能体；认不出时回落智能体并报警）
     */
    public ExecutionPolicy resolve(String sessionPresetCode, String agentPresetCode, String agentApprovalMode,
                                   boolean guestMode, String agentToolDeny) {
        return resolve(sessionPresetCode, agentPresetCode, agentApprovalMode, null, guestMode, agentToolDeny);
    }

    /** 带父轮审批档位继承的版本（分支轮用）：继承值仅在智能体未显式设置时生效，见 {@link ExecutionPolicy#of}。 */
    public ExecutionPolicy resolve(String sessionPresetCode, String agentPresetCode, String agentApprovalMode,
                                   String inheritedApprovalMode, boolean guestMode, String agentToolDeny) {
        PermissionPreset preset = requestedPreset(agentPresetCode);
        if (sessionPresetCode != null && !sessionPresetCode.isBlank()) {
            PermissionPreset sessionPreset = PermissionPreset.fromCode(sessionPresetCode);
            if (sessionPreset != null) {
                preset = sessionPreset;
            } else {
                log.error("[PERM] 会话级权限档位无法识别（按智能体档位执行，请修正库里的值）: code={}", sessionPresetCode);
            }
        }
        if (guestMode) {
            // 游客会话强制只读档。今天游客拿不到沙盒/MCP 靠的是各族描述符的 guestAllowed 与 provider 自查，
            // 那是「谁能看到什么」的可见性闸；本行管的是「这一轮允许干什么」的能力上限闸。两道闸都要成立：
            // 少了这条，将来任何一处按档位放宽的改动都会顺带把游客会话一起放开。
            preset = PermissionPreset.RESEARCH;
        }
        return ExecutionPolicy.of(preset, agentApprovalMode, inheritedApprovalMode,
                configService.get("sandbox.virtualPathPrefix"),
                agentToolDeny);
    }

    /** 智能体请求的档位：未设置→平台默认 standard；写了认不出的值→回落平台默认并报警（绝不回落到最高档） */
    private PermissionPreset requestedPreset(String code) {
        if (code == null || code.isBlank()) return PermissionPreset.STANDARD;
        PermissionPreset p = PermissionPreset.fromCode(code);
        if (p == null) {
            log.error("[PERM] 智能体权限档位无法识别（按平台默认档 standard 执行，请修正配置）: code={}", code);
            return PermissionPreset.STANDARD;
        }
        return p;
    }

    /**
     * 档位名册（智能体配置页的下拉就从这里来）。
     * <p>手工组装 Map：本工程用 fastjson 序列化，它不认 record（{@code JSON.toJSONString(record)}
     * 得到 {@code {}}），任何要出参的对象都得走 Map。
     * <p>{@code platformDefault=true} 标出智能体未选档时实际生效的那一档（固定 standard）：
     * 配置页的「跟随平台默认」要点名它是什么档。
     */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PermissionPreset p : PermissionPreset.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.code());
            m.put("label", p.label());
            m.put("summary", p.summary());
            m.put("sandboxMode", p.sandboxMode().code());
            m.put("sandboxLabel", p.sandboxMode().label());
            m.put("approvalDefault", p.approvalDefault());
            m.put("approvalFollowsAgent", p.approvalDefault() == null);
            m.put("platformDefault", p == PermissionPreset.STANDARD);
            out.add(m);
        }
        return out;
    }
}
