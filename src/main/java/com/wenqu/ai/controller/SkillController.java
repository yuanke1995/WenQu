package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ConfigService;
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

/**
 * 技能（Skills）：清单 / 正文审阅 / 用户自助安装与管理。
 *
 * <h3>为什么既有只读端点，也有写端点</h3>
 * 全框架化 P3 一度把写入整体交给模型的 {@code skill_manage} 工具，平台只剩只读清单——
 * 结果是「用户不能自己装技能」，而框架工具写的技能又只在当前会话可见。这里补回用户侧写入口，
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
@Tag(name = "技能（Skills）", description = "可插拔能力包：内置 + 用户自装 + 历史投影 + 本会话工作区，按用户隔离")
public class SkillController {

    private final WenquSkillRepository skills;
    private final UserSkillStore store;
    private final ConfigService configService;

    // ==================== 读 ====================

    @Operation(summary = "可用技能清单",
            description = "内置 + 用户自装 + 历史投影（+ 指定会话的沙盒工作区技能）；不含正文，同名只给生效那条")
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
            description = "管理页数据源：来源/停用（平台或本人）/可否编辑删除/体积/文件数，附带远程安装开关与安装限制")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "sessionId", required = false) String sessionId) {
        String uid = uid();
        RuntimeContext ctx = ctx(uid);
        Map<String, UserSkillStore.InstalledSkill> mine = mine(uid);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WenquSkillRepository.SkillEntry e : skills.catalog(ctx, sessionId)) {
            rows.add(view(uid, e, metaOf(mine, e), false));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skills", rows);
        data.put("remoteInstall", remoteInstall());
        data.put("limits", store.limits());
        return ResultJson.ok(data);
    }

    @Operation(summary = "技能详情",
            description = "元信息 + 正文（模型看到的同源正文）+ raw（用户自装技能的 SKILL.md 原文，编辑用）+ 文件清单")
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
    @GetMapping("/file")
    public ResultJson file(@RequestParam("name") String name,
                           @RequestParam("path") String path) {
        String uid = uid();
        requireMine(uid, name);
        return ResultJson.ok(Map.of("path", path, "content", store.readFile(uid, name, path)));
    }

    // ==================== 写：新建 / 安装 ====================

    @Operation(summary = "新建技能", description = "body: {name, description, content, overwrite?}；落到当前账号的 uid 级技能目录")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        String uid = uid();
        String name = text(body.get("name"));
        String description = text(body.get("description"));
        String content = text(body.get("content"));
        boolean overwrite = flag(body.get("overwrite"));
        UserSkillStore.InstalledSkill s = store.create(uid, name, description, content,
                overwrite, reserved(uid));
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已创建");
    }

    @Operation(summary = "从 URL 安装技能",
            description = "body: {url, name?, overwrite?}；仅 http/https 的 SKILL.md 原文（须含 frontmatter），来源域名白名单见系统设置")
    @PostMapping("/install")
    public ResultJson install(@RequestBody Map<String, Object> body) {
        String uid = uid();
        UserSkillStore.InstalledSkill s = store.installUrl(uid, text(body.get("url")),
                text(body.get("name")), flag(body.get("overwrite")), reserved(uid));
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已安装");
    }

    @Operation(summary = "上传 zip 安装技能",
            description = "multipart: file（含 SKILL.md，可带 references/scripts）+ name? + overwrite?；逐条目做路径与体积校验")
    @PostMapping("/install/zip")
    public ResultJson installZip(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "name", required = false) String name,
                                 @RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite) {
        String uid = uid();
        UserSkillStore.InstalledSkill s = store.installZip(uid, file, name, overwrite, reserved(uid));
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已安装");
    }

    // ==================== 写：编辑 / 删除 / 停用 ====================

    @Operation(summary = "编辑技能", description = "body: {description?, content}；只能改自己安装的技能，技能名不可改")
    @PutMapping("/{name}")
    public ResultJson update(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        String uid = uid();
        requireMine(uid, name);
        UserSkillStore.InstalledSkill s = store.update(uid, name,
                text(body.get("description")), text(body.get("content")));
        return ResultJson.ok(view(uid, requireEntry(uid, null, s.name()), metaOf(mine(uid), s.name()), false), "已保存");
    }

    @Operation(summary = "删除技能", description = "只能删自己安装的技能；内置/历史投影只读，工作区技能随会话走")
    @DeleteMapping("/{name}")
    public ResultJson delete(@PathVariable("name") String name) {
        String uid = uid();
        requireMine(uid, name);
        store.delete(uid, name);
        return ResultJson.ok(Map.of("name", name), "已删除");
    }

    @Operation(summary = "启用/停用技能", description = "body: {\"disabled\": true}；个人级停用，只影响自己；内置技能只能停用不能覆盖")
    @PutMapping("/{name}/disabled")
    public ResultJson disabled(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        String uid = uid();
        WenquSkillRepository.SkillEntry e = requireEntry(uid, null, name);
        if (WenquSkillRepository.SOURCE_WORKSPACE.equals(e.source())) {
            throw new BizException("工作区技能随会话走，不能单独停用；不需要它时直接忽略即可");
        }
        boolean disabled = flag(body.get("disabled"));
        store.setDisabled(uid, e.skill().getName(), disabled);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.skill().getName());
        m.put("disabled", disabled);
        return ResultJson.ok(m, disabled ? "已停用（只对你生效）" : "已启用");
    }

    @Operation(summary = "下载技能包", description = "把自己安装的技能打成 zip（备份/分享）；只有本人技能可下载")
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

    /** 登录身份：写操作不接受匿名（require-login 关闭时也不能往 anonymous 名下装东西）。 */
    private static String uid() {
        String uid = RequestUser.uid();
        if (uid == null || uid.isBlank() || RequestUser.ANONYMOUS.equals(uid)) {
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
            throw new BizException("只有你自己安装的技能才能修改/删除（内置与历史技能只读，工作区技能请在对话里让助手改）");
        }
        return e;
    }

    /** 不能被用户技能占用的名字：内置 + 该用户的历史投影（同名的存量资产不能被新装包顶掉）。 */
    private Set<String> reserved(String uid) {
        Set<String> out = new LinkedHashSet<>(skills.builtinNames());
        for (WenquSkillRepository.SkillEntry e : skills.catalog(ctx(uid), null)) {
            if (WenquSkillRepository.SOURCE_LEGACY.equals(e.source()) && e.skill().getName() != null) {
                out.add(e.skill().getName().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
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
        AgentSkill s = e.skill();
        String name = s.getName();
        String source = e.source();
        boolean mine = WenquSkillRepository.SOURCE_USER.equals(source);
        boolean legacy = WenquSkillRepository.SOURCE_LEGACY.equals(source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dirName", meta == null ? name : meta.dirName());
        m.put("ref", (mine || legacy) ? uid + "/" + name : name);
        m.put("description", s.getDescription());
        m.put("source", source);
        m.put("disabled", e.disabled());
        m.put("platformDisabled", e.platformDisabled());
        m.put("userDisabled", e.userDisabled());
        m.put("selectable", !WenquSkillRepository.SOURCE_WORKSPACE.equals(source));
        m.put("editable", mine);
        m.put("deletable", mine);
        m.put("disableable", !WenquSkillRepository.SOURCE_WORKSPACE.equals(source));
        m.put("size", meta != null ? meta.size() : bytes(s.getSkillContent()));
        m.put("updatedAt", meta != null ? meta.updatedAt() : 0L);
        m.put("fileCount", meta != null ? meta.fileCount() : resourceCount(s));
        if (withContent) m.put("content", s.getSkillContent());
        return m;
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

    /** 远程安装开关与允许的域名（空 = 关闭；服务端仍会逐跳校验公网地址）。 */
    private Map<String, Object> remoteInstall() {
        String raw = configService == null ? null : configService.get("skill.remoteAllowedHosts");
        Set<String> hosts = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String item : raw.split("[,，\\s]+")) {
                String h = item.trim().toLowerCase(Locale.ROOT);
                if (!h.isEmpty()) hosts.add(h);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", !hosts.isEmpty());
        m.put("hosts", new ArrayList<>(hosts));
        return m;
    }
}
