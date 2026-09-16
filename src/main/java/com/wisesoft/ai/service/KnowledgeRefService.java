package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.mapper.KnowledgeRefMapper;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.model.KnowledgeRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识块关联检索：
 * 1) 交叉引用识别（解析时，入库后）：扫描块 content 中"详见/参见/见 4.1.2 节/「章节名」"等引用表达，
 *    建立块间引用关系（同文档内），存 c_ai_knowledge_ref。
 * 2) 1-hop 扩散（检索时）：命中 A → 带出 A 引用的块 B（出边，默认）+ 引用 A 的块 C（入边，默认关）。
 * 3) 结构上下文扩展（检索时）：命中块 → 沿 titlePath 逐级带出父章节块（标题/摘要）。
 *
 * 纯派生数据：rebuildByDocId 先删后插，与块/文档同生命周期；任一环节失败降级为"不扩散"，不阻断回答。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRefService {

    private final KnowledgeRefMapper refMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ConfigService configService;

    /** 引用表达（记录一次命中：编号 或 章节名；loose=false 的提及类只做精确匹配，控制误报） */
    record Candidate(String refText, String num, String name, boolean loose) {
    }

    /** 扩散结果：original=原始命中块，extra=扩散块（出边→入边→父块，按此顺序），origins=块→来源标注 */
    public record ExpandResult(List<HybridRetrievalService.Hit> original,
                               List<HybridRetrievalService.Hit> extra,
                               Map<String, String> origins) {
    }

    // ==================== 引用识别（解析时） ====================

    /** 模式1 编号引用：详见/参见/见 4.1.2 节（可带"第X章"前缀） */
    private static final Pattern REF_NUM = Pattern.compile(
            "(?:详见|参见|参考|参看|见)\\s*(?:第[一二三四五六七八九十]+章)?\\s*(\\d+(?:\\.\\d+){1,3})\\s*(?:章|节|小节)?");
    /** 模式2 中文数字章节：见 第四章 / 参见 第3节 */
    private static final Pattern REF_ZH = Pattern.compile(
            "(?:详见|参见|参考|参看|见)\\s*第\\s*([一二三四五六七八九十]+)\\s*(?:章|节)");
    /** 模式3 书名号/引号章节名：参见《数据字典》/ 见"新建字典" */
    private static final Pattern REF_QUOTED = Pattern.compile(
            "(?:详见|参见|参考|参看|见)\\s*[《「\"『]([^》」\"』]{2,30})[》」\"』]");
    /** 模式4 裸章节名：详见 数值Api类型（负向前瞻排除 图/表/附件/相对引用/虚词开头） */
    private static final Pattern REF_BARE = Pattern.compile(
            "(?:详见|参见|参考|参看|见)\\s*(?!图|表|附件|上述|下文|上文|如下|上节|下节|上一小节|下一小节|上一节|下一节|上面|下面|前面|后面|前文|后文|官网|网址|http|链接|这|该|以及|下方|上方|关于)([一-龥A-Za-z0-9]{2,12})");
    /** 模式5 章节号提及（无动词）：如 4.1.2 所述 / 在 4.1.2 节中 / 第 4.1.2 章（排除图/表编号与连数字段） */
    private static final Pattern REF_NUM_MENTION = Pattern.compile(
            "(?<![0-9图表])(\\d+(?:\\.\\d+){1,3})\\s*(?:章|节|小节)(?![0-9])");
    /** 模式6 书名号/引号章节名提及（无动词）：在《数据字典》中 / 「新建字典」部分（书名号本身即指代） */
    private static final Pattern REF_QUOTED_MENTION = Pattern.compile(
            "[《「\"『]([^》」\"』]{2,30})[》」\"』]");
    /** 模式7 裸章节名+章节后缀：数据字典章节 / 报表设计模块 / 新建字典界面（提及类，仅精确匹配） */
    private static final Pattern REF_TITLE_SUFFIX = Pattern.compile(
            "(?<![一-龥A-Za-z0-9])([一-龥A-Za-z0-9]{2,12})(?:章节|部分|小节|模块|界面)(?![一-龥A-Za-z0-9])");

    private static final Map<Character, Integer> ZH_DIGIT = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);

    /** 删除某文档全部引用（文档删除时） */
    public void removeByDocId(String docId) {
        try {
            if (docId != null && !docId.isBlank()) refMapper.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 删除文档 {} 引用关系失败: {}", docId, e.getMessage());
        }
    }

    /** 删除某块的出边+入边（知识块删除时） */
    public void removeByKnowledgeId(String knowledgeId) {
        try {
            if (knowledgeId == null || knowledgeId.isBlank()) return;
            refMapper.deleteByFromId(knowledgeId);
            refMapper.deleteByToId(knowledgeId);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 删除知识块 {} 引用关系失败: {}", knowledgeId, e.getMessage());
        }
    }

    /** 重建单块的出边（知识块内容编辑后；入边目标 id 不变无需重建） */
    public void rebuildFromKnowledgeId(String knowledgeId, String docId) {
        if (knowledgeId == null || docId == null || knowledgeId.isBlank() || docId.isBlank()) return;
        try {
            Knowledge from = knowledgeMapper.selectById(knowledgeId);
            if (from == null) return;
            List<Knowledge> blocks = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getDocId, docId)
                    .orderByAsc(Knowledge::getChunkIndex));
            List<KnowledgeRef> refs = new ArrayList<>();
            for (Candidate c : detectRefs(from.getContent())) {
                String toId = matchTarget(from, c, blocks);
                if (toId == null || toId.equals(from.getId())) continue;
                KnowledgeRef r = new KnowledgeRef();
                r.setDocId(docId);
                r.setFromKnowledgeId(from.getId());
                r.setToKnowledgeId(toId);
                r.setRefText(c.refText().length() > 255 ? c.refText().substring(0, 255) : c.refText());
                refs.add(r);
            }
            refMapper.deleteByFromId(knowledgeId);
            if (!refs.isEmpty()) refMapper.insert(refs);
            log.debug("[REF] 知识块 {} 出边重建：{} 条", knowledgeId, refs.size());
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 知识块 {} 出边重建失败: {}", knowledgeId, e.getMessage());
        }
    }

    /** 全量重建某文档的引用关系（先删后插；fail-loud：失败告警不阻断解析，检索侧降级不扩散） */
    public void rebuildByDocId(String docId) {
        if (docId == null || docId.isBlank()) return;
        try {
            List<Knowledge> blocks = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getDocId, docId)
                    .orderByAsc(Knowledge::getChunkIndex));
            List<KnowledgeRef> refs = new ArrayList<>();
            Set<String> dedup = new HashSet<>();
            for (Knowledge a : blocks) {
                for (Candidate c : detectRefs(a.getContent())) {
                    String toId = matchTarget(a, c, blocks);
                    if (toId == null || toId.equals(a.getId())) continue;
                    if (!dedup.add(a.getId() + "->" + toId)) continue;
                    KnowledgeRef r = new KnowledgeRef();
                    r.setDocId(docId);
                    r.setFromKnowledgeId(a.getId());
                    r.setToKnowledgeId(toId);
                    r.setRefText(c.refText().length() > 255 ? c.refText().substring(0, 255) : c.refText());
                    refs.add(r);
                }
            }
            refMapper.deleteByDocId(docId);
            if (!refs.isEmpty()) refMapper.insert(refs);
            log.info("[REF] 文档 {} 引用关系重建完成：{} 块 → {} 条引用", docId, blocks.size(), refs.size());
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 文档 {} 引用关系重建失败（检索侧将降级为不扩散）: {}", docId, e.getMessage());
        }
    }

    /** 单块引用条数上限（retrieval.maxRefsPerBlock 可配，默认 8；提及类易多，防 ref 膨胀；显式引用在前、提及在后，超出丢弃） */
    private static final String MAX_REFS_PER_BLOCK_KEY = "retrieval.maxRefsPerBlock";
    private static final int MAX_REFS_PER_BLOCK_DEFAULT = 8;

    /** 扫描块 content，提取候选引用（显式引用 + 无动词提及；同 from+to 去重）。
     *  mentionEnabled 由调用方读取配置（retrieval.refDetectMention），无参桥接实例方法便于单测直接验证纯逻辑 */
    List<Candidate> detectRefs(String content) {
        int maxRefs = Math.max(1, configService.getInt(MAX_REFS_PER_BLOCK_KEY, MAX_REFS_PER_BLOCK_DEFAULT));
        return detectRefs0(content, configService.getBoolean("retrieval.refDetectMention"), maxRefs);
    }

    static List<Candidate> detectRefs0(String content, boolean mentionEnabled, int maxRefs) {
        List<Candidate> list = new ArrayList<>();
        if (content == null || content.isBlank()) return list;
        Set<String> seen = new HashSet<>();

        // —— 显式引用（详见/参见/见，可模糊匹配） ——
        Matcher m = REF_NUM.matcher(content);
        while (m.find()) {
            String ref = m.group().trim();
            // 排除"见图X/如表X"（图片/表格引用，非章节）
            if (ref.contains("图") || ref.contains("表")) continue;
            String num = m.group(1);
            if (seen.add("num:" + num)) list.add(new Candidate(ref, num, null, true));
        }
        Matcher m2 = REF_ZH.matcher(content);
        while (m2.find()) {
            String num = zhToArabic(m2.group(1));
            if (num != null && seen.add("num:" + num)) {
                list.add(new Candidate(m2.group().trim(), num, null, true));
            }
        }
        Matcher m3 = REF_QUOTED.matcher(content);
        while (m3.find()) {
            String name = m3.group(1).trim();
            // 排除纯数字（如 见"20" 是数值引用，非章节名）
            if (name.length() >= 2 && !name.matches("\\d+") && seen.add("name:" + name)) {
                list.add(new Candidate(m3.group().trim(), null, name, true));
            }
        }
        Matcher m4 = REF_BARE.matcher(content);
        while (m4.find()) {
            String name = m4.group(1).trim();
            if (name.length() >= 2 && seen.add("name:" + name)) {
                list.add(new Candidate(m4.group().trim(), null, name, true));
            }
        }

        // —— 无动词提及（开关默认开；仅精确匹配防误报） ——
        if (mentionEnabled) {
            Matcher m5 = REF_NUM_MENTION.matcher(content);
            while (m5.find()) {
                String num = m5.group(1);
                if (seen.add("num:" + num)) list.add(new Candidate(m5.group().trim(), num, null, false));
            }
            Matcher m6 = REF_QUOTED_MENTION.matcher(content);
            while (m6.find()) {
                String name = m6.group(1).trim();
                if (name.length() >= 2 && !name.matches("\\d+") && seen.add("name:" + name)) {
                    list.add(new Candidate(m6.group().trim(), null, name, false));
                }
            }
            Matcher m7 = REF_TITLE_SUFFIX.matcher(content);
            while (m7.find()) {
                String name = m7.group(1).trim();
                if (name.length() >= 2 && seen.add("name:" + name)) {
                    list.add(new Candidate(m7.group().trim(), null, name, false));
                }
            }
        }
        // 上限截断：显式引用排前面（插入顺序天然显式在前），超出丢弃
        if (list.size() > maxRefs) {
            return new ArrayList<>(list.subList(0, maxRefs));
        }
        return list;
    }

    /** 目标匹配：编号 → 章节名精确 → 弱匹配，逐级降级；匹配不到返回 null（丢弃不阻断） */
    String matchTarget(Knowledge from, Candidate c, List<Knowledge> blocks) {
        if (c.num() != null) {
            // 标题文本自带编号的文档（如"4.1.2 数值Api类型"）
            for (Knowledge b : blocks) {
                if (b.getId().equals(from.getId())) continue;
                String t = b.getTitle() == null ? "" : b.getTitle().trim();
                if (t.equals(c.num()) || t.startsWith(c.num() + " ")) {
                    return b.getId();
                }
            }
            return null; // 编号未命中 → 丢弃（V1 不做路径前缀匹配，避免误匹配子节）
        }
        if (c.name() != null) {
            List<Knowledge> exact = new ArrayList<>();
            for (Knowledge b : blocks) {
                if (b.getId().equals(from.getId())) continue;
                String t = b.getTitle() == null ? "" : b.getTitle().trim();
                if (t.equals(c.name())) exact.add(b);
            }
            if (!exact.isEmpty()) {
                // 同名多块取 chunkIndex 最近
                exact.sort(Comparator.comparingInt(b -> b.getChunkIndex() == null ? 0 : b.getChunkIndex()));
                return exact.get(exact.size() - 1).getId();
            }
            // 提及类（无动词）不做弱匹配，避免把正常话题词（如"数据字典"在正文高频出现）误建成引用边
            if (c.loose() && configService.getBoolean("retrieval.refExpandFuzzyName")) {
                List<Knowledge> fuzzy = new ArrayList<>();
                for (Knowledge b : blocks) {
                    if (b.getId().equals(from.getId())) continue;
                    String t = b.getTitle() == null ? "" : b.getTitle().trim();
                    if ((!t.isEmpty() && t.contains(c.name())) || c.name().contains(t)) fuzzy.add(b);
                }
                if (fuzzy.size() == 1) return fuzzy.get(0).getId(); // 弱匹配仅唯一命中才取
            }
        }
        return null;
    }

    /** 中文数字 → 阿拉伯数字（支持 一~九 / 十 / 十X / X十 / X十Y） */
    static String zhToArabic(String zh) {
        if (zh == null || zh.isEmpty()) return null;
        if ("十".equals(zh)) return "10";
        if (zh.length() == 1) {
            Integer v = ZH_DIGIT.get(zh.charAt(0));
            return v == null ? null : String.valueOf(v);
        }
        int idx = zh.indexOf('十');
        if (idx < 0) return null;
        int tens = idx == 0 ? 1 : ZH_DIGIT.getOrDefault(zh.charAt(idx - 1), 0);
        int ones = idx == zh.length() - 1 ? 0 : ZH_DIGIT.getOrDefault(zh.charAt(idx + 1), 0);
        return String.valueOf(tens * 10 + ones);
    }

    // ==================== 检索扩散（检索时） ====================

    /** 1-hop 扩散 + 结构上下文扩展；失败降级为不扩散 */
    public ExpandResult expand(List<HybridRetrievalService.Hit> hits) {
        if (!configService.getBoolean("retrieval.refExpandEnabled")
                || hits == null || hits.isEmpty()) {
            return new ExpandResult(hits, List.of(), Map.of());
        }
        try {
            Set<String> seen = new LinkedHashSet<>();
            for (HybridRetrievalService.Hit h : hits) seen.add(h.knowledgeId());
            List<HybridRetrievalService.Hit> extra = new ArrayList<>();
            Map<String, String> origins = new LinkedHashMap<>();
            List<String> hitIds = hits.stream().map(HybridRetrievalService.Hit::knowledgeId).toList();

            // 1) 出边 A→B：A 引用了 B
            List<KnowledgeRef> outRefs = refMapper.selectByFromIds(hitIds);
            for (KnowledgeRef r : outRefs) {
                if (seen.add(r.getToKnowledgeId())) {
                    Knowledge b = knowledgeMapper.selectById(r.getToKnowledgeId());
                    if (b != null && !(b.getStatus() != null && b.getStatus() == 1)) {
                        extra.add(toHit(b, "REF_OUT"));
                        origins.put(b.getId(), "REF_OUT");
                    }
                }
            }
            // 2) 入边 C→A：引用 A 的 C（默认关）
            if (configService.getBoolean("retrieval.refExpandIncludeIncoming")) {
                List<KnowledgeRef> inRefs = refMapper.selectByToIds(hitIds);
                for (KnowledgeRef r : inRefs) {
                    if (seen.add(r.getFromKnowledgeId())) {
                        Knowledge c = knowledgeMapper.selectById(r.getFromKnowledgeId());
                        if (c != null && !(c.getStatus() != null && c.getStatus() == 1)) {
                            extra.add(toHit(c, "REF_IN"));
                            origins.put(c.getId(), "REF_IN");
                        }
                    }
                }
            }
            // 3) 结构扩展：父章节块（按命中块 docId 分组一次查同文档块）
            if (configService.getBoolean("retrieval.refExpandParentEnabled")) {
                Set<String> docIds = new HashSet<>();
                for (HybridRetrievalService.Hit h : hits) {
                    if (h.docId() != null && !h.docId().isBlank()) docIds.add(h.docId());
                }
                if (!docIds.isEmpty()) {
                    List<Knowledge> all = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                            .in(Knowledge::getDocId, docIds));
                    for (HybridRetrievalService.Hit h : hits) {
                        for (Knowledge p : findParentBlocks(h, all)) {
                            if (p.getStatus() != null && p.getStatus() == 1) continue; // 块级停用
                            if (seen.add(p.getId())) {
                                extra.add(toHit(p, "PARENT"));
                                origins.put(p.getId(), "PARENT");
                            }
                        }
                    }
                }
            }
            // 数量截断（扩散块是可舍弃的增强，超限丢弃）
            int maxExtra = Math.max(1, configService.getInt("retrieval.refExpandMaxHits", 3));
            if (extra.size() > maxExtra) {
                extra = new ArrayList<>(extra.subList(0, maxExtra));
            }
            if (!extra.isEmpty()) {
                log.info("[REF-EXPAND] 命中 {} 块 → 扩散 {} 块（{}）", hits.size(), extra.size(), origins.values());
            }
            return new ExpandResult(hits, extra, origins);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 引用扩散失败，降级为不扩散: {}", e.getMessage());
            return new ExpandResult(hits, List.of(), Map.of());
        }
    }

    /** 父块定位：titlePath 分段，逐级向上找 title 匹配路径分段且 chunkIndex 在前的最近块 */
    List<Knowledge> findParentBlocks(HybridRetrievalService.Hit hit, List<Knowledge> blocks) {
        if (hit.titlePath() == null || hit.titlePath().isBlank() || blocks == null) return List.of();
        String[] segments = hit.titlePath().split("\\s*>\\s*");
        int maxLevels = Math.max(1, configService.getInt("retrieval.refExpandParentMaxLevels", 2));
        List<Knowledge> parents = new ArrayList<>();
        int hitIdx = hit.chunkIndex() == null ? 0 : hit.chunkIndex();
        int start = Math.max(0, segments.length - maxLevels);
        for (int i = segments.length - 1; i >= start; i--) {
            String parentTitle = segments[i].trim();
            Knowledge best = null;
            for (Knowledge b : blocks) {
                String t = b.getTitle() == null ? "" : b.getTitle().trim();
                if (!parentTitle.equals(t)) continue;
                int idx = b.getChunkIndex() == null ? 0 : b.getChunkIndex();
                if (idx < hitIdx && (best == null || idx > (best.getChunkIndex() == null ? 0 : best.getChunkIndex()))) {
                    best = b;
                }
            }
            if (best != null) parents.add(best); // 父章节无正文块则跳过该级
        }
        return parents;
    }

    /** 块实体 → 检索 Hit（score=0 不参与排序；PARENT 块按配置精简 content） */
    private HybridRetrievalService.Hit toHit(Knowledge b, String origin) {
        List<String> images = (b.getImages() == null || b.getImages().isBlank())
                ? List.of()
                : com.alibaba.fastjson2.JSON.parseArray(b.getImages(), String.class);
        String content = b.getContent() == null ? "" : b.getContent();
        if ("PARENT".equals(origin)) {
            String mode = configService.get("retrieval.refExpandParentMode");
            if (mode == null || mode.isBlank() || "summary".equals(mode)) {
                int chars = configService.getInt("retrieval.refExpandParentSummaryChars", 200);
                if (content.length() > chars) content = content.substring(0, chars) + "…";
            } else if ("title_only".equals(mode)) {
                content = "";
            }
            // full：整块
        }
        return new HybridRetrievalService.Hit(b.getId(), b.getDocId(),
                b.getTitle() == null ? "" : b.getTitle(), content, images, 0,
                b.getChunkIndex(), b.getTitlePath());
    }
}
