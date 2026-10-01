package com.wisesoft.ai.util;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量操作的结果收集器（各控制器/服务共用）。
 * <p>
 * 口径（与工作流批量一致）：逐条执行、<b>部分成功是批量的固有语义</b>——失败条目
 * 逐条带原因汇报，不静默吞；结果与全选对账：列表外（不存在/无权）的 id 也进
 * {@code failed}，不做静默丢弃。返回结构：{@code {succeeded:[id], failed:[{id,name,error}]}}。
 *
 * @author yuanke
 */
public final class BatchResults {

    private BatchResults() {
    }

    /** body.ids → List&lt;String&gt;（元素按字符串收，兼容前端传数字形态） */
    public static List<String> parseIds(Map<String, Object> body) {
        Object v = body == null ? null : body.get("ids");
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    /** 单条失败记录：name 拿不到时用 id 前缀兜底展示 */
    public static Map<String, Object> failItem(String id, String name, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name == null || name.isBlank()
                ? "id " + id.substring(0, Math.min(8, id.length())) + "…" : name);
        m.put("error", error);
        return m;
    }

    /** 组装结果：ids 里没进 succeeded 的（不存在/无权）逐条补进 failed，与全选对账 */
    public static Map<String, Object> result(List<String> ids, List<String> succeeded,
                                             List<Map<String, Object>> failed) {
        Set<String> ok = new HashSet<>(succeeded);
        for (String id : ids) {
            if (!ok.contains(id)) failed.add(failItem(id, null, "不存在或无权操作"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("succeeded", succeeded);
        out.put("failed", failed);
        return out;
    }

    /** 异常 → 可读错误串（null 安全） */
    public static String errMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
