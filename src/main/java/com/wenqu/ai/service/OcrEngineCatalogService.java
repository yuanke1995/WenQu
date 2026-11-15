package com.wenqu.ai.service;

import com.wenqu.ai.parser.ocr.OcrEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 版面解析引擎目录（知识库编辑页「版面还原」二级选择用）。
 * <p>
 * 普通用户可读：只回状态枚举 + 固定中文文案，不回落 {@link OcrEngine.EngineHealth#detail()}
 * 里的服务地址（detail 仅管理侧排障与解析失败文案使用）。
 * <p>
 * 两个引擎并行探活 + 60s 缓存：打开一次弹窗只探一轮，用户点「重新检测」才强制刷新。
 */
@Slf4j
@Service
public class OcrEngineCatalogService {

    public record EngineView(String id, String label, String status, String reason) {}

    /** 展示顺序与文案；引擎 id 是存量配置值（parse.ocrEngine），不随 UI 改名 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("mineru", "MinerU");
        LABELS.put("pp_structure_v3", "PP-StructureV3");
    }

    private static final long CACHE_MS = 60_000;

    private final Map<String, OcrEngine> engines = new LinkedHashMap<>();
    private volatile Cache cache;

    private record Cache(long at, List<EngineView> views) {}

    public OcrEngineCatalogService(List<OcrEngine> engineList) {
        for (OcrEngine e : engineList) {
            if (LABELS.containsKey(e.id())) engines.put(e.id(), e);
        }
    }

    /** @param refresh true = 跳过缓存（用户点「重新检测」） */
    public List<EngineView> layoutEngines(boolean refresh) {
        Cache c = cache;
        if (!refresh && c != null && System.currentTimeMillis() - c.at() < CACHE_MS) return c.views();

        Map<String, CompletableFuture<OcrEngine.EngineHealth>> futures = new LinkedHashMap<>();
        for (String id : LABELS.keySet()) {
            OcrEngine e = engines.get(id);
            if (e != null) futures.put(id, CompletableFuture.supplyAsync(e::probe));
        }

        List<EngineView> views = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<OcrEngine.EngineHealth>> en : futures.entrySet()) {
            OcrEngine.EngineHealth h;
            try {
                // 引擎自身 HTTP 超时 5s；这里再兜一层总时限，防单个探测卡死整页
                h = en.getValue().get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.warn("[OCR] {} 探活异常: {}", en.getKey(), ex.toString());
                h = OcrEngine.EngineHealth.timeout("探活异常: " + ex.getMessage());
            }
            views.add(view(en.getKey(), h));
        }
        cache = new Cache(System.currentTimeMillis(), views);
        return views;
    }

    private static EngineView view(String id, OcrEngine.EngineHealth h) {
        return switch (h.state()) {
            case AVAILABLE -> new EngineView(id, LABELS.get(id), "available", "可用");
            // 未配置文案由引擎给出（只含环境变量名，不含地址），正是用户要的修复指引
            case UNCONFIGURED -> new EngineView(id, LABELS.get(id), "unconfigured", h.detail());
            case TIMEOUT -> new EngineView(id, LABELS.get(id), "timeout", "探测超时，服务可能正在启动；解析时会再次校验");
            case UNREACHABLE -> new EngineView(id, LABELS.get(id), "unreachable", "服务不可达，请确认已启动（地址已配置）");
        };
    }
}