package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.common.BizException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skills 插件机制（4.5 基础版）：技能 = 一个目录 + {@code SKILL.md}。
 *
 * <p>SKILL.md 结构：YAML frontmatter（{@code name}/{@code description}/{@code version}）+ Markdown 正文（技能指令）。
 *
 * <p>两层来源，同名时用户层覆盖内置层（对应"个人技能覆盖共享"）：
 * <ul>
 *   <li>内置：{@code classpath:skills/*&#47;SKILL.md}（随发布分发）</li>
 *   <li>用户：{@code skill.dir}（默认 {@code ./data/skills}，设置页可改）</li>
 * </ul>
 *
 * <p>渐进披露（progressive disclosure）：默认只把「技能名 + 一行描述」注入 system prompt，
 * 正文由模型按需调用 {@code readSkill} 工具取回——避免把所有技能正文塞进每次请求的上下文。
 *
 * <p><b>安全边界</b>：技能只被当**纯文本**读取，任何脚本内容都不会被执行；
 * 技能名走白名单字符集 + 目录穿越校验；单文件读取长度有上限；删除只允许删用户目录下的技能。
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

    /**
     * 技能元信息（不含正文，列表用）。
     *
     * @param name        技能名（frontmatter 优先，缺失时用目录名）
     * @param description 一行描述（注入 system prompt 用）
     * @param version     版本（frontmatter，可空）
     * @param hash        SKILL.md 内容 SHA-256 前 8 位（内容变更可见）
     * @param source      builtin（内置）/ user（用户目录）
     * @param dirName     目录名（定位文件用）
     * @param size        SKILL.md 字节数
     */
    public record Skill(String name, String description, String version, String hash,
                        String source, String dirName, long size) {
    }

    /** 技能目录（用户层）。配置缺失时兜底 ./data/skills */
    private Path userDir() {
        String d = configService.get("skill.dir");
        if (d == null || d.isBlank()) d = "./data/skills";
        return Paths.get(d).toAbsolutePath().normalize();
    }

    /** 用户技能目录的绝对路径（管理界面展示"往哪放 SKILL.md"） */
    public String userDirPath() {
        return userDir().toString();
    }

    /**
     * 列出全部技能（内置 + 用户，用户层同名覆盖），按名称排序；不含正文。
     */
    public List<Skill> list() {
        Map<String, Skill> byName = new LinkedHashMap<>();
        // 1) 内置层（classpath，fat jar 内也可扫）
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath*:skills/*/" + SKILL_FILE)) {
                try (InputStream in = r.getInputStream()) {
                    String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Skill s = parse(raw, dirNameOf(r), "builtin");
                    byName.put(s.dirName(), s);
                }
            }
        } catch (Exception e) {
            log.warn("[SKILL] 内置技能扫描失败（跳过）: {}", e.getMessage());
        }
        // 2) 用户层（同名覆盖内置）
        Path base = userDir();
        if (Files.isDirectory(base)) {
            try (var dirs = Files.list(base)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    Path f = dir.resolve(SKILL_FILE);
                    if (!Files.isRegularFile(f)) {
                        log.debug("[SKILL] 目录 {} 缺少 {}，跳过", dir.getFileName(), SKILL_FILE);
                        continue;
                    }
                    try {
                        String raw = Files.readString(f, StandardCharsets.UTF_8);
                        Skill s = parse(raw, dir.getFileName().toString(), "user");
                        byName.put(s.dirName(), s);
                    } catch (Exception e) {
                        log.warn("[SKILL] 技能 {} 解析失败（跳过）: {}", dir.getFileName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("[SKILL] 用户技能目录扫描失败: {}", e.getMessage());
            }
        }
        List<Skill> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(Skill::name));
        return out;
    }

    /** 按目录名查技能元信息 */
    public Skill find(String dirName) {
        return list().stream().filter(s -> s.dirName().equals(dirName)).findFirst().orElse(null);
    }

    /**
     * 读取技能全文（含 frontmatter，模型看到的是完整技能说明），长度受 {@code skill.maxFileChars} 限制。
     * 找不到、被停用或超长时返回可读提示（工具调用结果直接回给模型，不抛异常）。
     */
    public String readContent(String nameOrDir) {
        if (nameOrDir == null || nameOrDir.isBlank()) return "错误：技能名为空";
        Skill s = find(nameOrDir.trim());
        if (s == null) {
            // 允许按 frontmatter 里的显示名匹配（模型更容易复述 name 而非法定目录名）
            s = list().stream().filter(x -> x.name().equals(nameOrDir.trim())).findFirst().orElse(null);
        }
        if (s == null) return "错误：没有名为「" + nameOrDir + "」的技能。可用技能见系统提示中的技能清单。";
        if (isDisabled(s)) return "错误：技能「" + s.name() + "」已被停用。";
        try {
            String raw = readRaw(s);
            int max = Math.max(500, configService.getInt("skill.maxFileChars", 20000));
            if (raw.length() > max) {
                return raw.substring(0, max) + "\n\n…（技能内容过长已截断，仅返回前 " + max + " 字符）";
            }
            return raw;
        } catch (Exception e) {
            return "错误：技能内容读取失败：" + e.getMessage();
        }
    }

    /**
     * 生成注入 system prompt 的技能清单块（只含名称与描述，控制字符数上限）。
     * 无启用技能时返回空串（调用方不追加段落）。
     */
    public String promptBlock(int maxChars) {
        List<Skill> enabled = list().stream().filter(s -> !isDisabled(s)).toList();
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

    /** 创建用户技能（写 {skill.dir}/{name}/SKILL.md） */
    public Skill create(String name, String description, String content) {
        String n = name == null ? "" : name.trim();
        if (!NAME_OK.matcher(n).matches()) {
            throw new BizException("技能名仅支持中英文、数字、下划线与连字符（1~64 字符）");
        }
        if (findByDirName(n) != null && "builtin".equals(findByDirName(n).source())) {
            throw new BizException("已存在同名内置技能，请换个名字");
        }
        Path base = userDir();
        Path dir = base.resolve(n).normalize();
        if (!dir.startsWith(base)) {
            throw new BizException("非法的技能名");   // 双保险：白名单之外仍校验不越出根目录
        }
        Path f = dir.resolve(SKILL_FILE);
        if (Files.exists(f)) {
            throw new BizException("已存在同名技能，请换个名字或先删除");
        }
        String body = content == null || content.isBlank()
                ? "# " + n + "\n\n在此写这个技能的具体做法：什么场景用、按什么步骤、输出要什么格式。\n"
                : content.trim() + "\n";
        String md = "---\n"
                + "name: " + n + "\n"
                + "description: " + (description == null ? "" : description.trim().replaceAll("\\s+", " ")) + "\n"
                + "version: 1.0.0\n"
                + "createdAt: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n"
                + "---\n\n" + body;
        try {
            Files.createDirectories(dir);
            Files.writeString(f, md, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("技能写入失败：" + e.getMessage());
        }
        log.info("[SKILL] 创建用户技能 {}（{} 字节）", n, md.length());
        return parse(md, n, "user");
    }

    /** 删除用户技能（内置技能不可删）；递归删除前已限定在 {skill.dir}/{白名单名} 之下 */
    public void delete(String name) {
        Skill s = find(name);
        if (s == null) throw new BizException("技能不存在");
        if ("builtin".equals(s.source())) throw new BizException("内置技能不可删除（可在用户目录建同名技能覆盖）");
        Path base = userDir();
        Path dir = base.resolve(s.dirName()).normalize();
        if (!dir.startsWith(base) || dir.equals(base)) throw new BizException("非法的技能路径");
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignore) {
                    // 单个文件删不掉不阻断整体（下次扫描会再列出）
                }
            });
        } catch (Exception e) {
            throw new BizException("技能删除失败：" + e.getMessage());
        }
        setDisabled(s.dirName(), false);   // 顺手清掉停用标记，避免残留
        log.info("[SKILL] 删除用户技能 {}", s.dirName());
    }

    /** 是否被停用（停用名单存配置 skill.disabledNames，JSON 数组） */
    public boolean isDisabled(Skill s) {
        return disabledNames().contains(s.dirName());
    }

    public void setDisabled(String dirName, boolean disabled) {
        Skill s = find(dirName);
        if (s == null) throw new BizException("技能不存在");
        List<String> names = new ArrayList<>(disabledNames());
        if (disabled) {
            if (!names.contains(s.dirName())) names.add(s.dirName());
        } else {
            names.remove(s.dirName());
        }
        configService.putInternal("skill.disabledNames", JSON.toJSONString(names));
    }

    // ==================== 内部 ====================

    private List<String> disabledNames() {
        try {
            String v = configService.get("skill.disabledNames");
            if (v == null || v.isBlank()) return List.of();
            List<String> l = JSON.parseArray(v, String.class);
            return l == null ? List.of() : l;
        } catch (Exception e) {
            log.warn("[SKILL] skill.disabledNames 解析失败（按未停用处理）: {}", e.getMessage());
            return List.of();
        }
    }

    private Skill findByDirName(String dirName) {
        return list().stream().filter(s -> s.dirName().equals(dirName)).findFirst().orElse(null);
    }

    private String readRaw(Skill s) throws Exception {
        if ("builtin".equals(s.source())) {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource r = resolver.getResource("classpath:skills/" + s.dirName() + "/" + SKILL_FILE);
            try (InputStream in = r.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(userDir().resolve(s.dirName()).resolve(SKILL_FILE), StandardCharsets.UTF_8);
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
            // jar 内 URL 解析失败：退化为 unknown（技能仍会被列出，只是目录名不理想）
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
                log.warn("[SKILL] {} 的 frontmatter 解析失败（仅用目录名）: {}", fallbackDir, e.getMessage());
            }
        } else if (raw.startsWith("---")) {
            log.warn("[SKILL] {} 的 frontmatter 未闭合（缺少结束 ---），已按无 frontmatter 处理", fallbackDir);
        }
        if (name == null || name.isBlank()) name = fallbackDir;
        return new Skill(name.trim(), desc.trim(), version.trim(), sha8(raw), source, fallbackDir,
                raw.getBytes(StandardCharsets.UTF_8).length);
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
