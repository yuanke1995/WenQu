package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.Provider;
import com.wenqu.ai.model.ScheduledJob;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.Workflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用批量替换：把「停用后仍有 N 处在用它的模型」从 N 次跳页压成 1 次操作。
 *
 * <h3>替换规则（与界面文案一致）</h3>
 * 目标只选<b>供应商</b>，模型段原样保留：{@code oldProv/deepseek-chat → target/deepseek-chat}。
 * 目标供应商没登记该模型（或未启用）时<b>这一处失败并给原因</b>，不做「就近换个别的模型」的
 * 自作主张——静默换模型等于改了用户没让他改的东西。
 *
 * <h3>权限边界（只写自己改得动的）</h3>
 * 逐处写回按各资源自身归属过滤：知识库/文档/定时任务/工作流/分享/个人默认都只动
 * 请求者本人的行。<b>系统配置槽位不进批量口</b>——它影响全平台，且是「谁的问答都在用」的
 * 公共闸口，批量改写的影响面不是一个供应商归属人能背的；清单里照旧逐条给「去修改」入口。
 * 他人的个人默认同理：只列不写，原因里写明需本人处理。
 *
 * <h3>与会话级覆盖的分工</h3>
 * 会话覆盖走既有 {@code clear-session-models}（清除后回到跟随各自默认），不在这里重复实现——
 * 两处各写一份「怎么清会话」必然漂移。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderReferenceReplaceService {

    private final com.wenqu.ai.mapper.KnowledgeBaseMapper kbMapper;
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;
    private final com.wenqu.ai.mapper.ScheduledJobMapper scheduledJobMapper;
    private final com.wenqu.ai.mapper.WorkflowMapper workflowMapper;
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    private final com.wenqu.ai.mapper.AgentShareMapper agentShareMapper;
    private final ModelRegistryService registry;
    private final ModelReferenceScanner scanner;

    /** 一处替换结果 */
    private record Outcome(List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
    }

    /**
     * 执行批量替换。
     *
     * @param providerId       被替换来源供应商（其模型正被引用的那家）
     * @param targetProviderId 目标供应商（同模型名须已在目标登记且启用）
     * @param uid              请求者（只动本人资源）
     * @return {replaced:[{kind,name,newRef}], failed:[{kind,name,error}], remaining}
     */
    public Map<String, Object> replace(String providerId, String targetProviderId, String uid) {
        Provider target = registry.providerById(targetProviderId);
        if (target == null) return error("目标供应商不存在（可能已被删除）");
        if (targetProviderId.equals(providerId)) return error("目标与来源是同一供应商，无需替换");
        String prefix = providerId + "/";

        List<Map<String, Object>> replaced = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        replaceKbs(prefix, target, uid, replaced, failed);
        replaceDocs(prefix, target, uid, replaced, failed);
        replaceScheduledJobs(prefix, target, uid, replaced, failed);
        replaceWorkflows(prefix, target, uid, replaced, failed);
        replaceUserDefault(prefix, target, uid, replaced, failed);
        replaceAgentShares(prefix, target, uid, replaced, failed);

        registry.refreshProviderCache();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("replaced", replaced);
        out.put("failed", failed);
        out.put("remaining", scanner.countOf(providerId, uid));
        log.info("[RefReplace] 供应商 {} → {} 批量替换：成功 {} 处，失败 {} 处",
                providerId, targetProviderId, replaced.size(), failed.size());
        return out;
    }

    // ==================== 各引用源 ====================

    /** 知识库：向量/图谱独立列 + 检索/解析 JSON 槽（一槽一处） */
    private void replaceKbs(String prefix, Provider target, String uid,
                            List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        List<KnowledgeBase> kbs = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .eq(KnowledgeBase::getCreatedBy, uid)
                .and(w -> w.likeRight(KnowledgeBase::getEmbeddingRef, prefix)
                        .or().likeRight(KnowledgeBase::getGraphModelRef, prefix)
                        .or().like(KnowledgeBase::getQueryParams, "\"" + prefix)
                        .or().like(KnowledgeBase::getParseParams, "\"" + prefix)));
        for (KnowledgeBase kb : kbs) {
            LambdaUpdateWrapper<KnowledgeBase> upd = new LambdaUpdateWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getId, kb.getId());
            boolean touched = false;
            String oldRef = kb.getEmbeddingRef();
            if (usesPrefix(oldRef, prefix)) {
                String nr = mapRef(oldRef, target);
                if (nr == null) fail(failed, "知识库向量模型", kb.getName(), target);
                else { upd.set(KnowledgeBase::getEmbeddingRef, nr); ok(replaced, "kbEmbedding", kb.getName(), nr); touched = true; }
            }
            oldRef = kb.getGraphModelRef();
            if (usesPrefix(oldRef, prefix)) {
                String nr = mapRef(oldRef, target);
                if (nr == null) fail(failed, "知识库图谱模型", kb.getName(), target);
                else { upd.set(KnowledgeBase::getGraphModelRef, nr); ok(replaced, "kbGraph", kb.getName(), nr); touched = true; }
            }
            JSONObject qp = parse(kb.getQueryParams());
            if (qp != null && usesPrefix(qp.getString("rerank.model"), prefix)) {
                String nr = mapRef(qp.getString("rerank.model"), target);
                if (nr == null) fail(failed, "知识库重排模型", kb.getName(), target);
                else {
                    qp.getJSONObject("rerank").put("model", nr);
                    upd.set(KnowledgeBase::getQueryParams, qp.toJSONString());
                    ok(replaced, "kbRerank", kb.getName(), nr);
                    touched = true;
                }
            }
            JSONObject pp = parse(kb.getParseParams());
            if (pp != null) {
                for (String slot : new String[]{"visionRef", "ocrRef"}) {
                    String v = pp.getString(slot);
                    if (!usesPrefix(v, prefix)) continue;
                    String nr = mapRef(v, target);
                    if (nr == null) fail(failed, "知识库图片理解模型", kb.getName() + "·" + slot, target);
                    else {
                        pp.put(slot, nr);
                        upd.set(KnowledgeBase::getParseParams, pp.toJSONString());
                        ok(replaced, "kbParse", kb.getName(), nr);
                        touched = true;
                    }
                }
            }
            if (touched) kbMapper.update(null, upd);
        }
    }

    /** 文档级图片理解覆盖（上传时单独指定的那批） */
    private void replaceDocs(String prefix, Provider target, String uid,
                             List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        for (AiDocument d : documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getDeleted, 0)
                .eq(AiDocument::getCreatedBy, uid)
                .likeRight(AiDocument::getVisionRef, prefix))) {
            String nr = mapRef(d.getVisionRef(), target);
            if (nr == null) { fail(failed, "文档图片理解模型", d.getFileName(), target); continue; }
            documentMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                    .eq(AiDocument::getId, d.getId()).set(AiDocument::getVisionRef, nr));
            ok(replaced, "docVision", d.getFileName(), nr);
        }
    }

    /** 定时任务模型覆盖 */
    private void replaceScheduledJobs(String prefix, Provider target, String uid,
                                      List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        for (ScheduledJob j : scheduledJobMapper.selectList(new LambdaQueryWrapper<ScheduledJob>()
                .eq(ScheduledJob::getDeleted, 0)
                .eq(ScheduledJob::getUid, uid)
                .likeRight(ScheduledJob::getModelRef, prefix))) {
            String nr = mapRef(j.getModelRef(), target);
            if (nr == null) { fail(failed, "定时任务", j.getName(), target); continue; }
            scheduledJobMapper.update(null, new LambdaUpdateWrapper<ScheduledJob>()
                    .eq(ScheduledJob::getId, j.getId()).set(ScheduledJob::getModelRef, nr));
            ok(replaced, "scheduledJob", j.getName(), nr);
        }
    }

    /**
     * 工作流 DSL 里的 modelRef（草稿 + 已发布两份都换——只换草稿的话，下一次发布又把旧值带回来）。
     * DSL 是整段 JSON，节点模型没有独立列；按 {@code "providerId/modelId"} 全串正则替换，
     * 每个不同模型名各算一处。
     */
    private void replaceWorkflows(String prefix, Provider target, String uid,
                                  List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        Pattern pat = Pattern.compile("\"" + Pattern.quote(prefix) + "([^\"\\\\]+)\"");
        for (Workflow w : workflowMapper.selectList(new LambdaQueryWrapper<Workflow>().eq(Workflow::getUid, uid))) {
            String dsl = w.getDsl();
            String pub = w.getPublishedDsl();
            boolean dslHit = dsl != null && dsl.contains("\"" + prefix);
            boolean pubHit = pub != null && pub.contains("\"" + prefix);
            if (!dslHit && !pubHit) continue;
            List<String> names = distinctRefs(pat, dslHit ? dsl : "", pubHit ? pub : "");
            boolean any = false, anyFail = false;
            LambdaUpdateWrapper<Workflow> upd = new LambdaUpdateWrapper<Workflow>().eq(Workflow::getId, w.getId());
            for (String oldRef : names) {
                String nr = mapRef(oldRef, target);
                if (nr == null) { anyFail = true; continue; }
                if (dslHit) dsl = dsl.replace("\"" + oldRef + "\"", "\"" + nr + "\"");
                if (pubHit) pub = pub.replace("\"" + oldRef + "\"", "\"" + nr + "\"");
                any = true;
                ok(replaced, "workflow", w.getName() + "·" + modelIdOf(oldRef), nr);
            }
            if (any) {
                if (dslHit) upd.set(Workflow::getDsl, dsl);
                if (pubHit) upd.set(Workflow::getPublishedDsl, pub);
                workflowMapper.update(null, upd);
            }
            if (anyFail) fail(failed, "工作流节点", w.getName(), target);
        }
    }

    /** 个人默认模型：只有请求者本人的行可写，他人行只报原因 */
    private void replaceUserDefault(String prefix, Provider target, String uid,
                                    List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>().likeRight(User::getDefaultModel, prefix))) {
            if (!uid.equals(u.getUid())) {
                failed.add(item("userDefault", u.getUsername() == null ? u.getUid() : u.getUsername(),
                        "属他人个人设置，需其本人修改"));
                continue;
            }
            String nr = mapRef(u.getDefaultModel(), target);
            if (nr == null) { fail(failed, "个人默认模型", u.getUid(), target); continue; }
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getUid, u.getUid()).set(User::getDefaultModel, nr));
            ok(replaced, "userDefault", "本人默认模型", nr);
        }
    }

    /** 游客对话模型（公开分享链接） */
    private void replaceAgentShares(String prefix, Provider target, String uid,
                                    List<Map<String, Object>> replaced, List<Map<String, Object>> failed) {
        for (AgentShare s : agentShareMapper.selectList(new LambdaQueryWrapper<AgentShare>()
                .eq(AgentShare::getCreatedBy, uid)
                .likeRight(AgentShare::getModelRef, prefix))) {
            String nr = mapRef(s.getModelRef(), target);
            if (nr == null) { fail(failed, "游客对话模型", "分享链接", target); continue; }
            agentShareMapper.update(null, new LambdaUpdateWrapper<AgentShare>()
                    .eq(AgentShare::getId, s.getId()).set(AgentShare::getModelRef, nr));
            ok(replaced, "agentShare", "分享链接 " + s.getAgentId(), nr);
        }
    }

    // ==================== 映射与工具 ====================

    /**
     * oldRef → 目标供应商下的同名引用；目标未登记该模型（或未启用）返回 null（该处失败）。
     */
    private String mapRef(String oldRef, Provider target) {
        String modelId = modelIdOf(oldRef);
        if (modelId == null || modelId.isBlank()) return null;
        for (ModelInfo mi : registry.modelsOf(target.getId())) {
            if (mi.getModelId().equals(modelId) && !Integer.valueOf(0).equals(mi.getEnabled())) {
                return target.getId() + "/" + modelId;
            }
        }
        return null;
    }

    private static String modelIdOf(String ref) {
        if (ref == null) return null;
        int i = ref.indexOf('/');
        return i < 0 || i == ref.length() - 1 ? null : ref.substring(i + 1);
    }

    private static boolean usesPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    /** 两份 DSL 文本里出现过的该前缀引用去重（同一模型被多个节点用只算一次替换动作） */
    private static List<String> distinctRefs(Pattern pat, String dsl, String pub) {
        List<String> out = new ArrayList<>();
        for (String src : new String[]{dsl, pub}) {
            Matcher m = pat.matcher(src);
            while (m.find()) {
                String full = m.group(0).substring(1, m.group(0).length() - 1);
                if (!out.contains(full)) out.add(full);
            }
        }
        return out;
    }

    private static JSONObject parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static void ok(List<Map<String, Object>> list, String kind, String name, String newRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("newRef", newRef);
        list.add(m);
    }

    private static void fail(List<Map<String, Object>> list, String kind, String name, Provider target) {
        list.add(item(kind, name, "目标供应商「" + target.getName() + "」未登记同名启用模型，该处保持不变；"
                + "可在「查看引用」里逐处改选"));
    }

    private static Map<String, Object> item(String kind, String name, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("error", error);
        return m;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", msg);
        out.put("replaced", List.of());
        out.put("failed", List.of());
        return out;
    }
}
