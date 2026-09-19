package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.storage.MinioStorageClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ZIP 处理：提取 markdown 内容与图片（knowledge/parser/zip_utils.py 全量移植）。
 *
 * <p>必要替换：{@code asyncio.run} 驱动的异步入口 → 直接同步实现
 * （本工程无事件循环，{@code process_zip_file_sync} 的线程桥接不再需要）。
 */
@Slf4j
public final class ZipUtils {

    public static final String DEFAULT_IMAGE_BUCKET = "kb-images";
    public static final String DEFAULT_IMAGE_PREFIX = "unknown/kb-images";

    private static final Pattern IMAGE_LINK_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    private ZipUtils() {}

    private static String normalizeObjectPrefix(String prefix) {
        String normalized = (prefix == null ? DEFAULT_IMAGE_PREFIX : prefix);
        normalized = normalized.replaceAll("^/+|/+$", "");
        return normalized.isEmpty() ? DEFAULT_IMAGE_PREFIX : normalized;
    }

    /** 处理 ZIP 文件，提取 markdown 内容并上传图片，返回替换链接后的 Markdown 文本。 */
    public static String processZipFile(String zipPath, String imageBucket, String imagePrefix) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath)) {
            List<String> names = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements(); ) {
                names.add(entries.nextElement().getName());
            }
            for (String name : names) {
                if (name.startsWith("/") || name.startsWith("\\")) {
                    throw new IllegalArgumentException("ZIP 包含不安全路径: " + name);
                }
                if (java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw new IllegalArgumentException("ZIP 路径包含上级引用: " + name);
                }
            }

            List<String> mdFiles = new ArrayList<>();
            for (String name : names) {
                if (name.toLowerCase().endsWith(".md")) {
                    mdFiles.add(name);
                }
            }
            if (mdFiles.isEmpty()) {
                throw new IllegalArgumentException("压缩包中未找到 .md 文件");
            }

            String mdFile = mdFiles.stream()
                    .filter(name -> PathName.name(name).equals("full.md"))
                    .findFirst()
                    .orElse(mdFiles.get(0));

            String markdownContent;
            try (InputStream in = zipFile.getInputStream(zipFile.getEntry(mdFile))) {
                markdownContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            List<Map<String, Object>> imagesInfo = new ArrayList<>();
            String imagesDir = findImagesDirectory(zipFile, mdFile);
            String normalizedPrefix = normalizeObjectPrefix(imagePrefix);

            if (imagesDir != null) {
                imagesInfo = processImages(zipFile, imagesDir, imageBucket, normalizedPrefix);
                markdownContent = replaceImageLinks(markdownContent, imagesInfo);
            }

            return markdownContent;
        }
    }

    /** 同步调用 ZIP 处理（Java 无事件循环，与异步入口合一；见类注释）。 */
    public static String processZipFileSync(String zipPath, String imageBucket, String imagePrefix) {
        try {
            return processZipFile(zipPath, imageBucket, imagePrefix);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 查找 images 目录。 */
    static String findImagesDirectory(ZipFile zipFile, String mdFilePath) {
        String mdParent = PathName.parent(mdFilePath);

        List<String> candidates = new ArrayList<>();
        if (!mdParent.isEmpty()) {
            candidates.add(mdParent + "/images");
            String grandParent = PathName.parent(mdParent);
            candidates.add(grandParent + "/images");
        }
        candidates.add("images");

        List<String> names = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements(); ) {
            names.add(entries.nextElement().getName());
        }

        for (String candidate : candidates) {
            String candidateClean = candidate.replaceAll("/+$", "");
            for (String name : names) {
                if (name.startsWith(candidateClean + "/")) {
                    return candidateClean;
                }
            }
        }

        return null;
    }

    /** 处理图片：上传到 MinIO 并返回信息。 */
    static List<Map<String, Object>> processImages(
            ZipFile zipFile, String imagesDir, String imageBucket, String imagePrefix) {
        Set<String> supportedExtensions = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

        List<Map<String, Object>> images = new ArrayList<>();
        List<String> imageNames = new ArrayList<>();
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements(); ) {
            String name = entries.nextElement().getName();
            if (name.startsWith(imagesDir + "/")) {
                imageNames.add(name);
            }
        }

        MinioStorageClient minioClient = MinioStorageClient.getInstance();
        minioClient.ensureBucketExists(imageBucket);

        for (String imgName : imageNames) {
            String suffix = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(imgName).toLowerCase();
            if (!supportedExtensions.contains(suffix)) {
                continue;
            }

            try {
                byte[] data;
                try (InputStream in = zipFile.getInputStream(zipFile.getEntry(imgName))) {
                    data = in.readAllBytes();
                }

                long timestamp = Instant.now().toEpochMilli() * 1000;
                String objectName = normalizedPrefix(timestamp, imagePrefix, imgName);

                minioClient.uploadFile(imageBucket, objectName, data, null);

                Map<String, Object> imgInfo = new LinkedHashMap<>();
                imgInfo.put("name", PathName.name(imgName));
                imgInfo.put("url", KbUtils.buildKbImageProxyUrl(objectName));
                imgInfo.put("path", "images/" + PathName.name(imgName));
                images.add(imgInfo);

                log.debug("图片上传成功: {} -> {}", PathName.name(imgName), imgInfo.get("url"));
            } catch (Exception exc) {
                log.error("上传图片失败 {}: {}", PathName.name(imgName), exc.getMessage());
                continue;
            }
        }

        return images;
    }

    private static String normalizedPrefix(long timestamp, String imagePrefix, String imgName) {
        return imagePrefix + "/" + timestamp + "_" + PathName.name(imgName);
    }

    /** 替换 markdown 中的图片链接为 MinIO URL。 */
    public static String replaceImageLinks(String markdownContent, List<Map<String, Object>> images) {
        if (images.isEmpty()) {
            return markdownContent;
        }

        Map<String, String> imageMap = new LinkedHashMap<>();
        for (Map<String, Object> img : images) {
            String path = String.valueOf(img.get("path"));
            String url = String.valueOf(img.get("url"));
            imageMap.put(path, url);
            imageMap.put("/" + path, url);
            imageMap.put(String.valueOf(img.get("name")), url);
        }

        Matcher matcher = IMAGE_LINK_PATTERN.matcher(markdownContent);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String altText = matcher.group(1) == null ? "" : matcher.group(1);
            String imgPath = matcher.group(2);

            String replacement = null;
            for (Map.Entry<String, String> entry : imageMap.entrySet()) {
                String pattern = entry.getKey();
                if (imgPath.endsWith(pattern) || imgPath.equals(pattern)) {
                    replacement = "![" + altText + "](" + entry.getValue() + ")";
                    break;
                }
            }
            if (replacement == null) {
                String filename = PathName.name(imgPath);
                if (imageMap.containsKey(filename)) {
                    replacement = "![" + altText + "](" + imageMap.get(filename) + ")";
                }
            }
            if (replacement == null) {
                // Python re.sub 的 group(0) 原样返回
                replacement = Matcher.quoteReplacement(matcher.group(0));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** pathlib.Path 的 name/parent 最小子集（zip 条目均为 '/' 分隔）。 */
    private static final class PathName {

        private static String name(String path) {
            String value = path;
            int slash = value.lastIndexOf('/');
            if (slash >= 0) {
                value = value.substring(slash + 1);
            }
            return value;
        }

        private static String parent(String path) {
            String value = path;
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            int slash = value.lastIndexOf('/');
            if (slash <= 0) {
                return "";
            }
            return value.substring(0, slash);
        }
    }
}
