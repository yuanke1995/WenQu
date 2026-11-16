package com.wenqu.ai.agent.context;

// [保留] 问渠检索 / 上下文个性化 —— 框架引擎与共享服务仍依赖，勿删。

import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本轮检索范围的解析。
 * <p>框架引擎与问答主链路要挂同一个知识检索
 * 工具，就必须拿得到同一把库界尺。留在上帝类里只有两种下场——要么框架侧复制一份（同一产品里两套
 * 库界判定，配错时静默放宽范围），要么框架侧干脆不限库（越权读库）。
 * <p>口径要点（{@code walled}）：{@code false}=登录页直接发起的父轮，普通智能体<b>返回 null＝不限库</b>，
 * 由检索侧按使用者可见库收口——智能体绑定的库不是「这一轮能查什么」的墙；
 * {@code true}=免登录分享页/对外 MCP/子智能体分支，才是权限墙。{@code null} 与「智能体没绑库」
 * 同路径，语义就是「没有墙」。
 * <p>内置「问渠」不受这条区分约束：它的范围恒为「本轮使用者的默认库＋官方手册库」，
 * <b>任何路径都是墙</b>（产品口径「不可改为其它库」，见智能体配置页资料范围一节），
 * 登录轮同样不放开——否则主智能体能自搜到专业域助手的库，派遣路由就永远不会发生。
 */
