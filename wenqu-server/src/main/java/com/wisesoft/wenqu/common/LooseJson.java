package com.wisesoft.wenqu.common;

import com.alibaba.fastjson2.JSON;

import java.util.Map;

/**
 * 宽松 JSON 解析（参考实现 {@code json_repair.loads} 的近似）。
 *
 * <p>能力差异（已显式标注）：本工程未引入 json_repair 库，此处按参考实现调用点实际依赖的
 * 容错能力实现——剥离 markdown 代码围栏、截取对象/数组边界、去尾逗号、单引号转双引号；
 * 若仍无法解析则抛异常（参考实现的 json_repair 会尽力拼出部分结果而不是抛错）。
 * 现有调用点（LLM 评判、基准生成、图谱抽取）都处在 try/except 内，语义等价。
 */
public final class LooseJson {

    private LooseJson() {
    }

    /** 解析文本，失败抛 {@link IllegalArgumentException}。 */
    public static Object parse(String text) {
        String candidate = trimToJson(text);
        if (candidate == null) {
            throw new IllegalArgumentException("无法解析 JSON：内容为空");
        }
        try {
            return JSON.parse(candidate);
        } catch (RuntimeException firstFailure) {
            String repaired = repair(candidate);
            try {
                return JSON.parse(repaired);
            } catch (RuntimeException secondFailure) {
                throw new IllegalArgumentException("无法解析 JSON：" + secondFailure.getMessage(), secondFailure);
            }
        }
    }

    /** 解析并窄化为对象；结果不是对象时抛异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object parsed = parse(text);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("JSON 内容不是对象");
    }

    /** 剥离代码围栏并截取第一个 '{'/'[' 到最后一个 '}'/']' 之间的片段；无效时返回 null。 */
    private static String trimToJson(String text) {
        if (text == null) {
            return null;
        }
        String candidate = text.strip();
        if (candidate.isEmpty()) {
            return null;
        }
        if (candidate.startsWith("```")) {
            int lineEnd = candidate.indexOf('\n');
            if (lineEnd >= 0) {
                candidate = candidate.substring(lineEnd + 1);
            }
            int fenceEnd = candidate.lastIndexOf("```");
            if (fenceEnd >= 0) {
                candidate = candidate.substring(0, fenceEnd);
            }
            candidate = candidate.strip();
        }

        int objectStart = candidate.indexOf('{');
        int arrayStart = candidate.indexOf('[');
        int start = objectStart < 0 ? arrayStart
                : (arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart));
        if (start > 0) {
            candidate = candidate.substring(start);
        }
        if (candidate.isEmpty()) {
            return null;
        }
        char open = candidate.charAt(0);
        char close = open == '[' ? ']' : '}';
        int end = candidate.lastIndexOf(close);
        if (end >= 0) {
            candidate = candidate.substring(0, end + 1);
        }
        return candidate;
    }

    private static String repair(String text) {
        return text.replaceAll(",\\s*([}\\]])", "$1").replace('\'', '"');
    }
}
