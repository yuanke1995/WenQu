package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.wenqu.ai.model.Knowledge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识块出参装配：把 {@link Knowledge} 行组装成前端可直接渲染的 Map。
 * <p>抽出来只有一件事要保证：<b>出参里的图片地址必须现场签名</b>。
 * 库里 {@code images} 存的是原始 URL（{@code /ai/images/{docId}/{sha}.png}），而
 * {@code /images/**} 由 {@code ImageAuthInterceptor} 恒开校验 {@code expire}+{@code sig}，
 * 不看 Cookie、不认登录态——原始 URL 拿去塞进 {@code <img>} 必然是 401。
 * 之前 {@code /knowledge/list} 直接把库里的原始串吐给前端，症状就是「展开知识块，图片全是 401」。
 * <p>签名是一次性凭据，所以每次响应都重签，落库的永远是原始 URL。
 */
@Slf4j
@Service
public class KnowledgeChunkAssembler {

    private final ImageUrlSigner imageUrlSigner;

    public KnowledgeChunkAssembler(ImageUrlSigner imageUrlSigner) {
        this.imageUrlSigner = imageUrlSigner;
    }

    /**
     * 列表行（知识块预览左侧列表）：带内容与签名后的图片，用于展开行渲染。
     *
     * @param qaCount 该块解析期生成的问答对条数（批量统计结果，按块 id 取）
     */
    public Map<String, Object> listRow(Knowledge k, int qaCount) {
        Map<String, Object> m = baseRow(k);
        m.put("content", k.getContent());
        m.put("qaCount", qaCount);
        return m;
    }

    private Map<String, Object> baseRow(Knowledge k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("title", k.getTitle());
        m.put("chunkIndex", k.getChunkIndex());
        m.put("titlePath", k.getTitlePath());
        m.put("vectorId", k.getVectorId());           // 空 = 未向量化（前端角标）
        m.put("status", k.getStatus());               // 块级启停用：0=生效, 1=停用
        m.put("images", signedImages(k));
        return m;
    }

    /**
     * 把库里的 JSON 字符串解析成字符串列表并签名；空/脏数据一律返回空列表。
     * <p>解析失败不抛：一个块的脏数据不该让整页知识块预览打不开，
     * 但必须留日志——否则坏块会静默变成「没有图片」。
     */
    public List<String> signedImages(Knowledge k) {
        String raw = k.getImages();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> urls;
        try {
            // 先读成原始 JSONArray 再自己筛，不能直接 JSON.parseArray(raw, String.class)：
            // fastjson 会把非字符串元素（{"a":1}、123）静默 toString 成 "{"a":1}" 混进列表，
            // 再被签名后塞进 <img src> 变成一个永远 404 的裂图。脏数据在这里就丢掉。
            JSONArray arr = JSON.parseArray(raw);
            if (arr == null || arr.isEmpty()) {
                return List.of();
            }
            urls = new ArrayList<>(arr.size());
            for (Object o : arr) {
                if (o instanceof String s && !s.isBlank()) {
                    urls.add(s);
                } else {
                    log.warn("[KNOWLEDGE] 知识块图片字段含非字符串元素，已跳过 chunkId={} value={}", k.getId(), o);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[KNOWLEDGE] 知识块图片字段解析失败，按无图处理 chunkId={} raw={}", k.getId(), abbreviate(raw));
            return List.of();
        }
        return urls.isEmpty() ? List.of() : imageUrlSigner.signUrls(urls);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}