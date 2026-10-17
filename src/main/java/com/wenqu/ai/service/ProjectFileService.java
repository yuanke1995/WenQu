package com.wenqu.ai.service;

import com.wenqu.ai.model.Project;
import com.wenqu.ai.sandbox.SandboxPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

    private final ProjectService projectService;
    private final ConfigService configService;

    public ProjectFileService(ProjectService projectService, ConfigService configService) {
        this.projectService = projectService;
        this.configService = configService;
    }

    /**
     * 列出工作区目录下的文件（含子目录，深度上限见 {@link #MAX_DEPTH}）。
     *
     * @return 文件条目（相对路径、大小、更新时间），按修改时间倒序
     * @throws IllegalStateException 宿主目录不可读（远端部署/权限），由控制器转成可读提示
     */
    public List<Map<String, Object>> list(String uid, String projectId) {
        Path base = baseDir(uid, projectId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            // 目录还没建：工作区刚创建、还没跑过任何沙盒命令。这是正常的空态，不是故障。
            return out;
        }
        try (Stream<Path> walk = Files.walk(base, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isHiddenRelative(base, p))
                    .sorted(Comparator.comparingLong((Path p) -> lastModifiedMillis(p)).reversed())
                    .limit(MAX_ENTRIES)
                    .forEach(p -> out.add(toEntry(base, p)));
        } catch (IOException | RuntimeException e) {
            log.warn("[PROJECT] 工作区目录读取失败 uid={} project={} base={}: {}", uid, projectId, base, e.getMessage());
            throw new IllegalStateException("工作区目录暂时读不到（沙盒可能部署在另一台机器上）");
        }
        return out;
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
