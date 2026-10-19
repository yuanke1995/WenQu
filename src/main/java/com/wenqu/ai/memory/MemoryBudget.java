package com.wenqu.ai.memory;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code MEMORY.md} 的 token 预算与确定性裁剪。
 *
 * <p><b>为什么需要它</b>：框架的 consolidation prompt 只是「请控制在 %d token」，
 * 模型并不保证遵守——本机 admin 账号的 {@code MEMORY.md} 实测到 57 KB，而框架的注入预算按
 * {@code 字符数 / 4} 估算，超出部分会被 {@code WorkspaceContextMiddleware} 截掉，
 * 表现为「记了但想不起来」。这里在<b>写入落盘前</b>做一次硬裁剪，保证文件永远不超过
 * {@code memory.maxMdTokens} 对应的字符预算；面板的「按上限裁剪」与自适应写入共用同一实现。
 *
 * <p>裁剪策略：保留文档头与所有 {@code ##} 小节标题，从正文最长的节开始逐行删末尾，
 * 仍然超限才从后往前删整节；最后追加一行裁剪说明。它是确定性的，不调模型、不增加成本。
 */
public final class MemoryBudget {

    private MemoryBudget() {
    }

    /** 与框架 {@code WorkspaceContextMiddleware#estimateTokens} 同一口径（字符数 / 4）。 */
    public static int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : text.length() / 4;
    }

    /** 当前配置下 {@code MEMORY.md} 允许的最大字符数。 */
    public static int maxChars(ConfigService configService) {
        int tokens = configService == null
                ? ConfigDefaults.MEMORY_MAX_MD_TOKENS
                : configService.getInt("memory.maxMdTokens", ConfigDefaults.MEMORY_MAX_MD_TOKENS);
        return Math.max(500, tokens) * 4;
    }

    /**
     * 把 markdown 裁到 {@code maxChars} 以内。
     *
     * @param text     原始内容（null/空原样返回）
     * @param maxChars 字符预算（≤0 表示不裁剪）
     * @return 不超过 {@code maxChars} 的文本；未超限时返回原文引用
     */
    public static String trim(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0 || text.length() <= maxChars) {
            return text == null ? "" : text;
        }

        String notice = "\n\n> 记忆已按上限裁剪：原始 " + text.length()
                + " 字符，保留 " + maxChars + " 字符以内。\n";
        int budget = maxChars - notice.length();
        if (budget <= 0) return notice.substring(0, Math.min(notice.length(), maxChars));

        List<String> lines = new ArrayList<>(text.lines().toList());
        int firstSection = 0;
        while (firstSection < lines.size() && !lines.get(firstSection).startsWith("## ")) {
            firstSection++;
        }
        String header = String.join("\n", lines.subList(0, firstSection)).stripTrailing();

        List<Section> sections = new ArrayList<>();
        for (int i = firstSection; i < lines.size(); ) {
            String line = lines.get(i);
            if (!line.startsWith("## ")) {
                i++;
                continue;
            }
            Section section = new Section(line);
            i++;
            while (i < lines.size() && !lines.get(i).startsWith("## ")) {
                section.body.add(lines.get(i));
                i++;
            }
            sections.add(section);
        }

        if (sections.isEmpty()) {
            // 没有小节结构：按行边界硬截断，保留文档头
            String head = header.isEmpty() ? String.join("\n", lines) : header;
            int keep = Math.max(0, budget);
            if (head.length() > keep) {
                int cut = head.lastIndexOf('\n', keep);
                head = head.substring(0, cut > 0 ? cut : keep).stripTrailing();
            }
            return head + notice;
        }

        while (render(header, sections).length() > budget) {
            Section largest = null;
            for (Section s : sections) {
                if (!s.body.isEmpty() && (largest == null || s.bodyChars() > largest.bodyChars())) {
                    largest = s;
                }
            }
            if (largest != null) {
                largest.body.remove(largest.body.size() - 1);
                continue;
            }
            if (sections.size() > 1) {
                sections.remove(sections.size() - 1);
                continue;
            }
            // 仅剩一个标题、仍然超限：截文档头
            int over = render(header, sections).length() - budget;
            if (header.length() > over) {
                header = header.substring(0, Math.max(0, header.length() - over)).stripTrailing();
                continue;
            }
            break;
        }

        String out = render(header, sections) + notice;
        if (out.length() > maxChars) {
            int cut = Math.max(0, maxChars - notice.length());
            String base = out.substring(0, Math.min(cut, out.length())).stripTrailing();
            out = base + notice;
        }
        return out;
    }

    private static String render(String header, List<Section> sections) {
        StringBuilder sb = new StringBuilder(header);
        for (Section s : sections) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(s.heading);
            if (!s.body.isEmpty()) {
                sb.append('\n');
                for (int i = 0; i < s.body.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(s.body.get(i));
                }
            }
        }
        return sb.toString();
    }

    private static final class Section {
        final String heading;
        final List<String> body = new ArrayList<>();

        Section(String heading) {
            this.heading = heading;
        }

        int bodyChars() {
            int n = 0;
            for (String line : body) n += line.length() + 1;
            return n;
        }
    }
}
