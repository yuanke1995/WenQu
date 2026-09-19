package com.wisesoft.wenqu.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库配置的敏感信息处理工具。
 *
 * <p>由参考实现的 knowledge/utils/security.py 逐行翻译，标记清单与大小写归一规则保持一致。
 */
public final class KnowledgeSecurity {

    /** 敏感参数键标记（与参考实现 _SENSITIVE_PARAMETER_MARKERS 一致）。 */
    private static final List<String> SENSITIVE_PARAMETER_MARKERS =
            List.of("token", "secret", "password", "api_key");

    private KnowledgeSecurity() {}

    /** 移除知识库类型参数中的敏感凭据。 */
    public static Map<String, Object> redactSensitiveParams(Map<String, Object> params) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        if (params == null) {
            return redacted;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String normalizedKey = key.toLowerCase();
            boolean sensitive = false;
            for (String marker : SENSITIVE_PARAMETER_MARKERS) {
                if (normalizedKey.contains(marker)) {
                    sensitive = true;
                    break;
                }
            }
            if (sensitive) {
                continue;
            }
            redacted.put(key, entry.getValue());
        }
        return redacted;
    }
}
