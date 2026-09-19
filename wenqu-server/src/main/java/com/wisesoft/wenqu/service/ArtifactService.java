package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.agents.ResolvedSkill;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.FilePreviewUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.workspace.Workspace;
import com.wisesoft.wenqu.workspace.WorkspaceErrors;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 线程 artifact 下载与保存用例（services/artifact_service.py 全量移植）。
 *
 * <p>逐函数翻译：{@code _normalize_artifact_path} / {@code _require_skill_artifact_access} /
 * {@code _copy_skill_file_to_path} / {@code _copy_artifact_to_path} /
 * {@code resolve_thread_artifact_view} / {@code save_thread_artifact_to_workspace_view}，
 * 含模块级常量 {@code MAX_ARTIFACT_DOWNLOAD_BYTES}（1 GiB）、
 * {@code MAX_SAVED_ARTIFACT_NAME_ATTEMPTS}（1000）、
 * {@code DEFAULT_ARTIFACT_DESTINATION}（{@code /saved_artifacts}）。
 *
 * <p><b>必要替换（显式标注，非遗漏）</b>：
 * <ul>
 *   <li>FastAPI {@code HTTPException(status_code, detail)} → {@link ApiHttpException}
 *       （响应体同为 {@code {"detail": ...}}，状态码与文案逐字照搬）。</li>
 *   <li>{@code FileResponse}/{@code StreamingResponse} 属传输层 → 服务层返回中立载体
 *       （与 {@link WorkspaceService}/{@link ViewerFilesystemService} 同一约定）：
 *       预览分支返回 {@link FilePreviewService#renderFilePreview} 的结果
 *       （文本场景为 {@code Map}，二进制场景为 {@link FilePreviewService.BinaryPreview}），
 *       超出预览上限时返回 {@link FilePreviewUtils#previewTooLarge} 的 payload（{@code Map}），
 *       下载分支返回 {@link ArtifactDownload}（控制器据此装配响应，并负责删除临时文件，
 *       对应参考实现的 {@code BackgroundTask(os.unlink, temp_path)}）。</li>
 *   <li>{@code asyncio.to_thread(...)} → 直接同步调用（本服务为阻塞式 Spring Bean 方法，
 *       输出一致，仅执行线程不同）。</li>
 *   <li>{@code tempfile.mkstemp(prefix="yuxi-artifact-"/"yuxi-save-artifact-")} →
 *       {@link Files#createTempFile}，前缀按本产品命名改为 {@code wenqu-artifact-} /
 *       {@code wenqu-save-artifact-}（与 {@link WorkspaceService} 的临时文件前缀约定一致）。</li>
 *   <li>{@code open_regular_file_fd} → {@link SafeFiles#openRegularFile}
 *       （Java NIO 无目录 fd 钉定能力，安全性质等价说明见 {@link SafeFiles} 类注解）。</li>
 *   <li>{@code os.open(target, O_WRONLY|O_TRUNC|O_NOFOLLOW)} →
 *       {@link Files#newOutputStream}（{@code WRITE + TRUNCATE_EXISTING + NOFOLLOW_LINKS}），
 *       并额外以 {@link Files#isSymbolicLink} 显式拒绝符号链接目标。</li>
 *   <li>{@code urllib.parse.quote} → {@link FilePreviewService#urlEncodeUtf8}；
 *       {@code Content-Disposition} 沿用本工程既有 {@code filename*=UTF-8''<pct>} 形式
 *       （与 {@link WorkspaceService.WorkspaceDownload}/{@link ViewerFilesystemService.ViewerDownload} 一致）。</li>
 * </ul>
 *
 * <p><b>异常粒度差（继承自 Workspace 层，已在 {@code Workspace} 类注解标注）</b>：
 * 参考实现的 {@code NotADirectoryError} 在 {@link Workspace#openDirectory} 中被并入
 * {@link Workspace.PermissionDeniedRuntime}。因此需要区分「不是目录」的分支
 * （参考实现 {@code save_thread_artifact_to_workspace_view} 的 400 分支）改为
 * <b>检查异常 cause 是否为 {@link SafeFiles.NotDirectoryPathException}</b> 来还原
 * ——与本工程 {@link WorkspaceService} 既有做法（同文件内 {@code isPermissionDeniedCause}
 * 与 {@code stat_authorized_path} 调用点的 cause 判定）完全一致。
 * 另需注意 {@link SafeFiles#openRegularFile} 把「符号链接/非普通文件」（PermissionError）与
 * 「目录/parts 为空」（IsADirectoryError、FileNotFoundException）都抛为
 * {@link FileNotFoundException}，故同样按其固定消息还原 PermissionError 语义。
 *
 * <p><b>能力差异（显式标注）</b>：
 * <ul>
 *   <li>参考实现下载分支在 {@code open(temp_path, "rb")} 读取 16 KiB 头部时若失败不会清理临时文件
 *       （该 {@code with open} 位于清理块之外）；本实现在读取失败时同样删除临时文件。
 *       该差异仅在临时目录 IO 故障时可见，不影响任何 HTTP 语义。</li>
 *   <li>{@code os.write} 的短写重试循环 → {@link OutputStream#write(byte[], int, int)}（语义等价）。</li>
 * </ul>
 */
@Service
public class ArtifactService {

    private static final long MAX_ARTIFACT_DOWNLOAD_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_SAVED_ARTIFACT_NAME_ATTEMPTS = 1000;
    private static final String DEFAULT_ARTIFACT_DESTINATION = "/saved_artifacts";

    /** 工作区授权根（参考实现 WORKSPACE_SCOPE_ROOT）。 */
    private static final String WORKSPACE_SCOPE_ROOT = "/";

    /** 读取头部字节数用于媒体类型探测（参考实现 read(16 * 1024)）。 */
    private static final int MEDIA_TYPE_PROBE_BYTES = 16 * 1024;

    private final WorkdirService workdirService;
    private final FilePreviewService filePreviewService;
    private final SkillService skillService;
    private final UserRepository userRepository;

    public ArtifactService(
            WorkdirService workdirService,
            FilePreviewService filePreviewService,
            SkillService skillService,
            UserRepository userRepository) {
        this.workdirService = workdirService;
        this.filePreviewService = filePreviewService;
        this.skillService = skillService;
        this.userRepository = userRepository;
    }

    // ==================== 路径与授权 ====================

    /** 校验并规范化 artifact 路径（参考实现 _normalize_artifact_path）。 */
    static String normalizeArtifactPath(String workdirPath, String path) {
        String raw = (path == null ? "" : path).strip();
        String normalized = PosixPathLite.parse(raw.startsWith("/") ? raw : "/" + raw).asPosix();
        if (PosixPathLite.parse(raw).partsContain("..")) {
            throw new ApiHttpException(403, "access denied");
        }
        String virtualPrefix = BackendPaths.VIRTUAL_PATH_PREFIX.replaceAll("/+$", "") + "/";
        boolean allowed = normalized.startsWith(workdirPath + "/")
                || normalized.startsWith(virtualPrefix)
                || normalized.startsWith(BackendPaths.VIRTUAL_SKILLS_PATH + "/");
        if (!allowed) {
            throw new ApiHttpException(403, "artifact is outside the current user's visible roots");
        }
        return normalized;
    }

    /** 已授权的 Skill artifact 来源（对应参考实现返回的 {@code (ResolvedSkill, relative_path)} 二元组）。 */
    record SkillArtifactSource(ResolvedSkill skill, String relativePath) {}

    /**
     * 命中虚拟 Skill 根时校验当前用户对该 Skill 的可见性
     * （参考实现 _require_skill_artifact_access）；非 Skill 路径返回 {@code null}。
     */
    SkillArtifactSource requireSkillArtifactAccess(String normalizedPath, String currentUid) {
        String skillsPrefix = BackendPaths.VIRTUAL_SKILLS_PATH + "/";
        if (!normalizedPath.startsWith(skillsPrefix)) {
            return null;
        }
        String remainder = normalizedPath.substring(skillsPrefix.length());
        int slash = remainder.indexOf('/');
        String slug = slash < 0 ? remainder : remainder.substring(0, slash);
        User user = userRepository.getByUid(String.valueOf(currentUid));
        if (user == null || isDeleted(user)) {
            throw new ApiHttpException(403, "artifact access denied");
        }
        Map<String, ResolvedSkill> accessible = new LinkedHashMap<>();
        for (ResolvedSkill item : skillService.listAccessibleSkills(user, true)) {
            accessible.put(item.slug(), item);
        }
        ResolvedSkill skill = accessible.get(slug);
        if (skill == null) {
            throw new ApiHttpException(403, "artifact access denied");
        }
        String relativePath = normalizedPath.substring(skillsPrefix.length() + slug.length()).replaceAll("^/+", "");
        if (relativePath.isEmpty()) {
            throw new ApiHttpException(400, "artifact path is not a regular file");
        }
        return new SkillArtifactSource(skill, relativePath);
    }

    // ==================== 复制 ====================

    /** 从已授权 Skill 真实来源有界复制普通文件（参考实现 _copy_skill_file_to_path）。 */
    static long copySkillFileToPath(ResolvedSkill skill, String relativePath, String targetPath, long maxBytes) {
        PosixPathLite relative = PosixPathLite.parse(relativePath);
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < relative.partCount(); index++) {
            parts.add(relative.part(index));
        }
        if (parts.isEmpty() || relative.partsContain("..")) {
            throw new IllegalArgumentException("invalid skill artifact path");
        }

        SafeFiles.OpenedRegularFile source;
        try {
            source = SafeFiles.openRegularFile(skill.sourceDir(), parts, false);
        } catch (NoSuchFileException exc) {
            throw new FileNotFoundCarrier(exc.getMessage(), exc);
        } catch (FileNotFoundException exc) {
            if (isPermissionDeniedCause(exc)) {
                throw new PermissionErrorCarrier(exc.getMessage(), exc);
            }
            throw new IsADirectoryCarrier(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }

        if (source.attrs().size() > maxBytes) {
            throw new WorkspaceErrors.FileTransferLimitError("file exceeds transfer limit");
        }
        Path target = Path.of(targetPath);
        // 对应 os.O_WRONLY|O_TRUNC|O_NOFOLLOW：不跟随写目标、不自动创建
        if (Files.isSymbolicLink(target)) {
            throw new PermissionErrorCarrier("symlink paths are not allowed", null);
        }
        try (InputStream in = source.openRead();
                OutputStream out = Files.newOutputStream(
                        target,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[1024 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new WorkspaceErrors.FileTransferLimitError("file exceeds transfer limit");
                }
                out.write(buffer, 0, read);
            }
            return total;
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 把已授权的 Workspace 或 Skill artifact 有界复制到临时文件（参考实现 _copy_artifact_to_path）。 */
    void copyArtifactToPath(
            WorkdirService.AuthorizedWorkdir access,
            String normalizedPath,
            SkillArtifactSource skillSource,
            String targetPath,
            long maxBytes) {
        try {
            if (skillSource == null) {
                access.workdir()
                        .getWorkspace()
                        .downloadAuthorizedFileToPath(
                                BackendPaths.workspaceScopeFromRuntimePath(normalizedPath), targetPath, maxBytes);
                return;
            }
            copySkillFileToPath(skillSource.skill(), skillSource.relativePath(), targetPath, maxBytes);
        } catch (WorkspaceErrors.FileTransferLimitError exc) {
            throw new ApiHttpException(413, "artifact exceeds transfer limit");
        } catch (IsADirectoryCarrier exc) {
            throw new ApiHttpException(400, "artifact path is not a regular file");
        } catch (PermissionErrorCarrier | Workspace.PermissionDeniedRuntime exc) {
            throw new ApiHttpException(403, "artifact access denied");
        } catch (FileNotFoundCarrier | Workspace.NoSuchFileRuntime exc) {
            throw new ApiHttpException(404, "artifact not found");
        } catch (IllegalArgumentException exc) {
            // 对应 open_regular_file_fd 的 ValueError 与 workspace_scope_from_runtime_path 的越界拒绝
            throw new ApiHttpException(404, "artifact not found");
        } catch (IllegalStateException exc) {
            if (isPermissionDeniedCause(exc.getCause())) {
                throw new ApiHttpException(403, "artifact access denied");
            }
            throw new ApiHttpException(400, "artifact path is not a regular file");
        }
    }

    // ==================== 用例入口 ====================

    /**
     * 把实时授权文件导出为自动清理的 HTTP 文件响应（参考实现 resolve_thread_artifact_view）。
     *
     * @return 文本/不支持预览场景为 {@code Map}；二进制预览为
     *     {@link FilePreviewService.BinaryPreview}；下载为 {@link ArtifactDownload}。
     */
    public Object resolveThreadArtifactView(
            String threadId, String currentUid, String path, boolean download, boolean preview) {
        WorkdirService.AuthorizedWorkdir access = workdirService.resolveAuthorizedWorkdir(threadId, currentUid);
        String normalized =
                normalizeArtifactPath(BackendPaths.runtimeUserDataPath(access.workdir().rootPath()), path);
        SkillArtifactSource skillSource = requireSkillArtifactAccess(normalized, currentUid);
        boolean isPreview = preview && !download;

        Path tempPath;
        try {
            tempPath = Files.createTempFile("wenqu-artifact-", PosixPathLite.suffixOf(normalized));
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        try {
            copyArtifactToPath(
                    access,
                    normalized,
                    skillSource,
                    tempPath.toString(),
                    isPreview ? FilePreviewUtils.MAX_BINARY_PREVIEW_SIZE_BYTES : MAX_ARTIFACT_DOWNLOAD_BYTES);
        } catch (ApiHttpException exc) {
            deleteQuietly(tempPath);
            if (isPreview && exc.getStatus() == 413) {
                return FilePreviewUtils.previewTooLarge().payload();
            }
            throw exc;
        } catch (RuntimeException exc) {
            deleteQuietly(tempPath);
            throw exc;
        }

        String fileName = PosixPathLite.nameOf(normalized);
        if (fileName.isEmpty()) {
            fileName = "artifact";
        }
        if (isPreview) {
            try {
                byte[] rawContent = Files.readAllBytes(tempPath);
                return filePreviewService.renderFilePreview(
                        normalized, rawContent, "artifact:" + currentUid + ":" + normalized);
            } catch (FilePreviewUtils.OfficePreviewConversionError exc) {
                throw new ApiHttpException(400, exc.getMessage());
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            } finally {
                deleteQuietly(tempPath);
            }
        }

        byte[] probe;
        try (InputStream artifactFile = Files.newInputStream(tempPath)) {
            probe = artifactFile.readNBytes(MEDIA_TYPE_PROBE_BYTES);
        } catch (IOException exc) {
            deleteQuietly(tempPath);
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        String mediaType = FilePreviewUtils.detectMediaType(fileName, probe);
        // filename=None（非下载）时不设置 Content-Disposition，对应 starlette 的省略行为
        return new ArtifactDownload(tempPath.toString(), mediaType, download ? fileName : null);
    }

    /** 把可见 artifact 复制到用户选择的工作区目录（参考实现 save_thread_artifact_to_workspace_view）。 */
    public Map<String, Object> saveThreadArtifactToWorkspaceView(
            String threadId, String currentUid, String path, String destinationPath) {
        WorkdirService.AuthorizedWorkdir access = workdirService.resolveAuthorizedWorkdir(threadId, currentUid);
        String normalized =
                normalizeArtifactPath(BackendPaths.runtimeUserDataPath(access.workdir().rootPath()), path);

        // 参考实现 str(destination_path or DEFAULT_ARTIFACT_DESTINATION)：空串同样回退默认值
        String rawDestination = (destinationPath == null || destinationPath.isEmpty()
                        ? DEFAULT_ARTIFACT_DESTINATION
                        : destinationPath)
                .strip();
        PosixPathLite destination = PosixPathLite.parse(rawDestination);
        if (!destination.isAbsolute()
                || PosixPathLite.parse(rawDestination).partsContain("..")
                || rawDestination.contains("\\")
                || rawDestination.contains("://")
                || BackendPaths.isRuntimePath(rawDestination)) {
            throw new ApiHttpException(403, "invalid artifact destination");
        }
        String destinationScope = destination.asPosix();
        boolean destinationMustExist =
                destinationPath != null && !DEFAULT_ARTIFACT_DESTINATION.equals(destinationScope);
        if (destinationMustExist) {
            Map<String, Object> destinationItem;
            try {
                destinationItem =
                        access.workdir().getWorkspace().statAuthorizedPath(destinationScope, WORKSPACE_SCOPE_ROOT);
            } catch (Workspace.PermissionDeniedRuntime exc) {
                if (exc.getCause() instanceof SafeFiles.NotDirectoryPathException) {
                    throw new ApiHttpException(400, "artifact destination is not a directory");
                }
                throw new ApiHttpException(403, "artifact destination access denied");
            } catch (Workspace.NoSuchFileRuntime exc) {
                throw new ApiHttpException(404, "artifact destination does not exist");
            } catch (IllegalArgumentException exc) {
                throw new ApiHttpException(403, "artifact destination access denied");
            } catch (IllegalStateException exc) {
                if (isPermissionDeniedCause(exc.getCause())) {
                    throw new ApiHttpException(403, "artifact destination access denied");
                }
                throw new ApiHttpException(400, "artifact destination is not a directory");
            }
            if (!Boolean.TRUE.equals(destinationItem.get("is_dir"))) {
                throw new ApiHttpException(400, "artifact destination is not a directory");
            }
        }

        SkillArtifactSource skillSource = requireSkillArtifactAccess(normalized, currentUid);
        Path tempPath;
        try {
            tempPath = Files.createTempFile("wenqu-save-artifact-", null);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        String target = null;
        try {
            copyArtifactToPath(access, normalized, skillSource, tempPath.toString(), MAX_ARTIFACT_DOWNLOAD_BYTES);
            String fileName = PosixPathLite.nameOf(normalized);
            if (fileName.isEmpty()) {
                fileName = "artifact";
            }
            String stem = PosixPathLite.stemOf(fileName);
            String suffix = PosixPathLite.suffixOf(fileName);
            // 参考实现 for index in range(MAX + 1) ... else: raise 409
            int index = 0;
            while (index <= MAX_SAVED_ARTIFACT_NAME_ATTEMPTS) {
                String candidateName = index == 0 ? fileName : stem + " (" + index + ")" + suffix;
                String targetScope = destinationScope.replaceAll("/+$", "") + "/" + candidateName;
                try {
                    String candidateTarget = BackendPaths.runtimeUserDataPath(targetScope);
                    access.workdir()
                            .getWorkspace()
                            .uploadAuthorizedFileFromPath(
                                    targetScope, tempPath.toString(), false, !destinationMustExist);
                    target = candidateTarget;
                    break;
                } catch (Workspace.AlreadyExistsRuntime exc) {
                    // 参考实现 except FileExistsError: continue（换下一个候选名）
                    index++;
                } catch (Workspace.NoSuchFileRuntime exc) {
                    throw new ApiHttpException(404, "artifact destination does not exist");
                } catch (Workspace.PermissionDeniedRuntime exc) {
                    if (exc.getCause() instanceof SafeFiles.NotDirectoryPathException) {
                        throw new ApiHttpException(400, "artifact destination is not a directory");
                    }
                    throw new ApiHttpException(403, "artifact destination access denied");
                } catch (IllegalArgumentException exc) {
                    throw new ApiHttpException(403, "artifact destination access denied");
                }
            }
            if (target == null) {
                throw new ApiHttpException(409, "saved artifact name space is exhausted");
            }
        } finally {
            deleteQuietly(tempPath);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", PosixPathLite.nameOf(target));
        result.put("source_path", normalized);
        result.put("saved_path", target);
        result.put(
                "saved_artifact_url",
                "/api/chat/thread/" + threadId + "/artifacts/" + target.replaceAll("^/+", ""));
        return result;
    }

    // ==================== 内部工具 ====================

    /** 参考实现 bool(user.is_deleted)：Integer 非 0 即为已删除。 */
    private static boolean isDeleted(User user) {
        return user.getIsDeleted() != null && user.getIsDeleted() != 0;
    }

    /**
     * 参考实现 {@code open_regular_file_fd} 对「符号链接」「非普通文件」抛 {@code PermissionError}、
     * 对「目录」「parts 为空」抛 {@code IsADirectoryError}/{@code FileNotFoundError}；
     * {@link SafeFiles#openRegularFile} 两者都抛 {@link FileNotFoundException}，
     * 故按其固定消息还原 PermissionError 语义（消息为 SafeFiles 内常量，
     * 与 {@link WorkspaceService} 既有判定一致）。
     */
    private static boolean isPermissionDeniedCause(Throwable cause) {
        if (!(cause instanceof FileNotFoundException)) {
            return false;
        }
        String message = cause.getMessage();
        return "symlink paths are not allowed".equals(message)
                || "only regular files are allowed".equals(message);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 参考实现 contextlib.suppress(FileNotFoundError)：清理失败不掩盖原始结果
        }
    }

    /** Python {@code PermissionError} 的运行时承载（Skill 来源分支）。 */
    private static final class PermissionErrorCarrier extends RuntimeException {
        PermissionErrorCarrier(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Python {@code IsADirectoryError} 的运行时承载（Skill 来源分支）。 */
    private static final class IsADirectoryCarrier extends RuntimeException {
        IsADirectoryCarrier(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Python {@code FileNotFoundError} 的运行时承载（Skill 来源分支）。 */
    private static final class FileNotFoundCarrier extends RuntimeException {
        FileNotFoundCarrier(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 对应参考实现 FileResponse：承载下载临时文件路径与响应元数据（控制器据其装配并清理）。 */
    public static final class ArtifactDownload {
        private final String tempPath;
        private final String mediaType;
        private final String fileName;

        public ArtifactDownload(String tempPath, String mediaType, String fileName) {
            this.tempPath = tempPath;
            this.mediaType = mediaType;
            this.fileName = fileName;
        }

        public String getTempPath() {
            return tempPath;
        }

        public String getMediaType() {
            return mediaType;
        }

        /** 参考实现 filename=file_name if download else None：为 null 表示不设置 Content-Disposition。 */
        public String getFileName() {
            return fileName;
        }

        /**
         * Content-Disposition: attachment; filename*=UTF-8''&lt;percent-encoded&gt;；
         * 非下载场景（fileName 为 null）返回 null，对应 starlette 在 filename=None 时省略该响应头。
         */
        public String contentDisposition() {
            if (fileName == null) {
                return null;
            }
            return "attachment; filename*=UTF-8''" + FilePreviewService.urlEncodeUtf8(fileName);
        }

        /** 供控制器以 Path 形式取用临时文件。 */
        public Path tempFilePath() {
            return Path.of(tempPath);
        }
    }
}
