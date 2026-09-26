package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.SkillDisabledMapper;
import com.wisesoft.ai.mapper.UserSkillMapper;
import com.wisesoft.ai.model.SkillDisabled;
import com.wisesoft.ai.model.UserSkill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skills 插件机制：技能 = 一段可复用的做法说明（SKILL.md 文本 = YAML frontmatter + Markdown 正文）。
 *
 * <p><b>归属：每个用户管自己的技能</b>（原「管理员在服务器上放目录 + 全局停用名单」的形态已废弃）：
 * <ul>
 *   <li>内置：{@code classpath:skills/*&#47;SKILL.md}（随发布分发，所有人可见、不可删，可各自停用）；</li>
 *   <li>个人：{@code c_ai_user_skill}，按 uid 隔离（自建或 URL 安装），停用随行。</li>
 * </ul>
 *
 * <p>渐进披露（progressive disclosure）：默认只把「技能名 + 一行描述」注入 system prompt，
 * 正文由模型按需调用 {@code readSkill} 工具取回——避免把所有技能正文塞进每次请求的上下文。
 *
 * <p><b>安全边界</b>：技能只被当**纯文本**读取，任何脚本内容都不会被执行；
 * 技能名走白名单字符集；单技能读取长度有上限（{@code skill.maxFileChars}）；
 * URL 安装限 http/https + 体积上限 + frontmatter 校验。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    /** 技能名白名单：汉字、字母、数字、下划线、连字符（排除 . / \ 等路径字符，从源头杜绝穿越） */
    private static final Pattern NAME_OK = Pattern.compile("[\\p{IsHan}A-Za-z0-9_-]{1,64}");
    /** frontmatter：开头 --- 到下一个 --- */
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?", Pattern.DOTALL);
    private static final String SKILL_FILE = "SKILL.md";

    private final ConfigService configService;
    private final UserSkillMapper userSkillMapper;
    private final SkillDisabledMapper skillDisabledMapper;

    /**
     * 技能元信息（不含正文，列表用）。
     *
     * @param name        技能名（frontmatter 优先，缺失时用技能标识）
     * @param description 一行描述（注入 system prompt 用）
     * @param version     版本（frontmatter，可空）
     * @param hash        内容 SHA-256 前 8 位（内容变更可见）
     * @param source      builtin（内置）/ user（自建）/ url（URL 安装）
     * @param dirName     技能标识（定位技能用）
     * @param size        内容字节数
     */
    public record Skill(String name, String description, String version, String hash,
                        String source, String dirName, long size) {
    }

    /** 技能 + 停用状态：一次查库得到全量视图，避免"列 N 个技能再逐个判定停用"的 N+1 */
    public record SkillState(Skill skill, boolean disabled) {
    }

    /**
     * 列出当前用户可见技能及其停用状态（内置 + 个人，同名时个人优先），按名称排序。
     *
     * @param uid 归属用户
     */
    public List<SkillState> listWithState(String uid) {
        Set<String> builtinDisabled = builtinDisabledDirNames(uid);
        Map<String, SkillState> byDir = new LinkedHashMap<>();
        // 1) 内置层（classpath，fat jar 内也可扫）
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath*:skills/*/" + SKILL_FILE)) {
                try (InputStream in = r.getInputStream()) {
                    Skill s = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), dirNameOf(r), "builtin");
                    byDir.put(s.dirName(), new SkillState(s, builtinDisabled.contains(s.dirName())));
                }
            }
        } catch (Exception e) {
            log.warn("[SKILL] 内置技能扫描失败（跳过）: {}", e.getMessage());
        }
        // 2) 个人层（同名覆盖内置）
        for (UserSkill row : ownRows(uid)) {
            if (row.getContent() == null) continue;
            try {
                Skill s = parse(row.getContent(), row.getDirName(), sourceOf(row));
                byDir.put(s.dirName(), new SkillState(s, Integer.valueOf(1).equals(row.getDisabled())));
            } catch (Exception e) {
                log.warn("[SKILL] 个人技能 {} 解析失败（跳过）: {}", row.getDirName(), e.getMessage());
            }
        }
        List<SkillState> out = new ArrayList<>(byDir.values());
        out.sort(Comparator.comparing(st -> st.skill().name()));
        return out;
    }

    /** 仅元信息的简版列表（调用方不关心停用时用） */
    public List<Skill> list(String uid) {
        return listWithState(uid).stream().map(SkillState::skill).toList();
    }

    /** 按技能标识查元信息（找不到返回 null） */
    public Skill find(String uid, String dirName) {
        return list(uid).stream().filter(s -> s.dirName().equals(dirName)).findFirst().orElse(null);
    }

    /** 按技能标识查一行个人技能（内置技能返回 null） */
    public SkillState findState(String uid, String dirName) {
        return listWithState(uid).stream().filter(st -> st.skill().dirName().equals(dirName))
                .findFirst().orElse(null);
    }

    /**
     * 读取技能全文（含 frontmatter；模型看到的是完整技能说明），长度受 {@code skill.maxFileChars} 限制。
     * 找不到、被停用或超长时返回可读提示（工具调用结果直接回给模型，不抛异常）。
     *
     * @param uid        归属用户（技能现在是个人资产，别人看不到）
     * @param nameOrDir  技能标识或 frontmatter 里的显示名
     */
    public String readContent(String uid, String nameOrDir) {
        if (nameOrDir == null || nameOrDir.isBlank()) return "错误：技能名为空";
        List<SkillState> all = listWithState(uid);
        Skill hit = null;
        boolean disabled = false;
        for (SkillState st : all) {
            if (st.skill().dirName().equals(nameOrDir.trim())) {
                hit = st.skill();
                disabled = st.disabled();
                break;
            }
        }
        if (hit == null) {
            // 允许按 frontmatter 里的显示名匹配（模型更容易复述 name 而非技能标识）
            for (SkillState st : all) {
                if (st.skill().name().equals(nameOrDir.trim())) {
                    hit = st.skill();
                    disabled = st.disabled();
                    break;
                }
            }
        }
        if (hit == null) return "错误：没有名为「" + nameOrDir + "」的技能。可用技能见系统提示中的技能清单。";
        if (disabled) return "错误：技能「" + hit.name() + "」已被停用。";
        String raw = readRaw(uid, hit);
        if (raw == null) return "错误：技能内容读取失败：技能记录不存在";
        int max = Math.max(500, configService.getInt("skill.maxFileChars", 20000));
        if (raw.length() > max) {
            return raw.substring(0, max) + "\n\n…（技能内容过长已截断，仅返回前 " + max + " 字符）";
        }
        return raw;
    }

    /**
     * 生成注入 system prompt 的技能清单块（只含名称与描述，控制字符数上限）。
     * 该用户无启用技能时返回空串（调用方不追加段落）。
     *
     * @param uid      归属用户：注入的是**这个人**的技能
     * @param maxChars 清单字符上限
     * @param only     null=不筛选（全部启用技能）；空集合=不注入任何技能；非空=只注入这些（按技能名匹配）
     */
    public String promptBlock(String uid, int maxChars, Set<String> only) {
        if (only != null && only.isEmpty()) return "";
        List<Skill> enabled = listWithState(uid).stream()
                .filter(st -> !st.disabled())
                .map(SkillState::skill)
                .filter(s -> only == null || only.contains(s.name()))
                .toList();
        if (enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill s : enabled) {
            String line = "- " + s.name() + (s.description().isBlank() ? "" : "：" + s.description()) + "\n";
            if (sb.length() + line.length() > Math.max(200, maxChars)) {
                sb.append("…（技能列表过长已截断）\n");
                break;
            }
            sb.append(line);
        }
        return "【可用技能】\n以下技能是用户预先配置好的专门做法。当问题涉及某个技能描述的领域时，"
                + "先用 readSkill 工具读取该技能的完整说明，再按其要求作答（不要在未读取时臆测技能内容）：\n" + sb;
    }

    /**
     * 创建个人技能。
     *
     * @param uid   归属用户
     * @param name  技能名（同时作为技能标识）
     * @param description 一句话描述（模型靠它决定是否读取）
     * @param content Markdown 正文（不含 frontmatter，由本方法补齐）
     */
    public Skill create(String uid, String name, String description, String content) {
        String n = name == null ? "" : name.trim();
        if (!NAME_OK.matcher(n).matches()) {
            throw new BizException("技能名仅支持中英文、数字、下划线与连字符（1~64 字符）");
        }
        if (builtinDirNames().contains(n)) {
            throw new BizException("已存在同名内置技能，请换个名字");
        }
        if (ownRow(uid, n) != null) {
            throw new BizException("已存在同名技能，请换个名字或先删除");
        }
        String body = content == null || content.isBlank()
                ? "# " + n + "\n\n在此写这个技能的具体做法：什么场景用、按什么步骤、输出要什么格式。\n"
                : content.trim() + "\n";
        String md = frontmatter(n, description, "1.0.0") + body;
        String desc = (description == null || description.isBlank()) ? firstLine(body) : description.trim();
        saveRow(newRow(uid, n, n, desc, md, "user", "1.0.0"));
        log.info("[SKILL] 创建个人技能 uid={} skill={}（{} 字节）", uid, n, md.length());
        return parse(md, n, "user");
    }

    /**
     * 从 URL 安装技能：只接受 http/https 上的 **SKILL.md 纯文本**，装到当前用户名下。
     *
     * <p>安全边界：协议白名单（杜绝 file:// 等）、响应体大小上限、连接与读取超时，
     * 内容仅作为文本入库（**不执行**）；要求内容自带 frontmatter（含 name/description），
     * 缺 frontmatter 直接报错而不是猜——避免装进来一个模型永远看不到的"哑技能"。
     *
     * @param url          技能文件地址（GitHub 请用 raw 链接）
     * @param nameOverride 技能标识（可空；空则用 frontmatter 里的 name）
     */
    public Skill installFromUrl(String uid, String url, String nameOverride) {
        if (url == null || url.isBlank()) throw new BizException("请填写技能文件地址");
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new BizException("仅支持 http/https 地址（GitHub 页面链接请换成 raw 链接）");
        }
        String content = download(u);
        if (!content.stripLeading().startsWith("---")) {
            throw new BizException("技能文件缺少 frontmatter（应以 --- 开头并包含 name / description），"
                    + "请确认链接指向 SKILL.md 原文");
        }
        Skill meta = parse(content, "", "url");
        String dir = (nameOverride != null && !nameOverride.isBlank()) ? nameOverride.trim() : meta.name();
        if (dir == null || dir.isBlank() || !NAME_OK.matcher(dir).matches()) {
            throw new BizException("无法从内容确定技能名，请显式填写名称（中英文/数字/下划线/连字符，1~64 字符）");
        }
        if (meta.description().isBlank()) {
            throw new BizException("技能缺少 description——模型靠它判断何时读取该技能，请在技能文件 frontmatter 里补上");
        }
        if (ownRow(uid, dir) != null) throw new BizException("已存在同名技能（" + dir + "），请换个名称或先删除");
        // 与 create() 同一规则：个人技能不得占用内置技能名。
        // 原文件形态下 URL 安装会「用户目录同名覆盖内置」，库和新建两条规则打架（新建拒、安装盖），
        // 结果是装个包能把内置技能悄悄顶掉——统一为拒绝：内置技能只能各人自行停用，不能被覆盖。
        if (builtinDirNames().contains(dir)) {
            throw new BizException("已存在同名内置技能（" + dir + "）：内置技能只能在技能列表里停用，不能被同名技能覆盖，请换个名称安装");
        }
        saveRow(newRow(uid, dir, meta.name(), meta.description(), content, "url", meta.version()));
        log.info("[SKILL] uid={} 从 URL 安装技能 {} ← {}", uid, dir, u);
        return parse(content, dir, "url");
    }

    /**
     * 启用/停用技能：个人技能改行状态，内置技能记到个人停用表（内置技能本身不可改）。
     */
    public void setDisabled(String uid, String dirName, boolean disabled) {
        UserSkill row = ownRow(uid, dirName);
        if (row != null) {
            row.setDisabled(disabled ? 1 : 0);
            userSkillMapper.updateById(row);
            return;
        }
        if (!builtinDirNames().contains(dirName)) throw new BizException("技能不存在");
        if (disabled) {
            if (skillDisabledMapper.selectOne(new LambdaQueryWrapper<SkillDisabled>()
                    .eq(SkillDisabled::getUid, uid).eq(SkillDisabled::getDirName, dirName)
                    .last("limit 1")) == null) {
                SkillDisabled d = new SkillDisabled();
                d.setUid(uid);
                d.setDirName(dirName);
                skillDisabledMapper.insert(d);
            }
        } else {
            skillDisabledMapper.delete(new LambdaQueryWrapper<SkillDisabled>()
                    .eq(SkillDisabled::getUid, uid).eq(SkillDisabled::getDirName, dirName));
        }
    }

    /** 删除个人技能（内置技能不可删：只能停用） */
    public void delete(String uid, String dirName) {
        UserSkill row = ownRow(uid, dirName);
        if (row == null) {
            throw new BizException(builtinDirNames().contains(dirName)
                    ? "内置技能不可删除（可停用）" : "技能不存在");
        }
        userSkillMapper.deleteById(row.getId());
        log.info("[SKILL] 删除个人技能 uid={} skill={}", uid, dirName);
    }

    // ==================== 内部 ====================

    /** 个人技能行（按技能标识查） */
    private UserSkill ownRow(String uid, String dirName) {
        if (uid == null || dirName == null) return null;
        return userSkillMapper.selectOne(new LambdaQueryWrapper<UserSkill>()
                .eq(UserSkill::getUid, uid).eq(UserSkill::getDirName, dirName).last("limit 1"));
    }

    /** 当前用户的全部个人技能行 */
    private List<UserSkill> ownRows(String uid) {
        if (uid == null) return List.of();
        return userSkillMapper.selectList(new LambdaQueryWrapper<UserSkill>()
                .eq(UserSkill::getUid, uid).orderByAsc(UserSkill::getCreateTime));
    }

    /** 该用户停用了哪些内置技能 */
    private Set<String> builtinDisabledDirNames(String uid) {
        if (uid == null) return Set.of();
        try {
            return skillDisabledMapper.selectList(new LambdaQueryWrapper<SkillDisabled>()
                            .eq(SkillDisabled::getUid, uid))
                    .stream().map(SkillDisabled::getDirName).collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.warn("[SKILL] 内置技能停用标记读取失败（按未停用处理）: {}", e.getMessage());
            return Set.of();
        }
    }

    /** 内置技能标识集合（同名不可新建） */
    private Set<String> builtinDirNames() {
        Set<String> out = new HashSet<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath*:skills/*/" + SKILL_FILE)) {
                out.add(dirNameOf(r));
            }
        } catch (Exception e) {
            log.warn("[SKILL] 内置技能扫描失败: {}", e.getMessage());
        }
        return out;
    }

    /** 读全文：内置走 classpath，个人库读 content 列 */
    private String readRaw(String uid, Skill s) {
        if ("builtin".equals(s.source())) {
            try {
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource r = resolver.getResource("classpath:skills/" + s.dirName() + "/" + SKILL_FILE);
                try (InputStream in = r.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("[SKILL] 内置技能 {} 读取失败: {}", s.dirName(), e.getMessage());
                return null;
            }
        }
        UserSkill row = ownRow(uid, s.dirName());
        return row == null ? null : row.getContent();
    }

    private UserSkill newRow(String uid, String dirName, String display, String desc, String content,
                             String source, String version) {
        UserSkill row = new UserSkill();
        row.setId(UUID.randomUUID().toString());
        row.setUid(uid);
        row.setDirName(dirName);
        row.setName(display);
        row.setDescription(desc);
        row.setVersion(version == null || version.isBlank() ? "1.0.0" : version);
        row.setContent(content);
        row.setSource(source);
        row.setDisabled(0);
        return row;
    }

    private void saveRow(UserSkill row) {
        try {
            userSkillMapper.insert(row);
        } catch (Exception e) {
            throw new BizException("技能写入失败：" + e.getMessage());
        }
    }

    private static String sourceOf(UserSkill row) {
        return row.getSource() == null || row.getSource().isBlank() ? "user" : row.getSource();
    }

    /** 下载 SKILL.md 文本（超时 + 大小上限；不跟随重定向到非 http/https） */
    private String download(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "wen-qu-skill-installer")
                    .GET().build();
            java.net.http.HttpResponse<byte[]> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new BizException("下载失败：HTTP " + resp.statusCode());
            byte[] body = resp.body();
            // 上限按字符配置换算成字节（中文 UTF-8 最多 3~4 字节/字符）
            int maxBytes = Math.max(10000, configService.getInt("skill.maxFileChars", 20000)) * 4;
            if (body.length > maxBytes) {
                throw new BizException("技能文件过大（" + body.length + " 字节，上限 " + maxBytes + "）");
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("下载失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 从资源 URL 取技能目录名（如 .../skills/step-by-step-answering/SKILL.md → step-by-step-answering） */
    private String dirNameOf(Resource r) {
        try {
            String p = r.getURL().toString();
            int slash = p.lastIndexOf('/');
            if (slash > 0) {
                String dir = p.substring(0, slash);
                int prev = dir.lastIndexOf('/');
                if (prev >= 0 && prev + 1 < dir.length()) return dir.substring(prev + 1);
            }
        } catch (Exception e) {
            // jar 内 URL 解析失败：退化为 unknown（技能仍会被列出，只是标识不理想）
        }
        return "unknown";
    }

    /** 解析 SKILL.md：frontmatter（YAML，safe 模式）取 name/description/version，其余为正文 */
    private Skill parse(String raw, String fallbackDir, String source) {
        String name = fallbackDir, desc = "", version = "";
        Matcher m = FRONTMATTER.matcher(raw);
        if (m.find()) {
            try {
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                Map<String, Object> fm = yaml.load(m.group(1));
                if (fm != null) {
                    name = str(fm.get("name"), fallbackDir);
                    desc = str(fm.get("description"), "");
                    version = str(fm.get("version"), "");
                }
            } catch (Exception e) {
                log.warn("[SKILL] {} 的 frontmatter 解析失败（仅用技能标识）: {}", fallbackDir, e.getMessage());
            }
        } else if (raw.startsWith("---")) {
            log.warn("[SKILL] {} 的 frontmatter 未闭合（缺少结束 ---），已按无 frontmatter 处理", fallbackDir);
        }
        if (name == null || name.isBlank()) name = fallbackDir;
        return new Skill(name.trim(), desc.trim(), version.trim(), sha8(raw), source, fallbackDir,
                raw.getBytes(StandardCharsets.UTF_8).length);
    }

    private static String frontmatter(String name, String description, String version) {
        return "---\n"
                + "name: " + name + "\n"
                + "description: " + (description == null ? "" : description.trim().replaceAll("\\s+", " ")) + "\n"
                + "version: " + version + "\n"
                + "createdAt: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n"
                + "---\n\n";
    }

    /** 正文首行当默认描述（用户没写描述时至少不是空的） */
    private static String firstLine(String body) {
        for (String line : body.split("\n")) {
            String t = line.replace("#", "").trim();
            if (!t.isBlank()) return t.length() > 200 ? t.substring(0, 200) : t;
        }
        return "";
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            return "--------";
        }
    }
}
