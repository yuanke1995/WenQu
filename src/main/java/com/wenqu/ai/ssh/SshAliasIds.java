package com.wenqu.ai.ssh;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 「本机 OpenSSH 别名」工作区的虚拟执行器 id 编解码。
 *
 * <p>id 形如 {@code ssh-alias:<executorId>:<base64url(alias)>}：executorId 是用户本机执行器（哪台电脑的
 * {@code ~/.ssh/config}），alias 是其中的 Host 别名（如 {@code wenqu}）。用 base64url 是为了让别名里
 * 可能出现的字符不影响 id 解析；问渠不保存任何私钥/密码。
 *
 * @author yuanke
 */
public final class SshAliasIds {

    public static final String PREFIX = "ssh-alias:";

    private SshAliasIds() {
    }

    public record Parsed(String executorId, String alias) {
    }

    public static String virtualId(String executorId, String alias) {
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(alias == null ? "" : alias).getBytes(StandardCharsets.UTF_8));
        return PREFIX + executorId + ":" + b64;
    }

    public static Parsed parse(String virtualId) {
        if (virtualId == null || !virtualId.startsWith(PREFIX)) return null;
        String rest = virtualId.substring(PREFIX.length());
        int i = rest.indexOf(':');
        if (i <= 0 || i == rest.length() - 1) return null;
        String executorId = rest.substring(0, i);
        try {
            String alias = new String(Base64.getUrlDecoder().decode(rest.substring(i + 1)), StandardCharsets.UTF_8);
            if (alias.isBlank()) return null;
            return new Parsed(executorId, alias);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