@Service
public class RetrievalScopeService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalScopeService.class);

    private final KnowledgeBaseService knowledgeBaseService;

    public RetrievalScopeService(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 一次解析出库级与文档级两层范围（工具与主链路共用同一个对象，避免两次解析走出分歧）。
     * <p>{@code parentScopeKbIds}：派遣分支轮带进来的「父轮实际解析出的库级墙」，
     * null = 父轮无墙或非分支轮。分支子轮按<b>「父 ∪ 子」</b>口径收口——子助手既查得到
     * 自己绑定的专业域库，也查得到父轮墙内的库（用户上传在默认库里的资料才不会被派遣挡在门外）；
     * 父轮无墙时子轮跟随普通口径（登录轮不限库），且子轮一律按整库收口、不参与文档级细选。
     */
    public KnowledgeRetrievalTool.KbScope resolve(Agent agent, String userId, boolean walled,
                                                  java.util.Collection<String> parentScopeKbIds) {
        java.util.Collection<String> kbIds = scopeKbIdsOf(agent, userId, walled, parentScopeKbIds);
        Set<String> docIds = resolveScopeDocIds(agent, userId, walled, kbIds, parentScopeKbIds);
        return new KnowledgeRetrievalTool.KbScope(kbIds, null, filterOf(agent, docIds));
    }

    /**
     * 智能体级文档条件 → 检索过滤器（单一出口）：标签（历史字段）+ 类型/最近更新（knowledge_filter JSON）。
     * <p>读取期非法 JSON 记 ERROR 并按「无此条件」处理：坏数据不能把该助手的检索整条打死，
     * 但也不能静默当成用户想要的条件——错误日志供管理侧发现。
     */
    private com.wenqu.ai.service.RetrievalFilter filterOf(Agent agent, Set<String> docIds) {
        Set<String> tags = tagFilterOf(agent);
        Set<String> fileTypes = null;
        java.time.LocalDateTime updatedAfter = null;
        String raw = agent == null ? null : agent.getKnowledgeFilter();
        if (raw != null && !raw.isBlank()) {
            try {
                var o = com.alibaba.fastjson2.JSON.parseObject(raw);
                var arr = o == null ? null : o.getJSONArray("fileTypes");
                if (arr != null) {
                    java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < arr.size(); i++) {
                        String v = arr.getString(i);
                        if (v != null && !v.isBlank()) {
                            types.add(v.trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                    if (!types.isEmpty()) fileTypes = types;
                }
                Integer days = o == null ? null : o.getInteger("updatedWithinDays");
                if (days != null && days > 0) {
                    updatedAfter = java.time.LocalDateTime.now().minusDays(days);
                }
            } catch (Exception e) {
                log.error("[KB] 智能体检索文档条件不是有效 JSON（本轮按无此条件处理）: {}", e.getMessage());
            }
        }
        return new com.wenqu.ai.service.RetrievalFilter(null, docIds, tags, fileTypes, updatedAfter, null, null);
    }

    /** 智能体级标签收窄（knowledgeTags 逗号分隔）：空=不过滤。文档打标见 c_ai_document.tags */
    private Set<String> tagFilterOf(Agent agent) {
        if (agent == null || agent.getKnowledgeTags() == null || agent.getKnowledgeTags().isBlank()) return null;
        Set<String> tags = Arrays.stream(agent.getKnowledgeTags().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return tags.isEmpty() ? null : tags;
    }

    private Collection<String> scopeKbIdsOf(Agent agent, String userId, boolean walled,
                                            Collection<String> parentScopeKbIds) {
        // 内置「问渠」判在 walled 闸之前：固定范围是产品口径，登录轮也成立（见类注释）。
        // 判在闸后就是「配置页写着不可改、运行时按可见库放开」——配了没生效。
        Set<String> builtinScope = builtinScopeKbIds(agent, userId);
        if (builtinScope != null) {
            if (parentScopeKbIds == null || parentScopeKbIds.isEmpty()) return builtinScope;
            Set<String> union = new java.util.LinkedHashSet<>(builtinScope);
            union.addAll(parentScopeKbIds);
            return union;
        }
        // 派遣子轮（口径 A「父 ∪ 子」）：父轮有墙时子轮至少看得见父墙，再并上自己绑定的库；
        // 没绑库的子助手就按父墙跑。父轮无墙（parentScopeKbIds=null）走下面的普通口径。
        if (parentScopeKbIds != null) {
            Set<String> union = new java.util.LinkedHashSet<>(parentScopeKbIds);
            if (agent != null && agent.getKnowledgeBaseIds() != null && !agent.getKnowledgeBaseIds().isBlank()) {
                union.addAll(KnowledgeBaseService.splitIds(agent.getKnowledgeBaseIds()));
            }
            return union;
        }
        if (!walled) return null;
        return (agent == null || agent.getKnowledgeBaseIds() == null || agent.getKnowledgeBaseIds().isBlank())
                ? null : KnowledgeBaseService.splitIds(agent.getKnowledgeBaseIds());
    }

    /**
     * 内置「问渠」的检索范围 = <b>本轮使用者的默认库「问渠」＋官方内置手册库</b>。
     * 默认库每人一个、没有可静态绑定的库 ID，按使用者动态解析；手册库全局一只、全员只读，
     * 并轨后「问渠怎么用」类问题才检索得到官方手册。
     *
     * @return 库 ID 集合（默认库在前）；非内置智能体或无法解析身份时返回 null（回落不限库）
     */
    private Set<String> builtinScopeKbIds(Agent agent, String userId) {
        String defaultKb = builtinDefaultKbId(agent, userId);
        if (defaultKb == null) return null;
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add(defaultKb);
        KnowledgeBase manual = knowledgeBaseService.builtinKb();
        if (manual != null && manual.getId() != null && !manual.getId().isBlank()) {
            out.add(manual.getId());
        }
        return out;
    }

    /** 内置「问渠」的默认库解析 = 本轮使用者自己的默认库（非内置/SYSTEM/解析失败返回 null）。 */
    private String builtinDefaultKbId(Agent agent, String userId) {
        if (agent == null || !Integer.valueOf(1).equals(agent.getIsBuiltin())) return null;
        if (userId == null || userId.isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(userId)) return null;
        try {
            return knowledgeBaseService.defaultId(userId);
        } catch (Exception e) {
            log.warn("[KB] 内置「问渠」默认库解析失败（本轮按不限库检索）uid={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 本轮允许检索的文档集合（null = 不额外限制，仍受可见性约束）。
     * 两级范围：库级（智能体关联库的文档并集）为主，{@code knowledgeScope} 非空时与之取交集。
     * 库 ID 配错得到空集合、检索自然为空——<b>不会退化为全库</b>，避免配置错误静默放宽范围。
     * 内置「问渠」例外：恒为默认库＋手册库的整库范围，文档级细选不参与。
     * 派遣子轮（父 ∪ 子）同样按整库收口：父墙里的库不该被子助手自己的细选误伤。
     */
    private Set<String> resolveScopeDocIds(Agent agent, String userId, boolean walled,
                                           Collection<String> resolvedKbIds,
                                           Collection<String> parentScopeKbIds) {
        if (agent == null) return null;
        // 与 scopeKbIdsOf 同一条口径：内置「问渠」的固定范围判在 walled 闸之前，登录轮同样收口
        if (builtinScopeKbIds(agent, userId) != null || parentScopeKbIds != null) {
            return resolvedKbIds == null || resolvedKbIds.isEmpty()
                    ? null : knowledgeBaseService.docIdsOf(resolvedKbIds);
        }
        if (!walled) return null;
        Set<String> byKb = null;
        String kbIds = agent.getKnowledgeBaseIds();
        if (kbIds != null && !kbIds.isBlank()) {
            Set<String> ids = KnowledgeBaseService.splitIds(kbIds);
            if (!ids.isEmpty()) byKb = knowledgeBaseService.docIdsOf(ids);
        }
        Set<String> fine = null;
        String scope = agent.getKnowledgeScope();
        if (scope != null && !scope.isBlank() && !"all".equalsIgnoreCase(scope.trim())) {
            fine = Arrays.stream(scope.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }
        if (byKb != null && fine != null) {
            Set<String> merged = new java.util.HashSet<>(byKb);
            merged.retainAll(fine);
            return merged;
        }
        return byKb != null ? byKb : fine;
    }
}
