package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnSourceRegistry;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import com.wenqu.ai.service.RerankService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架引擎这一轮的引用池：一条 {@code [1..N]} 编号空间、来源清单与图片编号体系。
 * <p>编号逻辑本身<b>不在这里</b>——它仍然只有 {@link TurnSourceRegistry} 一处实现（手写运行时用的也是它）。
 * 本类只是按框架引擎的需要把它组装起来，避免出现「第二套引用编号」。
 * 手写运行时的 {@code TurnState.refs} 由 ChatService 建，这里对应的那一份由框架引擎建，两者互不干涉。
 */
public final class WenquSourcePool {

    private final ChatRunRuntime runtime;
    private final TurnSourceRegistry registry;
    private final List<Map<String, Object>> sources = new ArrayList<>();
    private final Map<Integer, String> imgIndex = new HashMap<>();
    private final Map<Integer, String> imgDescIndex = new HashMap<>();
    private final List<Map<String, String>> degradations = new ArrayList<>();
    private final Set<String> degradedCodes = new HashSet<>();

    public WenquSourcePool(ChatRunRuntime runtime, ConfigService configService, RerankService rerankService,
                           ImageUrlSigner imageUrlSigner, String sessionId, String question, SseEmitter emitter,
                           com.wenqu.ai.service.DocumentMetaCache docMetaCache) {
        this.runtime = runtime;
        this.registry = new TurnSourceRegistry(configService, rerankService, imageUrlSigner, runtime,
                sessionId, question, emitter, sources, imgIndex, imgDescIndex, degradations, degradedCodes);
        // 工具本轮首次命中的文档不在主检索的 docFileNames 映射里，文件名靠 docMetaCache 按 docId 补查。
        // 手写侧是在 ChatService 里给 st.refs.docMetaCache 赋值；框架这条自己 new 引用池，不注入就恒为 null，
        // TurnSourceRegistry 的兜底被 `docMetaCache != null` 跳过 → 来源面板一律「来源文档不可用」。
        this.registry.docMetaCache = docMetaCache;
    }

    /** 知识检索工具的落点：命中块注册进本轮 sources 续编引用编号，工具文本改【引用N】提示。 */
    public KnowledgeRetrievalTool.SourceRegistrar registrar() {
        return new KnowledgeRetrievalTool.SourceRegistrar() {
            @Override
            public KnowledgeRetrievalTool.SourceRegistrar.Registration register(
                    HybridRetrievalService.Hit hit, String snippet) {
                return registry.registerToolSource(hit, snippet);
            }

            @Override
            public void degrade(String code, String msg) {
                runtime.addDegradation(degradations, degradedCodes, code, msg);
            }
        };
    }

    /** 联网搜索的落点=本轮引用池（sink 由 TurnSourceRegistry 实现，工具拿不到本轮其它字段）。 */
    public TurnSourceRegistry sink() {
        return registry;
    }

    /**
     * 被咨询那位查到的资料登记进<b>父轮</b>引用表（编号仍是父轮那一套，正文点得开出处）。
     * <p>与手写运行时共用 {@link TurnSourceRegistry} 这一个实现：门按父轮问题判、配额与编号空间
     * 都在父轮这里，框架侧另写一份就是第二条编号体系。
     */
    public java.util.List<com.wenqu.ai.agent.subagent.SubAgentConsultService.SourceSink.Registered>
    registerConsultedSources(java.util.List<java.util.Map<String, Object>> childSources) {
        return registry.registerConsultedSources(childSources);
    }

    public List<Map<String, Object>> sources() {
        return sources;
    }

    /** 本轮图片编号体系（引用完整性自检要按它判 [图片N] 是否悬空）。 */
    public Map<Integer, String> imgIndex() {
        return imgIndex;
    }

    public Map<Integer, String> imgDescIndex() {
        return imgDescIndex;
    }

    public Set<String> degradedCodes() {
        return degradedCodes;
    }

    /**
     * 回写引用完整性自检的结果：不支撑的来源被裁掉后<b>编号是重编过的</b>，
     * 池必须换成那一份，否则正文角标与引用面板错位（面板显示 [3]、正文指的是 [2]）。
     */
    public void applyCitationOutcome(com.wenqu.ai.agent.context.CitationIntegrityService.Outcome outcome) {
        if (outcome == null || outcome.sources() == null) return;
        // 先拷一份再换：自检没裁剪来源时 {@code outcome.sources()} 就是本池这一个 List（同一对象原样返回），
        // 直接 clear() 再 addAll(自己) 得到的是空表——双跑实测抓到：框架引擎每轮引用面板全空、
        // 正文角标没有出处，而 legacy 同一问题有 17 条来源。
        var snapshot = new ArrayList<>(outcome.sources());
        sources.clear();
        sources.addAll(snapshot);
    }

    public List<Map<String, String>> degradations() {
        return degradations;
    }
}
