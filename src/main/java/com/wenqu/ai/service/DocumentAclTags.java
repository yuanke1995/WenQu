package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.model.AiDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索期<b>库级</b>可见性下推（<b>刻意只管库门，文档级 ACL 不在此</b>）。
 *
 * <p><b>为什么只保留库门（2026-10-05 架构决策，此前是全量 ACL 标签下推）</b>：
 * 早绑定方案把文档 ACL 烘焙进向量 metadata（aclGlobal/aclDept/aclUser），检索期下推过滤。
 * 实际运行后确认它<b>不成立</b>，三条硬理由：
 * <ol>
 *   <li><b>没省到成本</b>：为兜底仍保留了「全表扫 {@code c_ai_document} 逐行解析 share_config」
 *       的事后过滤（安全底线不能只压在索引上）。于是早绑定付出了全部写入侧复杂度，
 *       却一点 DB 成本都没省——而晚绑定查的是 {@code WHERE id IN (命中块)}，走主键索引、亚毫秒级。</li>
 *   <li><b>制造两处真相</b>：同一份 share_config 被写入侧（{@code bake}）与检索侧
 *       （事后过滤的 {@code canReadDocFollowKb}）各解析一次。本次踩的「OR 门里没有标签=永不可见」
 *       正是两处口径不一致的直接后果——晚绑定下这类 bug 从根上不存在。</li>
 *   <li><b>变更传播缺失即越权</b>：改共享范围不重写向量标签，索引里的权限快照即陈旧；
 *       早绑定要成立必须补齐「权限变更 → 重写索引」链路，而它涉及 6 处写入点 + QA/子块向量，
 *       极易做漏。晚绑定天然免疫——改权限下次检索即生效，无需任何同步。</li>
 * </ol>
 * 结论：<b>文档级 ACL 回到 {@link ResourceVisibilityService} 单一判定（晚绑定）</b>，
 * 检索期只对命中的块批量查文档 ACL；本类只留下库门，因为它满足下推的三个前提——
 * 集合小（几十个库）、每次实时计算（<b>无陈旧问题</b>）、且真能减少整库召回。
 *
 * <p><b>与 {@code docId} TAG 字段的关系</b>：向量索引里保留 {@code docId}/{@code kbId} 两个 TAG
 * 字段（schema 已建，撤销代价大于收益），但<b>不再写入 ACL 标签</b>。{@code kbId} 用于库门下推，
 * {@code docId} 作为可观测字段便于排查「这批命中来自哪些文档」。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DocumentAclTags {

    /** 文档表（供 {@link #putKbId} 查文档所属库） */
    private final AiDocumentMapper documentMapper;

    public DocumentAclTags(AiDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /**
     * 往向量 metadata 写入库门字段 {@code kbId}（检索期 {@code kbId IN (可见库)} 下推用）。
     *
     * <p><b>只写库门，不写文档级 ACL</b>（2026-10-05 晚绑定改造）：文档级 ACL 改为检索期
     * 按命中块实时查 MySQL 判定（{@code HybridRetrievalService.loadVisibleDocIdsOfHits}），
     * 权限真相只留 {@code ResourceVisibilityService} 一处，改共享范围<b>无需重写向量</b>。
     *
     * <p>写向量<b>不按文档共享范围收紧</b>：那是检索期的活。写入侧多写字段等于把权限
     * 复制一份到索引——一旦索引侧与DB侧不一致，就是「权限静默失效」的来源。
     *
     * @param metadata 目标 metadata（原地修改）
     * @param docId    文档 id（查其所属库；查不到则不写，该块会被库门挡掉→ 漏召回，安全方向）
     */
    public void putKbId(Map<String, Object> metadata, String docId) {
        if (metadata == null || docId == null || docId.isBlank()) return;
        try {
            AiDocument doc = documentMapper.selectById(docId);
            if (doc != null && doc.getKbId() != null && !doc.getKbId().isBlank()) {
                metadata.put(FIELD_KB_ID, doc.getKbId());
            }
        } catch (Exception e) {
            // 缺库门字段只会让该块被库门挡掉（漏召回，安全方向），不阻断写入
            log.warn("[KB-VEC] 查询文档 {} 所属库失败，向量不写 kbId（该块可能检索不到）: {}",
                    docId, e.getMessage());
        }
    }

    /** 库门字段：文档所属知识库（检索期 {@code kbId IN (可见库)} 下推） */
    public static final String FIELD_KB_ID = "kbId";
    /** 文档 id 字段：可观测用，便于排查命中来源；<b>不参与权限判定</b> */
    public static final String FIELD_DOC_ID = "docId";
    /** 多值标签分隔符（TAG 字段不设 SEPARATOR 时整个值当字面量，多值无法按值命中） */
    public static final String MULTI_VALUE_SEPARATOR = ",";

    /**
     * 向量索引 schema 声明的 metadata 字段。
     * <p><b>刻意不再包含 aclGlobal/aclDept/aclUser</b>：文档级 ACL 已回到晚绑定（见类注释）。
     * 字段名不可改——已在库的索引 schema 按这些名字建过，改名等于全部索引失效。
     */
    public static final List<org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField> METADATA_FIELDS =
            List.of(
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_DOC_ID),
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_KB_ID));

    /**
     * 编译库门过滤表达式（检索期下推）。
     *
     * <p>结构：{@code kbId IN (可见库...)}。与文档级 ACL 不同，<b>这里没有「继承/排除」的语义</b>——
     * 文档 ACL 必须在应用层逐条判（{@link ResourceVisibilityService}），索引层无法表达
     * 「未配置共享=跟随库」这种依赖 DB 状态的规则。
     *
     * <p><b>可见库为空集时构造永假表达式而非省略过滤</b>：省略等于对全库放开，是最危险的写法。
     * 空集是合法状态（用户一个库都读不了，如全部私有且非归属人），此时就该检索不到任何东西。
     *
     * @param visibleKbIds 当前用户可读的知识库集合（null=查询失败，调用方退回事后过滤）
     * @return filter 表达式；{@code visibleKbIds == null} 时返回 null（表示「不下推」而非「不限制」）
     */
    public String buildKbGateExpression(Set<String> visibleKbIds) {
        if (visibleKbIds == null) return null;
        if (visibleKbIds.isEmpty()) {
            return FIELD_KB_ID + " IN ['__no_visible_kb__']";
        }
        List<String> quoted = visibleKbIds.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(DocumentAclTags::quoteTagValue)
                .toList();
        // 全部为空白值（脏数据）时同样锁死，不放行全库
        if (quoted.isEmpty()) {
            return FIELD_KB_ID + " IN ['__no_visible_kb__']";
        }
        return FIELD_KB_ID + " IN [" + String.join(", ", quoted) + "]";
    }

    /** FilterExpression 文本解析器里字符串字面量用单引号，内部单引号需转义 */
    private static String quoteTagValue(String v) {
        return "'" + escapeTagValue(v) + "'";
    }

    /** RediSearch TAG 查询子句的转义：{@code \ $ | { } ( ) [ ] - '} 有特殊含义 */
    private static String escapeTagValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\', '$', '|', '{', '}', '(', ')', '[', ']', '-', '\'' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}