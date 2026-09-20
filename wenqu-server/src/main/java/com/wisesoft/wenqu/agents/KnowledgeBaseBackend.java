package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 按上下文解析"当前可见知识库"，并把结果挂回上下文。
 *
 * <p>由参考实现的 {@code agents/backends/knowledge_base_backend.py} 翻译（该文件只有一个
 * 模块级函数 {@code resolve_visible_knowledge_bases_for_context}）。
 *
 * <p>参考实现的模块级函数只有函数本身（单函数模块），本工程沿用同类共享层的既有做法
 * （见 {@code service/KnowledgeRouteSupport}）：收敛为 {@code @Service}，便于注入
 * {@link KnowledgeBaseManager}。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>参考实现 {@code knowledge/runtime.py} 的模块级 {@code knowledge_base} 的函数内迟延导入 →
 *       构造注入 {@link KnowledgeBaseManager}（本工程知识库运行时即由它承载）。</li>
 *   <li>{@code await knowledge_base.get_databases_by_uid(...)} → 同步调用
 *       {@link KnowledgeBaseManager#getDatabasesByUid(String)}（本工程无异步 DB 层）。</li>
 *   <li>{@code summary.kb_id / .name / .description / .kb_type} → record 访问器
 *       {@code kbId() / name() / description() / kbType()}。</li>
 *   <li>{@code setattr(context, "_visible_knowledge_bases", …)} → {@link BaseContext#setDynamic}
 *       （本工程 {@code set()} 只写已声明字段，动态属性走单列一表，避免静默失效）。</li>
 * </ul>
 */
@Service
public class KnowledgeBaseBackend {

    /** 上下文动态属性名（参考实现字面量，逐字保留）。 */
    public static final String VISIBLE_KNOWLEDGE_BASES_ATTR = "_visible_knowledge_bases";

    private final KnowledgeBaseManager knowledgeBaseManager;

    public KnowledgeBaseBackend(KnowledgeBaseManager knowledgeBaseManager) {
        this.knowledgeBaseManager = knowledgeBaseManager;
    }

    /**
     * 解析当前上下文可见的知识库列表，并写入上下文动态属性。
     *
     * <p>无 {@code uid} 时写入空列表并返回空列表（对应参考实现未登录分支）；
     * 上下文声明了 {@code knowledges} 时按其白名单过滤（空串与非字符串值不构成 id）。
     */
    public List<Map<String, Object>> resolveVisibleKnowledgeBasesForContext(BaseContext context) {
        Object rawUid = context == null ? null : context.get("uid");
        String uid = rawUid == null ? "" : String.valueOf(rawUid);
        if (uid.isEmpty()) {
            context.setDynamic(VISIBLE_KNOWLEDGE_BASES_ATTR, new ArrayList<>());
            return new ArrayList<>();
        }

        List<KnowledgeBaseSummary> summaries = knowledgeBaseManager.getDatabasesByUid(uid);
        List<Map<String, Object>> databases = new ArrayList<>();
        if (summaries != null) {
            for (KnowledgeBaseSummary summary : summaries) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kb_id", summary.kbId());
                item.put("name", summary.name());
                item.put("description", summary.description());
                item.put("kb_type", summary.kbType());
                databases.add(item);
            }
        }

        Object enabledKnowledges = context.get("knowledges");
        if (enabledKnowledges != null) {
            Set<String> enabledIds = new LinkedHashSet<>();
            if (enabledKnowledges instanceof List<?> list) {
                for (Object value : list) {
                    String text = value == null ? "" : String.valueOf(value).strip();
                    if (!text.isEmpty()) {
                        enabledIds.add(text);
                    }
                }
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> database : databases) {
                Object kbId = database.get("kb_id");
                String text = kbId == null ? "" : String.valueOf(kbId).strip();
                if (enabledIds.contains(text)) {
                    filtered.add(database);
                }
            }
            databases = filtered;
        }

        context.setDynamic(VISIBLE_KNOWLEDGE_BASES_ATTR, databases);
        return databases;
    }
}
