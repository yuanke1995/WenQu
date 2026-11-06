package com.wenqu.ai.util;

import java.util.Set;

/**
 * 「哪些文件类型能预览、预览时发什么内容类型、多大算太大」——服务端这一份判据。
 *
 * <p><b>为什么要有这个类</b>：产物与工作区文件两条路各自都有一份扩展名清单，一份错了就是「这个页面有预览按钮、
 * 那个页面没有」；前端 {@code utils/artifactView.js} 那一份管的是「拿回来之后怎么摆」（页面档 / 读字档 / 只下载），
 * 与本类的职责不同：<b>前端给了按钮而本类说发不出去</b>，用户点到的就是一张白页或一次下载。
 * 现在这条对账不靠人工——{@link #kindOf} 经接口下发 {@code preview}，前端只认它；那边那张扩展名表
 * 只兜没有该字段的历史数据。
 *
 * <p><b>写侧也从这里派生</b>：{@link #TEXT_ARTIFACT_EXTS} 是「模型能写出成文本产物的类型」，
 * {@link #FILE_DELIVERY_EXTS} 是「沙盒里能交付成文件的类型」，两张都由上面几档拼出来——
 * 以前各写一张，结果是 yaml/xml/ts 能预览却写不出来、沙盒交付还拒收代码文件。
 * 不变式：能写的必然能看（{@code TEXT_ARTIFACT_EXTS ⊆ READABLE_EXTS ∪ PAGE_EXTS}）。
 * <p>{@link #kindOf} 是「怎么预览」的唯一判据，经接口下发 {@code preview} 字段；前端只认它，
 * 自己那张扩展名表降为历史数据的兜底。
 *
 * <p><b>为什么不列 ico/avif</b>：{@link #sniffImageType} 认不出的图片类型会兜成 image/jpeg，
 * 发一个错的内容类型出去比不给预览按钮更糊弄人。要加就连带把嗅探表一起补，别只加一头。
 *
 * @author yuanke
 */
public final class PreviewTypes {

    /** 页面设计类：浏览器真的渲染（脚本落进不透明源，见 ArtifactSandboxInterceptor） */
    public static final Set<String> PAGE_EXTS = Set.of("html", "htm");
    /** 图片类：浏览器直接渲染 */
    public static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg");
    /** PDF：浏览器内置阅读器渲染 */
    public static final Set<String> PDF_EXTS = Set.of("pdf");
    /**
     * 取原文在应用里渲染的文本类（Markdown / 纯文本 / 代码 / 分隔符表）。
     * <p>不含 {@link #PAGE_EXTS} 与 svg：那几样预览台直接把地址交给 iframe/img，取原文反而是多此一举的第二条路。
     */
    public static final Set<String> READABLE_EXTS = Set.of(
            "md", "markdown", "txt", "log", "csv", "tsv", "json", "yaml", "yml", "xml", "toml", "ini", "conf",
            "properties", "py", "js", "mjs", "ts", "jsx", "tsx", "java", "go", "rs", "sql", "sh", "bash", "zsh",
            "css", "scss", "vue");
    /** Office：能交付、能下载，但应用内不预览（没有渲染器） */
    public static final Set<String> OFFICE_EXTS = Set.of("xlsx", "xls", "docx", "pptx");
    /** 压缩包：只下载不预览 */
    public static final Set<String> ARCHIVE_EXTS = Set.of("zip");

    /** 模型能写成文本产物的类型：可读文本 + 网页 */
    public static final Set<String> TEXT_ARTIFACT_EXTS = union(READABLE_EXTS, PAGE_EXTS);
    /** 沙盒里能交付成文件产物的类型：文本 + 图片 + PDF + Office + 压缩包 */
    public static final Set<String> FILE_DELIVERY_EXTS =
            union(READABLE_EXTS, PAGE_EXTS, IMAGE_EXTS, PDF_EXTS, OFFICE_EXTS, ARCHIVE_EXTS);

    /** 取原文的字节上限（256KB）：再大就不是「看一眼」，前端会显示「后半部分没取回来」并引导下载 */
    public static final int TEXT_BODY_CAP = 256 * 1024;
    /** 文本预览的大小上限（2MB）：超过它连地址都不发 */
    public static final long MAX_TEXT_PREVIEW = 2L * 1024 * 1024;
    /** 图片、页面与 PDF 的预览上限（10MB）：整页 HTML 常把图片内联成 base64，套 2MB 会把能看的页面判成看不了 */
    public static final long MAX_DOC_PREVIEW = 10L * 1024 * 1024;

