package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 租户申请材料（营业执照/域名证明等）。
 *
 * <p>落在 {@code {images.dir}/images/tenant-materials/} 下，与文档图片共用静态映射与签名鉴权；
 * 文件名用 UUID，不保留用户原名作为路径（原名只写进展示字段）。
 * 这里只负责存，是否可读由图片鉴权链路决定。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantMaterialService {

    private static final Set<String> ALLOWED = Set.of("pdf", "png", "jpg", "jpeg", "webp");
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final AppProperties properties;
    private final ImageUrlSigner signer;

    /** 保存一份材料，返回 {name, path, url, size}；path 入库，url 是当前可访问地址 */
    public Map<String, Object> save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("材料文件为空");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) name = "材料";
        String ext = extOf(name);
        if (!ALLOWED.contains(ext)) {
            throw new BizException("材料仅支持 PDF / PNG / JPG / WEBP：" + name);
        }
        if (file.getSize() > MAX_BYTES) throw new BizException("单个材料不能超过 8MB");
        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String stored = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            Path dir = Paths.get(properties.getImages().getDir(), "images", "tenant-materials", month)
                    .toAbsolutePath().normalize();
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(stored).toFile());
            String path = "/ai/images/tenant-materials/" + month + "/" + stored;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("path", path);
            out.put("url", signer.signUrl(path));
            out.put("size", file.getSize());
            log.info("[TENANT] 申请材料已保存 name={} size={} path={}", name, file.getSize(), path);
            return out;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[TENANT] 申请材料保存失败 name={}: {}", name, e.toString());
            throw new BizException("材料保存失败，请稍后再试");
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
