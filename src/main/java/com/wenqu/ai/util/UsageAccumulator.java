package com.wenqu.ai.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次问答/一次请求链路内的 usage 逐轮累加器。
 * <p>
 * <b>为什么需要它</b>：模型一带工具就会多轮调用网关，Spring AI 每轮独立返回 usage；
 * 其跨轮累加链（previousChatResponse）在「工具触发块不带 usage、usage 在其后的独立
 * 末块」时断裂（本项目未开启 stream_options.include_usage，部分兼容网关不支持该
 * 参数），且嵌套 Flux 的发射顺序使最后到达的反而是第 1 轮的末块——直接覆盖赋值
 * 只会留下某一轮。native usage 恒为当轮原始值（Spring AI 的累加产物不带 native），
 * 逐轮求和即为全轮总量，与供应商账单同口径。
 * <p>
 * 去重：按响应 id 归并（同一份 usage 被 finish 块与末块各带一次时只计一次）；
 * 网关不返回 id（Spring AI 兜底为 NO_ID）时按出现顺序逐份计入。
 *
 * @author yuanke
 */
@Slf4j
public final class UsageAccumulator {

    /** value = [prompt, completion, cached] */
    private final Map<String, long[]> rounds = new ConcurrentHashMap<>();
    private final AtomicInteger anonSeq = new AtomicInteger();

    /**
     * 接收一个流式块的 usage。只接受带 native 的原始用量：
     * 无 native 者要么是空用量（中间块），要么是已被 Spring AI 累加过的值（混入会重复计数）。
     */
    public void accept(String respId, Usage usage) {
        if (usage == null) return;
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        long p = prompt == null ? 0 : prompt;
        long c = completion == null ? 0 : completion;
        if (p <= 0 && c <= 0) return;
        Object nativeUsage;
        try {
            nativeUsage = usage.getNativeUsage();
        } catch (Exception e) {
            return;
        }
        if (nativeUsage == null) return;
        String key = (respId == null || respId.isBlank() || "NO_ID".equals(respId))
                ? "#anon-" + anonSeq.incrementAndGet() : respId;
        long cached = cachedTokensOf(usage);
        rounds.merge(key, new long[]{p, c, cached}, (oldV, newV) -> {
            oldV[0] = Math.max(oldV[0], newV[0]);
            oldV[1] = Math.max(oldV[1], newV[1]);
            oldV[2] = Math.max(oldV[2], newV[2]);
            return oldV;
        });
    }

    /** 所有轮次输入 token 合计（多次请求的上下文重复计算，与计费口径一致） */
    public long promptTotal() {
        return rounds.values().stream().mapToLong(r -> r[0]).sum();
    }

    /** 所有轮次输出 token 合计（含思考过程与工具调用参数生成） */
    public long completionTotal() {
        return rounds.values().stream().mapToLong(r -> r[1]).sum();
    }

    /** 所有轮次命中缓存的输入 token 合计 */
    public long cachedTotal() {
        return rounds.values().stream().mapToLong(r -> r[2]).sum();
    }

    /**
     * 最终轮（prompt 最大的轮：工具结果逐轮入上下文，prompt 严格递增）的输出 token。
     * 用于生成最终回答那一轮的「输出触顶」判定——各轮工具参数的输出不该参与该判定。
     */
    public long finalRoundCompletion() {
        long best = 0, out = 0;
        for (long[] r : rounds.values()) {
            if (r[0] > best) {
                best = r[0];
                out = r[1];
            }
        }
        return best > 0 ? out : completionTotal();
    }

    /** 最终轮的输入 token（容量分类用量校准用：分类拆的是最终上下文而非各轮总和） */
    public long finalRoundPrompt() {
        long best = 0;
        for (long[] r : rounds.values()) best = Math.max(best, r[0]);
        return best;
    }

    public int rounds() {
        return rounds.size();
    }

    public boolean isEmpty() {
        return rounds.isEmpty();
    }

    /**
     * 缓存命中 token 提取：网关方言不一，按「强类型记录 → Map 方言字段」两路挖，
     * 挖不到返回 0（容量面板/台账的缓存列为展示增强，非正确性依赖）。
     */
    public static long cachedTokensOf(Usage usage) {
        Object nativeUsage;
        try {
            nativeUsage = usage.getNativeUsage();
        } catch (Exception e) {
            return 0;
        }
        if (nativeUsage == null) return 0;
        try {
            // ① 强类型记录：promptTokensDetails.cachedTokens（反射免硬编码，兼容不同 Spring AI 版本）
            Object details = invokeNoArg(nativeUsage, "promptTokensDetails");
            Integer typed = intOf(invokeNoArg(details, "cachedTokens"));
            if (typed != null && typed > 0) return typed;
            // ②/③ 方言字段：native 转成 JSON Map 后查找
            Map<String, Object> map = com.alibaba.fastjson2.JSON.parseObject(
                    com.alibaba.fastjson2.JSON.toJSONString(nativeUsage), Map.class);
            if (map != null) {
                for (String k : new String[]{"cached_tokens", "prompt_cache_hit_tokens"}) {
                    Integer v = intOf(map.get(k));
                    if (v != null && v > 0) return v;
                }
                Object d = map.get("prompt_tokens_details");
                if (d instanceof Map<?, ?> dm) {
                    Integer v = intOf(dm.get("cached_tokens"));
                    if (v != null && v > 0) return v;
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOf(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
