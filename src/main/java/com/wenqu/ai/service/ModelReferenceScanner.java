package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.model.Config;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.UserConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型引用扫描器：回答「这个供应商/模型到底被谁在用」。
 *
 * <h3>为什么抽出独立服务</h3>
 * 原先这套检查内联在 {@code ModelRegistryService.deleteProvider} 里（refs 是方法内局部变量），
 * 结果只有「删除时被拒绝」这一个场景能用。额度不足时用户真正需要的恰恰相反——他要知道该去
 * 哪里把引用改掉，「你知道有问题」本身没用，「你有问题的地方在这里」才有用。
 *
 * <h3>覆盖的引用源</h3>
 * 六类存模型引用的位置，全部按 {@code {providerId}/} 前缀 likeRight 匹配：
 * <ol>
 *   <li><b>知识库向量模型</b>（{@code c_ai_knowledge_base.embedding_ref}）——影响面最大，
 *       额度不足会导致该库检索直接失败；</li>
 *   <li><b>知识库图谱模型</b>（{@code graph_model_ref}）——图谱增强链路用；</li>
 *   <li><b>个人默认模型</b>（{@code c_ai_user.default_model}）——该用户每一次未指定模型的问答都会撞上；</li>
 *   <li><b>个人设置模型引用</b>（{@code c_ai_user_config.config_value} 里的历史个人覆盖行）；</li>
 *   <li><b>系统配置槽位</b>（{@code c_ai_config}，如 rerank.platformRef）——影响全平台；</li>
 *   <li><b>游客对话模型</b>（{@code c_ai_agent_share.model_ref}）——公开分享链接的调用方。</li>
 * </ol>
 * 另附<b>会话级覆盖</b>的提示：会话表不逐条扫描（数量大且会话短命），但会在结果里显式说明
 * 「当前会话里可能仍有指向它的历史覆盖」——不静默略过，否则用户改完仍会遇到失败而无解。
 *
 * <h3>与删除守门的关系</h3>
 * {@code deleteProvider} 改为复用本服务，保证「删不掉」和「改不掉」看到的引用集合完全一致——
 * 两处口径各写一遍必然漂移，出现「删时被拦却找不到地方改」的僵局。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelReferenceScanner {

    private final com.wenqu.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    private final com.wenqu.ai.mapper.UserConfigMapper userConfigMapper;
    private final com.wenqu.ai.mapper.ConfigMapper configMapper;
    private final com.wenqu.ai.mapper.AgentShareMapper agentShareMapper;
    private final ConfigService configService;

    /**
     * 一条引用记录。
     *
     * @param kind   引用类型标识（前端按此决定跳转与展示形态）
     * @param label  中文标签（如「知识库向量模型」）
     * @param name   引用方的可读名称（知识库名 / 用户名 / 配置键）
     * @param id     引用方 ID（可空：系统配置槽位无 ID）；前端据此拼跳转链接
     * @param hint   补充说明（如「该库检索会直接失败」「影响全平台重排」）
     * @param editable 是否可在界面内直接改掉（系统配置槽位可在设置页改，故为 true；
     *                 会话覆盖无管理入口，为 false）
     */
    public record Reference(String kind, String label, String name, String id, String hint, boolean editable) {
    }

    /** 引用类型常量（前端据此渲染图标/跳转） */
    public static final String KIND_KB_EMBEDDING = "kbEmbedding";
    public static final String KIND_KB_GRAPH = "kbGraph";
    public static final String KIND_USER_DEFAULT = "userDefault";
    public static final String KIND_USER_CONFIG = "userConfig";
    public static final String KIND_CONFIG_SLOT = "configSlot";
    public static final String KIND_AGENT_SHARE = "agentShare";
    public static final String KIND_SESSION = "session";

    /** 单类引用返回条数上限（防止一个供应商被几百个库引用时把响应撑爆；超出只给计数） */
    private static final int PER_KIND_MAX = 50;

    /**
     * 扫描某供应商被引用的全部位置（{@code providerId} 为空或供应商不存在时返回空列表）。
     *
     * @param providerId 供应商 ID
     * @return 引用清单（按影响面排序：知识库 → 个人默认 → 全平台配置 → 其它）
     */
    public List<Reference> referencesOf(String providerId) {
        List<Reference> refs = new ArrayList<>();
        if (providerId == null || providerId.isBlank()) return refs;
        String prefix = providerId + "/";

        refs.addAll(kbReferences(prefix));
        refs.addAll(userDefaultReferences(prefix));
        refs.addAll(configSlotReferences(prefix));
        refs.addAll(agentShareReferences(prefix));
        refs.addAll(userConfigReferences(prefix));
        return refs;
    }

    // ==================== 各类引用 ====================

    /** 知识库：向量模型 + 图谱模型（引用的是向量模型，重建索引会连带全库不可检索） */
    private List<Reference> kbReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .likeRight(KnowledgeBase::getEmbeddingRef, prefix))) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_KB_EMBEDDING, "知识库向量模型", kb.getName(), kb.getId(),
                        "该库向量检索会直接失败", true));
            }
        }
        List<KnowledgeBase> graphKb = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .likeRight(KnowledgeBase::getGraphModelRef, prefix));
        for (KnowledgeBase kb : graphKb) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_KB_GRAPH, "知识库图谱模型", kb.getName(), kb.getId(),
                        "图谱增强抽取会失败", true));
            }
        }
        return out;
    }

    /** 个人默认模型：该用户未显式选模型的每一轮问答都会用到 */
    private List<Reference> userDefaultReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .likeRight(User::getDefaultModel, prefix));
        for (User u : users) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_USER_DEFAULT, "个人默认模型", displayName(u), u.getUid(),
                        "该用户未选模型时的默认模型", true));
            }
        }
        return out;
    }

    /**
     * 系统配置槽位：影响面最大（全平台）。
     * 沿用 {@code deleteProvider} 的过滤口径——只有活跃键且非个人专属键才算「可改的引用」；
     * 退役键/个人专属键的遗留行只提示不计入（界面无处可改，列为引用只会让用户找不到地方下手）。
     */
    private List<Reference> configSlotReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<Config> rows = configMapper.selectList(new LambdaQueryWrapper<Config>()
                .likeRight(Config::getConfigValue, prefix));
        for (Config c : rows) {
            if (out.size() < PER_KIND_MAX) {
                boolean editable = configService.isLiveKey(c.getConfigKey())
                        && !configService.isPersonalOnly(c.getConfigKey());
                out.add(new Reference(KIND_CONFIG_SLOT, "系统配置", c.getConfigKey(), null,
                        "影响全平台", editable));
            }
        }
        return out;
    }

    /** 游客对话模型：公开分享链接的对话方 */
    private List<Reference> agentShareReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<AgentShare> shares = agentShareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .likeRight(AgentShare::getModelRef, prefix));
        for (AgentShare s : shares) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_AGENT_SHARE, "游客对话模型", "分享链接 " + shortToken(s.getToken()),
                        s.getAgentId(), "公开链接的访客对话会失败", true));
            }
        }
        return out;
    }

    /** 个人设置里的模型引用（personalOnly 键的历史存量行） */
    private List<Reference> userConfigReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<UserConfig> rows = userConfigMapper.selectList(new LambdaQueryWrapper<UserConfig>()
                .likeRight(UserConfig::getConfigValue, prefix));
        for (UserConfig c : rows) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_USER_CONFIG, "个人设置", c.getConfigKey(), c.getUid(),
                        "该用户的个人配置", true));
            }
        }
        return out;
    }

    /**
     * 会话级覆盖的提示项（不逐条扫描会话表——量大会拖慢每次额度登记）。
     * 显式列出而不是静默略过：会话级覆盖在聊天页切换模型时写入，历史会话里可能仍有指向它的记录，
     * 用户改完配置仍可能偶发失败，得让他知道还有这条路。
     */
    public Reference sessionHint() {
        return new Reference(KIND_SESSION, "历史会话", "会话级模型覆盖", null,
                "历史会话可能仍保留对它的覆盖（会话级切换模型时写入），必要时在会话内切一次模型即可覆盖", false);
    }

    // ==================== 面向界面的汇总 ====================

    /**
     * 界面用的引用概览：按类型分组 + 总数 + 可改数量，<b>并附上会话级覆盖的提示项</b>。
     *
     * @param providerId 供应商 ID
     * @return {total, editableCount, items:[Reference], byKind:{kind:count}, hasBlocking}
     */
    public Map<String, Object> overviewOf(String providerId) {
        List<Reference> refs = new ArrayList<>(referencesOf(providerId));
        // 会话覆盖项总是列出：它是唯一一类「可能在界面里改不掉」的引用，
        // 漏掉它会让用户改完所有可见引用仍偶发失败，且无从排查
        refs.add(sessionHint());
        Map<String, Integer> byKind = new LinkedHashMap<>();
        int editable = 0;
        for (Reference r : refs) {
            byKind.merge(r.kind(), 1, Integer::sum);
            if (r.editable()) editable++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", refs.size());
        out.put("editableCount", editable);
        out.put("items", refs);
        out.put("byKind", byKind);
        // 存在不可就地修改的引用（如会话覆盖）时提示用户还需额外处理，避免改完仍失败
        out.put("hasBlocking", refs.stream().anyMatch(r -> !r.editable()));
        return out;
    }

    // ==================== 工具 ====================

    /** 用户显示名（username 为空时退回 uid 前 8 位，至少能认出是谁） */
    private static String displayName(User u) {
        if (u.getUsername() != null && !u.getUsername().isBlank()) return u.getUsername();
        String uid = u.getUid();
        if (uid == null) return "(未知用户)";
        return uid.length() > 8 ? uid.substring(0, 8) : uid;
    }

    /** 分享令牌只展示头尾（避免把完整可用令牌抄进通知文案） */
    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "(无)";
        return token.length() <= 10 ? token : token.substring(0, 4) + "…" + token.substring(token.length() - 4);
    }

    /**
     * 引用清单的一行文本（通知文案用——通知里必须能看出「谁在用」，
     * 只说「某供应商额度不足」用户依然不知道该改哪里）。
     */
    public static String summarize(List<Reference> refs, int maxItems) {
        if (refs == null || refs.isEmpty()) return "";
        Map<String, List<Reference>> grouped = new LinkedHashMap<>();
        for (Reference r : refs) {
            grouped.computeIfAbsent(r.label(), k -> new ArrayList<>()).add(r);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, List<Reference>> e : grouped.entrySet()) {
            List<Reference> items = e.getValue();
            if (shown >= maxItems) {
                sb.append(sb.length() > 0 ? "；" : "").append(e.getKey()).append(" 还有 ")
                        .append(items.size()).append(" 处未列出");
                break;
            }
            StringBuilder names = new StringBuilder();
            for (Reference r : items) {
                if (shown >= maxItems) break;
                if (names.length() > 0) names.append('、');
                names.append(r.name());
                shown++;
            }
            if (names.length() > 0) {
                sb.append(sb.length() > 0 ? "；" : "").append(e.getKey()).append('：').append(names);
            }
            if (items.size() > shown) {
                sb.append(sb.length() > 0 ? "；" : "").append(e.getKey()).append(" 共 ")
                        .append(items.size()).append(" 处");
            }
        }
        return sb.toString();
    }
}