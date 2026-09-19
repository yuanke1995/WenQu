package com.wisesoft.wenqu.workspace;

import com.wisesoft.wenqu.common.FilePreviewUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.PreviewResult;
import com.wisesoft.wenqu.config.RuntimePaths;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * UserWorkspace 文件字节预览与本地 Office 缓存（workspace/preview.py 全量移植）。
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>{@code get_runtime_dir()}（参考实现 config 模块）→ {@link RuntimePaths#getRuntimeDir()}。</li>
 *   <li>{@code asyncio.to_thread} 协程卸载 → 直接同步文件读写（阻塞式 Spring Boot，输出一致）。</li>
 *   <li>Python {@code Path.read_bytes()/write_bytes()/glob()} → {@code Files} API。</li>
 *   <li>Office 转换复用 {@link FilePreviewUtils#convertOfficeToPdf}（LibreOffice 子进程）。</li>
 * </ul>
 */
public final class WorkspacePreview {

    private WorkspacePreview() {
    }

    /** 把 UserWorkspace 文件字节渲染为预览结果（参考实现 preview_workspace_file）。 */
    public static PreviewResult previewWorkspaceFile(String path, byte[] rawContent, String officeCacheKey) {
        if (FilePreviewUtils.isOfficePdfPreviewFile(path)) {
            byte[] pdfContent = convertOfficeToPdfCached(path, rawContent, officeCacheKey);
            String stem = PosixPathLite.stemOf(path);
            if (stem.isEmpty()) {
                stem = "preview";
            }
            return new PreviewResult(pdfContent, "pdf", true, "application/pdf",
                    stem + ".pdf", null, false, null);
        }
        return FilePreviewUtils.renderPreview(path, rawContent);
    }

    private static byte[] convertOfficeToPdfCached(String path, byte[] content, String cacheKey) {
        String namespace = sha256(String.valueOf(cacheKey));
        String contentDigest = sha256Bytes(content);
        Path cacheDir = RuntimePaths.getRuntimeDir().resolve("cache").resolve("office-previews");
        Path cachePath = cacheDir.resolve(namespace + "-" + contentDigest + ".pdf");
        try {
            return Files.readAllBytes(cachePath);
        } catch (NoSuchFileException e) {
            // 未命中缓存，正常回退到转换
        } catch (IOException e) {
            // 缓存读取异常不致命，重新生成
        }

        byte[] pdfContent = FilePreviewUtils.convertOfficeToPdf(PosixPathLite.nameOf(path), content);
        storeOfficePreviewCache(cacheDir, namespace, cachePath, pdfContent);
        return pdfContent;
    }

    private static void storeOfficePreviewCache(Path cacheDir, String namespace,
                                                Path cachePath, byte[] pdfContent) {
        try {
            Files.createDirectories(cacheDir);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, namespace + "-*.pdf")) {
                for (Path stale : stream) {
                    if (!stale.equals(cachePath)) {
                        try {
                            Files.deleteIfExists(stale);
                        } catch (IOException ignored) {
                            // 清理旧缓存项失败不致命
                        }
                    }
                }
            }
            Files.write(cachePath, pdfContent);
        } catch (IOException e) {
            // 缓存写入失败不致命，仅损失一次缓存命中
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sha256Bytes(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
