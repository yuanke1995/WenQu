package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.ConfigSchemaService;
import com.wenqu.ai.mapper.UserConfigMapper;
import com.wenqu.ai.model.UserConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 个人配置覆盖服务（个人设置 → 对话偏好）：个人值覆盖系统全局值（c_ai_config），仅对本人问答生效。
 * <p>
 * 键限定为 config-schema.json 中标记 {@code personal} 的字段（schema 是字段定义的唯一定义源，
 * 加参数只改 schema 勾选，本服务与前端页面自动跟随）；校验复用 {@link ConfigSchemaService#validate}
 * （与管理员设置页同一份规则）。存 c_ai_user_config（uid + 键 + 值），清空 = 删行 = 回落全局。
 * <p>
 * 不做缓存：问答流水线每轮一次唯一索引点查（≤ 数行），与每轮既有的一两次用户表主键查询同量级；
 * 换来的是"个人设置保存即生效、多实例天然一致"，不引入又要广播又要失效的缓存。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserConfigService {

    private final UserConfigMapper userConfigMapper;
    private final ConfigSchemaService schema;
    private final ConfigService configService;
    /** 模型引用判权（个人设置里的模型必须归属本人登记；类型须与字段声明一致） */
    private final ModelRegistryService modelRegistryService;

    /**
     * 读取某用户的个人覆盖（键过滤为 schema 标记 personal 的字段；空值不返回）。
     * 供问答流水线装载进 {@link ConfigService#putUserOverrides}（ThreadLocal，仅本轮线程生效）。
     */
    public Map<String, String> overrides(String uid) {
        if (uid == null || uid.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            List<UserConfig> rows = userConfigMapper.selectList(
                    new LambdaQueryWrapper<UserConfig>().eq(UserConfig::getUid, uid));
            for (UserConfig r : rows) {
                if (!schema.isPersonal(r.getConfigKey())) continue;
                if (r.getConfigValue() == null || r.getConfigValue().isBlank()) continue;
                out.put(r.getConfigKey(), r.getConfigValue());
            }
        } catch (Exception e) {
            // fail-safe：读失败按"未设置个人覆盖"继续（本轮问答走全局值），但必须留痕
            log.warn("[UserConfig] 个人配置读取失败（本轮按全局值）uid={}: {}", uid, e.getMessage());
        }
        return out;
    }

    /**
     * 个人设置页数据源：可个人覆盖的字段定义 + 当前个人值 + 系统全局值（界面展示"跟随系统"参照）。
     * 只下发个人字段用到的 tips 文案，避免整包文案过大。
     * <p>
     * 键口径：页面以**表单 path** 为键（与 fields[].path、提交键一致）；存储与运行键是
     * backendKey——个别字段两者不同（如 upload.maxFileSize 的表单 path 是 upload.maxFileSizeMB），
     * 这里统一映射，页面无感。
     */
    public Map<String, Object> describe(String uid) {
        List<JSONObject> fields = schema.personalFields();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> globals = new LinkedHashMap<>();
        Set<String> tipsKeys = new HashSet<>();
        for (JSONObject f : fields) {
            String key = f.getString("backendKey");
            String tips = f.getString("tips");
            if (tips != null && !tips.isBlank()) tipsKeys.add(tips);
            globals.put(schema.pathOf(key), configService.get(key));
        }
        if (uid != null && !uid.isBlank()) {
            for (Map.Entry<String, String> e : overrides(uid).entrySet()) {
                values.put(schema.pathOf(e.getKey()), e.getValue());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("tips", schema.tipsOf(tipsKeys));
        out.put("values", values);
        out.put("globals", globals);
        return out;
    }

    /**
     * 某键的个人设置值（按归属 uid 显式解析，线程无关；未设置返回 ""）。
     * <p>
     * 供解析/抽取等<b>异步链路</b>按库主身份取值——这些线程没有问答流水线的个人覆盖装载
     * （ThreadLocal 拿不到），模型引用又只认归属人（personalOnly：全局层不参与读取），
     * 必须显式点名 uid 查询；问答流水线线程内请继续走 ConfigService.get（有覆盖语义）。
     */
    public String personalValue(String uid, String key) {
        if (uid == null || uid.isBlank()) return "";
        String v = overrides(uid).get(key);
        return v == null ? "" : v;
    }

    /**
     * 保存个人覆盖（字段缺省 = 不修改；空串 = 清除该项、回落全局）。
     * 校验：仅接受 schema 标记 personal 的键（其余拒绝，防越权写配置）+ 类型/范围校验（与设置页同规则）；
     * 模型类字段追加归属校验——引用必须对本人可用（他人登记的个人供应商拒用，与聊天/向量入口同口径），
     * 且类型须与字段声明的 modelType 一致（重排字段不能填聊天模型），防止存进一个运行时静默失效的引用。
     *
     * @return 保存后的该项个人值（清空为 "" 表示已回落全局）
     */
    public Map<String, String> save(String uid, String role, Map<String, Object> body) {
        if (uid == null || uid.isBlank()) throw new com.wenqu.ai.common.BizException("未登录");
        if (body == null || body.isEmpty()) throw new com.wenqu.ai.common.BizException("没有要保存的配置项");
        Map<String, String> saved = new LinkedHashMap<>();
        List<UserConfig> rows = userConfigMapper.selectList(
                new LambdaQueryWrapper<UserConfig>().eq(UserConfig::getUid, uid));
        Map<String, UserConfig> existing = new HashMap<>();
        for (UserConfig r : rows) existing.put(r.getConfigKey(), r);

        for (Map.Entry<String, Object> e : body.entrySet()) {
            String rawKey = e.getKey();
            // 表单 path → 运行键（如 upload.maxFileSizeMB → upload.maxFileSize）：个人值按运行键存储，
            // 才能被 ConfigService.get / 检索覆盖读到；存储键同时是后续读取的唯一索引
            String key = schema.backendKeyOf(rawKey);
            if (key == null || !schema.isPersonal(key)) {
                throw new com.wenqu.ai.common.BizException("不支持个人设置的配置项：" + rawKey);
            }
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            String err = schema.validate(key, v);
            if (err != null) throw new com.wenqu.ai.common.BizException(err);
            if (!v.isEmpty() && "model".equals(schema.type(key))) {
                validateModelRef(key, v, uid, role);
            }
            UserConfig row = existing.get(key);
            if (v.isEmpty()) {
                // 清空 = 删行 = 回落全局（保留空串行会让"是否设置过"两种状态难区分）
                if (row != null) userConfigMapper.deleteById(row.getId());
                saved.put(key, "");
                continue;
            }
            if (row == null) {
                row = new UserConfig();
                row.setUid(uid);
                row.setConfigKey(key);
                row.setConfigValue(v);
                userConfigMapper.insert(row);
            } else {
                row.setConfigValue(v);
                userConfigMapper.updateById(row);
            }
            saved.put(key, v);
        }

        log.info("[UserConfig] 个人配置已更新 uid={} keys={}", uid, saved.keySet());
        return saved;
    }

    /** 模型类个人字段的引用校验：可解析 + 对本人可用 + 类型与字段声明一致（未登记类型放行；期望 vision 时放宽——vision/omni 或具备图片理解能力的模型均通过） */
    private void validateModelRef(String key, String v, String uid, String role) {
        if (modelRegistryService.resolveReference(v) == null) {
            throw new com.wenqu.ai.common.BizException("模型无效或已被删除，请重新选择（" + key + "）");
        }
        modelRegistryService.assertUsable(v, uid, role);
        String expected = schema.modelType(key);
        if (expected != null && !modelRegistryService.referenceMatchesType(v, expected)) {
            String actual = modelRegistryService.referenceType(v);
            throw new com.wenqu.ai.common.BizException("「" + key + "」需选择 " + expected
                    + " 类型的模型（当前所选为 " + actual + " 类型）");
        }
    }
}
