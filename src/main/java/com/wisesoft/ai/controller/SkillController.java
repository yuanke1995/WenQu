package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.SkillService;
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
 * 技能（Skills）管理接口（管理员）：列表 / 详情 / 新建 / 停用 / 删除。
 *
 * <p>技能是「目录 + SKILL.md」的纯文本能力包：system prompt 只注入名称与描述，
 * 模型按需用 readSkill 工具取全文。本控制器不在用户白名单内，因此天然需要管理员身份。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/skill")
@RequiredArgsConstructor
@Tag(name = "技能（Skills）", description = "可插拔能力包：目录 + SKILL.md")
public class SkillController {

    private final SkillService skillService;

    @Operation(summary = "技能列表", description = "内置 + 用户技能（用户层同名覆盖），不含正文")
    @GetMapping("/list")
    public ResultJson list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillService.Skill s : skillService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            m.put("dirName", s.dirName());
            m.put("description", s.description());
            m.put("version", s.version());
            m.put("hash", s.hash());
            m.put("source", s.source());
            m.put("size", s.size());
            m.put("disabled", skillService.isDisabled(s));
            out.add(m);
        }
        // 带上用户技能目录：前端展示"往哪放 SKILL.md"，比在文档里写路径更好找
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dir", skillService.userDirPath());
        data.put("skills", out);
        return ResultJson.ok(data);
    }

    @Operation(summary = "技能详情", description = "含 SKILL.md 全文（查看/编辑前预览用）")
    @GetMapping("/detail")
    public ResultJson detail(@Parameter(description = "技能目录名") @RequestParam("name") String name) {
        SkillService.Skill s = skillService.find(name);
        if (s == null) return ResultJson.error("技能不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("dirName", s.dirName());
        m.put("description", s.description());
        m.put("version", s.version());
        m.put("hash", s.hash());
        m.put("source", s.source());
        m.put("size", s.size());
        m.put("disabled", skillService.isDisabled(s));
        m.put("content", skillService.readContent(s.dirName()));
        return ResultJson.ok(m);
    }

    @Operation(summary = "新建技能", description = "body: {name, description, content}；写入用户技能目录")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, String> body) {
        SkillService.Skill s = skillService.create(body.get("name"), body.get("description"), body.get("content"));
        return ResultJson.ok(Map.of("name", s.name(), "dirName", s.dirName(), "hash", s.hash()));
    }

    @Operation(summary = "从 URL 安装技能", description = "body: {url, name?}；仅接受 http/https 的 SKILL.md 原文（须含 frontmatter）")
    @PostMapping("/install")
    public ResultJson install(@RequestBody Map<String, String> body) {
        SkillService.Skill s = skillService.installFromUrl(body.get("url"), body.get("name"));
        return ResultJson.ok(Map.of("name", s.name(), "dirName", s.dirName(), "hash", s.hash()));
    }

    @Operation(summary = "启用/停用技能", description = "body: {\"disabled\": true}；停用后不注入清单、readSkill 也会拒绝")
    @PutMapping("/{name}/disabled")
    public ResultJson setDisabled(@PathVariable String name, @RequestBody Map<String, Object> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        skillService.setDisabled(name, disabled);
        return ResultJson.ok(Map.of("name", name, "disabled", disabled));
    }

    @Operation(summary = "删除技能", description = "仅用户目录下的技能可删；内置技能不可删（可建同名覆盖）")
    @DeleteMapping("/{name}")
    public ResultJson delete(@PathVariable String name) {
        skillService.delete(name);
        return ResultJson.ok(Map.of("name", name));
    }
}
