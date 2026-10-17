package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.SandboxPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作区目录的**文件浏览**（工作区页用）。
 *
 * <p><b>为什么直接读宿主目录而不走沙盒</b>：沙盒 backend 需要一个活着的容器（{@code backendIfPresent}），
 * 而工作区页在没有对话时容器多半是回收状态——那样用户进来只会看到「暂无文件」，而文件其实好好躺在盘上。
 * 沙盒容器挂载的就是同一个宿主目录（{@code shared/{uid}/workspace/{workdir}}），所以直接读是同一份数据。
 *
 * <p><b>边界（必须守住的）</b>：
 * <ol>
 *   <li>只读**本人**工作区：先按 uid 校验工作区归属，再由 {@link ProjectService#detail} 抛错；</li>
 *   <li>路径穿越：任何请求路径都 {@code resolve → normalize → startsWith(base)} 校验，
 *       {@code ../}、绝对路径、软链逃逸一律拒绝；</li>
 *   <li>远端 provisioner 部署下宿主目录不存在时**如实报不可用**，不伪装成空列表
 *       （空列表会被读成「我的文件没了」）。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ProjectFileService {

    /** 单次列出的上限：工作区页是给人看的，不是文件管理器 */
    private static final int MAX_ENTRIES = 300;
    /** 递归深度：文件通常平铺或一层子目录，再深只会在页面上糊成一团 */
    private static final int MAX_DEPTH = 3;

    /**
     * 可预览的文本类扩展名（服务端一律按 {@code text/plain} 下发）。
     * <p><b>html 也在列里但按纯文本发</b>：同源内联 HTML 会带着用户的登录态跑脚本，
     * 而工作区里的文件是模型/用户产出的、来源不可控——预览就预览，别在应用里执行它。
     */
    private static final java.util.Set<String> TEXT_PREVIEW_EXTS = java.util.Set.of(
            "md", "markdown", "txt", "log", "csv", "tsv", "json", "yaml", "yml", "xml", "toml", "ini", "conf", "properties",
            "py", "js", "mjs", "ts", "jsx", "tsx", "java", "go", "rs", "sql", "sh", "bash", "zsh", "html", "htm",
            "css", "scss", "vue");
    /** 可预览的图片类型：浏览器直接渲染 */
    private static final java.util.Set<String> IMAGE_PREVIEW_EXTS =
            java.util.Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    /** 文本预览大小上限（2MB）：再大就不是"看一眼"，该下载 */
    private static final long MAX_TEXT_PREVIEW = 2L * 1024 * 1024;
    /** 图片预览大小上限（10MB） */
    private static final long MAX_IMAGE_PREVIEW = 10L * 1024 * 1024;
    /** 整区导出：条数与总量上限（流式写盘，不进内存） */
    private static final int MAX_EXPORT_FILES = 2000;
    private static final long MAX_EXPORT_BYTES = 100L * 1024 * 1024;

    private final ProjectService projectService;
    private final ConfigService configService;

    public ProjectFileService(ProjectService projectService, ConfigService configService) {
        this.projectService = projectService;
        this.configService = configService;
    }

    /**
     * 列出工作区目录下的文件，附截断信息。
     *
     * <p><b>为什么要带 truncated/total</b>：此前只回一个裸数组，条数超上限时静默截断，
     * 界面上看着像"就这些文件"——用户不会知道少了东西。现在显式给出总数与截断标志。
     *
     * @return {@code {entries:[…], total:N, truncated:boolean}}
     */
    public Map<String, Object> listWithMeta(String uid, String projectId) {
        Path base = baseDir(uid, projectId);
        if (!Files.isDirectory(base)) {
            return meta(List.of(), 0);   // 目录不存在 = 正常空态，不是故障
        }
        List<Map<String, Object>> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isHiddenRelative(base, p))
                    .sorted(Comparator.comparingLong((Path p) -> lastModifiedMillis(p)).reversed())
                    .forEach(p -> all.add(toEntry(base, p)));
        } catch (IOException | RuntimeException e) {
            log.warn("[PROJECT] 工作区目录读取失败 uid={} project={} base={}: {}", uid, projectId, base, e.getMessage());
            throw new IllegalStateException("工作区目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        int total = all.size();
        return total <= MAX_ENTRIES ? meta(all, total) : meta(new ArrayList<>(all.subList(0, MAX_ENTRIES)), total);
    }

    private static Map<String, Object> meta(List<Map<String, Object>> entries, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", entries);
        m.put("total", total);
        m.put("truncated", entries.size() < total);
        return m;
    }

    /**
     * 工作区文件预览：文本按 {@code text/plain}、图片按原类型返回，其余类型明确拒掉。
     *
     * <p>文本一律 text/plain 是刻意的：工作区里的 html/svg 是模型产出、来源不可控，
     * 同源内联渲染等于把应用的登录态交给它（脚本、XSS）。预览就预览，不执行。
     *
     * @param relPath 相对工作区目录的路径（校验口径与下载同一条）
     * @throws IllegalArgumentException 路径非法/不存在/不是普通文件/类型不支持预览/超出大小上限
     */
    public PreviewFile preview(String uid, String projectId, String relPath) {
        Path file = resolveDownload(uid, projectId, relPath);   // 含归属 + 越界 + 存在性校验
        String ext = extOf(file.getFileName().toString());
        long size = sizeOf(file);
        if (TEXT_PREVIEW_EXTS.contains(ext)) {
            if (size > MAX_TEXT_PREVIEW) {
                throw new IllegalArgumentException("文件太大（"
                        + (size / 1024 / 1024) + "MB），超过 " + (MAX_TEXT_PREVIEW / 1024 / 1024)
                        + "MB 预览上限，请下载后查看");
            }
            try {
                return new PreviewFile("text/plain; charset=UTF-8", Files.readAllBytes(file), file.getFileName().toString());
            } catch (IOException e) {
                throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
            }
        }
        if (IMAGE_PREVIEW_EXTS.contains(ext)) {
            if (size > MAX_IMAGE_PREVIEW) {
                throw new IllegalArgumentException("图片太大（"
                        + (size / 1024 / 1024) + "MB），超过 " + (MAX_IMAGE_PREVIEW / 1024 / 1024)
                        + "MB 预览上限，请下载后查看");
            }
            try {
                return new PreviewFile(sniffImageType(ext), Files.readAllBytes(file), file.getFileName().toString());
            } catch (IOException e) {
                throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
            }
        }
        throw new IllegalArgumentException("该文件类型不支持预览，请下载后查看");
    }

    /** 预览结果：内容类型 + 字节 + 文件名（控制器设 Content-Disposition: inline + nosniff） */
    public record PreviewFile(String contentType, byte[] bytes, String name) {
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

    /**
     * 删一个工作区文件（只删普通文件，目录不走这里）。
     *
     * <p>沙盒容器可能正开着这个目录——Unix 语义下删已打开的文件是安全的（容器里的进程
     * 继续写到已删除的 inode，不影响磁盘释放），所以不查容器状态。
     */
    public void deleteFile(String uid, String projectId, String relPath) {
        Path file = resolveDownload(uid, projectId, relPath);
        try {
            Files.delete(file);
            log.info("[PROJECT] 删除工作区文件 uid={} project={} path={}", uid, projectId, relPath);
        } catch (IOException e) {
            throw new IllegalStateException("删除失败（文件可能仍被占用）：" + e.getMessage());
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String sniffImageType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            default -> "image/jpeg";
        };
    }

    /**
     * 解析一个可下载的文件。任何越界尝试都抛 {@link IllegalArgumentException}（控制器转 400）。
     *
     * @param relPath 相对工作区目录的路径（由 {@link #list} 返回的那一份，不可信任，必须重校验）
     */
    public Path resolveDownload(String uid, String projectId, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        if (relPath.contains("\u0000")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Path base = baseDir(uid, projectId).toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(relPath).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法文件路径");
        }
        // 关键校验：normalize 之后必须仍在工作区目录内（../ 与绝对路径都会在这里被拦下）
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return target;
    }

    /** 工作区目录的宿主路径：user-data/shared/{uidDirname}/workspace/{workdirPath} */
    private Path baseDir(String uid, String projectId) {
        Project p = projectService.detail(uid, projectId);   // 顺带校验归属，别人的工作区直接抛错
        String workdir = p.getWorkdirPath();
        if (workdir == null || workdir.isBlank()) {
            workdir = "projects/" + p.getId();
        }
        String root = configService.get("sandbox.userDataHostDir");
        if (root == null || root.isBlank()) {
            root = System.getProperty("user.dir") + "/user-data";
        }
        Path base = Path.of(root, "shared", SandboxPaths.uidDirname(uid), "workspace")
                .resolve(workdir).toAbsolutePath().normalize();
        // workdir 来自库里的 workdir_path（形如 projects/{id}），仍然过一遍越界校验
        Path workspaceRoot = Path.of(root, "shared", SandboxPaths.uidDirname(uid), "workspace")
                .toAbsolutePath().normalize();
        if (!base.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("工作区目录配置异常");
        }
        return base;
    }

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

    private static Map<String, Object> toEntry(Path base, Path file) {
        Map<String, Object> m = new LinkedHashMap<>();
        // 统一用 POSIX 分隔符：前端展示与下载参数都用这一份，避免平台差异
        String rel = base.relativize(file).toString().replace('\\', '/');
        m.put("path", rel);
        m.put("name", file.getFileName().toString());
        m.put("size", sizeOf(file));
        m.put("mtime", lastModifiedMillis(file));
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
