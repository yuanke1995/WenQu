package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.skill.UserSkillStore;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.util.RequestUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.wenqu.ai.security.Permission;

/**
 * 技能（Skills）：清单 / 正文审阅 / 用户自助安装与管理。
 *
 * <h3>为什么既有只读端点，也有写端点</h3>
 * 写入不能只交给模型的 {@code skill_manage} 工具：那样写的技能只在当前会话可见，
 * 用户也无法自己安装。这里提供用户侧写入口，
 * 但落点换成 {@link UserSkillStore}（{@code user-data/skills-users/{uid}/}）：
 * <ul>
 *   <li>跨会话、跨工作区、不依赖沙箱；</li>
 *   <li>三种安装方式：粘贴 SKILL.md / 上传 zip / 从 URL 安装；</li>
 *   <li>支持编辑、删除、个人停用（内置技能只能停用，不能覆盖）。</li>
 * </ul>
 *
 * <h3>归属边界</h3>
 * 所有读写都取登录态 {@link RequestUser#uid()}，看不到也改不了别人的技能；
 * 端点本身在 {@code SecurityConfig} 的个人资产白名单里（登录即可用），因此<b>资源级判定必须在这里做</b>。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/skill")
@RequiredArgsConstructor
@Tag(name = "技能（Skills）", description = "可插拔能力包：内置 + 用户自装 + 本会话工作区，按用户隔离")
public class SkillController {

    private final WenquSkillRepository skills;
    private final UserSkillStore store;
    private final com.wenqu.ai.skill.SkillStatsStore stats;
    private final com.wenqu.ai.service.SessionService sessionService;

    // ==================== 读 ====================

    @Operation(summary = "可用技能清单",
            description = "内置 + 用户自装（+ 指定会话的沙盒工作区技能）；不含正文，同名只给生效那条")
    @Permission("skill:read")
    @GetMapping("/available")
    public ResultJson available(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String uid = uid();
        RuntimeContext ctx = ctx(uid);
        // 对话选用只关心 name/description/ref/selectable：不查 store.list（省一次全目录解析）
        List<Map<String, Object>> out = new ArrayList<>();
        for (WenquSkillRepository.SkillEntry e : skills.catalog(ctx, sessionId)) {
            out.add(view(uid, e, null, false));
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "技能管理清单",
            description = "管理页数据源：来源/停用（本人）/可否编辑删除/体积/文件数/分类/使用统计，"
                    + "附带安装限制与页头统计条汇总")
    @Permission("skill:read")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String uid = uid();
        RuntimeContext ctx = ctx(uid);
        Map<String, UserSkillStore.InstalledSkill> mine = mine(uid);
        Map<String, Map<String, Object>> statsMap = stats.summary(uid);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WenquSkillRepository.SkillEntry e : skills.catalog(ctx, sessionId)) {
            rows.add(view(uid, e, metaOf(mine, e), false, statsMap));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", rows);
        data.put("limits", store.limits());
        data.put("summary", summarizeRows(rows));
        return ResultJson.ok(data);
    }

    /** 页头统计条：总数/生效中/30 天总读取/零调用/缺描述——每项对应列表页一个可点筛选。 */
    private static Map<String, Object> summarizeRows(List<Map<String, Object>> rows) {
        int active = 0, reads30dTotal = 0, zeroCall = 0, missingDesc = 0;
        for (Map<String, Object> r : rows) {
            boolean disabled = Boolean.TRUE.equals(r.get("disabled"));
            if (!disabled) {
                active++;
                Object rd = r.get("reads30d");
                int n = rd instanceof Number nb ? nb.intValue() : 0;
                reads30dTotal += n;
                if (n == 0) zeroCall++;
            }
            Object desc = r.get("description");
            if (desc == null || String.valueOf(desc).isBlank()) missingDesc++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", rows.size());
        m.put("active", active);
        m.put("reads30dTotal", reads30dTotal);
        m.put("zeroCall", zeroCall);
        m.put("missingDesc", missingDesc);
        return m;
    }

    @Operation(summary = "技能使用统计",
            description = "单条技能 30 天序列 + 最近触发会话（带标题反查）；抽屉「使用数据」Tab 数据源")
    @Permission("skill:read")
    @GetMapping("/stats")
    public ResultJson statsEndpoint(@RequestParam("name") String name) {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireEntry(uid, null, name);
        Map<String, Object> d = new LinkedHashMap<>(stats.detail(uid, e.skill().getName()));
        // 最近触发补会话标题：查不到的（已删会话）只留 sessionId，前端降级显示
        Object recent = d.get("recent");
        if (recent instanceof List<?> rl) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : rl) {
                if (!(o instanceof Map<?, ?> r)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", r.get("sessionId"));
                item.put("at", r.get("at"));
                String sid = r.get("sessionId") == null ? "" : String.valueOf(r.get("sessionId"));
                String title = "";
                if (!sid.isBlank()) {
                    try { title = sessionService.titleOf(sid); } catch (RuntimeException ignored) {}
                }
                item.put("title", title == null ? "" : title);
                out.add(item);
            }
            d.put("recent", out);
        }
        return ResultJson.ok(d);
    }

    @Operation(summary = "技能详情",
            description = "元信息 + 正文（模型看到的同源正文）+ raw（用户自装技能的 SKILL.md 原文，编辑用）+ 文件清单")
    @Permission("skill:read")
    @GetMapping("/detail")
    public ResultJson detail(@RequestParam("name") String name,
                             @RequestParam(value = "sessionId", required = false) String sessionId) {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireEntry(uid, sessionId, name);
        UserSkillStore.InstalledSkill meta = metaOf(mine(uid), e);
        Map<String, Object> m = view(uid, e, meta, true);
        m.put("raw", e.skill().getSkillContent());
        List<Map<String, Object>> files = new ArrayList<>();
        if (WenquSkillRepository.SOURCE_USER.equals(e.source())) {
            try {
                UserSkillStore.SkillDetail d = store.detail(uid, e.skill().getName());
                if (d != null) {
                    m.put("raw", store.readContent(uid, e.skill().getName()));
                    for (UserSkillStore.SkillFile f : d.files()) {
                        files.add(fileView(f));
                    }
                }
            } catch (RuntimeException ex) {
                // 文件清单读失败不影响正文审阅：正文已经在上面了
                m.put("filesError", ex.getMessage());
            }
        } else if (e.skill().getResources() != null) {
            for (Map.Entry<String, String> r : e.skill().getResources().entrySet()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", r.getKey());
                f.put("size", r.getValue() == null ? 0 : r.getValue().getBytes(StandardCharsets.UTF_8).length);
                f.put("binary", false);
                files.add(f);
            }
        }
        m.put("files", files);
        return ResultJson.ok(m);
    }

    @Operation(summary = "技能正文",
            description = "按名取 SKILL.md 正文（含已停用的那条）；与模型本轮吃到的那份同源")
    @Permission("skill:read")
    @GetMapping("/content")
    public ResultJson content(@RequestParam("name") String name,
                              @RequestParam(value = "sessionId", required = false) String sessionId) {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireEntry(uid, sessionId, name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.skill().getName());
        m.put("description", e.skill().getDescription());
        m.put("source", e.source());
        m.put("disabled", e.disabled());
        m.put("content", e.skill().getSkillContent());
        if (WenquSkillRepository.SOURCE_USER.equals(e.source())) {
            m.put("raw", store.readContent(uid, e.skill().getName()));
        }
        return ResultJson.ok(m);
    }

    @Operation(summary = "技能文件正文", description = "用户自装技能目录下的文本文件（references/scripts 等），供详情页预览")
    @Permission("skill:read")
    @GetMapping("/file")
    public ResultJson file(@RequestParam("name") String name,
                           @RequestParam("path") String path) {
        String uid = uid();
        requireMine(uid, name);
        return ResultJson.ok(Map.of("path", path, "content", store.readFile(uid, name, path)));
    }

    // ==================== 写：新建 / 安装 ====================

    @Operation(summary = "新建技能", description = "body: {name, description, content, overwrite?, category?}；落到当前账号的 uid 级技能目录")
    @Permission("skill:write")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        String uid = uid();
        String name = text(body.get("name"));
        String description = text(body.get("description"));
        String content = text(body.get("content"));
        boolean overwrite = flag(body.get("overwrite"));
        String category = text(body.get("category"));
        UserSkillStore.InstalledSkill s = store.create(uid, name, description, content,
                overwrite, reserved(), category);
        skills.invalidate(null);
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已创建");
    }

    @Operation(summary = "从 URL 安装技能",
            description = "body: {url, name?, overwrite?}；仅 http/https 的 SKILL.md 原文（须含 frontmatter），来源域名白名单见系统设置")
    @Permission("skill:write")
    @PostMapping("/install")
    public ResultJson install(@RequestBody Map<String, Object> body) {
        String uid = uid();
        UserSkillStore.InstalledSkill s = store.installUrl(uid, text(body.get("url")),
                text(body.get("name")), flag(body.get("overwrite")), reserved());
        skills.invalidate(null);
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已安装");
    }

    @Operation(summary = "上传 zip 安装技能",
            description = "multipart: file（含 SKILL.md，可带 references/scripts）+ name? + overwrite?；逐条目做路径与体积校验")
    @Permission("skill:write")
    @PostMapping("/install/zip")
    public ResultJson installZip(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "name", required = false) String name,
                                 @RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite) {
        String uid = uid();
        UserSkillStore.InstalledSkill s = store.installZip(uid, file, name, overwrite, reserved());
        skills.invalidate(null);
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已安装");
    }

    // ==================== 写：编辑 / 删除 / 停用 ====================

    @Operation(summary = "编辑技能", description = "body: {description?, content, category?}；只能改自己安装的技能，技能名不可改；旧版自动留快照")
    @Permission("skill:write")
    @PutMapping("/{name}")
    public ResultJson update(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        String uid = uid();
        requireMine(uid, name);
        UserSkillStore.InstalledSkill s = store.update(uid, name,
                text(body.get("description")), text(body.get("content")), text(body.get("category")));
        skills.invalidate(null);
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已保存");
    }

    @Operation(summary = "版本历史", description = "编辑/覆盖前自动留存的 SKILL.md 快照列表（新→旧，最多 20 份）")
    @Permission("skill:read")
    @GetMapping("/{name}/versions")
    public ResultJson versions(@PathVariable("name") String name) {
        String uid = uid();
        requireMine(uid, name);
        return ResultJson.ok(Map.of("versions", store.listVersions(uid, name)));
    }

    @Operation(summary = "回滚版本", description = "body: {ts}；把 SKILL.md 写回指定快照（当前内容先留新快照，附属文件不动）")
    @Permission("skill:write")
    @PostMapping("/{name}/rollback")
    public ResultJson rollback(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        String uid = uid();
        requireMine(uid, name);
        Object rawTs = body.get("ts");
        long ts;
        try {
            ts = rawTs instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rawTs).trim());
        } catch (Exception e) {
            throw new BizException("缺少版本号 ts");
        }
        UserSkillStore.InstalledSkill s = store.rollback(uid, name, ts);
        skills.invalidate(null);
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已回滚");
    }

    @Operation(summary = "批量操作",
            description = "body: {names: [技能名], action: disable|enable|delete}；逐项校验归属与可操作性，返回逐项结果")
    @Permission("skill:write")
    @PostMapping("/batch")
    public ResultJson batch(@RequestBody Map<String, Object> body) {
        String uid = uid();
        String action = text(body.get("action"));
        if (action == null || !Set.of("disable", "enable", "delete").contains(action)) {
            throw new BizException("action 仅支持 disable / enable / delete");
        }
        Object rawNames = body.get("names");
        if (!(rawNames instanceof List<?> list) || list.isEmpty()) throw new BizException("缺少 names");
        if (list.size() > 100) throw new BizException("单次批量上限 100 条");
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        for (Object o : list) {
            String name = o == null ? "" : String.valueOf(o).trim();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            try {
                if (name.isEmpty()) throw new BizException("技能名为空");
                if ("delete".equals(action)) {
                    requireMine(uid, name);
                    store.delete(uid, name);
                } else {
                    WenquSkillRepository.SkillEntry e = requireEntry(uid, null, name);
                    if (WenquSkillRepository.SOURCE_WORKSPACE.equals(e.source())) {
                        throw new BizException("工作区技能随会话走，不能停用");
                    }
                    store.setDisabled(uid, e.skill().getName(), "disable".equals(action));
                }
                r.put("ok", true);
                ok++;
            } catch (BizException e) {
                r.put("ok", false);
                r.put("msg", e.getMessage());
            }
            results.add(r);
        }
        if (ok > 0) skills.invalidate(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        out.put("ok", ok);
        out.put("failed", results.size() - ok);
        return ResultJson.ok(out, "批量完成：成功 " + ok + " 条");
    }

    @Operation(summary = "删除技能", description = "只能删自己安装的技能；内置技能只读，工作区技能随会话走")
    @Permission("skill:delete")
    @DeleteMapping("/{name}")
    public ResultJson delete(@PathVariable("name") String name) {
        String uid = uid();
        requireMine(uid, name);
        store.delete(uid, name);
        skills.invalidate(null);
        return ResultJson.ok(Map.of("name", name), "已删除");
    }

    @Operation(summary = "启用/停用技能", description = "body: {\"disabled\": true}；个人级停用，只影响自己；内置技能只能停用不能覆盖")
    @Permission("skill:write")
    @PutMapping("/{name}/disabled")
    public ResultJson disabled(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireEntry(uid, null, name);
        if (WenquSkillRepository.SOURCE_WORKSPACE.equals(e.source())) {
            throw new BizException("工作区技能随会话走，不能单独停用；不需要它时直接忽略即可");
        }
        boolean disabled = flag(body.get("disabled"));
        store.setDisabled(uid, e.skill().getName(), disabled);
        skills.invalidate(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.skill().getName());
        m.put("disabled", disabled);
        return ResultJson.ok(m, disabled ? "已停用" : "已启用");
    }

    @Operation(summary = "下载技能包", description = "把自己安装的技能打成 zip（备份/分享）；只有本人技能可下载")
    @Permission("skill:read")
    @GetMapping("/download")
    public void download(@RequestParam("name") String name, HttpServletResponse response) throws IOException {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireMine(uid, name);
        String safe = e.skill().getName();
        String filename = URLEncoder.encode(safe + ".zip", StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        store.writeZip(uid, safe, response.getOutputStream());
    }

    // ==================== 内部 ====================

    /** 登录身份：写操作不接受 SYSTEM（没有真实登录身份时也不能往 SYSTEM 名下装东西）。 */
    private static String uid() {
        String uid = RequestUser.uid();
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) {
            throw new BizException(401, "请先登录后再管理技能");
        }
        return uid;
    }

    private static RuntimeContext ctx(String uid) {
        return RuntimeContext.builder().userId(uid).sessionId("skill-list").build();
    }

    private WenquSkillRepository.SkillEntry requireEntry(String uid, String sessionId, String name) {
        if (name == null || name.isBlank()) throw new BizException("缺少技能名");
        WenquSkillRepository.SkillEntry e = skills.resolve(ctx(uid), sessionId, name.trim());
        if (e == null) throw new BizException("没有这条技能，或它当前对你不可见");
        return e;
    }

    private WenquSkillRepository.SkillEntry requireMine(String uid, String name) {
        WenquSkillRepository.SkillEntry e = requireEntry(uid, null, name);
        if (!WenquSkillRepository.SOURCE_USER.equals(e.source())) {
            throw new BizException("只有你自己安装的技能才能修改/删除（内置技能只读，工作区技能请在对话里让助手改）");
        }
        return e;
    }

    /** 不能被用户技能占用的名字：内置技能名（随版本分发的公共资产不能被新装包顶掉）。 */
    private Set<String> reserved() {
        return new LinkedHashSet<>(skills.builtinNames());
    }

    /** 当前用户的技能元信息，按技能名与目录名双键索引（历史手工目录两者可能不同）。 */
    private Map<String, UserSkillStore.InstalledSkill> mine(String uid) {
        Map<String, UserSkillStore.InstalledSkill> map = new LinkedHashMap<>();
        for (UserSkillStore.InstalledSkill s : store.list(uid)) {
            map.put(s.name(), s);
            map.put(s.dirName(), s);
        }
        return map;
    }

    private static UserSkillStore.InstalledSkill metaOf(Map<String, UserSkillStore.InstalledSkill> mine,
                                                        WenquSkillRepository.SkillEntry e) {
        if (!WenquSkillRepository.SOURCE_USER.equals(e.source())) return null;
        return mine.get(e.skill().getName());
    }

    private static UserSkillStore.InstalledSkill metaOf(Map<String, UserSkillStore.InstalledSkill> mine, String name) {
        return mine.get(name);
    }

    private Map<String, Object> view(String uid, WenquSkillRepository.SkillEntry e,
                                     UserSkillStore.InstalledSkill meta, boolean withContent) {
        return view(uid, e, meta, withContent, Map.of());
    }

    private Map<String, Object> view(String uid, WenquSkillRepository.SkillEntry e,
                                     UserSkillStore.InstalledSkill meta, boolean withContent,
                                     Map<String, Map<String, Object>> statsMap) {
        AgentSkill s = e.skill();
        String name = s.getName();
        String source = e.source();
        boolean mine = WenquSkillRepository.SOURCE_USER.equals(source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dirName", meta == null ? name : meta.dirName());
        m.put("ref", mine ? uid + "/" + name : name);
        m.put("description", s.getDescription());
        m.put("source", source);
        m.put("disabled", e.disabled());
        m.put("selectable", !WenquSkillRepository.SOURCE_WORKSPACE.equals(source));
        m.put("editable", mine);
        m.put("deletable", mine);
        m.put("disableable", !WenquSkillRepository.SOURCE_WORKSPACE.equals(source));
        m.put("size", meta != null ? meta.size() : bytes(s.getSkillContent()));
        m.put("updatedAt", meta != null ? meta.updatedAt() : 0L);
        m.put("fileCount", meta != null ? meta.fileCount() : resourceCount(s));
        m.put("category", categoryOf(source, name, meta));
        Map<String, Object> st = statsMap == null ? null : statsMap.get(name);
        if (st == null) st = statsMap == null ? null : statsMap.get(name.toLowerCase(Locale.ROOT));
        m.put("reads7d", st == null ? 0 : st.getOrDefault("reads7d", 0));
        m.put("reads30d", st == null ? 0 : st.getOrDefault("reads30d", 0));
        m.put("lastUsedAt", st == null ? 0L : st.getOrDefault("lastUsedAt", 0L));
        m.put("series7d", st == null ? List.of() : st.getOrDefault("series7d", List.of()));
        m.put("bodyExcerpt", bodyExcerpt(s.getSkillContent()));
        if (withContent) m.put("content", s.getSkillContent());
        return m;
    }

    /**
     * 正文首段摘录（列表页短描述补显用）：剥 frontmatter、跳过标题与列表符号，
     * 取第一段有效文字，压成一行、上限 120 字。正文已在内存里（清单同源），不额外读盘。
     */
    private static String bodyExcerpt(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String text = raw;
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end > 0) {
                int bodyStart = end + 4;
                while (bodyStart < text.length()
                        && (text.charAt(bodyStart) == '\n' || text.charAt(bodyStart) == '\r')) bodyStart++;
                text = text.substring(Math.min(bodyStart, text.length()));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            String v = line.strip();
            boolean isHeading = v.startsWith("#");
            if (v.isEmpty() || isHeading) {
                // 段前空行与标题跳过；段落已经开始后又遇到空行/新标题 = 第一段结束
                if (sb.length() > 0) break;
                continue;
            }
            String clean = v.replaceFirst("^[-*+]\\s+", "").replaceFirst("^\\d+\\.\\s+", "")
                    .replaceAll("[*_`>]", "").strip();
            if (clean.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(clean);
            if (sb.length() >= 120) break;
        }
        return sb.length() > 120 ? sb.substring(0, 120) : sb.toString();
    }

    // ==================== 分类解析 ====================

    /**
     * 分类口径：用户技能取 frontmatter category，缺省按名称关键词推断；
     * 工作区沉淀固定一档。前端 chips 从行数据里聚合，不另发接口。
     */
    private static String categoryOf(String source, String name, UserSkillStore.InstalledSkill meta) {
        if (WenquSkillRepository.SOURCE_WORKSPACE.equals(source)) return "工作区沉淀";
        if (meta != null && meta.category() != null && !meta.category().isBlank()) {
            return meta.category().trim();
        }
        return guessCategory(name);
    }

    /** 名称关键词推断（用户技能没写 category 时的兜底）：命中不了就归「通用」。 */
    private static String guessCategory(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.startsWith("form-") || n.contains("表单") || n.contains("表格") || n.contains("子表")
                || n.contains("问卷") || n.contains("登记")) return "表单表格";
        if (n.contains("审查") || n.contains("review") || n.contains("代码")) return "代码审查";
        if (n.contains("文档") || n.contains("写作") || n.contains("api") || n.contains("手册")) return "文档写作";
        if (n.contains("设计") || n.contains("走查") || n.contains("ui") || n.contains("视觉")) return "设计走查";
        if (n.contains("检索") || n.contains("搜索") || n.contains("crosscheck")) return "检索问答";
        return "通用";
    }

    private static Map<String, Object> fileView(UserSkillStore.SkillFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", f.path());
        m.put("size", f.size());
        m.put("binary", f.binary());
        return m;
    }

    private static int resourceCount(AgentSkill s) {
        return s.getResources() == null ? 1 : s.getResources().size() + 1;
    }

    private static long bytes(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean flag(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }
}
