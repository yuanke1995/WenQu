package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ImageDescCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 图片描述缓存运维：查统计 / 手动清理过期与旧版本 / 一键清空（清空后重解析全量重新描述）
 */
@Tag(name = "图片描述缓存")
@RestController
@RequestMapping("/api/ai/desc-cache")
@RequiredArgsConstructor
public class DescCacheController {

    private final ImageDescCache imageDescCache;

    @Operation(summary = "缓存统计（总条数/模型数/最新与最旧命中时间）")
    @GetMapping("/stats")
    public ResultJson<Map<String, Object>> stats() {
        return ResultJson.ok(imageDescCache.stats());
    }

    @Operation(summary = "清理过期与旧版本缓存（TTL 到期行 + 版本号变更后的旧 key）")
    @PostMapping("/prune")
    public ResultJson<String> prune() {
        imageDescCache.prune();
        return ResultJson.ok("缓存清理完成");
    }

    @Operation(summary = "清空图片描述缓存（下次重解析全量重新描述，用于模型/提示词变更后强制重生成）")
    @DeleteMapping
    public ResultJson<String> clear() {
        imageDescCache.clear();
        return ResultJson.ok("图片描述缓存已清空");
    }
}
