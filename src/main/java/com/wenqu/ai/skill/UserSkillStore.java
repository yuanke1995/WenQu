package com.wenqu.ai.skill;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.service.AuditService;
import com.wenqu.ai.sandbox.SandboxPaths;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.util.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 用户级技能仓库（宿主文件系统）：{@code user-data/skills-users/{uid}/{技能名}/SKILL.md}。
 *
 * <h3>为什么是宿主目录，而不是沙箱工作区</h3>
 * 框架 {@code skill_manage} 写的技能落在沙箱工作区，作用域默认是<b>会话</b>——换个会话就没了。
 * 用户自己「装」的技能必须跨会话、跨工作区稳定存在，所以这里单独开一个 uid 级目录：
 * <ul>
 *   <li>宿主路径，不走沙箱，沙箱没起也能读写；</li>
 *   <li>按 uid 隔离，读侧由 {@link WenquSkillRepository} 每轮按当轮用户解析；</li>
 *   <li>随 {@code user-data/} 一起备份，容器重建不丢。</li>
 * </ul>
 *
 * <h3>目录约定</h3>
 * <pre>
 * user-data/skills-users/{uid}/
 * ├── .disabled                    # 个人停用清单（一行一个技能名，含内置技能名）
 * └── my-skill/
 *     ├── SKILL.md                  # 必需，frontmatter 的 name 必须等于目录名
 *     ├── references/guide.md       # 可选，会作为 AgentSkill.resources 提供给模型按需读取
 *     └── scripts/run.sh            # 可选
 * </pre>
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>技能名白名单（中英文/数字/下划线/连字符），杜绝 {@code ..}/{@code /} 路径穿越；</li>
 *   <li>zip 逐条目校验：绝对路径、{@code ..}、反斜杠、盘符、隐藏文件、深度/条目数/解压总量全部封顶；</li>
 *   <li>URL 安装：仅 http/https + 域名白名单（{@code skill.remoteAllowedHosts}）+ 每跳公网校验
 *       + 3 次重定向上限 + 体积上限；内容只当文本保存，从不执行。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class UserSkillStore {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private static final Logger log = LoggerFactory.getLogger(UserSkillStore.class);

    /** 技能名白名单：汉字、字母、数字、下划线、连字符（与旧实现同一口径，排除路径字符）。 */
    private static final Pattern NAME_OK = Pattern.compile("[\\p{IsHan}A-Za-z0-9_-]{1,64}");
    private static final Pattern YAML_PLAIN = Pattern.compile("[\\p{IsHan}A-Za-z0-9 _.,/()\\-]+");
    private static final String SKILL_FILE = "SKILL.md";
    private static final String DISABLED_FILE = ".disabled";
    private static final String TMP_DIR = ".tmp";
    private static final String TRASH_DIR = ".trash";

    private static final int MAX_SKILL_BYTES = 512 * 1024;
    private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final long MAX_ZIP_BYTES = 24L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 240;
    private static final int MAX_FILES = 120;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_DESCRIPTION = 1000;
    private static final int MAX_URL_BYTES = 512 * 1024;
    private static final int MAX_DISABLED = 500;
    private static final int MAX_PREVIEW_BYTES = 256 * 1024;

    private static final Set<String> BINARY_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "pdf", "zip", "gz", "tar",
            "docx", "xlsx", "pptx", "jar", "class", "so", "dylib", "dll", "exe", "bin",
            "woff", "woff2", "ttf", "otf", "mp3", "mp4", "mov", "wav");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 用户技能根（{@code user-data/skills-users}，可配）。 */
    private final Path root;
    private final ConfigService configService;

    public UserSkillStore(
            @Value("${wenqu.skills.users-dir:user-data/skills-users}") String usersDir,
            ConfigService configService,
            com.wenqu.ai.service.AuditService audit) {
        this.root = Paths.get(usersDir == null || usersDir.isBlank() ? "user-data/skills-users" : usersDir)
                .toAbsolutePath().normalize();
        this.configService = configService;
        this.audit = audit;
    }

    /** 用户技能根（测试/装配用）。 */
    public Path root() {
        return root;
    }

    // ==================== 读 ====================

    /** 某个用户的技能目录（路径安全化后的 uid；不存在也算合法路径）。 */
    public Path userDir(String uid) {
        if (uid == null || uid.isBlank()) throw new BizException("缺少用户身份");
        return root.resolve(SandboxPaths.uidDirname(uid));
    }

    /** 技能元信息（不含正文）。 */
    public record InstalledSkill(String name, String dirName, String description, long size,
                                 long updatedAt, int fileCount, boolean disabled) {
    }

    /** 技能里的一个文件（预览/下载列表用）。 */
    public record SkillFile(String path, long size, boolean binary) {
    }

    /** 技能详情：元信息 + SKILL.md 全文 + 文件清单。 */
    public record SkillDetail(InstalledSkill skill, String content, List<SkillFile> files) {
    }

    /** 列出某用户安装的全部技能（目录坏了只跳过该条，不让整页报错）。 */
    public List<InstalledSkill> list(String uid) {
        Path dir = userDir(uid);
        if (!Files.isDirectory(dir)) return List.of();
        Set<String> disabled = disabledNames(uid);
        List<InstalledSkill> out = new ArrayList<>();
        try (Stream<Path> sub = Files.list(dir)) {
            for (Path p : (Iterable<Path>) sub::iterator) {
                String dirName = p.getFileName().toString();
                if (dirName.startsWith(".") || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                Path md = p.resolve(SKILL_FILE);
                if (!Files.isRegularFile(md, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    Doc doc = readDoc(md, dirName, false);
                    long size = Files.size(md);
                    long updated = Files.getLastModifiedTime(md).toMillis();
                    int files = countFiles(p);
                    out.add(new InstalledSkill(
                            doc.name() == null || doc.name().isBlank() ? dirName : doc.name(),
                            dirName, doc.description(), size, updated, files,
                            disabled.contains(dirName.toLowerCase(Locale.ROOT))));
                } catch (Exception e) {
                    log.warn("[SKILL] 读取用户技能失败 uid={} dir={}: {}", uid, dirName, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[SKILL] 列出用户技能失败 uid={}: {}", uid, e.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(InstalledSkill::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** 按技能名或目录名找一条（新装技能两者相同；历史手工目录可能不一致）。 */
    public InstalledSkill find(String uid, String key) {
        if (key == null || key.isBlank()) return null;
        Path dir = findDir(uid, key);
        if (dir == null) return null;
        String dirName = dir.getFileName().toString();
        Doc doc = readDoc(dir.resolve(SKILL_FILE), dirName, false);
        boolean disabled = disabledNames(uid).contains(dirName.toLowerCase(Locale.ROOT));
        try {
            return new InstalledSkill(doc.name() == null || doc.name().isBlank() ? dirName : doc.name(),
                    dirName, doc.description(), Files.size(dir.resolve(SKILL_FILE)),
                    Files.getLastModifiedTime(dir.resolve(SKILL_FILE)).toMillis(), countFiles(dir), disabled);
        } catch (IOException e) {
            return null;
        }
    }

    public SkillDetail detail(String uid, String key) {
        Path dir = findDir(uid, key);
        if (dir == null) return null;
        InstalledSkill meta = find(uid, key);
        if (meta == null) return null;
        String content = readContent(uid, key);
        List<SkillFile> files = listFiles(dir);
        return new SkillDetail(meta, content, files);
    }

    /** 读 SKILL.md 原文（含 frontmatter；与模型看到的正文同源同字节）。 */
    public String readContent(String uid, String key) {
        Path dir = findDir(uid, key);
        if (dir == null) throw new BizException("技能不存在：" + key);
        return readText(dir.resolve(SKILL_FILE), MAX_SKILL_BYTES);
    }

    /** 技能 frontmatter 里的 version（没有则空串）——市场上架时登记版本号用。 */
    public String versionOf(String uid, String key) {
        Path dir = findDir(uid, key);
        if (dir == null) return "";
        try {
            Doc doc = readDoc(dir.resolve(SKILL_FILE), "", false);
            return doc.version() == null ? "" : doc.version().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 读技能目录下的任意文本文件（路径穿越/超限/二进制一律拒绝）。 */
    public String readFile(String uid, String key, String relativePath) {
        Path dir = findDir(uid, key);
        if (dir == null) throw new BizException("技能不存在：" + key);
        String rel = normalizeRelative(relativePath, false);
        if (rel == null || rel.isBlank()) throw new BizException("缺少文件路径");
        Path file = dir.resolve(rel).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BizException("文件不存在：" + rel);
        }
        return readText(file, MAX_PREVIEW_BYTES);
    }

    // ==================== 写 ====================

    /**
     * 新建/覆盖一个技能（正文可以是完整 SKILL.md，也可以只是 markdown 正文）。
     *
     * @param overwrite 同名时是否整包替换（替换会丢掉旧包里的 references/scripts）
     */
    public InstalledSkill create(String uid, String name, String description,
                                 String content, boolean overwrite) {
        return create(uid, name, description, content, overwrite, Set.of());
    }

    /**
     * 新建/覆盖一个技能（带保留名：内置技能名不允许被用户技能占用）。
     *
     * @param reserved 保留名（小写），命中即拒绝
     */
    public InstalledSkill create(String uid, String name, String description,
                                 String content, boolean overwrite, Set<String> reserved) {
        String n = normalizeName(name);
        ensureNotReserved(n, reserved);
        Doc doc = parseContent(content, n, description);
        writeSkill(uid, n, doc, overwrite);
        audit.record("[AUDIT] 新建技能 uid={} skill={} overwrite={}", uid, n, overwrite);
        return find(uid, n);
    }

    /**
     * 编辑已有技能的 SKILL.md（保留 references/scripts 等附属文件）。
     * <p>技能名是身份与引用串的一部分，不允许在这里改——改名请新建后删除旧技能。
     */
    public InstalledSkill update(String uid, String key, String description, String content) {
        Path dir = findDir(uid, key);
        if (dir == null) throw new BizException("技能不存在，或不是你安装的技能：" + key);
        String dirName = dir.getFileName().toString();
        Doc doc = parseContent(content, dirName, description);
        try {
            writeSkillMd(dir, doc);
        } catch (IOException e) {
            throw new BizException("写入技能失败: " + e.getMessage());
        }
        audit.record("[AUDIT] 编辑技能 uid={} skill={}", uid, dirName);
        return find(uid, dirName);
    }

    /** 删除自己安装的技能（先移到 .trash 再删，避免半删状态）。 */
    public void delete(String uid, String key) {
        Path dir = findDir(uid, key);
        if (dir == null) throw new BizException("技能不存在，或不是你安装的技能：" + key);
        String dirName = dir.getFileName().toString();
        try {
            Path trash = userDir(uid).resolve(TRASH_DIR).resolve(dirName + "-" + System.currentTimeMillis());
            Files.createDirectories(trash.getParent());
            Files.move(dir, trash, StandardCopyOption.ATOMIC_MOVE);
            deleteRecursively(trash);
        } catch (Exception e) {
            // 移动失败也要把目录删掉：否则「删了但还在」比报错更难解释
            try {
                deleteRecursively(dir);
            } catch (IOException io) {
                throw new BizException("删除技能失败：" + io.getMessage());
            }
        }
        // 删除后清掉个人停用记录：否则重装同名技能会莫名处于停用态。
        // 清理失败只告警：技能已经删掉了，不该让「停用清单没顺手清」把删除报成失败。
        try {
            Set<String> disabled = new LinkedHashSet<>(disabledNames(uid));
            if (disabled.remove(dirName.toLowerCase(Locale.ROOT))) {
                writeDisabled(uid, disabled);
            }
        } catch (RuntimeException e) {
            log.warn("[SKILL] 清理个人停用记录失败 uid={} skill={}: {}", uid, dirName, e.toString());
        }
        audit.record("[AUDIT] 删除技能 uid={} skill={}", uid, dirName);
    }

    // ==================== 安装：zip ====================

    /**
     * 从 zip 包安装：支持 SKILL.md 在压缩包根目录，或包在唯一的外层目录里。
     * <p>逐条目做路径/体积校验，先解到 {@code .tmp} 再整体移动——失败不会留下半个技能。
     */
    public InstalledSkill installZip(String uid, MultipartFile file, String nameOverride, boolean overwrite) {
        return installZip(uid, file, nameOverride, overwrite, Set.of());
    }

    /**
     * 从 zip 包安装（带保留名：内置技能名不允许被用户技能占用）。
     * <p>逐条目做路径/体积校验，先解到 {@code .tmp} 再整体移动——失败不会留下半个技能。
     */
    public InstalledSkill installZip(String uid, MultipartFile file, String nameOverride,
                                     boolean overwrite, Set<String> reserved) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要上传的 zip 文件");
        if (file.getSize() > MAX_ZIP_BYTES) {
            throw new BizException("技能包过大（上限 " + (MAX_ZIP_BYTES / 1024 / 1024) + "MB）");
        }
        try {
            return installZipStream(uid, file.getInputStream(), nameOverride, overwrite, reserved);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage());
        }
    }

    /**
     * 从本地 zip 文件安装（市场/站内分发用）：与上传 zip 同一条校验链（路径/体积/解压总量）。
     * <p>文件来源是服务端管理的市场快照包，不是用户直传——但内容仍可能是别的用户上架的，
     * 所以校验一条不少：市场安装的信任边界与匿名上传相同。
     */
    public InstalledSkill installZipFile(String uid, Path zip, String nameOverride,
                                         boolean overwrite, Set<String> reserved) {
        if (zip == null || !Files.isRegularFile(zip)) {
            throw new BizException("物品包缺失，请联系作者重新上架");
        }
        long size;
        try {
            size = Files.size(zip);
        } catch (IOException e) {
            throw new BizException("物品包读取失败：" + e.getMessage());
        }
        if (size > MAX_ZIP_BYTES) {
            throw new BizException("技能包过大（上限 " + (MAX_ZIP_BYTES / 1024 / 1024) + "MB）");
        }
        try (InputStream in = Files.newInputStream(zip)) {
            return installZipStream(uid, in, nameOverride, overwrite, reserved);
        } catch (IOException e) {
            throw new BizException("物品包读取失败：" + e.getMessage());
        }
    }

    /** zip 安装主体：解压到 .tmp → 定位 SKILL.md → 校验 → 整包落位（上传与市场安装共用）。 */
    private InstalledSkill installZipStream(String uid, InputStream in, String nameOverride,
                                            boolean overwrite, Set<String> reserved) {
        Path tmp = userDir(uid).resolve(TMP_DIR).resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(tmp);
            extractZip(in, tmp);
            Path skillRoot = locateSkillRoot(tmp);
            Doc doc = readDoc(skillRoot.resolve(SKILL_FILE), "", true);
            if ((nameOverride == null || nameOverride.isBlank()) && (doc.name() == null || doc.name().isBlank())) {
                throw new BizException("技能包的 SKILL.md 缺少 name，请在安装时显式填写技能名");
            }
            String name = normalizeName(nameOverride == null || nameOverride.isBlank()
                    ? doc.name() : nameOverride);
            ensureNotReserved(name, reserved);
            if (doc.name() != null && !doc.name().isBlank() && !doc.name().equals(name)) {
                throw new BizException("zip 里的技能名（" + doc.name() + "）与指定的名称（" + name + "）不一致");
            }
            if (doc.description() == null || doc.description().isBlank()) {
                throw new BizException("技能缺少 description——模型靠它判断何时读取该技能");
            }
            Path target = skillDir(uid, name);
            promote(skillRoot, target, overwrite);
            audit.record("[AUDIT] zip 安装技能 uid={} skill={} overwrite={}", uid, name, overwrite);
            return find(uid, name);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("技能包解析失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            deleteQuietly(tmp);
        }
    }

    // ==================== 安装：URL ====================

    /**
     * 从 URL 安装单个 SKILL.md 文本。
     * <p>安全边界：协议白名单 → 域名白名单 → 每跳公网校验（禁内网）→ 手动逐跳重定向 →
     * 体积上限。内容只保存为文本，绝不执行。
     */
    public InstalledSkill installUrl(String uid, String url, String nameOverride, boolean overwrite) {
        return installUrl(uid, url, nameOverride, overwrite, Set.of());
    }

    /** 从 URL 安装单个 SKILL.md 文本（带保留名）。 */
    public InstalledSkill installUrl(String uid, String url, String nameOverride,
                                     boolean overwrite, Set<String> reserved) {
        if (url == null || url.isBlank()) throw new BizException("请填写技能文件地址");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new BizException("技能文件地址不合法：" + e.getMessage());
        }
        String content = download(uri);
        Doc doc = readDocFromString(content, "", true);
        if ((nameOverride == null || nameOverride.isBlank()) && (doc.name() == null || doc.name().isBlank())) {
            throw new BizException("技能文件缺少 name，请在安装时显式填写技能名");
        }
        String name = normalizeName(nameOverride == null || nameOverride.isBlank()
                ? doc.name() : nameOverride);
        ensureNotReserved(name, reserved);
        if (doc.name() != null && !doc.name().isBlank() && !doc.name().equals(name)) {
            throw new BizException("技能文件里的 name（" + doc.name() + "）与指定的名称（" + name + "）不一致");
        }
        if (doc.description() == null || doc.description().isBlank()) {
            throw new BizException("技能缺少 description——模型靠它判断何时读取该技能");
        }
        if (doc.description().length() > MAX_DESCRIPTION) {
            throw new BizException("技能描述过长（最多 " + MAX_DESCRIPTION + " 字）");
        }
        writeSkill(uid, name, doc, overwrite);
        audit.record("[AUDIT] URL 安装技能 uid={} skill={} url={}", uid, name, uri);
        return find(uid, name);
    }

    // ==================== 个人停用 ====================

    /** 个人停用/启用（内置、历史、自己装的都能停；工作区技能随会话走，不在本清单里）。 */
    public void setDisabled(String uid, String name, boolean disabled) {
        String n = normalizeName(name).toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>(disabledNames(uid));
        if (disabled) {
            if (names.size() >= MAX_DISABLED) throw new BizException("个人停用清单已达上限（" + MAX_DISABLED + " 条）");
            names.add(n);
        } else {
            names.remove(n);
        }
        writeDisabled(uid, names);
        audit.record("[AUDIT] {}技能 uid={} skill={}", disabled ? "停用" : "启用", uid, n);
    }

    /** 个人停用清单（读不到按空处理）。 */
    public Set<String> disabledNames(String uid) {
        Path file = userDir(uid).resolve(DISABLED_FILE);
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            Set<String> out = new LinkedHashSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String v = line == null ? "" : line.trim();
                if (v.isEmpty() || v.startsWith("#")) continue;
                out.add(v.toLowerCase(Locale.ROOT));
                if (out.size() >= MAX_DISABLED) break;
            }
            return out;
        } catch (IOException e) {
            log.warn("[SKILL] 读取个人停用清单失败 uid={}: {}", uid, e.toString());
            return Set.of();
        }
    }

    // ==================== 导出 ====================

    /** 把一条技能打成 zip 流（备份/分享用）。 */
    public void writeZip(String uid, String key, OutputStream out) throws IOException {
        Path dir = findDir(uid, key);
        if (dir == null) throw new BizException("技能不存在，或不是你安装的技能：" + key);
        String dirName = dir.getFileName().toString();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .sorted()
                        .toList();
            }
            for (Path p : files) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                ZipEntry e = new ZipEntry(dirName + "/" + rel);
                e.setTime(Files.getLastModifiedTime(p).toMillis());
                zos.putNextEntry(e);
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    // ==================== 内部：路径 / 文档 ====================

    private Path skillDir(String uid, String name) {
        Path base = userDir(uid);
        Path dir = base.resolve(name).normalize();
        if (!dir.startsWith(base)) throw new BizException("技能名不合法");
        return dir;
    }

    private Path findDir(String uid, String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim();
        if (NAME_OK.matcher(k).matches()) {
            Path direct = skillDir(uid, k);
            if (Files.isDirectory(direct, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(direct.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS)) {
                return direct;
            }
        }
        // 兼容 frontmatter name 与目录名不一致的历史目录：按展示名再找一遍
        for (InstalledSkill s : list(uid)) {
            if (s.name().equals(k) || s.dirName().equals(k)) return skillDir(uid, s.dirName());
        }
        return null;
    }

    private static String normalizeName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (!NAME_OK.matcher(n).matches()) {
            throw new BizException("技能名仅支持中英文、数字、下划线与连字符（1~64 字符）");
        }
        return n;
    }

    /** 保留名（内置技能）不许被用户技能占用：否则「装个包把内置技能悄悄顶掉」。 */
    private static void ensureNotReserved(String name, Set<String> reserved) {
        if (reserved != null && reserved.contains(name.trim().toLowerCase(Locale.ROOT))) {
            throw new BizException("已存在同名内置技能（" + name
                    + "）：内置技能只能停用，不能被同名技能覆盖，请换个名字");
        }
    }

    /** 安装限制（前端提示用，与校验常量同源）。 */
    public Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zipMB", MAX_ZIP_BYTES / 1024 / 1024);
        m.put("totalMB", MAX_TOTAL_BYTES / 1024 / 1024);
        m.put("fileMB", MAX_RESOURCE_BYTES / 1024 / 1024);
        m.put("skillKB", MAX_SKILL_BYTES / 1024);
        m.put("files", MAX_FILES);
        m.put("entries", MAX_ZIP_ENTRIES);
        return m;
    }

    /** 新建/覆盖：先落到 .tmp，再整目录原子移动，失败不会留下半个技能。 */
    private void writeSkill(String uid, String name, Doc doc, boolean overwrite) {
        Path tmp = userDir(uid).resolve(TMP_DIR).resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(tmp);
            writeSkillMd(tmp, doc);
            promote(tmp, skillDir(uid, name), overwrite);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("保存技能失败：" + e.getMessage());
        } finally {
            deleteQuietly(tmp);
        }
    }

    private void promote(Path source, Path target, boolean overwrite) throws IOException {
        Files.createDirectories(target.getParent());
        Path trash = null;
        if (Files.exists(target)) {
            if (!overwrite) throw new BizException("已存在同名技能（" + target.getFileName() + "），请换个名字或勾选覆盖");
            trash = target.getParent().resolve(TRASH_DIR)
                    .resolve(target.getFileName() + "-" + System.currentTimeMillis());
            Files.createDirectories(trash.getParent());
            Files.move(target, trash, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            restoreTrash(trash, target);
            throw e;
        } catch (RuntimeException e) {
            restoreTrash(trash, target);
            throw e;
        }
        if (trash != null) deleteRecursively(trash);
    }

    /** 覆盖失败时把旧技能搬回去：宁可回到覆盖前，也不能「新内容没落位、旧内容也没了」。 */
    private static void restoreTrash(Path trash, Path target) {
        if (trash == null || Files.exists(target)) return;
        try {
            Files.move(trash, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // 回滚也失败时只能保留 .trash 里的副本，主流程仍抛原始异常
        }
    }

    private static void writeSkillMd(Path dir, Doc doc) throws IOException {
        Files.createDirectories(dir);
        String text = render(doc);
        Path tmp = Files.createTempFile(dir, "SKILL", ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve(SKILL_FILE),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, dir.resolve(SKILL_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Doc parseContent(String content, String expectedName, String description) {
        if (content == null || content.isBlank()) {
            throw new BizException("技能正文不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_SKILL_BYTES) {
            throw new BizException("SKILL.md 过大（上限 " + (MAX_SKILL_BYTES / 1024) + "KB）");
        }
        Doc parsed = readDocFromString(content, expectedName, true);
        String desc = description == null ? "" : description.trim();
        if (desc.isEmpty()) desc = parsed.description();
        if (desc.isEmpty()) desc = firstLine(parsed.body());
        if (desc.isEmpty()) {
            throw new BizException("请填写技能描述——模型靠这一句判断什么时候该用这条技能");
        }
        if (desc.length() > MAX_DESCRIPTION) {
            throw new BizException("技能描述过长（最多 " + MAX_DESCRIPTION + " 字）");
        }
        if (parsed.name() != null && !parsed.name().isBlank() && !parsed.name().equals(expectedName)) {
            throw new BizException("内容里的 name（" + parsed.name() + "）与技能名（" + expectedName + "）不一致");
        }
        return new Doc(expectedName, desc, parsed.body(), parsed.version());
    }

    private static String render(Doc doc) {
        StringBuilder sb = new StringBuilder(64 + doc.body().length());
        sb.append("---\n");
        sb.append("name: ").append(scalar(doc.name())).append('\n');
        sb.append("description: ").append(scalar(doc.description())).append('\n');
        if (doc.version() != null && !doc.version().isBlank()) {
            sb.append("version: ").append(scalar(doc.version())).append('\n');
        }
        sb.append("updated: ").append(LocalDate.now().format(DateTimeFormatter.ISO_DATE)).append('\n');
        sb.append("---\n\n");
        sb.append(doc.body() == null ? "" : doc.body().strip()).append('\n');
        return sb.toString();
    }

    /** YAML 标量：安全字符直接裸写，其余双引号转义（description 里常有冒号/引号）。 */
    private static String scalar(String s) {
        String v = s == null ? "" : s;
        String lower = v.toLowerCase(Locale.ROOT);
        boolean reserved = Set.of("true", "false", "null", "~", "yes", "no", "on", "off").contains(lower);
        if (!v.isEmpty() && !reserved && YAML_PLAIN.matcher(v).matches()) return v;
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n") + '"';
    }

    /** 文档模型：frontmatter 里的 name/description/version + markdown 正文。 */
    private record Doc(String name, String description, String body, String version) {
    }

    private Doc readDoc(Path md, String fallbackName, boolean strict) {
        try {
            String raw = Files.readString(md, StandardCharsets.UTF_8);
            return readDocFromString(raw, fallbackName, strict);
        } catch (IOException e) {
            throw new BizException("读取 SKILL.md 失败：" + e.getMessage());
        }
    }

    private Doc readDocFromString(String raw, String fallbackName, boolean strict) {
        String text = raw == null ? "" : raw;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String body = text;
        Map<String, Object> fm = new LinkedHashMap<>();
        boolean hasFrontmatter = false;
        if (text.startsWith("---")) {
            int firstNl = text.indexOf('\n');
            if (firstNl > 0) {
                int end = text.indexOf("\n---", firstNl);
                if (end > 0) {
                    String yamlText = text.substring(firstNl + 1, end);
                    int bodyStart = end + 4;
                    while (bodyStart < text.length()
                            && (text.charAt(bodyStart) == '\n' || text.charAt(bodyStart) == '\r')) {
                        bodyStart++;
                    }
                    body = text.substring(bodyStart);
                    hasFrontmatter = true;
                    try {
                        fm = parseYaml(yamlText);
                    } catch (Exception e) {
                        if (strict) throw new BizException("frontmatter 不是合法 YAML：" + e.getMessage());
                        log.warn("[SKILL] frontmatter 解析失败（按无 frontmatter 处理）: {}", e.getMessage());
                    }
                } else if (strict) {
                    throw new BizException("frontmatter 未闭合（开头 --- 缺少结束 ---）");
                }
            }
        }
        String name = str(fm.get("name"));
        String desc = str(fm.get("description"));
        String version = str(fm.get("version"));
        if (hasFrontmatter && strict && desc.isBlank()) {
            throw new BizException("技能缺少 description（frontmatter 里补上：模型靠它判断何时读取）");
        }
        if (name.isBlank()) name = fallbackName == null ? "" : fallbackName;
        return new Doc(name, desc, body == null ? "" : body, version);
    }

    private static Map<String, Object> parseYaml(String text) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = yaml.load(text);
        Map<String, Object> out = new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        for (String line : body.split("\\R")) {
            String v = line == null ? "" : line.trim();
            if (v.isEmpty()) continue;
            v = v.replaceFirst("^#{1,6}\\s*", "").replaceFirst("^[-*+]\\s+", "").trim();
            if (v.isEmpty()) continue;
            return v.length() > 120 ? v.substring(0, 120) : v;
        }
        return "";
    }

    // ==================== 内部：zip ====================

    private void extractZip(InputStream in, Path tmp) throws IOException {
        long total = 0;
        int entries = 0;
        int files = 0;
        try (ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) throw new BizException("技能包条目过多（上限 " + MAX_ZIP_ENTRIES + "）");
                if (e.isDirectory()) continue;
                String rel = normalizeRelative(e.getName(), true);
                if (rel == null) continue;
                if (++files > MAX_FILES) throw new BizException("技能包文件过多（上限 " + MAX_FILES + "）");
                Path out = tmp.resolve(rel).normalize();
                if (!out.startsWith(tmp)) throw new BizException("技能包里有非法路径：" + e.getName());
                Files.createDirectories(out.getParent());
                long written = copyLimited(zis, out, MAX_RESOURCE_BYTES);
                total += written;
                if (total > MAX_TOTAL_BYTES) throw new BizException("技能包解压后过大（上限 " + (MAX_TOTAL_BYTES / 1024 / 1024) + "MB）");
                restoreExecBit(out);
            }
        }
        if (files == 0) throw new BizException("技能包里没有文件");
    }

    private static long copyLimited(InputStream in, Path out, long limit) throws IOException {
        long total = 0;
        byte[] buf = new byte[8192];
        try (OutputStream os = Files.newOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > limit) throw new BizException("技能包里的单个文件过大（上限 " + (limit / 1024) + "KB）");
                os.write(buf, 0, n);
            }
        }
        return total;
    }

    /** 相对路径规范化：拒绝绝对路径、..、盘符、反斜杠；隐藏文件返回 null（跳过）。 */
    private static String normalizeRelative(String raw, boolean forZip) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.isEmpty()) return null;
        if (p.indexOf('\\') >= 0) throw new BizException("非法路径（不允许反斜杠）：" + raw);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new BizException("非法路径（不允许绝对路径）：" + raw);
        List<String> kept = new ArrayList<>();
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new BizException("非法路径（不允许 ..）：" + raw);
            if (part.startsWith(".")) return null;
            if (part.length() > 128) throw new BizException("路径片段过长：" + part);
            kept.add(part);
        }
        if (kept.isEmpty()) return null;
        if (kept.size() > MAX_DEPTH) throw new BizException("目录层级过深：" + raw);
        return String.join("/", kept);
    }

    private static Path locateSkillRoot(Path tmp) {
        if (Files.isRegularFile(tmp.resolve(SKILL_FILE))) return tmp;
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(tmp)) {
            s.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(dirs::add);
        } catch (IOException e) {
            throw new BizException("读取技能包失败：" + e.getMessage());
        }
        if (dirs.size() == 1 && Files.isRegularFile(dirs.get(0).resolve(SKILL_FILE))) {
            return dirs.get(0);
        }
        throw new BizException("技能包里没有找到 SKILL.md（支持放在根目录，或放在唯一的外层目录里）");
    }

    private static void restoreExecBit(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean script = name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".py")
                || name.endsWith(".rb") || name.endsWith(".pl") || name.endsWith(".js") || name.endsWith(".mjs");
        if (!script) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            perms.addAll(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
            // 非 POSIX 文件系统（Windows）忽略：脚本能不能执行由运行环境决定
        }
    }

    // ==================== 内部：URL ====================

    private String download(URI start) {
        URI current = start;
        for (int hop = 0; hop < 4; hop++) {
            requireAllowedHost(current);
            SsrfGuard.requirePublicHost(current);
            HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "wenqu-skill-installer")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp;
            try {
                resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new BizException("下载技能文件失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            int code = resp.statusCode();
            try (InputStream body = resp.body()) {
                if (code >= 300 && code < 400) {
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc == null || loc.isBlank()) throw new BizException("下载失败：重定向没有 Location");
                    current = current.resolve(loc.trim());
                    continue;
                }
                if (code != 200) throw new BizException("下载失败：HTTP " + code);
                byte[] bytes = readLimited(body, MAX_URL_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("读取技能文件失败：" + e.getMessage());
            }
        }
        throw new BizException("重定向次数过多（上限 3 次）");
    }

    /** 远程安装来源白名单：精确匹配 host（不做后缀匹配，避免 github.com.attacker.net 这类伪装）。 */
    private void requireAllowedHost(URI uri) {
        Set<String> allowed = allowedHosts();
        if (allowed.isEmpty()) {
            throw new BizException("远程安装未开启：请在「系统设置 → 技能 → 允许远程安装的域名」里配置");
        }
        String host = uri.getHost();
        if (host == null || !allowed.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BizException("不允许的来源域名：" + (host == null ? uri : host)
                    + "（允许：" + String.join("、", allowed) + "）");
        }
    }

    private Set<String> allowedHosts() {
        String raw = configService == null ? null : configService.get("skill.remoteAllowedHosts");
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String item : raw.split("[,，\\s]+")) {
            String h = item.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) out.add(h);
        }
        return out;
    }

    /** 测试用：暴露白名单校验（不发起请求）。 */
    void checkAllowedHost(String url) {
        requireAllowedHost(URI.create(url));
    }

    // ==================== 内部：杂项 ====================

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > limit) throw new BizException("技能文件过大（上限 " + (limit / 1024) + "KB）");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String readText(Path file, int limit) {
        try {
            long size = Files.size(file);
            if (size > limit) throw new BizException("文件过大，不能在线查看（上限 " + (limit / 1024) + "KB）");
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 512); i++) {
                if (bytes[i] == 0) throw new BizException("二进制文件不支持预览");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException("读取文件失败：" + e.getMessage());
        }
    }

    private static int countFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) Math.min(MAX_FILES, walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".")).count());
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<SkillFile> listFiles(Path dir) {
        List<SkillFile> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(MAX_FILES)
                    .forEach(p -> {
                        String rel = dir.relativize(p).toString().replace('\\', '/');
                        try {
                            out.add(new SkillFile(rel, Files.size(p), isBinary(rel)));
                        } catch (IOException ignored) {
                            out.add(new SkillFile(rel, 0, false));
                        }
                    });
        } catch (IOException e) {
            log.warn("[SKILL] 列出技能文件失败 dir={}: {}", dir, e.toString());
        }
        return out;
    }

    private static boolean isBinary(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return false;
        return BINARY_EXT.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void writeDisabled(String uid, Set<String> names) {
        Path dir = userDir(uid);
        try {
            Files.createDirectories(dir);
            String text = names.isEmpty() ? "" : String.join("\n", names) + "\n";
            Path tmp = Files.createTempFile(dir, DISABLED_FILE, ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dir.resolve(DISABLED_FILE),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, dir.resolve(DISABLED_FILE), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException("保存个人停用清单失败：" + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // 临时目录清理失败不影响主流程；下次安装会复用随机目录名
        }
    }
}
