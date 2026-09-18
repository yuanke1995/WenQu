package com.wisesoft.wenqu.permissions;

/**
 * 声明可通过共享配置进行权限解析的资源字段。
 *
 * <p>由参考实现的 permissions/resource_permission.py 中 ShareableResource（Protocol）翻译。
 * 参考实现依赖 Python 的鸭子类型，只要求资源对象具备 {@code created_by} 与 {@code share_config}
 * 两个属性；Java 侧改为显式接口，并提供各实体的适配方法——这是语言差异，不改变解析逻辑。
 */
public interface ShareableResource {

    /** 资源创建者 uid。 */
    String createdBy();

    /** 原始共享配置（v2 JSON 对象）。 */
    com.alibaba.fastjson2.JSONObject shareConfig();

    /** Skill 专用：来源范围（personal / 其它）。非 Skill 资源默认返回 null。 */
    default String sourceScope() {
        return null;
    }

    /** 从实体适配：智能体。 */
    static ShareableResource of(com.wisesoft.wenqu.models.Agent agent) {
        return new ShareableResource() {
            @Override
            public String createdBy() {
                return agent.getCreatedBy();
            }

            @Override
            public com.alibaba.fastjson2.JSONObject shareConfig() {
                return com.wisesoft.wenqu.repositories.RepoValues.parseObject(agent.getShareConfig());
            }
        };
    }

    /** 从实体适配：知识库。 */
    static ShareableResource of(com.wisesoft.wenqu.models.KnowledgeBase knowledgeBase) {
        return new ShareableResource() {
            @Override
            public String createdBy() {
                return knowledgeBase.getCreatedBy();
            }

            @Override
            public com.alibaba.fastjson2.JSONObject shareConfig() {
                return com.wisesoft.wenqu.repositories.RepoValues.parseObject(knowledgeBase.getShareConfig());
            }
        };
    }

    /** 从实体适配：技能。 */
    static ShareableResource of(com.wisesoft.wenqu.models.Skill skill) {
        return new ShareableResource() {
            @Override
            public String createdBy() {
                return skill.getCreatedBy();
            }

            @Override
            public com.alibaba.fastjson2.JSONObject shareConfig() {
                return com.wisesoft.wenqu.repositories.RepoValues.parseObject(skill.getShareConfig());
            }

        };
        // 注意：技能实体没有 source_scope 列（参考实现的 Skill 模型只有 source_type），
        // 而参考实现用 getattr(resource, "source_scope", None) 读取——对 ORM 对象恒为 None。
        // 因此这里沿用接口默认值 null，与参考实现对 ORM 对象的行为一致；
        // 需要该字段的调用方应使用 ofMap（对应参考实现传字典的用法）。
    }

    /** 从 Map 适配（参考实现支持字典入参）。 */
    static ShareableResource ofMap(java.util.Map<String, Object> source) {
        return new ShareableResource() {
            @Override
            public String createdBy() {
                Object value = source.get("created_by");
                return value == null ? null : String.valueOf(value);
            }

            @Override
            public com.alibaba.fastjson2.JSONObject shareConfig() {
                return com.wisesoft.wenqu.repositories.RepoValues.toJsonObject(source.get("share_config"));
            }

            @Override
            public String sourceScope() {
                Object value = source.get("source_scope");
                return value == null ? null : String.valueOf(value);
            }
        };
    }
}
