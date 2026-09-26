package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.SkillService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能（Skills）管理接口：**每人管自己的**。列表 / 详情 / 新建 / 停用 / 删除。
 *
 * <p>技能是「一段 Markdown 做法说明」的能力包：system prompt 只注入名称与描述，
 * 模型按需用 readSkill 工具取全文。数据来源两层：
 * <ul>
 *   <li>内置：随版本分发的 {@code classpath:skills/*&#47;SKILL.md}（所有人可见、不可删，可各自停用）；</li>
 *   <li>个人：当前用户名下的记录（自建或 URL 安装），与他人完全隔离。</li>
 * </ul>
 * 归属一律取 {@link RequestUser#uid()}（来自登录令牌），不接受客户端自报的用户标识。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/skill")
@RequiredArgsConstructor
@Tag(name = "技能（Skills）", description = "可插拔能力包：一段可复用的做法说明，按用户隔离")
public class SkillController {

    private final SkillService skillService;
    private final com.wisesoft.ai.service.ConfigService configService;

    /** 平台「工具调用」总开关：关闭时模型无法调用 readSkill 读正文，界面据此给出提示（技能仍能登记） */
    private boolean toolsEnabled() {
        return configService.getBoolean("tool.enabled");
    }

    @Operation(summary = "对话页可用技能", description = "当前用户未停用技能的精简列表（name/description/source），输入框「+」菜单数据源")
    @GetMapping("/available")
    public ResultJson available() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillService.SkillState st : skillService.listWithState(RequestUser.uid())) {
            if (st.disabled()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", st.skill().name());
            m.put("description", st.skill().description());
            m.put("source", st.skill().source());
            out.add(m);
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "技能列表", description = "内置（带本人是否停用）+ 本人技能，不含正文")
    @GetMapping("/list")
    public ResultJson list() {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (SkillService.SkillState st : skillService.listWithState(RequestUser.uid())) {
            SkillService.Skill s = st.skill();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("dirName", s.dirName());
            m.put("description", s.description());
            m.put("version", s.version());
            m.put("hash", s.hash());
            m.put("source", s.source());
            m.put("size", s.size());
            m.put("disabled", st.disabled());
            skills.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", skills);
        data.put("toolsEnabled", toolsEnabled());
        return ResultJson.ok(data);
    }

    @Operation(summary = "技能详情", description = "含全文（查看前预览用）")
    @GetMapping("/detail")
    public ResultJson detail(@Parameter(description = "技能标识（dirName）") @RequestParam("name") String name) {
        SkillService.SkillState st = skillService.findState(RequestUser.uid(), name);
        if (st == null) return ResultJson.error("技能不存在");
        SkillService.Skill s = st.skill();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("dirName", s.dirName());
        m.put("description", s.description());
        m.put("version", s.version());
        m.put("hash", s.hash());
        m.put("source", s.source());
        m.put("size", s.size());
        m.put("disabled", st.disabled());
        m.put("content", skillService.readContent(RequestUser.uid(), s.dirName()));
        return ResultJson.ok(m);
    }

    @Operation(summary = "新建技能", description = "body: {name, description, content}；落到当前用户名下")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, String> body) {
        SkillService.Skill s = skillService.create(RequestUser.uid(),
                body.get("name"), body.get("description"), body.get("content"));
        return ResultJson.ok(Map.of("name", s.name(), "dirName", s.dirName(), "hash", s.hash()));
    }

    @Operation(summary = "从 URL 安装技能", description = "body: {url, name?}；仅接受 http/https 的 SKILL.md 原文（须含 frontmatter）")
    @PostMapping("/install")
    public ResultJson install(@RequestBody Map<String, String> body) {
        SkillService.Skill s = skillService.installFromUrl(RequestUser.uid(), body.get("url"), body.get("name"));
        return ResultJson.ok(Map.of("name", s.name(), "dirName", s.dirName(), "hash", s.hash()));
    }

    @Operation(summary = "启用/停用技能", description = "body: {\"disabled\": true}；停用后不注入清单、readSkill 也会拒绝")
    @PutMapping("/{name}/disabled")
    public ResultJson setDisabled(@PathVariable String name, @RequestBody Map<String, Object> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        skillService.setDisabled(RequestUser.uid(), name, disabled);
        return ResultJson.ok(Map.of("dirName", name, "disabled", disabled));
    }

    @Operation(summary = "删除技能", description = "仅本人技能可删；内置技能不可删（可停用）")
    @DeleteMapping("/{name}")
    public ResultJson delete(@PathVariable String name) {
        skillService.delete(RequestUser.uid(), name);
        return ResultJson.ok(Map.of("dirName", name));
    }
}