    private PreviewTypes() {
    }

    /**
     * 预览时下发什么内容类型：页面按 text/html（真的渲染成页面）、图片按原类型、PDF 交给浏览器内置阅读器、
     * 其余可读文本按 text/plain；发不出去的类型返回 {@code null}（列表就不给预览地址）。
     *
     * <p><b>页面为什么敢发 text/html</b>：这条响应带 {@code Content-Security-Policy: sandbox}，
     * 顶层导航与预览台的 iframe 都落进不透明源——脚本照跑，但碰不到本域的登录令牌与存储。
     */
    public static String viewContentType(String ext) {
        if (ext == null) return null;
        if (PAGE_EXTS.contains(ext)) return "text/html; charset=UTF-8";
        if (IMAGE_EXTS.contains(ext)) return sniffImageType(ext);
        if (PDF_EXTS.contains(ext)) return "application/pdf";
        if (READABLE_EXTS.contains(ext)) return "text/plain; charset=UTF-8";
        return null;
    }

    /** 这个类型该不该按文本读回来给前端排版 */
    public static boolean isReadable(String ext) {
        return ext != null && READABLE_EXTS.contains(ext);
    }

    /**
     * 「怎么预览」的唯一判据，取值与前端 {@code artifactView.PV} 同形：
     * {@code page / image / pdf / md / table / text / none}。
     * <p>经接口的 {@code preview} 字段下发，前端六个入口面都只认它；{@code none}＝只给下载，
     * 别再摆一个点开是白页的「预览」按钮。
     */
    public static String kindOf(String ext) {
        if (ext == null || ext.isBlank()) return "none";
        String e = ext.toLowerCase(java.util.Locale.ROOT);
        if (PAGE_EXTS.contains(e)) return "page";
        if (IMAGE_EXTS.contains(e)) return "image";
        if (PDF_EXTS.contains(e)) return "pdf";
        if ("md".equals(e) || "markdown".equals(e)) return "md";
        if ("csv".equals(e) || "tsv".equals(e)) return "table";
        if (READABLE_EXTS.contains(e)) return "text";
        return "none";
    }

    /**
     * 给拒收类错误消息用的一句话清单（截断，别把 40 个扩展名整个糊进对话框）。
     * <p>拒收却不说能收什么，模型只能反复猜、用户也不知道该改成哪种——一次说清才谈得上自愈。
     */
    public static String extListHint(Set<String> exts) {
        java.util.List<String> sorted = new java.util.ArrayList<>(exts);
        java.util.Collections.sort(sorted);
        int cap = Math.min(sorted.size(), 25);
        String list = String.join("/", sorted.subList(0, cap));
        return sorted.size() > cap ? list + " 等 " + sorted.size() + " 种" : list;
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... groups) {
        java.util.Set<String> all = new java.util.HashSet<>();
        for (Set<String> g : groups) all.addAll(g);
        return Set.copyOf(all);
    }

    /** 是不是「整页/整图」那一档（体积帽比纯文本宽） */
    private static boolean isDoc(String ext) {
        return IMAGE_EXTS.contains(ext) || PAGE_EXTS.contains(ext) || PDF_EXTS.contains(ext);
    }

    /** 体积上限：超限返回一句可读原因，没超限返回 {@code null} */
    public static String viewCapError(String ext, long size) {
        if (ext == null) return "该文件类型不支持预览，请下载后查看";
        long cap = isDoc(ext) ? MAX_DOC_PREVIEW : MAX_TEXT_PREVIEW;
        if (size <= cap) return null;
        return (isDoc(ext) ? "图片/页面" : "文件") + "太大（" + (size / 1024 / 1024) + "MB），超过 "
                + (cap / 1024 / 1024) + "MB 预览上限，请下载后查看";
    }

    /** 扩展名（小写、不带点） */
    public static String extOf(String name) {
        if (name == null) return "";
        String n = name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static String sniffImageType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            default -> "image/jpeg";
        };
    }
}
