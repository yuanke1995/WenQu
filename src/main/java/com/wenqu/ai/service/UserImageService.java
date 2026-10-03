package com.wenqu.ai.service;

import com.wenqu.ai.config.AppProperties;
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
 * 用户上传图片处理：data URL 保存到本地（并行），图片是否进模型只看当前聊天模型的能力位——
 * 支持读图走 {@link #processDirect}（原图直发），不支持走 {@link #processDisplay}（仅落盘展示 +
 * 可见降级，诚实说明注入见 RagService）。独立视觉模型描述链路已随「个人默认视觉模型」下线。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class UserImageService {

    private final AppProperties properties;
    private final ExecutorService imageExecutor;

    public UserImageService(AppProperties properties, ConfigService configService) {
        this.properties = properties;
        // 用户图片并发处理（解码/落盘很快，并发上限留小防大图洪峰；vision.userImageConcurrency 可调，默认 2）。
        // 有界队列（20）+ 满即拒绝：避免大图洪峰无限积压
        int concurrency = Math.max(1, configService.getInt("vision.userImageConcurrency", 2));
        this.imageExecutor = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(20), r -> {
            Thread t = new Thread(r, "user-image");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 用户图片记录：url=落盘后回显地址（随消息持久化展示）；
     * dataUrl=原始 data URL（仅直读链路保留，随消息以 image_url 部件发给聊天模型；仅展示链路为 null 不占内存）
     */
    public record UserImage(String url, String dataUrl) {}

    /**
     * 聊天直读链路：落盘回显 + 保留原始 dataUrl。聊天模型自带图片理解（visionCapable）时使用——
     * 原图以 image_url 内容部件直发模型，不丢图细节。
     */
    public List<UserImage> processDirect(List<String> dataUrls) {
        return processInternal(dataUrls, u -> processOne(u, true));
    }

    /**
     * 仅展示链路：只落盘回显，dataUrl 为 null（图片不进模型）。聊天模型不支持图片理解时使用——
     * 图片对模型不可见，本轮登记可见降级并在问题里注入诚实说明（见 RagService），不静默忽略。
     */
    public List<UserImage> processDisplay(List<String> dataUrls) {
        return processInternal(dataUrls, u -> processOne(u, false));
    }

    /** 并行处理骨架：队列满/单张异常跳过该项（fail-loud 告警），不影响其余图片与回答主流程 */
    private List<UserImage> processInternal(List<String> dataUrls, java.util.function.Function<String, UserImage> fn) {
        if (dataUrls == null || dataUrls.isEmpty()) return List.of();
        List<CompletableFuture<UserImage>> futures = dataUrls.stream()
                .filter(u -> u != null && u.startsWith("data:"))
                .map(u -> {
                    try {
                        return CompletableFuture.supplyAsync(() -> fn.apply(u), imageExecutor);
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

    private UserImage processOne(String dataUrl, boolean keepDataUrl) {
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
            return new UserImage(url, keepDataUrl ? dataUrl : null);
        } catch (Exception e) {
            // L3 fail-loud：用户上传图片处理失败（落盘失败则图片既不展示也不进模型，升级明确告警）
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
