package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.util.RequestUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能只读清单与正文查看。
 * <p><b>为什么没有增删改</b>：技能内容的写入已随「全框架化 P3」交给框架的 {@code skill_manage}
 * 工具——助手在对话里把做过的做法沉淀成技能，落在沙盒工作区。平台不再提供「技能面板 / URL 安装 /
 * 停用开关」，但<b>写入方换成了模型，读的一侧就必须更可审</b>：技能正文是逐字拼进本轮提示词的，
 * 用户看不到正文等于看不到自己被注入了什么。所以这里给两个只读端点：
 * <ul>
 *   <li>{@code /available}：有哪些、各自管什么、从哪来（内置 / 我的 / 工作区）、哪条已被停用；</li>
 *   <li>{@code /content}：某一条的 SKILL.md 全文，与模型本轮吃到的那份同源。</li>
 * </ul>
 * <p>数据源与模型实际能看到的三份同源（{@link WenquSkillRepository} 按当轮 uid 解析）。
 * 「工作区」那一路只在点名会话且该会话沙盒在场时才读——看一眼清单不会把容器拉起来。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/skill")
@RequiredArgsConstructor
@Tag(name = "技能（只读）", description = "本轮可选技能清单与正文查看；管理动作由 AgentScope 框架技能工具承担")
public class SkillQueryController {

    private final WenquSkillRepository skills;

    @Operation(summary = "可用技能清单",
            description = "内置 + 本人技能目录（+ 指定会话的沙盒工作区技能）；不含正文，同名只给生效那条，"
                    + "登录用户只看自己的归属")
    @GetMapping("/available")
    public ResultJson available(@RequestParam(value = "sessionId", required = false) String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WenquSkillRepository.SkillEntry e : entries(sessionId)) {
            out.add(viewOf(e, false));
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "技能正文",
            description = "按名取 SKILL.md 全文（含已停用的那条，停用不影响查看）；只给当前用户可见的那份")
    @GetMapping("/content")
    public ResultJson content(@RequestParam("name") String name,
                              @RequestParam(value = "sessionId", required = false) String sessionId) {
        if (name == null || name.isBlank()) return ResultJson.error("缺少技能名");
        WenquSkillRepository.SkillEntry hit = null;
        for (WenquSkillRepository.SkillEntry e : entries(sessionId)) {
            if (name.trim().equals(e.skill().getName())) {
                hit = e;
                break;
            }
        }
        if (hit == null) return ResultJson.error("没有这条技能，或它当前对你不可见");
        Map<String, Object> view = viewOf(hit, true);
        return ResultJson.ok(view);
    }

    /** 按当前登录身份解析本轮可见清单；带会话时才并上沙盒工作区那一路。 */
    private List<WenquSkillRepository.SkillEntry> entries(String sessionId) {
        String uid = RequestUser.uid();
        RuntimeContext ctx = RuntimeContext.builder().userId(uid).sessionId("skill-list").build();
        List<WenquSkillRepository.SkillEntry> all = skills.catalog(ctx, sessionId);
        return all == null ? List.of() : all;
    }

    /**
     * 出参视图。
     *
     * @param withContent 是否带上正文（清单不带，避免一次拉出几十 KB）
     */
    private Map<String, Object> viewOf(WenquSkillRepository.SkillEntry e, boolean withContent) {
        String uid = RequestUser.uid();
        AgentSkill s = e.skill();
        boolean mine = WenquSkillRepository.SOURCE_USER.equals(e.source());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        // 引用串：本人技能带 {uid}/{name} 精确绑定归属（别人同名但内容不同的那条不该被张冠李戴），
        // 内置与工作区技能不带归属——前者随版本分发、后者随会话/工作区走
        m.put("ref", mine ? uid + "/" + s.getName() : s.getName());
        m.put("description", s.getDescription());
        m.put("source", e.source());
        m.put("disabled", e.disabled());
        // 平台层也停了这条：个人这边改不动，界面不许给一个按下去没反应的「恢复使用」
        m.put("disabledByPlatform", e.global());
        // 工作区那一路只给看、不给点名：它随会话走，把它钉进智能体配置会存进一个
        // 换个会话就解析不出来的名字
        m.put("selectable", !WenquSkillRepository.SOURCE_WORKSPACE.equals(e.source()));
        if (withContent) {
            m.put("content", s.getSkillContent());
        }
        return m;
    }
}
