package com.wenqu.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答中 [图片N] 标记与图片描述的相关性校验（LLM 偶发错配的兜底防线）。
 * 策略：提取回答中的图片标记，取标记前文 + 用户问题作为判定文本，
 * 与图片真实描述做包含关系判断；不匹配的标记由调用方删除并重建编号。
 * 空描述 / 判定文本无可用内容一律放行，避免误杀旧文档（无视觉描述）与泛化问题。
 * 匹配算法：描述切 2 字滑窗，任一窗口出现在判定文本中即命中（宁漏检、勿误杀）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ImageFilterService {

    /** 匹配 [图片N] / [图片N：描述]（N 为全局图片编号；无编号的 [图片] 占位不匹配） */
    private static final Pattern MARK_PATTERN = Pattern.compile("\\[图片(\\d+)(?:[：:][^\\]]*)?\\]");

    /** 回答中的图片标记（pos 为标记起始位置，seq 为全局图片编号） */
    public record Match(int pos, int seq) {
    }

    /**
     * 提取回答中所有 [图片N] 标记，按出现位置有序
     */
    public List<Match> extractMarks(String answer) {
        List<Match> marks = new ArrayList<>();
        if (answer == null || answer.isEmpty()) return marks;
        Matcher m = MARK_PATTERN.matcher(answer);
        while (m.find()) {
            marks.add(new Match(m.start(), Integer.parseInt(m.group(1))));
        }
        return marks;
    }

    /**
     * 取标记前文：从 [pos-maxChars, pos) 窗口内最近的分句符（。！？；\n）之后开始，
     * 最多 maxChars 字；窗口内无分句符则取整个窗口。用于近似"LLM 为什么选这张图"的上下文。
     */
    public String precedingContext(String answer, int pos, int maxChars) {
        if (answer == null || answer.isEmpty() || pos <= 0 || maxChars <= 0) return "";
        int start = Math.max(0, pos - maxChars);
        String window = answer.substring(start, pos);
        int cut = -1;
        for (char c : new char[]{'。', '！', '？', '；', '\n'}) {
            cut = Math.max(cut, window.lastIndexOf(c));
        }
        return (cut >= 0 ? window.substring(cut + 1) : window).trim();
    }

    /**
     * 相关性判定：desc 切 2 字滑窗（去重），任一窗口出现在 judgeText 中即命中，命中数 ≥ minHits 为相关。
     * judgeText / desc 为空一律放行（true）。宁漏检、勿误杀：漏检仅维持现状（图不额外剔除），误杀会丢掉正确配图。
     * 不用 KeywordExtractor：其长整词（如"评分组件怎么使用"）与短描述（"评分框五星"）做 contains 必然失配，会误杀。
     * 宽松回退：2 字窗不足时，任一连续 4 字子串（主题词级，如"分页子表"）命中判定文本也放行——
     * 视觉补描述的通用句式（"界面截图展示操作流程…"）2 字窗常与回答失配，但主题词往往对得上。
     */
    public boolean relevant(String judgeText, String desc, int minHits) {
        if (judgeText == null || judgeText.isBlank() || desc == null || desc.isBlank() || desc.length() < 2) return true;
        Set<String> seen = new HashSet<>();
        int hits = 0;
        for (int i = 0; i < desc.length() - 1; i++) {
            char a = desc.charAt(i);
            char b = desc.charAt(i + 1);
            // 跳过含空白/标点的窗口（描述多为连续中文，窗口切在分隔处无检索价值）
            if (!Character.isLetterOrDigit(a) || !Character.isLetterOrDigit(b)) continue;
            String w = desc.substring(i, i + 2);
            if (seen.add(w) && judgeText.contains(w)) hits++;
        }
        if (hits >= minHits) return true;
        // 宽松回退：连续 4 字主题词命中判定文本即放行
        for (int i = 0; i <= desc.length() - 4; i++) {
            String w4 = desc.substring(i, i + 4);
            if (w4.charAt(0) != ' ' && judgeText.contains(w4)) return true;
        }
        return false;
    }

    /**
     * 校验并重建回答：逐图（首次出现）用「标记前文 + 用户问题」与图片真实描述做相关性判定，
     * 不匹配的标记删除、保留的按首次出现顺序重编 1..M。
     * 编造的不存在编号（imgDescIndex 无此 key）直接剔除；描述为空（旧文档无视觉描述）放行。
     *
     * @param answer           LLM 原始回答
     * @param imgDescIndex     全局图片编号 → 图片描述（RagService 构建上下文时生成）
     * @param question         用户问题（参与判定）
     * @param minHits          关键词命中数阈值
     * @param preContextChars  取标记前文的最大字符数
     */
    public RebuildResult rebuild(String answer, Map<Integer, String> imgDescIndex,
                                 String question, int minHits, int preContextChars) {
        List<Match> marks = extractMarks(answer);
        Map<Integer, Integer> seqMap = new HashMap<>();   // oldSeq → newSeq
        Set<Integer> dropped = new LinkedHashSet<>();     // 被剔除的编号
        List<Integer> keptSeq = new ArrayList<>();        // 保留的编号，按新编号升序
        int newSeq = 0;
        for (Match m : marks) {
            if (seqMap.containsKey(m.seq()) || dropped.contains(m.seq())) continue; // 重复引用按首次判定
            String desc = imgDescIndex.get(m.seq());
            if (desc == null) {            // LLM 编造的不存在编号
                dropped.add(m.seq());
                continue;
            }
            if (desc.isBlank()) {          // 无描述无法校验，放行
                seqMap.put(m.seq(), ++newSeq);
                keptSeq.add(m.seq());
                continue;
            }
            String ctx = precedingContext(answer, m.pos(), preContextChars);
            String judgeText = (ctx + " " + (question == null ? "" : question)).trim();
            if (relevant(judgeText, desc, minHits)) {
                seqMap.put(m.seq(), ++newSeq);
                keptSeq.add(m.seq());
            } else {
                dropped.add(m.seq());
            }
        }
        if (dropped.isEmpty()) return new RebuildResult(answer, dropped, keptSeq);
        // 重建文本：保留标记重编号（统一输出 [图片newSeq]，去掉 LLM 附带的描述），剔除标记删除
        Matcher m = MARK_PATTERN.matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Integer seq = seqMap.get(Integer.parseInt(m.group(1)));
            m.appendReplacement(sb, Matcher.quoteReplacement(seq == null ? "" : "[图片" + seq + "]"));
        }
        m.appendTail(sb);
        return new RebuildResult(sb.toString(), dropped, keptSeq);
    }

    /** 校验重建结果：text 为重建后的回答文本，dropped 为被剔除的编号，keptSeq 为保留编号（新编号升序） */
    public record RebuildResult(String text, Set<Integer> dropped, List<Integer> keptSeq) {
    }
}
