package com.wenqu.ai.openapi;

import java.util.List;

/**
 * API Key 能力范围的唯一真源：注解元数据、签发校验、前端名册、OpenAPI 契约四处都引用这里。
 *
 * <p>此前名单写在 {@code ApiKeyService} 里，注册表要校验注解时只能反向依赖 service；
 * 现在下沉到契约层，service 侧只保留同名的公开常量别名，不再自己维护一份集合。</p>
 */
public final class OpenApiScopes {

    public static final String CHAT = "chat";
    public static final String EMBEDDING = "embedding";
    public static final String KB_READ = "kb.read";
    public static final String KB_WRITE = "kb.write";
    public static final String WORKFLOW_RUN = "workflow.run";

    /** 全部合法 scope（前端勾选项与后端校验共用这一份名单） */
    public static final List<String> ALL = List.of(
            CHAT, EMBEDDING, KB_READ, KB_WRITE, WORKFLOW_RUN);

    /** 默认集：除 kb.write 外全部——写库需显式授权 */
    public static final List<String> DEFAULT = List.of(
            CHAT, EMBEDDING, KB_READ, WORKFLOW_RUN);

    private OpenApiScopes() {
    }
}
