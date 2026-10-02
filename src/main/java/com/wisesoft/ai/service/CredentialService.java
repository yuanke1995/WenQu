package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.CredentialMapper;
import com.wisesoft.ai.model.Credential;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流凭据（第 3 期）：http 节点里以 {@code {{credential:名称}}} 引用，密钥不进 DSL 明文。
 * <p>
 * 归属口径与个人资产一致——完全按 {@code uid} 隔离，不共享（创建者之外任何人查不到）。
 * 值以 RSA 密文落库（复用 {@link ConfigCryptoService}，与供应商 apiKey 同款）；
 * 出参一律脱敏为 {@code ****后4位}，明文只在节点执行瞬间解密，不进 state / trace / 日志。
 * 提交值以 {@code ****} 开头视为「未修改」跳过（与 ConfigService 的掩码回写保护同约定）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private static final int MAX_NAME = 64;
    private static final int MAX_REMARK = 200;
    private static final int MAX_VALUE = 4000;
    /** 掩码前缀：提交值以此开头视为未修改（不覆盖已存密文） */
    private static final String MASK_PREFIX = "****";

    private final CredentialMapper credentialMapper;
    private final ConfigCryptoService crypto;

    /** 我的凭据列表（值脱敏；按更新时间倒序） */
    public List<Map<String, Object>> list(String uid) {
        List<Credential> rows = credentialMapper.selectList(new LambdaQueryWrapper<Credential>()
                .eq(Credential::getUid, uid)
                .orderByDesc(Credential::getUpdateTime));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Credential c : rows) out.add(toDto(c));
        return out;
    }

    /** 新建凭据：名称必填且同用户内唯一（唯一键兜底并发） */
    public Map<String, Object> create(String uid, String name, String value, String remark) {
        String n = requireName(name);
        String v = requireValue(value);
        Credential c = new Credential();
        c.setId(UUID.randomUUID().toString());
        c.setUid(uid);
        c.setName(n);
        c.setValue(crypto.encrypt(v));
        c.setRemark(trimTo(remark, MAX_REMARK));
        c.setCreateTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        try {
            credentialMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new BizException("已有同名凭据「" + n + "」，请换个名称");
        }
        log.info("[CREDENTIAL] uid={} 新建凭据「{}」", uid, n);
        return toDto(c);
    }

    /** 编辑：值以 **** 开头（或留空）= 保持不变 */
    public Map<String, Object> update(String uid, String id, String name, String value, String remark) {
        Credential c = ownRow(uid, id);
        LambdaUpdateWrapper<Credential> up = new LambdaUpdateWrapper<Credential>()
                .eq(Credential::getId, id)
                .set(Credential::getUpdateTime, LocalDateTime.now());
        if (name != null && !name.isBlank()) up.set(Credential::getName, requireName(name));
        if (remark != null) up.set(Credential::getRemark, trimTo(remark, MAX_REMARK));
        if (value != null && !value.isBlank() && !value.trim().startsWith(MASK_PREFIX)) {
            if (value.length() > MAX_VALUE) throw new BizException("凭据值不超过 " + MAX_VALUE + " 个字符");
            up.set(Credential::getValue, crypto.encrypt(value));
        }
        try {
            credentialMapper.update(null, up);
        } catch (DuplicateKeyException e) {
            throw new BizException("已有同名凭据「" + name + "」，请换个名称");
        }
        log.info("[CREDENTIAL] uid={} 更新凭据 {}", uid, id);
        return toDto(ownRow(uid, id));
    }

    public void delete(String uid, String id) {
        Credential c = ownRow(uid, id);
        credentialMapper.deleteById(c.getId());
        log.info("[CREDENTIAL] uid={} 删除凭据「{}」", uid, c.getName());
    }

    /**
     * 引擎用：取该用户全部凭据的<b>明文</b>（名称 → 明文）。一次查库，避免逐条解密；
     * 只在节点执行瞬间调用，明文不得进入 state / trace / 日志。
     */
    public Map<String, String> plainByName(String uid) {
        Map<String, String> out = new LinkedHashMap<>();
        if (uid == null || uid.isBlank()) return out;
        for (Credential c : credentialMapper.selectList(new LambdaQueryWrapper<Credential>()
                .eq(Credential::getUid, uid))) {
            String plain = crypto.decrypt(c.getValue());
            if (plain != null) out.put(c.getName(), plain);
        }
        return out;
    }

    /** 归属校验：非本人记录一律按「不存在」处理（不泄露存在性） */
    private Credential ownRow(String uid, String id) {
        Credential c = id == null ? null : credentialMapper.selectById(id);
        if (c == null || !uid.equals(c.getUid())) throw new BizException(404, "凭据不存在");
        return c;
    }

    /** 出参：值脱敏为 ****后4位（与供应商 apiKey / 配置快照同约定），绝不回明文 */
    private Map<String, Object> toDto(Credential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("remark", c.getRemark());
        m.put("valueMasked", mask(crypto.decrypt(c.getValue())));
        m.put("createTime", c.getCreateTime() == null ? null : c.getCreateTime().toString());
        m.put("updateTime", c.getUpdateTime() == null ? null : c.getUpdateTime().toString());
        return m;
    }

    private static String mask(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (plain.length() <= 4) return MASK_PREFIX;
        return MASK_PREFIX + plain.substring(plain.length() - 4);
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new BizException("请填写凭据名称");
        if (n.length() > MAX_NAME) throw new BizException("凭据名称不超过 " + MAX_NAME + " 个字符");
        if (n.contains("}") || n.contains("{")) throw new BizException("凭据名称不能包含大括号");
        return n;
    }

    private static String requireValue(String value) {
        if (value == null || value.isBlank()) throw new BizException("请填写凭据值");
        if (value.length() > MAX_VALUE) throw new BizException("凭据值不超过 " + MAX_VALUE + " 个字符");
        return value;
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
