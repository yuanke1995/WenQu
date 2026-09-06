package com.wisesoft.ai.service;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文查询词元提取（用于关键词召回）
 * <p>
 * 两级提取：
 * 1. jieba 搜索模式分词（SEARCH：细粒度切出有效词），过滤停用词，长词优先
 * 2. 对长词补充 2-gram/4-gram 分解子词元（提高"换说法/子串匹配"场景的召回）
 *
 * @author yuanke
 */
@Component
public class KeywordExtractor {

    private final ConfigService configService;
    /** jieba 分词器（线程安全，可共享单例） */
    private final JiebaSegmenter segmenter;

    public KeywordExtractor(ConfigService configService) {
        this.configService = configService;
        this.segmenter = new JiebaSegmenter();
    }

    /** 预热：WordDictionary 懒加载单例，首次调用加载词典较慢，启动时先跑一次避免并发首访竞态 */
    @PostConstruct
    void warmup() {
        try {
            segmenter.process("预热测试分词加载", JiebaSegmenter.SegMode.SEARCH);
        } catch (Exception e) {
            // 词典加载失败不影响启动，首次实际调用时会重试
        }
    }

    /** 常见停用词（疑问词/语气词/无检索价值）。注意：Set.of 不允许重复元素！ */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "吧", "啊", "么", "怎么", "怎样", "如何", "什么", "哪些",
            "哪个", "为什么", "请问", "请", "帮我", "一下", "介绍", "说明", "讲讲", "告诉",
            "这个", "那个", "一个", "功能", "操作", "使用", "方法", "步骤", "流程", "设置",
            "可以", "需要", "是否", "应该", "咋", "咋样", "比如", "例如", "关于", "在", "是", "和", "与");

    /** 单字/噪声级 bigram 停用字（bigram 中含这些字时跳过，如"个新"、"么设"） */
    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '吗', '呢', '吧', '啊', '么', '是', '在', '和', '与', '请', '我', '你', '它', '这', '那');

    /** 原词最大数 / 原词+子词元总上限（控制 LIKE OR 数量与 SQL 开销）；retrieval.keywordMax* 可调 */
    private int maxTerms() { return configService.getInt("retrieval.keywordMaxTerms", 6); }
    private int maxTotal() { return configService.getInt("retrieval.keywordMaxTotal", 12); }

    /**
     * 提取检索词元（jieba 主词元优先，子词元补充；空输入返回空列表）
     */
    public List<String> extract(String query) {
        MainSub ms = extractMainSub(query);
        List<String> main = ms.main().stream()
                .sorted((a, b) -> b.length() - a.length())
                .limit(maxTerms()).toList();
        List<String> sub = ms.sub().stream()
                .sorted((a, b) -> b.length() - a.length())
                .limit(Math.max(0, maxTotal() - main.size())).toList();
        List<String> result = new ArrayList<>(main);
        result.addAll(sub);
        return result;
    }

    /**
     * 仅主词元（jieba 有效词去停用词，不含 2-gram/4-gram 子词元）。
     * 供检索状态行等面向用户的展示：子词元（如"据源"这类 2-gram 碎片）只参与内部召回、不展示。
     */
    public List<String> extractMain(String query) {
        MainSub ms = extractMainSub(query);
        return ms.main().stream()
                .sorted((a, b) -> b.length() - a.length())
                .limit(maxTerms())
                .toList();
    }

    /** 主词元 + 子词元 拆分结果（extract / extractMain 共用一次 jieba 分词） */
    private record MainSub(Set<String> main, Set<String> sub) {
    }

    private MainSub extractMainSub(String query) {
        if (query == null || query.isBlank()) return new MainSub(Set.of(), Set.of());
        LinkedHashSet<String> mainTerms = new LinkedHashSet<>();
        LinkedHashSet<String> subTerms = new LinkedHashSet<>();
        for (SegToken token : segmenter.process(query, JiebaSegmenter.SegMode.SEARCH)) {
            String t = token.word.trim();
            if (t.length() < 2) continue;               // 单字无检索价值
            if (STOP_WORDS.contains(t)) continue;
            mainTerms.add(t);
            // 补充子词元（提高"换说法/子串"场景召回，LIKE 全词匹配不到子内容）
            if (isPureChinese(t) && t.length() >= 3) {
                // 纯中文长词：2-gram 子词元（"表单""创建"等有效词）
                for (int i = 0; i < t.length() - 1; i++) {
                    String g = t.substring(i, i + 2);
                    if (STOP_CHARS.contains(g.charAt(0)) || STOP_CHARS.contains(g.charAt(1))) continue;
                    subTerms.add(g);
                }
            } else if (t.length() >= 6) {
                // 中英混合长词：4 字滑窗子词元（拆出"核心优势"这类子串）
                for (int i = 0; i <= t.length() - 4; i++) {
                    String g = t.substring(i, i + 4);
                    if (STOP_CHARS.contains(g.charAt(0)) || STOP_CHARS.contains(g.charAt(1))) continue;
                    subTerms.add(g);
                }
            }
        }
        return new MainSub(mainTerms, subTerms);
    }

    private boolean isPureChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 0x4E00 && c <= 0x9FFF)) return false;
        }
        return true;
    }
}
