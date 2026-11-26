package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.UserModelThinkMapper;
import com.wenqu.ai.model.UserModelThink;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人「按模型」的思考档位记忆（{@code c_ai_user_model_think}）。
 *
 * <p>为什么不是一列全局开关：运行设置里档位是<b>按模型</b>记的——qwen 选「中」不该把 kimi 也带成「中」，
 * 模型能支持哪几档也各不一样。所以归属键必须是 {@code (uid, model_ref)}，每模型一行。
 * <p>存的是前端那三种状态之一：{@code off}（关闭思考）、{@code on}（开启但该模型未登记强度）、
 * 或 {@code low/medium/high/xhigh/max}。没有行 = 没单独选过，运行时回落模型登记默认档位。
 * <p>写入时复用模型库的判据（能不能用 / 支不支持思考 / 支持哪几档），避免存进一个永远不会生效的值；
 * 这里的拒绝只影响「记忆」，不影响已发出的问答。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThinkPreferenceService {

    /** 关闭思考（显式选择；与「没有行」区分开） */
    public static final String OFF = "off";

    /** 开启思考但不下发强度：模型未登记档位时前端只能给「开启」这一项 */
    public static final String ON = "on";

    /** 与 c_ai_message.model 列同宽 */
    private static final int MAX_REF_LEN = 190;

    private final UserModelThinkMapper mapper;
    private final ModelRegistryService modelRegistry;

    /**
     * 本人按模型的思考档位：{模型引用 → off|on|档位}。未设置的模型不在表里（调用方按默认档位回落）。
     */
    public Map<String, String> levelsOf(String uid) {
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) return Map.of();
        List<UserModelThink> rows = mapper.selectList(new LambdaQueryWrapper<UserModelThink>()
                .eq(UserModelThink::getUid, uid));
        Map<String, String> out = new LinkedHashMap<>();
        for (UserModelThink row : rows) {
            if (row.getModelRef() == null || row.getModelRef().isBlank()) continue;
            if (row.getThinkLevel() == null || row.getThinkLevel().isBlank()) continue;
            out.put(row.getModelRef(), row.getThinkLevel());
        }
        return out;
    }

    /**
     * 保存某模型的思考档位（只写本人）：{@code level} 为空 = 清除该模型的记忆（回到模型默认）。
     *
     * @param uid      本人 uid（取登录态）
     * @param role     本人主角色（模型可用性判据需要）
     * @param modelRef 模型引用 providerId/modelId
     * @param level    off / on / low|medium|high|xhigh|max；空 = 清除
     */
    public void save(String uid, String role, String modelRef, String level) {
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) {
            throw new BizException(401, "未登录：思考档位只保存到本人名下");
        }
        String ref = modelRef == null ? "" : modelRef.trim();
        if (ref.isEmpty()) throw new BizException("缺少模型引用（providerId/modelId）");
        if (ref.length() > MAX_REF_LEN) throw new BizException("模型引用过长");
        String value = level == null ? "" : level.trim().toLowerCase();
        if (value.isEmpty()) {
            mapper.delete(new LambdaQueryWrapper<UserModelThink>()
                    .eq(UserModelThink::getUid, uid).eq(UserModelThink::getModelRef, ref));
            log.info("[THINK-PREF] 已清除 uid={} model={} 的思考档位记忆", uid, ref);
            return;
        }
        assertSupported(ref, value, uid, role);
        UserModelThink row = mapper.selectOne(new LambdaQueryWrapper<UserModelThink>()
                .eq(UserModelThink::getUid, uid).eq(UserModelThink::getModelRef, ref));
        if (row == null) {
            row = new UserModelThink();
            row.setUid(uid);
            row.setModelRef(ref);
            row.setThinkLevel(value);
            mapper.insert(row);
        } else if (!value.equals(row.getThinkLevel())) {
            row.setThinkLevel(value);
            mapper.updateById(row);
        }
        log.info("[THINK-PREF] 已保存 uid={} model={} level={}", uid, ref, value);
    }

    /** 该值在这个模型上是否成立：模型可用、支持思考、不是恒思考锁定、档位在登记集合内。 */
    private void assertSupported(String ref, String value, String uid, String role) {
        ModelRegistryService.Usability usability = modelRegistry.referenceUsability(ref, uid, role);
        if (!usability.usable()) {
            throw new BizException("该模型" + modelRegistry.issueMessage(usability) + "，不能保存思考档位");
        }
        String caps = modelRegistry.referenceThinking(ref);
        if (ModelRegistryService.THINK_NONE.equals(caps)) {
            throw new BizException("该模型不支持思考，不能保存思考档位");
        }
        if (ModelRegistryService.THINK_ALWAYS.equals(caps)) {
            throw new BizException("该模型恒思考，强度由管理员在模型库登记，不能按人调整");
        }
        if (OFF.equals(value) || ON.equals(value)) return;
        List<String> levels = modelRegistry.reasoningLevelsOf(ref);
        if (!levels.contains(value)) {
            throw new BizException("该模型不支持「" + value + "」思考档位"
                    + (levels.isEmpty() ? "（未登记强度档位，只能选开启/关闭）" : "，可选：" + String.join(" / ", levels)));
        }
    }
}