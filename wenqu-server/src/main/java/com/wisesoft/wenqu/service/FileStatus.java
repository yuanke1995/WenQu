package com.wisesoft.wenqu.service;

import java.util.List;
import java.util.Set;

/**
 * 文档处理状态机。
 * <p>
 * 常量与集合逐字对应参考实现的 {@code knowledge/base.py::FileStatus} 与
 * {@code knowledge_router.py} 的两个待处理集合。状态是**字符串**而非数字：
 * 数字状态在多阶段（上传→解析→索引）流程中辨析度低，字符串状态能直接表达"卡在哪一步"。
 *
 * <pre>
 * uploaded         已上传，待解析
 * parsing          解析中
 * parsed           已解析，待索引
 * error_parsing    解析失败
 * indexing         索引中（向量化 + 关键词索引）
 * indexed          已索引（终态）
 * error_indexing   索引失败
 * </pre>
 *
 * 两个集合决定"批量补处理"的范围，语义与参考实现一致：
 * <ul>
 *   <li>{@link #PENDING_PARSE_STATUSES}：待解析 = 仅 uploaded</li>
 *   <li>{@link #PENDING_INDEX_STATUSES}：待索引 = parsed 或 error_indexing（失败可重试）</li>
 * </ul>
 */
public final class FileStatus {

    public static final String UPLOADED = "uploaded";
    public static final String PARSING = "parsing";
    public static final String PARSED = "parsed";
    public static final String ERROR_PARSING = "error_parsing";
    public static final String INDEXING = "indexing";
    public static final String INDEXED = "indexed";
    public static final String ERROR_INDEXING = "error_indexing";

    /**
     * 计入"已索引"统计的状态集合（对应参考实现 INDEXED_STATS_STATUSES）。
     * 含历史值 {@code done}：旧数据里用它表示完成，统计时不应漏算。
     */
    public static final Set<String> INDEXED_STATS_STATUSES = Set.of(INDEXED, "done");

    /** 待解析状态（对应 PENDING_PARSE_STATUSES） */
    public static final List<String> PENDING_PARSE_STATUSES = List.of(UPLOADED);

    /** 待索引状态（对应 PENDING_INDEX_STATUSES）：索引失败允许重试，故包含 error_indexing */
    public static final List<String> PENDING_INDEX_STATUSES = List.of(PARSED, ERROR_INDEXING);

    /**
     * 已弃用：本系统扩展状态（参考实现无此概念）。
     * 保留是因为"弃用但不删除"是本产品的既有能力（弃用后不参与检索、仍可恢复）。
     */
    public static final String DEPRECATED = "deprecated";

    private FileStatus() {
    }

    /** 是否已索引（含历史 done），用于统计与"是否需补索引"判断 */
    public static boolean isIndexed(String status) {
        return status != null && INDEXED_STATS_STATUSES.contains(status);
    }

    public static boolean isPendingParse(String status) {
        return status != null && PENDING_PARSE_STATUSES.contains(status);
    }

    public static boolean isPendingIndex(String status) {
        return status != null && PENDING_INDEX_STATUSES.contains(status);
    }

    /**
     * 数字状态 → 字符串状态（兼容历史数据的迁移映射）：
     * 0 生效 → indexed；1 弃用 → deprecated；2 解析中 → parsing；3 失败 → error_parsing。
     */
    public static String fromLegacy(Integer legacy) {
        if (legacy == null) return UPLOADED;
        return switch (legacy) {
            case 0 -> INDEXED;
            case 1 -> DEPRECATED;
            case 2 -> PARSING;
            case 3 -> ERROR_PARSING;
            default -> UPLOADED;
        };
    }

    /** 状态文案（接口与界面展示用） */
    public static String text(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case UPLOADED -> "待解析";
            case PARSING -> "解析中";
            case PARSED -> "待索引";
            case ERROR_PARSING -> "解析失败";
            case INDEXING -> "索引中";
            case INDEXED -> "已索引";
            case ERROR_INDEXING -> "索引失败";
            case DEPRECATED -> "已弃用";
            default -> status;
        };
    }
}
