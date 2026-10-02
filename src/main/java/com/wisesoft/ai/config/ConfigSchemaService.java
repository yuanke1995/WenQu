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
import java.util.Collection;
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
                        int tier, List<String> options, String help, boolean personal,
                        boolean personalOnly, String modelType) {
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
                // 声明了 tips 就必须在 tips 表里有文案：否则前端按 tips 键查不到 ⇒ 问号图标静默不渲染
                // （配置项"没有 tip"最难自查——定义为空即失败，不让它悄悄少一个提示）
                String tipsKey = j.getString("tips");
                if (tipsKey != null && !tipsKey.isBlank() && !this.tips.containsKey(tipsKey)) {
                    throw new IllegalStateException("config-schema.json 字段 " + key + " 的 tips 键不存在于 tips 表: " + tipsKey);
                }
                Field f = new Field(key, j.getString("panel"), j.getString("label"), j.getString("type"),
                        dbl(j, "min"), dbl(j, "max"), dbl(j, "step"), dbl(j, "factor"),
                        j.getIntValue("tier", 2), List.copyOf(opts), h.get(key),
                        j.getBooleanValue("personal", false),
                        j.getBooleanValue("personalOnly", false), j.getString("modelType"));
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

    /**
     * 字段是否允许个人覆盖（个人设置 → 对话偏好；值存 c_ai_user_config，覆盖系统全局值）。
     * 未定义字段按 false（fail-closed：个人保存接口只接受 schema 显式标记 personal 的键）。
     */
    public boolean isPersonal(String key) {
        Field f = byKey.get(key);
        return f != null && f.personal();
    }

    /**
     * 字段是否「个人专属」（仅个人设置可配、无系统全局层）。
     * <p>
     * 用于模型引用这类「值 = 某个用户登记的私有资产」的字段：全局槽位天然会把归属人的模型
     * 用成全平台默认（隔离原则：用户级数据不得被全局消费），故这类键：
     * <ul>
     *   <li>不出现在管理员设置页（{@link #describe()} 剔除）、管理端保存拒绝；</li>
     *   <li>{@code ConfigService.get} 跳过全局缓存/默认值层——未装载个人值的线程一律视为未配置。</li>
     * </ul>
     */
    public boolean isPersonalOnly(String key) {
        Field f = byKey.get(key);
        return f != null && f.personalOnly();
    }

    /** 字段类型（switch/number/model/...；未定义返回 null）。个人保存校验按类型分流用 */
    public String type(String key) {
        Field f = byKey.get(key);
        return f == null ? null : f.type();
    }

    /** 模型类字段声明的模型类型（chat/embedding/rerank/...；非模型字段或未声明返回 null） */
    public String modelType(String key) {
        Field f = byKey.get(key);
        return f == null ? null : f.modelType();
    }

    /**
     * 表单 path → backendKey 归一：两者通常相同，但个别字段表单 path 带面板前缀而运行键不带
     * （如 {@code retrieval.rerank.enabled} → {@code rerank.enabled}）。
     * 个人设置页提交的是表单 path，个人值必须落成运行键（ConfigService.get / 检索覆盖用的键），
     * 故保存前统一经此归一；未匹配原样返回（由 isPersonal 白名单判定拒绝）。
     */
    public String backendKeyOf(String pathOrKey) {
        if (pathOrKey == null) return null;
        if (byKey.containsKey(pathOrKey)) return pathOrKey;
        for (JSONObject j : rawFields) {
            if (pathOrKey.equals(j.getString("path"))) {
                String bk = j.getString("backendKey");
                if (bk != null && !bk.isBlank()) return bk;
            }
        }
        return pathOrKey;
    }

    /** backendKey → 表单 path（个人设置页 values/globals 按 path 回显；未知键原样返回） */
    public String pathOf(String backendKey) {
        if (backendKey == null) return null;
        for (JSONObject j : rawFields) {
            if (backendKey.equals(j.getString("backendKey"))) {
                String p = j.getString("path");
                return p == null || p.isBlank() ? backendKey : p;
            }
        }
        return backendKey;
    }

    /** schema 中标记 personal 的字段原始定义（个人设置页渲染用，保留 label/type/min/max/def 等表单属性） */
    public List<JSONObject> personalFields() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject j : rawFields) {
            if (j.getBooleanValue("personal", false)) out.add(j);
        }
        return out;
    }

    /** 指定 tips 键的文案子集（个人设置页只下发个人字段用到的 tips，避免整体文案包过大） */
    public Map<String, String> tipsOf(Collection<String> tipsKeys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : tipsKeys) {
            String v = tips.get(k);
            if (v != null) out.put(k, v);
        }
        return out;
    }

    /**
     * 按 backendKey 取字段问号文案（经字段定义的 tips 引用解析）：知识库参数弹窗等
     * 「非设置页」表单复用同一份文案，避免同一参数两处解释漂移。
     */
    public Map<String, String> tipsByKey(Collection<String> backendKeys) {
        java.util.Set<String> wanted = new java.util.HashSet<>(backendKeys);
        Map<String, String> out = new LinkedHashMap<>();
        for (JSONObject j : rawFields) {
            String key = j.getString("backendKey");
            if (key == null || !wanted.contains(key)) continue;
            String tipsKey = j.getString("tips");
            String text = tipsKey == null ? null : tips.get(tipsKey);
            if (text != null && !text.isBlank()) out.put(key, text);
        }
        return out;
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
        // personalOnly 字段（模型引用这类个人私有资产）不下发给管理端设置页：全局槽位不存在，
        // 渲染出来只会诱导管理员把个人模型配成"全平台默认"（真正的消费方都不再读全局层）
        List<JSONObject> adminFields = new ArrayList<>(rawFields.size());
        for (JSONObject j : rawFields) {
            if (!j.getBooleanValue("personalOnly", false)) adminFields.add(j);
        }
        m.put("fields", adminFields);
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
