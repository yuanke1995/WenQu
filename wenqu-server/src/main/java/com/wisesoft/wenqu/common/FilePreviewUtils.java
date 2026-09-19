package com.wisesoft.wenqu.common;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 与存储和 HTTP 无关的文件预览渲染原语（utils/filepreview.py 全量移植）。
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>{@code mimetypes.guess_type}（Python 标准库注册表）→ {@code URLConnection.guessContentTypeFromName}
 *       兜底，Office 媒体类型走本项目内置 {@link #OFFICE_MEDIA_TYPES} 映射（语义等价）。
 *   <li>{@code asyncio.to_thread} 的协程卸载 → 直接同步调用（本项目为阻塞式 Spring Boot，
 *       输出一致，仅执行线程不同）。
 *   <li>{@code subprocess.run} → {@link ProcessBuilder} + {@code waitFor(timeout)}。
 *   <li>Python {@code bytes.decode("utf-8")} 抛 {@code UnicodeDecodeError} → 用
 *       {@code CharsetDecoder} 设 {@code CodingErrorAction.REPORT}，非 UTF-8 时抛
 *       {@link CharacterCodingException}（与参考实现“非 UTF-8 文本不支持预览”分支一致）。
 * </ul>
 */
public final class FilePreviewUtils {

    public static final int MAX_BINARY_PREVIEW_SIZE_BYTES = 30 * 1024 * 1024;
    public static final int MAX_TEXT_PREVIEW_CHARS = 250_000;

    private static final Set<String> MARKDOWN_EXTENSIONS = Set.of(".md", ".markdown", ".mdx");
    private static final Set<String> PDF_EXTENSIONS = Set.of(".pdf");
    private static final Set<String> HTML_EXTENSIONS = Set.of(".html", ".htm");
    private static final Set<String> OFFICE_PDF_PREVIEW_EXTENSIONS = Set.of(".docx", ".pptx");
    private static final java.util.Map<String, String> OFFICE_MEDIA_TYPES = Map.of(
            ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".text", ".log", ".json", ".jsonl", ".yaml", ".yml", ".toml", ".ini", ".cfg",
            ".conf", ".csv", ".tsv", ".py", ".js", ".ts", ".jsx", ".tsx", ".vue", ".css", ".less",
            ".scss", ".xml", ".sql", ".sh", ".bash", ".zsh", ".fish", ".env", ".dockerfile",
            ".gitignore", ".weather");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".svg");

    private static final byte[][] BINARY_SIGNATURES = {
            "\u007fELF".getBytes(StandardCharsets.ISO_8859_1),
            "MZ".getBytes(StandardCharsets.ISO_8859_1),
            "%PDF-".getBytes(StandardCharsets.ISO_8859_1),
            "PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1),
            "PK\u0005\u0006".getBytes(StandardCharsets.ISO_8859_1),
            "PK\u0007\u0008".getBytes(StandardCharsets.ISO_8859_1),
            "\u0089PNG\r\n\u001a\n".getBytes(StandardCharsets.ISO_8859_1),
            "ÿØÿ".getBytes(StandardCharsets.ISO_8859_1),
            "GIF87a".getBytes(StandardCharsets.ISO_8859_1),
            "GIF89a".getBytes(StandardCharsets.ISO_8859_1),
            "RIFF".getBytes(StandardCharsets.ISO_8859_1),
    };

    private static int officePreviewTimeoutSeconds() {
        String raw = System.getenv("OFFICE_PREVIEW_TIMEOUT_SECONDS");
        if (raw == null) {
            raw = "60";
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static final int OFFICE_PREVIEW_TIMEOUT_SECONDS = officePreviewTimeoutSeconds();

    private FilePreviewUtils() {
    }

    /** Office 文件转换为 PDF 失败。 */
    public static final class OfficePreviewConversionError extends RuntimeException {
        public OfficePreviewConversionError(String message) {
            super(message);
        }
    }

    /** 是否是需要 Office→PDF 预览的文件（参考实现 is_office_pdf_preview_file）。 */
    public static boolean isOfficePdfPreviewFile(String path) {
        return OFFICE_PDF_PREVIEW_EXTENSIONS.contains(suffixOf(path));
    }

    /** 构造“文件过大”的不支持结果（参考实现 preview_too_large）。 */
    public static PreviewResult previewTooLarge() {
        return new PreviewResult(null, "unsupported", false, null, null,
                "文件过大，当前仅支持 30 MB 以内的文件预览", false, MAX_BINARY_PREVIEW_SIZE_BYTES);
    }

    /** 把受支持的 Office 文件字节转换为 PDF（参考实现 convert_office_to_pdf / _convert_office_to_pdf_sync）。 */
    public static byte[] convertOfficeToPdf(String filename, byte[] content) {
        return convertOfficeToPdfSync(filename, content);
    }

    private static String suffixOf(String path) {
        return PosixPathLite.suffixOf(path).toLowerCase(Locale.ROOT);
    }

    private static byte[] convertOfficeToPdfSync(String filename, byte[] content) {
        String suffix = suffixOf(filename);
        if (!OFFICE_PDF_PREVIEW_EXTENSIONS.contains(suffix)) {
            throw new OfficePreviewConversionError("当前文件类型不支持转换为 PDF 预览");
        }

        String executable = officeConverterExecutable();
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("wenqu-office-preview-");
        } catch (IOException e) {
            throw new OfficePreviewConversionError("创建 Office 预览临时目录失败: " + e.getMessage());
        }
        Path inputPath = tempDir.resolve("source" + suffix);
        Path outputPath = tempDir.resolve("source.pdf");
        Path profilePath = tempDir.resolve("lo-profile");
        Path logPath = tempDir.resolve("lo.log");
        try {
            Files.createDirectories(profilePath);
            Files.write(inputPath, content);

            ProcessBuilder pb = new ProcessBuilder(Arrays.asList(
                    executable,
                    "--headless",
                    "--nologo",
                    "--nofirststartwizard",
                    "--nodefault",
                    "--nolockcheck",
                    "-env:UserInstallation=" + profilePath.toUri().toString(),
                    "--convert-to", "pdf",
                    "--outdir", tempDir.toString(),
                    inputPath.toString()));
            pb.redirectOutput(logPath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(OFFICE_PREVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new OfficePreviewConversionError("Office 文件转换 PDF 被中断");
            }
            if (!finished) {
                process.destroyForcibly();
                throw new OfficePreviewConversionError(
                        "Office 文件转换 PDF 超时（" + OFFICE_PREVIEW_TIMEOUT_SECONDS + " 秒）");
            }
            if (process.exitValue() != 0) {
                throw new OfficePreviewConversionError(
                        "Office 文件转换 PDF 失败: " + (readLog(logPath).isEmpty() ? "LibreOffice 执行失败" : readLog(logPath)));
            }
            if (!Files.exists(outputPath)) {
                throw new OfficePreviewConversionError(
                        "Office 文件转换 PDF 失败: 未生成 PDF 文件。" + readLog(logPath));
            }
            byte[] pdfContent = Files.readAllBytes(outputPath);
            if (!startsWith(pdfContent, "%PDF-".getBytes(StandardCharsets.ISO_8859_1))) {
                throw new OfficePreviewConversionError("Office 文件转换 PDF 失败: 输出文件不是有效 PDF");
            }
            return pdfContent;
        } catch (IOException e) {
            throw new OfficePreviewConversionError("Office 文件转换 PDF 失败: " + e.getMessage());
        } finally {
            deleteRecursive(tempDir);
        }
    }

    private static String officeConverterExecutable() {
        String executable = findExecutable("soffice");
        if (executable == null) {
            executable = findExecutable("libreoffice");
        }
        if (executable == null) {
            throw new OfficePreviewConversionError("Office PDF 预览依赖 LibreOffice，请先安装 soffice/libreoffice");
        }
        return executable;
    }

    private static String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Paths.get(dir, name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static String readLog(Path logPath) {
        try {
            if (Files.exists(logPath)) {
                return new String(Files.readAllBytes(logPath), StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            // 忽略日志读取失败
        }
        return "";
    }

    private static void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                    for (Path child : ds) {
                        deleteRecursive(child);
                    }
                }
                Files.deleteIfExists(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 临时目录清理失败不致命
        }
    }

    /** 识别预览类型（参考实现 detect_preview_type）。 */
    public static DetectResult detectPreviewType(String path, byte[] rawContent) {
        String suffix = suffixOf(path);
        String mimeType = guessMimeType(path);
        byte[] head = head(rawContent, 1024);

        if (IMAGE_EXTENSIONS.contains(suffix) || (mimeType != null && mimeType.startsWith("image/"))) {
            return new DetectResult("image", true, null);
        }
        if (PDF_EXTENSIONS.contains(suffix) || "application/pdf".equals(mimeType)
                || startsWith(head, "%PDF-".getBytes(StandardCharsets.ISO_8859_1))) {
            return new DetectResult("pdf", true, null);
        }
        if (MARKDOWN_EXTENSIONS.contains(suffix)) {
            return new DetectResult("markdown", true, null);
        }
        if (HTML_EXTENSIONS.contains(suffix)) {
            return new DetectResult("html", true, null);
        }
        if (TEXT_EXTENSIONS.contains(suffix)) {
            return new DetectResult("text", true, null);
        }
        if (indexOf(head, (byte) 0) >= 0) {
            return new DetectResult("unsupported", false, "当前文件是二进制文件，暂不支持预览");
        }
        if (anyStartsWith(head, BINARY_SIGNATURES)) {
            if (startsWith(head, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
                    && containsWithin(head, 16, "WEBP".getBytes(StandardCharsets.ISO_8859_1))) {
                return new DetectResult("image", true, null);
            }
            return new DetectResult("unsupported", false, "当前文件格式暂不支持预览");
        }
        if (mimeType != null) {
            if (mimeType.startsWith("text/")) {
                return new DetectResult("text", true, null);
            }
            if ("application/json".equals(mimeType) || "application/xml".equals(mimeType)
                    || "application/javascript".equals(mimeType)) {
                return new DetectResult("text", true, null);
            }
            if (mimeType.startsWith("application/")) {
                return new DetectResult("unsupported", false, "当前文件格式暂不支持预览");
            }
        }
        if (rawContent == null || rawContent.length == 0) {
            return new DetectResult("text", true, null);
        }
        try {
            new String(rawContent, StandardCharsets.UTF_8);
            return new DetectResult("text", true, null);
        } catch (Exception e) {
            return new DetectResult("unsupported", false, "当前文件不是可读文本，暂不支持预览");
        }
    }

    /** 把文件字节渲染为中立 Preview 结果（参考实现 render_preview）。 */
    public static PreviewResult renderPreview(String path, byte[] rawContent) {
        DetectResult det = detectPreviewType(path, rawContent);
        if ("image".equals(det.previewType()) || "pdf".equals(det.previewType())) {
            String name = PosixPathLite.nameOf(path);
            if (name.isEmpty()) {
                name = "preview";
            }
            return new PreviewResult(rawContent, det.previewType(), true,
                    detectMediaType(path, rawContent), name, null, false, null);
        }
        if (!det.supported()) {
            return new PreviewResult(null, det.previewType(), false, null, null, det.message(), false, null);
        }

        String content;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            content = decoder.decode(ByteBuffer.wrap(rawContent)).toString();
        } catch (CharacterCodingException e) {
            return new PreviewResult(null, "unsupported", false, null, null,
                    "当前文件不是 UTF-8 文本，暂不支持预览", false, null);
        }

        boolean truncated = content.length() > MAX_TEXT_PREVIEW_CHARS;
        if (truncated) {
            content = content.substring(0, MAX_TEXT_PREVIEW_CHARS);
        }
        return new PreviewResult(content, det.previewType(), true, null, null, det.message(),
                truncated, MAX_TEXT_PREVIEW_CHARS);
    }

    /** 优先按文件签名识别响应媒体类型（参考实现 detect_media_type）。 */
    public static String detectMediaType(String path, byte[] rawContent) {
        byte[] head = head(rawContent, 512);
        if (startsWith(head, "\u0089PNG\r\n\u001a\n".getBytes(StandardCharsets.ISO_8859_1))) {
            return "image/png";
        }
        if (startsWith(head, "ÿØÿ".getBytes(StandardCharsets.ISO_8859_1))) {
            return "image/jpeg";
        }
        if (startsWith(head, "GIF87a".getBytes(StandardCharsets.ISO_8859_1))
                || startsWith(head, "GIF89a".getBytes(StandardCharsets.ISO_8859_1))) {
            return "image/gif";
        }
        if (startsWith(head, "RIFF".getBytes(StandardCharsets.ISO_8859_1))
                && containsWithin(head, 16, "WEBP".getBytes(StandardCharsets.ISO_8859_1))) {
            return "image/webp";
        }
        if (startsWith(head, "BM".getBytes(StandardCharsets.ISO_8859_1))) {
            return "image/bmp";
        }
        if (startsWith(head, "%PDF-".getBytes(StandardCharsets.ISO_8859_1))) {
            return "application/pdf";
        }

        byte[] stripped = lstrip(head);
        if (startsWith(stripped, "<svg".getBytes(StandardCharsets.ISO_8859_1))
                || startsWith(stripped, "<?xml".getBytes(StandardCharsets.ISO_8859_1))) {
            String suffix = suffixOf(path);
            if (".svg".equals(suffix) || containsWithin(stripped, 256, "<svg".getBytes(StandardCharsets.ISO_8859_1))) {
                return "image/svg+xml";
            }
        }

        String suffix = suffixOf(path);
        if (OFFICE_MEDIA_TYPES.containsKey(suffix)) {
            return OFFICE_MEDIA_TYPES.get(suffix);
        }
        String guessed = guessMimeType(path);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String guessMimeType(String path) {
        String suffix = suffixOf(path);
        if (OFFICE_MEDIA_TYPES.containsKey(suffix)) {
            return OFFICE_MEDIA_TYPES.get(suffix);
        }
        return java.net.URLConnection.guessContentTypeFromName(path);
    }

    private static byte[] head(byte[] data, int limit) {
        if (data == null) {
            return new byte[0];
        }
        if (data.length <= limit) {
            return data;
        }
        byte[] copy = new byte[limit];
        System.arraycopy(data, 0, copy, 0, limit);
        return copy;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte value) {
        if (data == null) {
            return -1;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static boolean anyStartsWith(byte[] data, byte[][] signatures) {
        for (byte[] signature : signatures) {
            if (startsWith(data, signature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWithin(byte[] data, int limit, byte[] target) {
        if (data == null || target == null || target.length == 0) {
            return false;
        }
        int max = Math.min(limit, data.length);
        if (max < target.length) {
            return false;
        }
        int last = max - target.length;
        for (int i = 0; i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static byte[] lstrip(byte[] data) {
        int i = 0;
        while (i < data.length) {
            byte b = data[i];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                i++;
            } else {
                break;
            }
        }
        if (i == 0) {
            return data;
        }
        byte[] copy = new byte[data.length - i];
        System.arraycopy(data, i, copy, 0, copy.length);
        return copy;
    }

    /** detect_preview_type 的结果载体（参考实现返回的 tuple[str, bool, str | None]）。 */
    public static final class DetectResult {
        private final String previewType;
        private final boolean supported;
        private final String message;

        public DetectResult(String previewType, boolean supported, String message) {
            this.previewType = previewType;
            this.supported = supported;
            this.message = message;
        }

        public String previewType() {
            return previewType;
        }

        public boolean supported() {
            return supported;
        }

        public String message() {
            return message;
        }
    }
}
