package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.SkillService;
import com.wenqu.ai.util.BatchResults;
import com.wenqu.ai.util.RequestUser;
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
 * 技能（Skills）管理接口：**个人技能每人管自己的**；内置技能所有人可见、只能各自停用，管理员可改写。
 *
 * <p>技能是「一段 Markdown 做法说明」的能力包：system prompt 只注入名称与描述，
 * 模型按需用 readSkill 工具取全文。数据来源三层：
 * <ul>
 *   <li>内置：随版本分发的 {@code classpath:skills/*&#47;SKILL.md}（所有人可见、不可删，可各自停用）；</li>
 *   <li>内置改写：{@code c_ai_builtin_skill_override}，管理员改写后全局生效（仅管理员可写，见 updateBuiltin/resetBuiltin）；</li>
 *   <li>个人：当前用户名下的记录（自建或 URL 安装），与他人完全隔离。</li>
 * </ul>
 * 归属一律取 {@link RequestUser#uid()}（来自登录令牌），不接受客户端自报的用户标识。
 *
 * <p><b>为什么改写端点要方法内自查管理员</b>：{@code /api/ai/skill} 前缀在 SecurityConfig 里整段放行给登录用户
 * （个人资产语义），所以内置改写这类全局动作拿不到拦截器兜底，必须 fail-closed 自查。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/skill")
@RequiredArgsConstructor
@Tag(name = "技能（Skills）", description = "可插拔能力包：一段可复用的做法说明，按用户隔离")
public class SkillController {

    private final SkillService skillService;
    private final com.wenqu.ai.service.ConfigService configService;
    private final com.wenqu.ai.service.RoleService roleService;

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
        String uid = RequestUser.uid();
        List<Map<String, Object>> skills = new ArrayList<>();
        for (SkillService.SkillState st : skillService.listWithState(uid)) {
            SkillService.Skill s = st.skill();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            // 智能体引用串：个人技能带归属 {uid}/{name}（精确绑定，别人用不到也不该被误当成自己的同名技能）；
            // 内置技能不带归属——它随版本分发、人人都有（各人可自行停用），按名字弱匹配才是对的。
            m.put("ref", "builtin".equals(s.source()) ? s.name() : uid + "/" + s.name());
            m.put("dirName", s.dirName());
            m.put("description", s.description());
            m.put("version", s.version());
            m.put("hash", s.hash());
            m.put("source", s.source());
            m.put("size", s.size());
            m.put("overridden", s.overridden());
            m.put("disabled", st.disabled());
            skills.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", skills);
        data.put("toolsEnabled", toolsEnabled());
        // 内置改写是全局动作，界面据此只给管理员显示「编辑/恢复默认」入口（后端仍会再判一次权限）
        data.put("canOverrideBuiltin", isAdmin());
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
        m.put("overridden", s.overridden());
        m.put("disabled", st.disabled());
        m.put("content", skillService.readContent(RequestUser.uid(), s.dirName()));
        return ResultJson.ok(m);
    }

    @Operation(summary = "新建技能", description = "body: {name, description, content}；落到当前账号名下")
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
    public ResultJson setDisabled(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        skillService.setDisabled(RequestUser.uid(), name, disabled);
        return ResultJson.ok(Map.of("dirName", name, "disabled", disabled));
    }

    @Operation(summary = "删除技能", description = "仅本人技能可删；内置技能不可删（可停用）")
    @DeleteMapping("/{name}")
    public ResultJson delete(@PathVariable("name") String name) {
        skillService.delete(RequestUser.uid(), name);
        return ResultJson.ok(Map.of("dirName", name));
    }

    // --------------------------------------------------------------------------------------------------
    // 内置技能改写（仅管理员）：内容落 c_ai_builtin_skill_override，全局生效而非「管理员自己那份」
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "改写内置技能", description = "body: {content}（SKILL.md 全文，须含 frontmatter 的 name/description）。"
            + "改写后所有用户（含其他管理员）读到的都是这份内容；仅管理员。内置技能的 name 是智能体技能引用的匹配键，"
            + "改名需同步改智能体上引用它的技能名")
    @PutMapping("/{name}/builtin")
    public ResultJson updateBuiltin(@PathVariable("name") String name, @RequestBody Map<String, String> body) {
        requireAdmin();
        SkillService.Skill s = skillService.updateBuiltin(RequestUser.uid(), name, body.get("content"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dirName", s.dirName());
        data.put("name", s.name());
        data.put("description", s.description());
        data.put("version", s.version());
        data.put("hash", s.hash());
        data.put("size", s.size());
        data.put("overridden", true);
        return ResultJson.ok(data);
    }

    @Operation(summary = "恢复内置技能默认内容", description = "删除改写行，回到随版本分发的 SKILL.md；无改写时幂等成功。仅管理员")
    @DeleteMapping("/{name}/builtin")
    public ResultJson resetBuiltin(@PathVariable("name") String name) {
        requireAdmin();
        skillService.resetBuiltin(name);
        return ResultJson.ok(Map.of("dirName", name, "overridden", false));
    }

    /** 管理员级判定（含自定义 admin_flag=1 角色）；fail-closed，不用拦截器兜底 */
    private boolean isAdmin() {
        return roleService.isAdminCode(RequestUser.role());
    }

    private void requireAdmin() {
        if (!isAdmin()) throw new BizException(403, "仅管理员可改写内置技能");
    }

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——失败条目逐条带原因（结构收口在 BatchResults）
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "批量删除技能", description = "body: {ids:[...]}（技能名/dirName 清单）；逐条按单条口径"
            + "（仅本人技能可删，内置技能不可删）；返回 {succeeded:[dirName], failed:[{id,name,error}]}")
    @PostMapping("/batch-delete")
    public ResultJson batchDelete(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        if (ids.isEmpty()) return ResultJson.error("请先选择要删除的技能");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String name : ids) {
            try {
                skillService.delete(RequestUser.uid(), name);
                succeeded.add(name);
            } catch (Exception e) {
                failed.add(BatchResults.failItem(name, name, BatchResults.errMsg(e)));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    @Operation(summary = "批量启用/停用技能", description = "body: {ids:[...], disabled:true|false}（技能名/dirName 清单）；"
            + "停用后不注入清单、readSkill 拒绝读取；内置技能也可停用（按用户记停用位）；返回 {succeeded, failed}")
    @PostMapping("/batch-disabled")
    public ResultJson batchDisabled(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        boolean disabled = Boolean.TRUE.equals(body.get("disabled"));
        if (ids.isEmpty()) return ResultJson.error("请先选择要操作的技能");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String name : ids) {
            try {
                skillService.setDisabled(RequestUser.uid(), name, disabled);
                succeeded.add(name);
            } catch (Exception e) {
                failed.add(BatchResults.failItem(name, name, BatchResults.errMsg(e)));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }
}
