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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final ImageUrlSigner imageUrlSigner;
    private final TurnSourceRegistry registry;
    private final List<Map<String, Object>> sources = new ArrayList<>();
    // 必须是 LinkedHashMap：`.values()` 就是 [图片N] 的下标序列（编号 N → 数组第 N-1 项），
    // HashMap 在条目数越过扩容阈值后按桶序迭代，正文里的 [图片3] 会配到另一张图。
    private final Map<Integer, String> imgIndex = new LinkedHashMap<>();
    private final Map<Integer, String> imgDescIndex = new LinkedHashMap<>();
    private final List<Map<String, String>> degradations = new ArrayList<>();
    private final Set<String> degradedCodes = new HashSet<>();

    public WenquSourcePool(ChatRunRuntime runtime, ConfigService configService, RerankService rerankService,
                           ImageUrlSigner imageUrlSigner, String sessionId, String question, SseEmitter emitter,
                           com.wenqu.ai.service.DocumentMetaCache docMetaCache) {
        this.runtime = runtime;
        this.imageUrlSigner = imageUrlSigner;
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

    /**
     * 引用池快照（副本）。
     * <p>池在本轮工具执行期间会被多路并行工具并发写入（模型一次可发多个 search_knowledge），
     * 直接返回内部 List 会让调用方（接流快照/落库序列化）在遍历时撞上并发 add。副本的代价是
     * 一次浅拷贝，换来的是「任何读方都不需要懂这把锁」。
     */
    public List<Map<String, Object>> sources() {
        synchronized (sources) {
            return new ArrayList<>(sources);
        }
    }

    /** 本轮图片编号体系快照（引用完整性自检要按它判 [图片N] 是否悬空）。 */
    public Map<Integer, String> imgIndex() {
        synchronized (sources) {
            return new LinkedHashMap<>(imgIndex);
        }
    }

    public Map<Integer, String> imgDescIndex() {
        synchronized (sources) {
            return new LinkedHashMap<>(imgDescIndex);
        }
    }

    /**
     * 本轮正文配图清单（原始 URL，下标即 [图片N] 的 N-1）——落库用这份。
     * <p>库里必须存原始地址：签名是一次性凭据，落库等于写进一批过期数据（产物 URL 同一口径，
     * 读侧由 {@code ImageUrlSigner.signMessageMedia} 现场签）。
     */
    public List<String> rawImages() {
        synchronized (sources) {
            return new ArrayList<>(imgIndex.values());
        }
    }

    /** 按图片鉴权配置签一份 URL 清单（未开鉴权时原样返回）。出 SSE 帧的都是签名副本，落库的必须不是。 */
    public List<String> sign(List<String> rawUrls) {
        return rawUrls.stream().map(imageUrlSigner::signUrl).toList();
    }

    /** 本轮配图清单的展示副本（已签名）：接流快照用它，image 帧与 done.finalImages 同形。 */
    public List<String> signedImages() {
        return sign(rawImages());
    }

    /**
     * 降级去重表：<b>故意返回活引用</b>——引用自检（{@link com.wenqu.ai.agent.context.CitationIntegrityService}）
     * 要在检查过程中往里登记降级，给副本等于把提示丢在半路。并发写由
     * {@code ChatRunRuntime#addDegradation} 在 {@code codes} 上串行；读方都在本轮收口之后。
     */
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
        synchronized (sources) {
            sources.clear();
            sources.addAll(snapshot);
        }
    }

    /** 降级明细：同样返回活引用（理由见 {@link #degradedCodes()}），登记与消费共用这一份。 */
    public List<Map<String, String>> degradations() {
        return degradations;
    }
}
