package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.ConfigSchemaService;
import com.wisesoft.ai.mapper.UserConfigMapper;
import com.wisesoft.ai.model.UserConfig;
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
            globals.put(key, configService.get(key));
        }
        if (uid != null && !uid.isBlank()) {
            values.putAll(overrides(uid));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("tips", schema.tipsOf(tipsKeys));
        out.put("values", values);
        out.put("globals", globals);
        return out;
    }

    /**
     * 保存个人覆盖（字段缺省 = 不修改；空串 = 清除该项、回落全局）。
     * 校验：仅接受 schema 标记 personal 的键（其余拒绝，防越权写配置）+ 类型/范围校验（与设置页同规则）。
     *
     * @return 保存后的该项个人值（清空为 "" 表示已回落全局）
     */
    public Map<String, String> save(String uid, Map<String, Object> body) {
        if (uid == null || uid.isBlank()) throw new com.wisesoft.ai.common.BizException("未登录");
        if (body == null || body.isEmpty()) throw new com.wisesoft.ai.common.BizException("没有要保存的配置项");
        Map<String, String> saved = new LinkedHashMap<>();
        List<UserConfig> rows = userConfigMapper.selectList(
                new LambdaQueryWrapper<UserConfig>().eq(UserConfig::getUid, uid));
        Map<String, UserConfig> existing = new HashMap<>();
        for (UserConfig r : rows) existing.put(r.getConfigKey(), r);

        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = e.getKey();
            if (!schema.isPersonal(key)) {
                throw new com.wisesoft.ai.common.BizException("不支持个人设置的配置项：" + key);
            }
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            String err = schema.validate(key, v);
            if (err != null) throw new com.wisesoft.ai.common.BizException(err);
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
}
