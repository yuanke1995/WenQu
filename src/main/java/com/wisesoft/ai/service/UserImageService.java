package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 用户上传图片处理：data URL 保存到本地 + 视觉模型生成描述（并行）
 * 描述用于：① 拼入回答上下文（主 LLM 结合图片内容） ② 参与检索召回（结合知识库）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class UserImageService {

    private final AppProperties properties;
    private final VisionService visionService;
    private final ExecutorService imageExecutor;

    public UserImageService(AppProperties properties, VisionService visionService, ConfigService configService) {
        this.properties = properties;
        this.visionService = visionService;
        // 用户图片并发处理（本地视觉模型资源有限；vision.userImageConcurrency 可调，默认 2）。
        // 有界队列（20）+ 满即拒绝：避免大图洪峰让视觉任务无限积压（解码/落盘/180s 视觉调用逐张占资源）
        int concurrency = Math.max(1, configService.getInt("vision.userImageConcurrency", 2));
        this.imageExecutor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(20), r -> {
            Thread t = new Thread(r, "user-image");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public record UserImage(String url, String desc) {}

    /**
     * 处理多张图片（data URL），并行保存+描述；返回 URL 与描述（失败项过滤）。
     * 单张处理失败/队列满被拒：跳过该项并告警（fail-loud），不影响其余图片与回答主流程。
     */
    public List<UserImage> process(List<String> dataUrls) {
        if (dataUrls == null || dataUrls.isEmpty()) return List.of();
        List<CompletableFuture<UserImage>> futures = dataUrls.stream()
                .filter(u -> u != null && u.startsWith("data:"))
                .map(u -> {
                    try {
                        return CompletableFuture.supplyAsync(() -> processOne(u), imageExecutor);
                    } catch (RejectedExecutionException e) {
                        log.warn("[FAIL-LOUD] 用户图片处理队列繁忙，跳过 1 张: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        return futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        // 单张处理线程内异常（processOne 已自吞，此处兜底异常路径）
                        log.warn("[FAIL-LOUD] 用户图片处理异常: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 清理聊天图片目录中超过保留期的临时文件（聊天图只在会话/回答中展示，过期即弃；
     * 引用该图的旧会话走 FALLBACK_IMG 兜底，不影响正文）
     */
    public void cleanupChatImages(long retentionMillis) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", "chat");
        if (!Files.isDirectory(dir)) return;
        long deadline = System.currentTimeMillis() - retentionMillis;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                try {
                    return Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < deadline;
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理聊天图片失败: {} ({})", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("扫描聊天图片目录失败: {}", e.getMessage());
        }
    }

    private UserImage processOne(String dataUrl) {
        try {
            // 解析 data:image/png;base64,xxx
            int comma = dataUrl.indexOf(',');
            if (comma <= 0) return null;
            String meta = dataUrl.substring(5, comma);
            String b64 = dataUrl.substring(comma + 1);
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            String ext = switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (bytes.length == 0) return null;

            String url = persist(bytes, ext);
            String desc = visionService.describe(bytes, ext);
            return new UserImage(url, desc);
        } catch (Exception e) {
            // L3 fail-loud：用户上传图片处理失败（描述生成失败会在回答 prompt 显示"无法识别"，此处升级明确告警）
            log.warn("[FAIL-LOUD] 用户图片处理失败: {}", e.getMessage());
            return null;
        }
    }

    private String persist(byte[] bytes, String ext) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", "chat");
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Files.write(dir.resolve(name), bytes);
        return "/ai/images/chat/" + name;
    }
}
