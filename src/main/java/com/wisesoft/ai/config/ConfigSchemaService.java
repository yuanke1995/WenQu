package com.wisesoft.ai.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置字段定义服务：`classpath:config-schema.json` 是配置字段的**唯一定义源**。
 * <p>
 * 同一份定义同时服务三件事，避免"后端白名单 / 分层 / 前端表单 / 校验规则"四处各写一遍：
 * <ol>
 *   <li><b>下发给前端</b>：{@link #describe()} 供 {@code GET /api/ai/config/schema}，设置页据此渲染字段与校验提示；</li>
 *   <li><b>可编辑白名单与分层</b>：替代原先手写的 {@code EDITABLE} / {@code TIER} 两张 Map；</li>
 *   <li><b>保存校验</b>：{@link #validate(String, String)} 按字段类型/范围/枚举校验，替代原先按前缀分组的一长串 if。</li>
 * </ol>
 * 字段定义改这一处即可；{@code ConfigService.defaults()} 仍只负责"运行时默认值"（yml/env 种子）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigSchemaService {

    /** 字段定义（内部用；下发给前端的是原始 JSON，保留 group/key/path/submitKey 等表单属性） */
    public record Field(String key, String panel, String label, String type,
                        Double min, Double max, Double step, Double factor,
                        int tier, List<String> options, String help) {
    }

    private final List<JSONObject> panels;
    private final List<JSONObject> rawFields;
    private final List<Field> fields;
    private final Map<String, Field> byKey = new LinkedHashMap<>();
    private final Map<String, String> tips;
    private final List<String> corePaths;
    /** 后端键 → 中文说明（原 EDITABLE 的 value，落库进 c_ai_config.remark） */
    private final Map<String, String> help;

    public ConfigSchemaService() {
        JSONObject root;
        try (InputStream in = new ClassPathResource("config-schema.json").getInputStream()) {
            root = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 定义文件随应用分发：缺失/损坏就是部署错误。此处**不做降级**——静默成"零字段"会让
            // 设置页空白、且所有保存被当成"未知配置项"拒绝，比启动即失败更难排查
            throw new IllegalStateException("配置字段定义 config-schema.json 加载失败: " + e.getMessage(), e);
        }

        List<JSONObject> ps = new ArrayList<>();
        JSONArray pa = root.getJSONArray("panels");
        if (pa != null) for (Object o : pa) ps.add((JSONObject) o);
        this.panels = List.copyOf(ps);

        Map<String, String> h = new LinkedHashMap<>();
        JSONObject ej = root.getJSONObject("editable");
        if (ej != null) for (String k : ej.keySet()) h.put(k, ej.getString(k));
        this.help = Map.copyOf(h);

        Map<String, String> t = new LinkedHashMap<>();
        JSONObject tj = root.getJSONObject("tips");
        if (tj != null) for (String k : tj.keySet()) t.put(k, tj.getString(k));
        this.tips = Map.copyOf(t);

        List<String> cps = new ArrayList<>();
        JSONArray ca = root.getJSONArray("corePaths");
        if (ca != null) for (Object o : ca) cps.add(String.valueOf(o));
        this.corePaths = List.copyOf(cps);

        List<JSONObject> raws = new ArrayList<>();
        List<Field> fs = new ArrayList<>();
        JSONArray fa = root.getJSONArray("fields");
        if (fa != null) {
            for (Object o : fa) {
                JSONObject j = (JSONObject) o;
                raws.add(j);
                String key = j.getString("backendKey");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("config-schema.json 字段缺少 backendKey: " + j);
                }
                List<String> opts = new ArrayList<>();
                JSONArray oa = j.getJSONArray("options");
                if (oa != null) {
                    for (Object oo : oa) {
                        Object v = (oo instanceof JSONObject jo) ? jo.get("value") : oo;
                        if (v != null) opts.add(String.valueOf(v));
                    }
                }
                Field f = new Field(key, j.getString("panel"), j.getString("label"), j.getString("type"),
                        dbl(j, "min"), dbl(j, "max"), dbl(j, "step"), dbl(j, "factor"),
                        j.getIntValue("tier", 2), List.copyOf(opts), h.get(key));
                if (byKey.put(key, f) != null) {
                    throw new IllegalStateException("config-schema.json 字段 backendKey 重复: " + key);
                }
                fs.add(f);
            }
        }
        this.rawFields = List.copyOf(raws);
        this.fields = List.copyOf(fs);
        log.info("[Config] 配置字段定义已加载：{} 面板 / {} 字段（可编辑 {} 项）",
                panels.size(), fields.size(), byKey.size());
    }

    private static Double dbl(JSONObject j, String k) {
        Object v = j.get(k);
        return v == null ? null : j.getDouble(k);
    }

    /** 是否可编辑（替代原 EDITABLE 白名单） */
    public boolean isEditable(String key) {
        return byKey.containsKey(key);
    }

    /** 分层：1 必需 / 2 调优 / 3 工程排障（替代原 TIER；未定义按 2） */
    public int tier(String key) {
        Field f = byKey.get(key);
        return f == null ? 2 : f.tier();
    }

    /** 字段中文说明（落 c_ai_config.remark）；未知键返回 null */
    public String help(String key) {
        return help.get(key);
    }

    public String helpOrDefault(String key, String def) {
        return help.getOrDefault(key, def);
    }

    /** 下发给前端的完整定义（面板/字段/文案/核心项），字段保留原始表单属性 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("panels", panels);
        m.put("fields", rawFields);
        m.put("tips", tips);
        m.put("corePaths", corePaths);
        return m;
    }

    /**
     * 保存前校验：按字段定义的类型与范围判断。返回 {@code null} = 通过，否则为给用户看的错误信息。
     * <p>空值一律放行（清空 = 回落默认值，由各消费方兜底）；未定义字段一律拒绝（白名单语义，防越权写配置）。
     */
    public String validate(String key, String raw) {
        Field f = byKey.get(key);
        if (f == null) return "未知配置项：" + key;
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        String type = f.type() == null ? "" : f.type();
        switch (type) {
            case "switch" -> {
                if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                    return key + " 仅允许 true / false";
                }
            }
            case "select" -> {
                if (!f.options().contains(v)) {
                    return key + " 仅允许 " + String.join(" / ", f.options());
                }
            }
            case "number" -> {
                double n;
                try {
                    n = Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    return key + " 必须是数字";
                }
                if (isIntStep(f.step()) && n != Math.floor(n)) {
                    return key + " 必须是整数";
                }
                // factor：表单值 × factor = 落库值（如上传上限表单用 MB、落库存字节）⇒ 范围按表单单位提示
                double factor = f.factor() == null || f.factor() == 0 ? 1 : f.factor();
                if (f.min() != null && n < f.min() * factor) return key + " 需 ≥ " + num(f.min());
                if (f.max() != null && n > f.max() * factor) return key + " 需 ≤ " + num(f.max());
            }
            default -> {
                // text / textarea / password：无通用约束（敏感项另走掩码保护与加密）
            }
        }
        return null;
    }

    /** 整数步长（1/5/100…）⇒ 该字段只接受整数；小数步长（0.1/0.05）⇒ 允许小数 */
    private static boolean isIntStep(Double step) {
        return step == null || step == Math.floor(step);
    }

    private static String num(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
