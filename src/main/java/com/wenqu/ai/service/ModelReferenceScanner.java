package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Config;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.ScheduledJob;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.UserConfig;
import com.wenqu.ai.model.Workflow;
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
 * 停用供应商走的是同一份清单：清单少一类，界面就会理直气壮地说「没有地方在用」，
 * 而用户回到聊天页/定时任务里照样撞上失败——所以<b>宁可多报也不能漏报</b>。
 *
 * <h3>覆盖的引用源</h3>
 * 独立列按 {@code {providerId}/} 前缀 likeRight 匹配，JSON 槽位按 {@code "{providerId}/} 匹配后
 * 再逐槽解析（避免把同前缀的别的值算进来）：
 * <ol>
 *   <li><b>知识库向量模型</b>（{@code c_ai_knowledge_base.embedding_ref}）——影响面最大，
 *       额度不足会导致该库检索直接失败；</li>
 *   <li><b>知识库图谱模型</b>（{@code graph_model_ref}）——图谱增强链路用；</li>
 *   <li><b>知识库检索/解析参数</b>（{@code query_params.rerank.model}、
 *       {@code parse_params.visionRef}、{@code parse_params.ocrRef}）——一槽一条；
 *       重排失效是静默降质，图片描述/OCR 失败是解析报错；</li>
 *   <li><b>文档图片理解模型</b>（{@code c_ai_document.vision_ref}）——重解析该文档时撞上；</li>
 *   <li><b>定时任务模型覆盖</b>（{@code c_ai_scheduled_job.model_ref}）——每次触发都失败；</li>
 *   <li><b>工作流节点模型</b>（{@code c_ai_workflow.dsl/published_dsl} 里的 modelRef）——运行即失败；</li>
 *   <li><b>个人默认模型</b>（{@code c_ai_user.default_model}）——该用户每一次未指定模型的问答都会撞上；</li>
 *   <li><b>历史个人配置</b>（{@code c_ai_user_config.config_value} 里的遗留行）——入口已下线，
 *       只报不计入可改；</li>
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
 * <h3>软删行不算引用</h3>
 * 带 {@code deleted} 列的表一律过滤：已删的库/文档/任务没有界面能改回去，列进清单只会把用户
 * 指向一扇已经不存在的门，还会让「共 N 处引用」虚高。
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
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;
    private final com.wenqu.ai.mapper.ScheduledJobMapper scheduledJobMapper;
    private final com.wenqu.ai.mapper.WorkflowMapper workflowMapper;
    private final ConfigService configService;
    /** backendKey → 设置面板：系统配置槽位要跳到真正能改的那一组，而不是永远落在设置页第一组 */
    private final com.wenqu.ai.config.ConfigSchemaService configSchema;
    /** 会话级覆盖的计数与清除（只有请求者本人这一维能界定范围） */
    private final SessionService sessionService;

    /**
     * 一条引用记录。
     *
     * @param kind   引用类型标识（前端按此决定跳转与展示形态）
     * @param label  中文标签（如「知识库向量模型」）
     * @param name   引用方的可读名称（知识库名 / 用户名 / 配置键）
     * @param id     跳转参数（可空）：多数是引用方主键（库 ID / 工作流 ID）；
     *               系统配置槽位没有主键，这里放该键所属<b>设置面板 key</b>，前端据此开对分组；
     *               文档一行放的是它所属<b>知识库 ID</b>（改文档的入口在库的文档页里）
     * @param hint   补充说明（如「该库检索会直接失败」「影响全平台重排」）
     * @param editable 是否可在界面内直接改掉（系统配置槽位可在设置页改，故为 true；
     *                 会话覆盖无管理入口，为 false）
     */
    public record Reference(String kind, String label, String name, String id, String hint, boolean editable) {
    }

    /** 引用类型常量（前端据此渲染图标/跳转） */
    public static final String KIND_KB_EMBEDDING = "kbEmbedding";
    public static final String KIND_KB_GRAPH = "kbGraph";
    public static final String KIND_KB_RERANK = "kbRerank";
    public static final String KIND_KB_PARSE_VISION = "kbParseVision";
    public static final String KIND_KB_PARSE_OCR = "kbParseOcr";
    public static final String KIND_DOC_VISION = "docVision";
    public static final String KIND_SCHEDULED_JOB = "scheduledJob";
    public static final String KIND_WORKFLOW = "workflow";
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
     * @return 引用清单（按影响面排序：知识库 → 文档/任务/工作流 → 个人默认 → 全平台配置 → 其它）
     */
    public List<Reference> referencesOf(String providerId) {
        List<Reference> refs = new ArrayList<>();
        if (providerId == null || providerId.isBlank()) return refs;
        String prefix = providerId + "/";

        refs.addAll(kbReferences(prefix));
        refs.addAll(kbParamReferences(prefix));
        refs.addAll(docReferences(prefix));
        refs.addAll(scheduledJobReferences(prefix));
        refs.addAll(workflowReferences(prefix));
        refs.addAll(userDefaultReferences(prefix));
        refs.addAll(configSlotReferences(prefix));
        refs.addAll(agentShareReferences(prefix));
        refs.addAll(userConfigReferences(prefix));
        return refs;
    }

    /**
     * 引用处数（含请求者本人的会话级覆盖）。
     * <p>供应商列表只给<b>已停用</b>的行算它：停用后「还有几处没改干净」是常驻提醒，
     * 而启用中的行每次开页都扫一遍全部引用表纯属浪费。
     */
    public int countOf(String providerId, String uid) {
        if (providerId == null || providerId.isBlank()) return 0;
        int n = referencesOf(providerId).size();
        if (uid != null && !uid.isBlank()) {
            n += Math.max(sessionService.countModelOverridesOf(uid, providerId), 0);
        }
        return n;
    }

    // ==================== 各类引用 ====================

    /** 知识库：向量模型 + 图谱模型（引用的是向量模型，重建索引会连带全库不可检索） */
    private List<Reference> kbReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        for (KnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .likeRight(KnowledgeBase::getEmbeddingRef, prefix))) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_KB_EMBEDDING, "知识库向量模型", kb.getName(), kb.getId(),
                        "该库向量检索会直接失败", true));
            }
        }
        List<KnowledgeBase> graphKb = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .likeRight(KnowledgeBase::getGraphModelRef, prefix));
        for (KnowledgeBase kb : graphKb) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_KB_GRAPH, "知识库图谱模型", kb.getName(), kb.getId(),
                        "图谱增强抽取会失败", true));
            }
        }
        return out;
    }

    /**
     * 知识库的检索/解析参数里的模型（{@code query_params.rerank.model}、
     * {@code parse_params.visionRef}、{@code parse_params.ocrRef}）：这三槽不在独立列里，
     * 只能整列 like 粗筛再逐槽解析。**一个槽一条引用**——同一库把图片描述与 OCR 都绑到同一个
     * 供应商时那是两处要改，不是一处，且前端按 kind 决定「去修改」定位到哪个字段。
     * <p>重排模型失效是<b>静默降质</b>（退回融合分顺序，不报错），比直接失败更难归因，
     * 所以即使界面不会红着提醒用户，清单也必须报出来。
     */
    private List<Reference> kbParamReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<KnowledgeBase> kbs = kbMapper.selectList(new QueryWrapper<KnowledgeBase>()
                .eq("deleted", 0)
                .and(w -> w.like("query_params", jsonQuote(prefix)).or().like("parse_params", jsonQuote(prefix))));
        for (KnowledgeBase kb : kbs) {
            if (usesRef(jsonStr(kb.getQueryParams(), "rerank.model"), prefix)) {
                out.add(new Reference(KIND_KB_RERANK, "知识库重排模型", kb.getName(), kb.getId(),
                        "该库精排会静默失效（退回融合分顺序，引用变多变杂但不报错）", true));
            }
            if (usesRef(jsonStr(kb.getParseParams(), "visionRef"), prefix)) {
                out.add(new Reference(KIND_KB_PARSE_VISION, "知识库图片描述模型", kb.getName(), kb.getId(),
                        "解析时插图/截图转不成文字，图片内容进不了检索", true));
            }
            if (usesRef(jsonStr(kb.getParseParams(), "ocrRef"), prefix)) {
                out.add(new Reference(KIND_KB_PARSE_OCR, "知识库扫描件 OCR 模型", kb.getName(), kb.getId(),
                        "扫描件/图片型 PDF 识别失败；留空会回落图片描述模型", true));
            }
        }
        return capPerKind(out, PER_KIND_MAX);
    }

    /**
     * 按 kind 各自截断到上限：同一库里绑到同一供应商的两个解析槽要各自计数，
     * 否则前 50 个库的图片描述模型会把后面所有库的 OCR 模型整类挤没（那一类就静默不报了）。
     */
    private static List<Reference> capPerKind(List<Reference> refs, int max) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Reference> out = new ArrayList<>();
        for (Reference r : refs) {
            if (seen.merge(r.kind(), 1, Integer::sum) <= max) out.add(r);
        }
        return out;
    }

    /** 文档级图片理解模型覆盖（上传时单独指定过的那批；重解析该文档时撞上） */
    private List<Reference> docReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        for (AiDocument d : documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getDeleted, 0)
                .likeRight(AiDocument::getVisionRef, prefix))) {
            if (out.size() >= PER_KIND_MAX) {
                out.add(new Reference(KIND_DOC_VISION, "文档图片理解模型", "更多文档", null,
                        "以上未列全，按知识库逐库检查", false));
                break;
            }
            out.add(new Reference(KIND_DOC_VISION, "文档图片理解模型", d.getFileName(), d.getKbId(),
                    "重解析该文档时图片理解会失败", true));
        }
        return out;
    }

    /** 定时任务模型覆盖：每次触发都失败，且失败发生在没人看着的夜里 */
    private List<Reference> scheduledJobReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        for (ScheduledJob j : scheduledJobMapper.selectList(new LambdaQueryWrapper<ScheduledJob>()
                .eq(ScheduledJob::getDeleted, 0)
                .likeRight(ScheduledJob::getModelRef, prefix))) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_SCHEDULED_JOB, "定时任务", j.getName(), j.getId(),
                        "该任务每轮都会失败（结果会话里只留一条报错）", true));
            }
        }
        return out;
    }

    /**
     * 工作流节点里的模型（草稿 DSL 与已发布 DSL 命中任一就算这一条）。
     * DSL 是整段 JSON，节点模型没有独立列，只能整列粗筛；具体哪个节点要进编辑器才看得到，
     * 所以这里按工作流给一条，而不是按节点给一堆。
     */
    private List<Reference> workflowReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        String needle = jsonQuote(prefix);
        for (Workflow w : workflowMapper.selectList(new QueryWrapper<Workflow>()
                .and(q -> q.like("dsl", needle).or().like("published_dsl", needle)))) {
            if (out.size() >= PER_KIND_MAX) break;
            out.add(new Reference(KIND_WORKFLOW, "工作流节点", w.getName(), w.getId(),
                    "该工作流有节点用它，运行会失败（草稿与已发布版本都算）", true));
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
                // id 位放设置面板 key：前端据此把设置页开到对的那一组
                out.add(new Reference(KIND_CONFIG_SLOT, "系统配置", c.getConfigKey(),
                        editable ? configSchema.panelOf(c.getConfigKey()) : null,
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

    /**
     * 个人设置里的模型引用。
     * <p>当前 schema 已不再标记任何 personal 字段（原「回答偏好」五项与个人重排/向量覆盖入口都已下线），
     * 所以命中的只会是历史存量行——<b>界面上没有地方能改它</b>，故 {@code editable=false}：
     * 给一个跳不过去的「去修改」比不给按钮更糟。
     */
    private List<Reference> userConfigReferences(String prefix) {
        List<Reference> out = new ArrayList<>();
        List<UserConfig> rows = userConfigMapper.selectList(new LambdaQueryWrapper<UserConfig>()
                .likeRight(UserConfig::getConfigValue, prefix));
        for (UserConfig c : rows) {
            if (out.size() < PER_KIND_MAX) {
                out.add(new Reference(KIND_USER_CONFIG, "历史个人配置", c.getConfigKey(), c.getUid(),
                        "个人设置入口已下线，这一行是历史存量；它不影响当前问答，可忽略", false));
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
        return overviewOf(providerId, null);
    }

    /**
     * @param uid 请求者 uid。给了才逐条扫本人会话里的覆盖并给出<b>条数与一键清除</b>；
     *            不给（额度通知这类非请求线程）保持原来的泛化提示项——扫会话表既超出该场景的边界，
     *            也没有「谁的会话」这个概念可依。
     */
    public Map<String, Object> overviewOf(String providerId, String uid) {
        List<Reference> refs = new ArrayList<>(referencesOf(providerId));
        // 会话级覆盖必须显式列出来，不能静默略过：漏掉它会让用户改完所有可见引用仍偶发失败、且无从排查
        int sessionCount = uid == null || uid.isBlank() || providerId == null || providerId.isBlank()
                ? -1 : sessionService.countModelOverridesOf(uid, providerId);
        if (sessionCount > 0) {
            refs.add(new Reference(KIND_SESSION, "历史会话", sessionCount + " 条会话上次用的模型在这个供应商下",
                    null, "这些会话此后每轮都会撞上「模型已停用」，可一次清除这些会话的选择", true));
        } else if (sessionCount < 0) {
            refs.add(sessionHint());
        }
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
        // 前端「清除这些会话的选择」按钮要的就是这个数：零就整个按钮不出现
        out.put("sessionOverrideCount", Math.max(sessionCount, 0));
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

    /**
     * JSON 槽位粗筛用的串：{@code "providerId/}（带前引号）。
     * 独立列用 likeRight 锚住开头，JSON 里值在中间，只能整列 like；补上前引号才不会把
     * 恰好以该前缀开头的其它文本（如一段含斜杠的备注）也算成引用。
     */
    private static String jsonQuote(String prefix) {
        return "\"" + prefix;
    }

    /** JSON 文本里的一个字符串槽位（解析失败/缺失返回 null，与 KnowledgeBaseService 同口径） */
    private static String jsonStr(String json, String attr) {
        if (json == null || json.isBlank()) return null;
        try {
            com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(json);
            return o == null ? null : o.getString(attr);
        } catch (Exception e) {
            return null;
        }
    }

    /** 粗筛命中后逐槽复核：值确实以 {@code providerId/} 开头才算引用 */
    private static boolean usesRef(String value, String prefix) {
        return value != null && value.startsWith(prefix);
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