package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.SandboxPaths;
import com.wenqu.ai.util.PreviewTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作区目录的<b>浏览与管理</b>（工作区页用）。
 *
 * <p><b>为什么直接读宿主目录而不走沙盒</b>：沙盒 backend 需要一个活着的容器（{@code backendIfPresent}），
 * 而工作区页在没有对话时容器多半是回收状态——那样用户进来只会看到「暂无文件」，而文件其实好好躺在盘上。
 * 沙盒容器挂载的就是同一个宿主目录（{@code shared/{uid}/workspace/{workdir}}），所以直接读是同一份数据。
 *
 * <p><b>边界（必须守住的）</b>：
 * <ol>
 *   <li>只动**本人**工作区：先按 uid 校验工作区归属，再由 {@link ProjectService#detail} 抛错；
 *       唯一的例外是 {@link #openView}（浏览器顶层导航带不上登录头），它的凭据是这条地址自己的签名；</li>
 *   <li>路径穿越：任何请求路径都逐段拆开重拼（{@code ..}、{@code .}、绝对路径、空段一律拒），
 *       落盘前再按真实路径比一次前缀——软链逃逸也算越界；</li>
 *   <li>列表不显示隐藏文件（点开头、{@code node_modules}、{@code __pycache__}、软链），
 *       所以新建与上传也不许取这类名字：传进去就在界面上永远找不到，比报错更骗人；</li>
 *   <li>远端 provisioner 部署下宿主目录不存在时**如实报不可用**，不伪装成空列表
 *       （空列表会被读成「我的文件没了」）。</li>
 * </ol>
 *
 * <p><b>写动作的两道闸</b>（见 {@link #assertWritable}）：归档区一概不写（与「归档不接新会话」同一口径）；
 * 改名/移动/删除在工作区里有轮次正在跑时拒绝——那一轮以为某个文件还在原位，中途抽走会让它读到半截或报错。
 * 上传与新建文件夹不受后者限制：往正在跑的目录里补一份材料是合法动作。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ProjectFileService {

    /** 单层列出的上限：工作区页是给人看的，不是文件管理器 */
    private static final int MAX_ENTRIES = 300;
    /** 目录层级上限（也是全区统计的递归深度） */
    private static final int MAX_TREE_DEPTH = 8;
    /** 全区统计最多看多少个文件：给「共 N 个、合计 X MB」定住最坏耗时，不代表精确总数 */
    private static final int MAX_TREE_FILES = 5000;
    /** 一次批量整理最多带多少条 */
    private static final int MAX_BATCH = 500;
    /** 一次上传的文件数 */
    private static final int MAX_UPLOAD_FILES = 50;
    /** 单个文件名长度上限（文件系统一般 255 字节，中文一名就吃 3 倍） */
    private static final int MAX_NAME_CHARS = 100;
    /** 「移动到…」里最多列多少个文件夹 */
    private static final int MAX_DIR_CHOICES = 500;
    /** 上传大小读不到配置键时的兜底（与 config-schema 的 def 同值） */
    private static final int UPLOAD_MB_FALLBACK = 100;
    /** 全区文件名单的上限：只用来判断「产物那份拷贝是否已经在目录里」，太多也点不过来 */
    private static final int MAX_NAME_SET = 2000;

    // 「哪些类型能预览、发什么内容类型、多大算太大」收在 com.wenqu.ai.util.PreviewTypes——
    // 产物那条路也读同一份，两边各留一张清单时，同一份文件在两个页面会给出不同的按钮集。

    /** 整区导出：条数与总量上限（流式写盘，不进内存） */
    private static final int MAX_EXPORT_FILES = 2000;
    private static final long MAX_EXPORT_BYTES = 100L * 1024 * 1024;

    /** 预览地址的路径前缀，与 {@code server.servlet.context-path}（/ai）拼成完整地址——与产物那条路同一约定 */
    private static final String VIEW_PATH_PREFIX = "/ai/project-files/";
    /** token 里工作区 id 与相对路径的分隔符：换行不会出现在 UUID 里，也不会被 base64url 编码改掉 */
    private static final String TOKEN_SEP = "\n";

    private final ProjectService projectService;
    private final ConfigService configService;
    private final ImageUrlSigner imageUrlSigner;
    /**
     * 「这个工作区里有轮次正在跑」的判据，用来挡住改名与删除。
     * <p>它自己没有任何依赖，直接注入不构成环（{@code ProjectService} 那边才是惰性取的一头）。
     */
    private final ProjectTurnMutex projectTurnMutex;

    public ProjectFileService(ProjectService projectService, ConfigService configService,
                              ImageUrlSigner imageUrlSigner, ProjectTurnMutex projectTurnMutex) {
        this.projectService = projectService;
        this.configService = configService;
        this.imageUrlSigner = imageUrlSigner;
        this.projectTurnMutex = projectTurnMutex;
    }

    /**
     * 列出工作区目录里的<b>一层</b>内容，附整个目录的统计。
     *
     * <p><b>为什么从「递归平铺到第三层」改成单层</b>：旧口径下第四层往里的文件在界面上根本不存在，
     * 用户既看不见也删不掉；既然要能改名、移动、删除，就得能走到文件的真实位置——单层导航＋面包屑。
     * <p><b>为什么要带 truncated/total</b>：条数超上限时静默截断，界面看着就像「就这些文件」。
     * <p><b>全区字段（files/bytes/dirs/names）另走一次遍历</b>：它们说的是整个目录而不是当前这一层——
     * 删除弹窗要报「这里有多少东西会被删掉」，「移动到…」要一份文件夹清单，产物去重要知道全区文件名。
     * 遍历遇到隐藏文件夹就整支跳过（否则一个 {@code node_modules} 就能吃满配额，把统计变成假数）。
     *
     * @param dir 要列出的文件夹（相对工作区根；空＝根）。不存在＝这一层被别处删掉了，抛可读错误让界面重拉
     * @return {@code {dir, entries, total, truncated, files, bytes, capped, dirs, names, agentShared}}
     */
    public Map<String, Object> listWithMeta(String uid, String projectId, String dir) {
        Path base = baseDir(uid, projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Files.isDirectory(base)) {
            m.put("dir", "");
            m.put("entries", List.of());
            m.put("total", 0);
            m.put("truncated", false);
            m.put("files", 0);
            m.put("bytes", 0L);
            m.put("capped", false);
            m.put("dirs", List.of());
            m.put("names", List.of());
            m.put("agentShared", workdirSharedOf(projectId));
            return m;   // 目录不存在 = 正常空态，不是故障
        }
        Path at = existingDir(base, dir);
        Tree tree = scanTree(base);
        List<Map<String, Object>> entries = new ArrayList<>();
        int totalHere;
        try (Stream<Path> kids = Files.list(at)) {
            List<Path> visible = kids.filter(ProjectFileService::isShown).toList();
            totalHere = visible.size();
            visible.stream().filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> nameOf(p).toLowerCase(java.util.Locale.ROOT)))
                    .forEach(p -> entries.add(toDirEntry(base, p)));
            visible.stream().filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(ProjectFileService::lastModifiedMillis).reversed())
                    .forEach(p -> entries.add(toEntry(base, p, projectId)));
        } catch (IOException | RuntimeException e) {
            log.warn("[PROJECT] 工作区目录读取失败 uid={} project={} base={}: {}", uid, projectId, base, e.getMessage());
            throw new IllegalStateException("工作区目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        m.put("dir", relOf(base, at));
        m.put("entries", entries.size() > MAX_ENTRIES
                ? new ArrayList<>(entries.subList(0, MAX_ENTRIES)) : entries);
        m.put("total", totalHere);
        m.put("truncated", totalHere > MAX_ENTRIES);
        m.put("files", tree.files);
        m.put("bytes", tree.bytes);
        m.put("capped", tree.capped);
        m.put("dirs", tree.dirPaths);
        m.put("names", tree.names);
        // 这个目录是不是沙盒正在用的那一份，由统一判据（本区开关＋全局总闸，见
        // ProjectService#workdirShared）决定：不共用的时候沙盒挂 sessions/{会话}，这里读写的是
        // projects/{工作区}，两边各一份。界面必须说实话，否则用户传完材料去对话里问
        // 「读一下我传的 X」，模型只会说查无此文件（2026-10-21 复核确认的边界）。
        m.put("agentShared", workdirSharedOf(projectId));
        return m;
    }

    /** 共用目录的生效判据（项目行现查；调用方都已先过归属校验） */
    private boolean workdirSharedOf(String projectId) {
        return projectService.workdirShared(projectService.byId(projectId));
    }

    /**
     * 会话所属工作区的<b>文件简报</b>：一行式顶层清单，随工作背景段一起注入 system prompt。
     *
     * <p><b>为什么要有它</b>：检索域（kb_ids）解决「资料库里有什么」，这段解决「目录里有什么」
     * ——没有它，模型不知道工作区里躺着《考勤制度.pdf》，要么答「没有这个文件」，要么盲猜文件名。
     * 模型对工作区文件的「知道有什么」与「摸得着」（共用目录挂载）必须同时成立，
     * 所以只在 {@link ProjectService#workdirShared} 为真时注入——目录不共用时给模型看一份
     * 它读不到的清单，等于教它对着用户说谎。
     *
     * <p><b>任何失败都返回 null</b>：目录读不到（远端 provisioner）、会话没挂工作区、统计异常，
     * 一律当「这段不注入」处理——它是增益信息，不能因为读失败把整轮打挂，也不能注入半截清单。
     *
     * @return 可直接拼接的文本段；无工作区/未共用/空目录/读取失败返回 null
     */
    public String promptBriefOfSession(String sessionId) {
        try {
            Project project = projectService.projectOfSession(sessionId);
            if (project == null || !projectService.workdirShared(project)) return null;
            Path base = baseDir(project.getUid(), project.getId());
            if (!Files.isDirectory(base)) return null;
            // 顶层一层：文件夹在前（名字序）、文件按修改时间倒序——与工作区页文件栏同一排法，
            // 用户对着页面看到的顺序和模型读到的一致，引用哪个名字不会对不上
            List<String> shown = new ArrayList<>();
            int total = 0;
            try (Stream<Path> kids = Files.list(base)) {
                List<Path> visible = kids.filter(ProjectFileService::isShown).toList();
                total = visible.size();
                if (total == 0) return null;
                List<Path> dirs = new ArrayList<>(), files = new ArrayList<>();
                for (Path p : visible) (Files.isDirectory(p) ? dirs : files).add(p);
                dirs.sort(Comparator.comparing(p -> nameOf(p).toLowerCase(java.util.Locale.ROOT)));
                files.sort(Comparator.comparingLong(ProjectFileService::lastModifiedMillis).reversed());
                int budget = BRIEF_MAX_ENTRIES;
                for (Path p : dirs) {
                    if (budget-- <= 0) break;
                    shown.add(nameOf(p) + "/");
                }
                for (Path p : files) {
                    if (budget-- <= 0) break;
                    shown.add(nameOf(p));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("[PROJECT] 工作区文件简报读取失败（本轮不注入）project={}: {}", project.getId(), e.getMessage());
                return null;
            }
            StringBuilder sb = new StringBuilder("\n\n【工作区文件】本工作区目录里现有：");
            sb.append(String.join("、", shown));
            if (total > shown.size()) sb.append(" 等（共 ").append(total).append(" 项）");
            else sb.append("（共 ").append(total).append(" 项）");
            sb.append("。需要哪个文件的内容，按文件名用文件工具读取即可。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[PROJECT] 工作区文件简报构建失败（本轮不注入）session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 文件简报最多点多少个名字：它是每轮的 token 成本，清单长了挤掉真实上下文，超了给「等共 N 项」 */
    private static final int BRIEF_MAX_ENTRIES = 20;

    /** 全区统计的中间结果（一次遍历同时凑齐计数、体积、文件夹清单与文件名集合） */
    private static final class Tree {
        int files;
        long bytes;
        boolean capped;
        final List<String> dirPaths = new ArrayList<>();
        final LinkedHashSet<String> names = new LinkedHashSet<>();
    }

    private Tree scanTree(Path base) {
        Tree t = new Tree();
        try {
            Files.walkFileTree(base, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_TREE_DEPTH,
                    new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                            String rel = relOf(base, dir);
                            if (!rel.isEmpty()) {
                                if (isHiddenName(nameOf(dir))) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                                if (t.dirPaths.size() < MAX_DIR_CHOICES) t.dirPaths.add(rel);
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (isHiddenName(nameOf(file))) return java.nio.file.FileVisitResult.CONTINUE;
                            if (t.files >= MAX_TREE_FILES) {
                                t.capped = true;
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            t.files++;
                            t.bytes += sizeOf(file);
                            if (t.names.size() < MAX_NAME_SET) t.names.add(nameOf(file));
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return java.nio.file.FileVisitResult.CONTINUE;   // 走到一半被沙盒删掉：不算读不到
                        }
                    });
        } catch (IOException | RuntimeException e) {
            log.warn("[PROJECT] 工作区目录统计失败 uid={} base={}: {}", base, e.getMessage());
            throw new IllegalStateException("工作区目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        return t;
    }

    private static Map<String, Object> toDirEntry(Path base, Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "dir");
        m.put("name", nameOf(dir));
        m.put("path", relOf(base, dir));
        m.put("size", 0L);
        m.put("mtime", lastModifiedMillis(dir));
        return m;
    }

    /**
     * 按签名 token 取一个工作区文件，供浏览器<b>顶层导航</b>直接开成标签页
     * （{@code GET /ai/project-files/{token}}）。
     *
     * <p><b>为什么这里没有登录态</b>：浏览器地址栏/新标签页那一跳加不上 {@code Authorization} 头，
     * 所以这条路与产物同一套做法——地址本身就是凭据。能走到这里说明调用方交出了对<b>这个 token</b>
     * 的有效且未过期签名（HMAC，见 {@link ImageUrlSigner#verify}），签名基准是整段 token，
     * 换不了工作区也换不了文件。归属改按 token 里那个工作区行自己的 uid 解析目录。
     * <p>类型与体积上限与签发侧（{@link #toEntry}）共用同一份判据，不会出现「列表里有按钮、点开却失败」。
     *
     * @throws IllegalArgumentException token 非法/工作区不存在/路径越界/类型不支持/超出体积上限
     */
    public ViewFile openView(String token) {
        String[] parts = decodeViewToken(token);
        Path file;
        if (SESSION_VIEW_MARK.equals(parts[0])) {
            // 会话沙盒那一份：token 的剩余段是「会话 id + 换行 + 相对路径」，再拆一次
            String rest = parts[1];
            int sep = rest.indexOf(TOKEN_SEP);
            if (sep <= 0 || sep == rest.length() - 1) {
                throw new IllegalArgumentException("预览地址无效");
            }
            String sessionId = rest.substring(0, sep);
            // 归属从会话行反推——与工作区那一份同一做法：token 里只有 id，uid 由库里那一行给
            String owner = projectService.uidOfSession(sessionId);
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("会话不存在或已删除");
            }
            file = resolveIn(sessionBase(owner, sessionId), rest.substring(sep + 1));
        } else {
            Project p = projectService.byId(parts[0]);
            if (p == null) {
                throw new IllegalArgumentException("工作区不存在或已删除");
            }
            file = resolveIn(baseDirOf(p), parts[1]);
        }
        String name = file.getFileName().toString();
        String ext = extOf(name);
        String contentType = PreviewTypes.viewContentType(ext);
        if (contentType == null) {
            throw new IllegalArgumentException("该文件类型不支持预览，请下载后查看");
        }
        String capError = PreviewTypes.viewCapError(ext, sizeOf(file));
        if (capError != null) {
            throw new IllegalArgumentException(capError);
        }
        try {
            return new ViewFile(contentType, Files.readAllBytes(file), name);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 读一个工作区文件的<b>原文</b>，给预览台里「读字」那一档（Markdown / 代码 / 分隔符表）用。
     *
     * <p><b>为什么不走 {@link #openView} 那条签名地址</b>：预览台是应用内的一层，它拿得到登录态，
     * 按 {@code uid + projectId + 相对路径} 逐条校归属即可；让前端去 fetch 那份签名地址，
     * 等于把「签名 1 小时过期」和响应上那份 CSP sandbox 一起变成前端要处理的异常，
     * 而用户看到的只是「点开是空白」。
     *
     * <p><b>只读前 {@link PreviewTypes#TEXT_BODY_CAP} 字节</b>：读满整份再截断会让一次预览吃掉几十兆堆，
     * 截断标记由前端显式写成一句人话，不假装那是全文。
     *
     * @throws IllegalArgumentException 归属不符/路径越界/不是可读文本类型
     */
    public Map<String, Object> readText(String uid, String projectId, String relPath) {
        Path file = resolveDownload(uid, projectId, relPath);
        String name = file.getFileName().toString();
        String ext = extOf(name);
        if (!PreviewTypes.isReadable(ext)) {
            throw new IllegalArgumentException("该文件类型不按文本预览，请下载后查看");
        }
        long size = sizeOf(file);
        try {
            byte[] buf = new byte[(int) Math.min(size, PreviewTypes.TEXT_BODY_CAP)];
            int got;
            try (InputStream in = Files.newInputStream(file)) {
                got = in.readNBytes(buf, 0, buf.length);
            }
            boolean truncated = size > buf.length;
            String content = new String(buf, 0, got, StandardCharsets.UTF_8);
            // 截在半个汉字中间时末尾会剩一个替换符，留着像文件坏了
            if (truncated && !content.isEmpty()
                    && content.charAt(content.length() - 1) == '\uFFFD') {
                content = content.substring(0, content.length() - 1);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", name);
            m.put("ext", ext);
            m.put("size", size);
            m.put("cap", (long) PreviewTypes.TEXT_BODY_CAP);
            m.put("truncated", truncated);
            m.put("content", content);
            return m;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
        }
    }

    /** 预览结果：内容类型 + 字节 + 文件名（控制器设 Content-Disposition: inline；CSP sandbox 与 nosniff 归拦截器） */
    public record ViewFile(String contentType, byte[] bytes, String name) {
    }

    /** token = base64url(工作区 id + 换行 + 相对路径)：整段 ASCII，浏览器不再对路径做百分号编码，签发与验签的字节必然一致 */
    private static String encodeViewToken(String projectId, String relPath) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((projectId + TOKEN_SEP + relPath).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解 token → 首段 + 剩余段（只按<b>第一个</b>换行拆）。两种形态共用一条签名路：
     * 工作区＝{@code [工作区 id, 相对路径]}；会话沙盒＝{@code ["s", 会话 id + 换行 + 相对路径]}，
     * 剩余段由 {@link #openView} 再拆一次。工作区 id 是 UUID 十六进制，永远不会是 {@code s}。
     * <p>解不开一律按「预览地址无效」拒掉（不区分细节，免得给探测者回话）
     */
    private static String[] decodeViewToken(String token) {
        String raw;
        try {
            raw = new String(java.util.Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("预览地址无效");
        }
        int sep = raw.indexOf(TOKEN_SEP);
        if (sep <= 0 || sep == raw.length() - 1) {
            throw new IllegalArgumentException("预览地址无效");
        }
        return new String[]{raw.substring(0, sep), raw.substring(sep + 1)};
    }

    /**
     * 整区导出的前置检查：收集要打包的文件并校验条数/总量上限。
     *
     * <p><b>为什么与写流分成两步</b>：超限必须发生在响应头发出之前——头一开就没法再改成
     * 400 了，只能吐出一个坏掉的 zip 给用户。校验与写流分开，控制器才能先失败再开流。
     * <p>不给「少打一部分」的包：那种包看起来像全部，恰好是 truncated 提示要治的病。
     *
     * @throws IllegalArgumentException 目录不存在 / 文件数或总量超上限（可读原因）
     */
    public List<Path> collectExportFiles(String uid, String projectId) {
        Path base = baseDir(uid, projectId);
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("这个工作区还没有文件可导出");
        }
        List<Path> files = new ArrayList<>();
        long total = 0;
        try (Stream<Path> walk = Files.walk(base)) {
            Iterator<Path> it = walk.filter(Files::isRegularFile)
                    .filter(p -> !isHiddenRelative(base, p))
                    .iterator();
            while (it.hasNext()) {
                Path p = it.next();
                total += sizeOf(p);
                files.add(p);
                if (files.size() > MAX_EXPORT_FILES) {
                    throw new IllegalArgumentException("文件超过 " + MAX_EXPORT_FILES
                            + " 个，无法整区导出：请按需下载单个文件");
                }
                if (total > MAX_EXPORT_BYTES) {
                    throw new IllegalArgumentException("目录总量超过 " + (MAX_EXPORT_BYTES / 1024 / 1024)
                            + "MB，无法整区导出：请按需下载单个文件");
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            log.warn("[PROJECT] 工作区目录遍历失败 uid={} project={}: {}", uid, projectId, e.getMessage());
            throw new IllegalStateException("工作区目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        return files;
    }

    /**
     * 把 {@link #collectExportFiles} 收好的文件打成 zip 流式写出。
     * <p>流式而不是先打进内存：目录总量上限 100MB，攒成一个 byte[] 再吐出去等于白占一份内存高峰。
     */
    public void writeZip(String uid, String projectId, List<Path> files, OutputStream out) {
        Path base = baseDir(uid, projectId);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Path p : files) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(rel);
                entry.setTime(Files.getLastModifiedTime(p).toMillis());
                zip.putNextEntry(entry);
                Files.copy(p, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            log.warn("[PROJECT] 工作区导出失败 uid={} project={}: {}", uid, projectId, e.getMessage());
            throw new IllegalStateException("导出失败：" + e.getMessage());
        }
    }

    // ==================== 写侧（上传 / 新建文件夹 / 改名移动 / 删除 / 批量） ====================

    /**
     * 往工作区目录里上传文件（可多份，落到 {@code dir} 这一层）。
     *
     * <p><b>先落临时文件再改名</b>：直写目标名会让半截文件以正式名字躺在盘上——沙盒里的命令、
     * 下一轮对话都可能读到那份残缺内容，而且失败后没人知道它残缺。临时名以点开头，
     * 列表本来就不显示隐藏文件，中断了也不会留一条「看着像成功」的行。
     * <p><b>上限读配置键</b>（{@code sandbox.projectUploadMaxMb}），并钳到 spring multipart 上限之内：
     * 超过那一条会被容器直接回 413，用户看到的是一句没有原因的错误，不如在这一步说清。
     *
     * @return 每个成功文件的 {@code {name, path, size}}（前端只用来拼提示，列表照常重拉）
     */
    public List<Map<String, Object>> upload(String uid, String projectId, String dir,
                                            List<MultipartFile> files, boolean overwrite) {
        Path base = writableBase(uid, projectId, false);
        Path at = existingDir(base, dir);
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("没有收到文件");
        if (files.size() > MAX_UPLOAD_FILES) {
            throw new IllegalArgumentException("一次最多上传 " + MAX_UPLOAD_FILES + " 个文件");
        }
        long cap = uploadMaxMb() * 1024L * 1024L;
        List<Map<String, Object>> out = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = uploadedName(f);
            if (f.getSize() > cap) {
                throw new IllegalArgumentException("单个文件最大 " + uploadMaxMb()
                        + "MB，「" + name + "」有 " + (f.getSize() / 1024 / 1024) + "MB");
            }
            Path tmp = at.resolve(".upload-" + java.util.UUID.randomUUID() + ".part");
            try (InputStream in = f.getInputStream()) {
                long written = copyCapped(in, tmp, cap, name);
                Path target = at.resolve(name);
                if (Files.exists(target) && !overwrite) {
                    throw new IllegalArgumentException("「" + name + "」已经在这里，要换掉请勾上「重名时覆盖」");
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", name);
                e.put("path", relOf(base, target));
                e.put("size", written);
                out.add(e);
            } catch (IOException e) {
                throw new IllegalStateException("上传失败：" + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                    // 临时文件没删干净只是盘上多一个看不见的残件，不影响这一单的结果
                }
            }
        }
        log.info("[PROJECT] 工作区上传 uid={} project={} dir={} 个={} 覆盖={}",
                uid, projectId, relOf(base, at), out.size(), overwrite);
        return out;
    }

    /** 新建一个文件夹（父目录必须已经存在——界面只会往看得见的位置建） */
    public String createDir(String uid, String projectId, String path) {
        Path base = writableBase(uid, projectId, false);
        List<String> segs = parseRel(path, false);
        for (String seg : segs) checkNewName(seg);
        existingDir(base, parentRel(segs));
        Path target = child(base, segs);
        if (Files.exists(target)) throw new IllegalArgumentException("这里已经有同名文件夹");
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new IllegalStateException("新建文件夹失败：" + e.getMessage());
        }
        log.info("[PROJECT] 工作区新建文件夹 uid={} project={} path={}", uid, projectId, relOf(base, target));
        return relOf(base, target);
    }

    /**
     * 重命名或移动（文件和文件夹都走这一条：界面上「改名」与「换文件夹」是同一动作的两个方向）。
     *
     * @param from 现有条目的相对路径
     * @param to   目标相对路径——父目录必须已存在，且目标位置上不能有同名东西（不静默覆盖）
     */
    public String move(String uid, String projectId, String from, String to) {
        Path base = writableBase(uid, projectId, true);
        List<String> fromSegs = parseRel(from, false);
        List<String> toSegs = parseRel(to, false);
        checkNewName(toSegs.get(toSegs.size() - 1));
        Path src = existingEntry(base, fromSegs);
        Path target = child(base, toSegs);
        if (target.equals(src)) return relOf(base, src);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("目标位置已经有同名" + (Files.isDirectory(target) ? "文件夹" : "文件"));
        }
        existingDir(base, parentRel(fromSegs));
        existingDir(base, parentRel(toSegs));
        if (Files.isDirectory(src) && target.startsWith(src)) {
            throw new IllegalArgumentException("不能把文件夹放进它自己里面");
        }
        try {
            Files.move(src, target);
        } catch (IOException e) {
            throw new IllegalStateException("改名失败：" + e.getMessage());
        }
        log.info("[PROJECT] 工作区条目改名/移动 uid={} project={} {} -> {}", uid, projectId, from, relOf(base, target));
        return relOf(base, target);
    }

    /**
     * 删除条目：文件直接删，文件夹连里面的内容一起删。
     * <p>已经不存在的条目按「目标已达成」跳过——批量删的中途被沙盒清掉是常事，报错只会让人以为整批没删。
     */
    public int deleteEntries(String uid, String projectId, List<String> paths) {
        Path base = writableBase(uid, projectId, true);
        requireBatch(paths);
        int deleted = 0;
        for (String raw : paths) {
            List<String> segs = parseRel(raw, false);
            Path cand = child(base, segs);
            if (Files.notExists(cand)) continue;
            if (Files.isSymbolicLink(cand)) {
                throw new IllegalArgumentException("「" + nameOf(cand) + "」是软链接，不在这里删");
            }
            try {
                deleteTree(cand);
                deleted++;
            } catch (IOException e) {
                throw new IllegalStateException("删除失败（可能正被占用）：「" + nameOf(cand) + "」" + e.getMessage());
            }
        }
        log.info("[PROJECT] 删除工作区条目 uid={} project={} 请求={} 实删={}", uid, projectId, paths.size(), deleted);
        return deleted;
    }

    /** 批量把若干条目挪进同一个文件夹；撞上同名就停手并报出那个名字（宁可少挪，不要静默丢） */
    public int batchMove(String uid, String projectId, List<String> paths, String destDir) {
        Path base = writableBase(uid, projectId, true);
        requireBatch(paths);
        Path dest = existingDir(base, destDir);
        String destLabel = relOf(base, dest);
        int moved = 0;
        for (String raw : paths) {
            List<String> segs = parseRel(raw, false);
            String name = segs.get(segs.size() - 1);
            checkNewName(name);
            Path src = existingEntry(base, segs);
            if (dest.equals(src.getParent())) {
                moved++;   // 本来就在这个文件夹里，等于没动
                continue;
            }
            if (Files.isDirectory(src) && dest.startsWith(src)) {
                throw new IllegalArgumentException("不能把文件夹「" + name + "」放进它自己里面");
            }
            Path target = dest.resolve(name).toAbsolutePath().normalize();
            if (!target.startsWith(base)) throw new IllegalArgumentException("非法文件路径");
            if (Files.exists(target)) {
                throw new IllegalArgumentException("「" + name + "」在"
                        + (destLabel.isEmpty() ? "根目录" : "「" + destLabel + "」") + "里已经存在");
            }
            try {
                Files.move(src, target);
                moved++;
            } catch (IOException e) {
                throw new IllegalStateException("移动失败：" + e.getMessage());
            }
        }
        log.info("[PROJECT] 批量移动 uid={} project={} -> {} 项={}", uid, projectId, destLabel, moved);
        return moved;
    }

    // ==================== 写侧的校验件 ====================

    /**
     * 写侧的目录根：过写闸，并确保目录已经在盘上。
     *
     * <p><b>为什么读侧不建、写侧要建</b>：新建的工作区从没跑过沙盒时，{@code projects/{id}} 在盘上根本
     * 不存在——列表把「还没有文件」当正常空态是对的，但用户第一次点「上传」或「新建文件夹」时，
     * 报「工作区目录还不存在」等于让人自己去创建平台内部目录（2026-10-21 接口冒烟当场抓到）。
     *
     * <p><b>属主这条边界（没处理，也别再写一段假装处理的代码）</b>：应用进程建出来的目录与文件归应用
     * 用户，而沙盒运行时用户是 uid 1000；provisioner 每次建容器只把<b>目录本身</b> chown 给 1000
     * （{@code _prepare_project_workdir}），不动里面的文件。于是「先上传、再让模型改这一份」在 Linux
     * 宿主上可能读得到、改不动。Java NIO 只能按名字查属主、拿不到数字 uid，应用侧无法按同一口径
     * chown；把权限放宽成全局可写是拿权限边界换功能，不该顺手做。容器回收后重建即自愈；
     * 这条写进手册与 docs 的已知边界，不藏在注释里。
     */
    private Path writableBase(String uid, String projectId, boolean relocation) {
        Path base = baseDirOf(writableProject(uid, projectId, relocation));
        if (Files.isDirectory(base)) return base;
        try {
            Files.createDirectories(base);
            log.info("[PROJECT] 工作区目录按需建立 uid={} project={} base={}", uid, projectId, base);
        } catch (IOException e) {
            throw new IllegalStateException("工作区目录创建失败：" + e.getMessage());
        }
        return base;
    }

    /**
     * 取本人工作区并过写闸（返回工作区行本身，省得调用方再查一次库）。
     *
     * <p>归档区一概不写——与「归档不接新会话」同一个「收工」的口径，否则归档只是把侧栏折起来而已。
     * <p>{@code relocation}（改名、移动、删除）还要求这个工作区当前没有轮次在跑：那一轮以为某个文件
     * 还在原位，中途把名字抽走会让它读到半截或干脆报找不到。上传与新建文件夹不挡——往正在跑的
     * 目录里补一份材料是合法动作，也正是这条路径最需要的能力。
     */
    private Project writableProject(String uid, String projectId, boolean relocation) {
        Project p = projectService.detail(uid, projectId);
        if (Project.STATUS_ARCHIVED.equals(p.getStatus())) {
            throw new IllegalArgumentException("工作区已归档：恢复后才能整理这里的文件");
        }
        if (relocation && projectTurnMutex.isBusy(p.getId())) {
            throw new IllegalStateException("这个工作区里有任务正在跑，等它结束后再改名或删除文件");
        }
        return p;
    }

    private static void requireBatch(List<String> paths) {
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("没有选中任何文件");
        if (paths.size() > MAX_BATCH) throw new IllegalArgumentException("一次最多整理 " + MAX_BATCH + " 项");
    }

    /**
     * 拆安全段：绝对路径、{@code ..}、{@code .}、空段、NUL 一律拒；根目录只在 rootAllowed 时返回空段集。
     * <p>不能只靠「normalize 之后再比前缀」兜底：改名与新建给出的是<b>还不存在</b>的路径，
     * 那一步没有实体可 resolve，只能按段判。
     */
    private static List<String> parseRel(String rel, boolean rootAllowed) {
        String raw = (rel == null ? "" : rel.trim()).replace('\\', '/');
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        if (raw.isEmpty()) {
            if (rootAllowed) return List.of();
            throw new IllegalArgumentException("缺少文件路径");
        }
        if (raw.startsWith("/")) throw new IllegalArgumentException("非法文件路径");
        List<String> out = new ArrayList<>();
        for (String seg : raw.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("非法文件路径");
            }
            out.add(seg);
        }
        if (out.size() > MAX_TREE_DEPTH) {
            throw new IllegalArgumentException("层级太深（最多 " + MAX_TREE_DEPTH + " 层）");
        }
        return out;
    }

    /** 要落到盘上的那一段名字：长度与「传进去就看不见」都在这里挡 */
    private static void checkNewName(String seg) {
        if (seg.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("名称太长（最多 " + MAX_NAME_CHARS + " 字）");
        }
        if (isHiddenName(seg)) {
            throw new IllegalArgumentException("「" + seg + "」这一类名字在这里不显示（隐藏文件与依赖目录），换一个");
        }
    }

    /** 列表不显示的名字：点开头、依赖缓存目录——新建与上传用同样的判据，否则传进去就永远找不着 */
    private static boolean isHiddenName(String name) {
        return name.startsWith(".") || name.equals("node_modules") || name.equals("__pycache__");
    }

    /** 界面上看得见的一条：不是软链（点开必然被越界校验拒掉，与其给个坏按钮不如不列）、不是隐藏名、是文件或文件夹 */
    private static boolean isShown(Path p) {
        return !Files.isSymbolicLink(p) && !isHiddenName(nameOf(p))
                && (Files.isRegularFile(p) || Files.isDirectory(p));
    }

    private static String nameOf(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }

    private static String relOf(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    /** 去掉最后一段＝父目录的相对路径（只有一段时为空串，代表根） */
    private static String parentRel(List<String> segs) {
        return String.join("/", segs.subList(0, segs.size() - 1));
    }

    /** 按段重拼出目录内的绝对路径（只做越界校验，不要求存在——改名给的就是新位置） */
    private static Path child(Path base, List<String> segs) {
        Path p = base.toAbsolutePath().normalize();
        for (String seg : segs) p = p.resolve(seg);
        p = p.normalize();
        if (!p.startsWith(base)) throw new IllegalArgumentException("非法文件路径");
        return p;
    }

    /**
     * 解析一个<b>已存在</b>的文件夹，并用真实路径再比一次前缀。
     * <p>{@code ../} 与绝对路径已被 {@link #parseRel} 挡掉，这一层管的是另一件事：路径里某一段
     * 是软链（沙盒里 {@code ln -s /etc/x y} 就能把 {@code y/} 变成系统目录），文本前缀比对看不出来。
     */
    private static Path existingDir(Path base, String rel) {
        List<String> segs = parseRel(rel, true);
        Path p = child(base, segs);
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(rel == null || rel.isBlank()
                    ? "工作区目录还不存在" : "文件夹不存在（可能刚被删掉）：" + rel);
        }
        try {
            if (!p.toRealPath().startsWith(base.toRealPath())) throw new IllegalArgumentException("非法文件路径");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("读不到这个文件夹：" + e.getMessage());
        }
        return p;
    }

    /** 解析一个<b>已存在</b>的条目（文件或文件夹）；软链不当作可管理的对象 */
    private static Path existingEntry(Path base, List<String> segs) {
        Path p = child(base, segs);
        if (Files.notExists(p)) throw new IllegalArgumentException("「" + segs.get(segs.size() - 1) + "」已经不在了");
        if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("「" + nameOf(p) + "」是软链接，不在这里改");
        return p;
    }

    /** 上传来的名字：浏览器可能把整个源路径一起塞进 filename（选文件夹上传时），只留末段再验一遍 */
    private static String uploadedName(MultipartFile f) {
        String raw = f.getOriginalFilename();
        String name = raw == null ? "" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("文件名不合法：" + raw);
        }
        checkNewName(name);
        return name;
    }

    /** 上传单文件上限（MB）：管理员在系统设置里改，读不到就按默认；钳到 256 与 spring multipart 对齐 */
    private int uploadMaxMb() {
        int mb = configService.getInt("sandbox.projectUploadMaxMb", UPLOAD_MB_FALLBACK);
        if (mb <= 0) return UPLOAD_MB_FALLBACK;
        return Math.min(mb, 256);
    }

    /** 边写边数：声明大小可能撒谎（或被中间层改过），超限必须当场放弃而不是先落一份超的再删 */
    private static long copyCapped(InputStream in, Path out, long cap, String name) throws IOException {
        long total = 0;
        byte[] buf = new byte[16 * 1024];
        try (OutputStream os = Files.newOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) {
                    throw new IllegalArgumentException("「" + name + "」超过上限 " + (cap / 1024 / 1024) + "MB，已放弃");
                }
                os.write(buf, 0, n);
            }
        }
        return total;
    }

    /** 删一条：文件直接删；文件夹自底向上连内容一起删（不跟随软链，walk 默认就把软链当普通条目） */
    private static void deleteTree(Path entry) throws IOException {
        if (!Files.isDirectory(entry) || Files.isSymbolicLink(entry)) {
            Files.delete(entry);
            return;
        }
        try (Stream<Path> walk = Files.walk(entry)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static String extOf(String name) {
        return PreviewTypes.extOf(name);
    }

    /**
     * 解析一个可下载的文件。任何越界尝试都抛 {@link IllegalArgumentException}（控制器转 400）。
     *
     * @param relPath 相对工作区目录的路径（由 {@link #list} 返回的那一份，不可信任，必须重校验）
     */
    public Path resolveDownload(String uid, String projectId, String relPath) {
        return resolveIn(baseDir(uid, projectId), relPath);   // baseDir 里含归属校验
    }

    /** 在给定工作区目录内解析一个普通文件（归属与否由调用方决定，越界与存在性在这里查） */
    private static Path resolveIn(Path baseDir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        if (relPath.contains("\u0000")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(relPath).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法文件路径");
        }
        // 关键校验：normalize 之后必须仍在目录内（../ 与绝对路径都会在这里被拦下）
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        // 比**真实路径**，不是比文本前缀，也不是只查末段是不是软链：沙盒里 `ln -s /etc evil` 之后，
        // {@code evil/passwd} 的末段是个真实文件、{@code isSymbolicLink} 为 false，文本前缀比对也过得去，
        // 于是 /etc/passwd 能从「下载」口被递出去（2026-10-21 接口冒烟实测：下载回了 200）。
        try {
            if (!target.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("非法文件路径");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.nio.file.NoSuchFileException e) {
            throw new IllegalArgumentException("文件不存在");
        } catch (IOException e) {
            throw new IllegalArgumentException("读不到这个文件");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return target;
    }

    /** 工作区目录的宿主路径（先按登录 uid 校验归属，别人的工作区直接抛错） */
    private Path baseDir(String uid, String projectId) {
        return baseDirOf(projectService.detail(uid, projectId));
    }

    /** 工作区目录的宿主路径：user-data/shared/{uidDirname}/workspace/{workdirPath}（不再校验归属） */
    private Path baseDirOf(Project p) {
        String workdir = p.getWorkdirPath();
        if (workdir == null || workdir.isBlank()) {
            workdir = "projects/" + p.getId();
        }
        Path workspaceRoot = workspaceRootOf(p.getUid());
        Path base = workspaceRoot.resolve(workdir).toAbsolutePath().normalize();
        // workdir 来自库里的 workdir_path（形如 projects/{id}），仍然过一遍越界校验
        if (!base.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("工作区目录配置异常");
        }
        return base;
    }

    // ==================== 会话沙盒目录（没挂工作区的那一路） ====================

    /**
     * 会话沙盒目录的宿主路径：{@code user-data/shared/{uidDirname}/workspace/sessions/{sessionId}}。
     *
     * <p><b>为什么要单独开这一路</b>：沙盒的工作目录按会话切分时（会话没挂工作区，或
     * {@code sandbox.projectWorkdirEnabled} 关着），模型写出来的文件躺在 {@code sessions/{会话}} 下，
     * 而工作区文件接口读的是 {@code projects/{工作区}}——两边各一份，界面上<b>没有任何一处</b>能看到它。
     * 真机 2026-10-23 21:04 那一轮做好的 20.7 KB 看板页就是这么躺在盘上、用户在应用里取不出来的。
     *
     * <p><b>归属为什么不用查库</b>：目录名用的是<b>请求者自己的</b> uid（{@code RequestUser.uid()}），
     * 别人的会话根本不在同一个人的 shared 目录下；会话 id 再过一个路径安全字符集（与
     * {@link SandboxPaths} 给 uid 用的那道同一判据），拼出来的路径出不了本人 workspace 根。
     */
    private Path sessionBase(String uid, String sessionId) {
        Path workspaceRoot = workspaceRootOf(uid);
        String id = sessionId == null ? "" : sessionId.strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("缺少会话标识");
        }
        if (!SESSION_ID_RE.matcher(id).matches()) {
            throw new IllegalArgumentException("非法会话标识");
        }
        Path base = workspaceRoot.resolve("sessions").resolve(id).toAbsolutePath().normalize();
        if (!base.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("非法会话标识");
        }
        return base;
    }

    /** 本人 workspace 根（工作区目录与会话沙盒目录共同的上级，越界校验以它为基准） */
    private Path workspaceRootOf(String uid) {
        String root = configService.get("sandbox.userDataHostDir");
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir") + "/user-data";
        }
        return Path.of(root, "shared", SandboxPaths.uidDirname(uid), "workspace").toAbsolutePath().normalize();
    }

    /**
     * 列出本会话沙盒目录里的<b>文件</b>（递归、按修改时间倒序，最新那份在最前面）。
     *
     * <p>刻意与工作区那份不同形：这里不做单层导航，也不列文件夹——接口只交一维的文件清单，
     * 「哪条路径下有几个文件、合计多大」由前端按相对路径折成目录树去算（目录不是文件系统里的
     * 第二份事实，多回一次就多一处对账）。它回答的是「助手刚才做出来的东西在哪」这一个问题，
     * 不是一台文件管理器。
     *
     * <p><b>空与读不到分开报</b>：目录不存在＝这一轮确实还没写过东西（正常空态）；而连本人 workspace 根
     * 都摸不到（沙盒在另一台机器上）要抛错——把故障写成「暂无文件」，用户读到的就是「我的文件没了」。
     */
    public Map<String, Object> listSessionFiles(String uid, String sessionId) {
        Path base = sessionBase(uid, sessionId);
        Path workspaceRoot = base.getParent().getParent();   // .../workspace
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("沙盒目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        List<Map<String, Object>> files = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> walk = Files.walk(base, MAX_TREE_DEPTH)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !isHiddenRelative(base, p))
                        .sorted(Comparator.comparingLong(ProjectFileService::lastModifiedMillis).reversed())
                        .forEach(p -> files.add(toSessionEntry(base, p, sessionId)));
            } catch (IOException | RuntimeException e) {
                log.warn("[PROJECT] 会话沙盒目录读取失败 uid={} session={} base={}: {}",
                        uid, sessionId, base, e.getMessage());
                throw new IllegalStateException("沙盒目录暂时读不到（沙盒可能部署在另一台机器上）");
            }
        }
        int total = files.size();
        m.put("files", total > MAX_ENTRIES ? new ArrayList<>(files.subList(0, MAX_ENTRIES)) : files);
        m.put("total", total);
        m.put("truncated", total > MAX_ENTRIES);
        return m;
    }

    /** 会话沙盒里一个文件的下载路径（归属由请求者自己的 uid 目录保证，见 {@link #sessionBase}） */
    public Path resolveSessionDownload(String uid, String sessionId, String relPath) {
        return resolveIn(sessionBase(uid, sessionId), relPath);
    }

    private static final java.util.regex.Pattern SESSION_ID_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private Map<String, Object> toSessionEntry(Path base, Path file, String sessionId) {
        String rel = relOf(base, file);
        String name = nameOf(file);
        long size = sizeOf(file);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "file");
        m.put("path", rel);
        m.put("name", name);
        m.put("ext", extOf(name));
        m.put("size", size);
        m.put("mtime", lastModifiedMillis(file));
        // 与工作区同一判据：只有「点得开」的类型才给预览地址，其余只下载（点开掉出一份下载比不给按钮更糊弄人）
        if (PreviewTypes.viewContentType(extOf(name)) != null && PreviewTypes.viewCapError(extOf(name), size) == null) {
            m.put("viewUrl", imageUrlSigner.signUrlRequired(
                    VIEW_PATH_PREFIX + encodeSessionViewToken(sessionId, rel)));
        }
        return m;
    }

    /**
     * 会话作用域的预览 token：{@code "s" + 换行 + 会话 id + 换行 + 相对路径}。
     * <p>比工作区那份多一个作用域标记、多带一个 uid 之外的定位段——顶层导航没有登录态，
     * 凭据就是这条地址自己的签名（与工作区那条同一口径，见类注释边界 1 的唯一例外）。
     * 工作区的 id 是 UUID 十六进制，永远不会等于标记 {@code s}，两种形态共存不需要改老地址。
     */
    private static String encodeSessionViewToken(String sessionId, String relPath) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((SESSION_VIEW_MARK + TOKEN_SEP + sessionId + TOKEN_SEP + relPath)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static final String SESSION_VIEW_MARK = "s";


    /**
     * 删除整个工作区目录（删工作区时用户显式选择「连文件一起删」才走这里）。
     *
     * <p><b>为什么先删文件再删行</b>：行删了文件没删掉，就再也找不到这个目录了
     * （工作区 id 只在已删的行里）——磁盘泄漏且无法补救。所以文件删除失败即抛，
     * 让工作区行保留，用户可以重试。
     * <p>目录不存在算成功（从没跑过沙盒的工作区本该如此）；路径校验与下载同口径
     * （{@code baseDir} 已做归属 + 越界双重校验）。
     *
     * @throws IllegalStateException 目录删除失败（控制器转可读错误，工作区行不动）
     */
    public void deleteWorkdir(String uid, String projectId) {
        Path base = baseDir(uid, projectId);
        if (!Files.exists(base)) return;
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("工作区目录不是文件夹，拒绝删除：" + base);
        }
        try (Stream<Path> walk = Files.walk(base)) {
            // 深度优先：先删子文件再删目录（反序即自底向上）
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.delete(p);
            }
            log.info("[PROJECT] 工作区目录已删除 uid={} project={} base={} 条目={}", uid, projectId, base, all.size());
        } catch (IOException | RuntimeException e) {
            log.warn("[PROJECT] 工作区目录删除失败 uid={} project={} base={}: {}", uid, projectId, base, e.getMessage());
            throw new IllegalStateException("工作区文件删除失败（目录可能仍被占用），工作区未删除，可重试");
        }
    }

    private static boolean isHiddenRelative(Path base, Path file) {
        Path rel = base.relativize(file);
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.startsWith(".") || s.equals("node_modules") || s.equals("__pycache__")) return true;
        }
        return false;
    }

    private static long lastModifiedMillis(Path p) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            Instant i = t.toInstant();
            return i.toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> toEntry(Path base, Path file, String projectId) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 统一用 POSIX 分隔符：前端展示与下载参数都用这一份，避免平台差异
        String rel = relOf(base, file);
        String name = nameOf(file);
        long size = sizeOf(file);
        m.put("kind", "file");
        m.put("path", rel);
        m.put("name", name);
        m.put("size", size);
        m.put("mtime", lastModifiedMillis(file));
        // 预览地址：只有「点得开」的类型才给，其余仅下载（点了掉出一份下载比不给按钮更糊弄人）
        String ext = extOf(name);
        if (PreviewTypes.viewContentType(ext) != null && PreviewTypes.viewCapError(ext, size) == null) {
            m.put("viewUrl", imageUrlSigner.signUrlRequired(VIEW_PATH_PREFIX + encodeViewToken(projectId, rel)));
        }
        return m;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0L;
        }
    }
}
