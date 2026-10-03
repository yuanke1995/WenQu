package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聊天附件上传（独立通道）。
 *
 * <p><b>为什么要有这个服务</b>：此前聊天附件以 base64 内联在 {@code /chat} 的 JSON body 里，
 * 5 个 15MB 附件 ≈ 100MB 的字符串——弱网上传慢、断线要整包重传、后端也得先把整包读进内存才能开工。
 * 现在文件先经 multipart 上传落盘换一个 fileId，问答请求体只带 fileId（几十字节）。
 *
 * <p>落盘：{@code {images.dir}/chat-uploads/{uidHash}/{fileId}}，元信息侧写为 {@code {fileId}.json}
 * （原名/MIME/体积/归属/时间）。目录名取 uid 的 SHA-256 前 16 位——uid 可能含中文或特殊字符，
 * 直接拿来做目录名会带来路径与编码坑；归属校验也据此完成：文件在该用户目录下即为他的。
 *
 * <p>清理：{@link #cleanupExpired()} 由 ScheduleCenter 周期调用，按 {@code chat.uploadRetentionHours}
 * （默认 24h，≤0 = 不清理）删除过期文件——上传是"当轮看一眼"的轻路径，长时间留盘只是泄漏。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ChatUploadService {

    /** 上传完成的附件（fileId 是问答请求里的引用凭据） */
    public record Uploaded(String fileId, String name, String mime, long size) {
    }

    private static final String SUB_DIR = "chat-uploads";
    /** fileId 形态（32 位 hex，无横线 UUID）：读取前必须校验，否则 fileId 会成为路径穿越入口 */
    private static final Pattern FILE_ID = Pattern.compile("[0-9a-f]{32}");

    private final AppProperties properties;
    private final ConfigService configService;

    public ChatUploadService(AppProperties properties, ConfigService configService) {
        this.properties = properties;
        this.configService = configService;
    }

    /** 保存上传文件并返回 fileId（类型白名单与体积上限在此把关，不受支持的在落盘前就拒绝） */
    public Uploaded save(MultipartFile file, String uid) {
        if (file == null || file.isEmpty()) {
            throw new BizException("附件内容为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "未命名附件";
        if (!ChatAttachmentService.supportedExt(name)) {
            throw new BizException("暂不支持的附件类型：" + name + "（支持 PDF / Word / Excel / PPT / 文本与代码文件）");
        }
        long maxBytes = Math.max(1, configService.getInt("chat.maxAttachmentMb", 15)) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BizException("单个附件不能超过 " + (maxBytes / 1024 / 1024) + "MB：" + name);
        }
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String mime = file.getContentType() == null ? "" : file.getContentType();
        try {
            Path dir = dirOf(uid);
            Files.createDirectories(dir);
            // transferTo 相对路径会落到容器临时目录，必须给绝对路径
            file.transferTo(dir.resolve(fileId).toAbsolutePath().toFile());
            JSONObject meta = new JSONObject();
            meta.put("name", name);
            meta.put("mime", mime);
            meta.put("size", file.getSize());
            meta.put("uid", uid);
            meta.put("ts", System.currentTimeMillis());
            Files.writeString(dir.resolve(fileId + ".json"), meta.toJSONString(), StandardCharsets.UTF_8);
            log.info("[ATTACH] 附件上传完成 user={} file={} size={} id={}", uid, name, file.getSize(), fileId);
            return new Uploaded(fileId, name, mime, file.getSize());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 附件落盘失败: user={} file={} - {}", uid, name, e.toString());
            throw new BizException("附件上传失败：" + e.getMessage());
        }
    }

    /** 元信息（存在性 + 归属校验用；不属于该用户或不存在返回 null） */
    public Uploaded stat(String fileId, String uid) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) return null;
        Path meta = dirOf(uid).resolve(fileId + ".json");
        if (!Files.isRegularFile(meta)) return null;
        try {
            JSONObject m = JSON.parseObject(Files.readString(meta, StandardCharsets.UTF_8));
            return new Uploaded(fileId, m.getString("name"), m.getString("mime"), m.getLongValue("size"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取附件字节（问答链路注入上下文用）；文件不在该用户目录下即视为不存在（不泄露他人上传） */
    public byte[] read(String fileId, String uid) {
        if (fileId == null || !FILE_ID.matcher(fileId).matches()) {
            throw new BizException("附件标识非法，请重新上传");
        }
        Path p = dirOf(uid).resolve(fileId);
        if (!Files.isRegularFile(p)) {
            throw new BizException("附件已过期或不存在，请重新上传");
        }
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            throw new BizException("附件读取失败：" + e.getMessage());
        }
    }

    /** 过期清理（ScheduleCenter 周期调用）：删除超过保留期的附件与其元信息 */
    public int cleanupExpired() {
        int hours = configService.getInt("chat.uploadRetentionHours", 24);
        if (hours <= 0) return 0;
        long deadline = System.currentTimeMillis() - hours * 3600_000L;
        Path root = Paths.get(properties.getImages().getDir(), SUB_DIR);
        if (!Files.isDirectory(root)) return 0;
        int removed = 0;
        try (var dirs = Files.list(root)) {
            for (Path userDir : dirs.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(userDir)) {
                    for (Path f : files.toList()) {
                        String fn = f.getFileName().toString();
                        if (fn.endsWith(".json")) continue; // 元信息随数据文件一起删，避免先删 meta 留下孤儿
                        if (Files.getLastModifiedTime(f).toMillis() > deadline) continue;
                        Files.deleteIfExists(f);
                        Files.deleteIfExists(userDir.resolve(fn + ".json"));
                        removed++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ATTACH] 过期附件清理失败（下轮重试）: {}", e.getMessage());
        }
        if (removed > 0) log.info("[ATTACH] 已清理过期聊天附件 {} 件（保留 {} 小时）", removed, hours);
        return removed;
    }

    private Path dirOf(String uid) {
        return Paths.get(properties.getImages().getDir(), SUB_DIR, hashUid(uid));
    }

    /** 目录名 = uid 的 SHA-256 前 16 位（uid 可能含中文/特殊字符，直接做目录名会带来路径与编码坑） */
    private static String hashUid(String uid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(String.valueOf(uid).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
