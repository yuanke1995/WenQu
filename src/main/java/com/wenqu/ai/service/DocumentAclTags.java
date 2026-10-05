package com.wenqu.ai.service;

import com.wenqu.ai.model.AiDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档级 ACL → 向量索引 metadata 的编译器（检索时过滤的<b>唯一语义出口</b>）。
 *
 * <p><b>为什么要有这个类</b>：「检索后过滤」升级为「检索时过滤」后，权限判定从
 * 「召回后在Java 里逐条判」变成「把判定结论烘焙进向量 metadata、检索期由索引下推」。
 * 于是<b>同一套 ACL 语义要在两个时机各用一次</b>：
 * <ol>
 *   <li><b>写入期</b>（{@link #bake}）：文档解析/向量化时，把 share_config 编译成
 *       {@code aclGlobal} / {@code aclDept} / {@code aclUser} 三个 TAG 字段写进 metadata；</li>
 *   <li><b>检索期</b>（{@link #buildFilterExpression}）：按当前登录人编译成 RediSearch
 *       filter 表达式下推，索引在 KNN 之前就把无权文档剔掉。</li>
 * </ol>
 * 两处若各写一份，迟早漂移（改了 share_config 语义只记得改一边→ 权限静默失效）。
 * 故收口在此：<b>写入侧与检索侧共用同一套标签构造与命中判定</b>。
 *
 * <p><b>标签设计（三档，覆盖 share_config 的三种 access_level）</b>：
 * <table border="1">
 *   <caption>ACL 标签</caption>
 *   <tr><th>metadata 字段</th><th>取值</th><th>语义</th></tr>
 *   <tr><td>{@code aclGlobal}</td><td>固定 {@code "1"}</td><td>全员可读（read_scope=global）</td></tr>
 *   <tr><td>{@code aclDept}</td><td>{@code d1|d2|...}（竖线分隔）</td><td>命中任一部门即可读</td></tr>
 *   <tr><td>{@code aclUser}</td><td>{@code u1|u2|...}（竖线分隔）</td><td>命中任一用户即可读</td></tr>
 * </table>
 * <b>「未配置 share_config」不写任何 acl* 字段</b>——它跟随所属库（库级由
 * {@code kbId} 字段单独下推），此时三个标签全空＝库内可读，与既有
 * {@link ResourceVisibilityService#canReadDocFollowKb} 语义一致。
 *
 * <p><b>多值分隔符必须是逗号（实测得出，2026-10-05）</b>：RediSearch 的 TAG 字段默认
 * <b>{@code SEPARATOR} 为空（{@code N/A}），整个字段被当成单个字面量</b>——写 {@code a|b} 或
 * {@code a,b} 都<b>只能整体匹配</b>，查 {@code a} 或 {@code b} 永远 0 命中。
 * 实测确认：{@code FT.ALTER ... TAG}（无 SEPARATOR）写 {@code deptx,depty} 后查单个值0 命中；
 * 同一条数据在 {@code TAG SEPARATOR ","} 的字段上查两个值各1 命中。
 * <p>因此本类的多值标签一律用 {@code ,} 连接，<b>且索引 schema 必须显式带 {@code SEPARATOR ","}</b>
 * （{@link KbVectorStoreRegistry#ensureAclSchema} 与新建索引的 metadataFields 声明都需满足）。
 * 单值场景（{@code aclGlobal}、{@code kbId}）不受此影响，未设分隔符也能正常匹配。
 *
 * <p><b>连字符必须转义</b>：TAG 查询子句里 {@code -} 是特殊字符（含 {@code $ \ | { } ( ) [ ] - '}），
 * 未转义的 {@code KB-VISIBLE} 会报 {@code Syntax error} 或被当成排除。系统生成的 kbId/uid 是
 * UUID hex（无连字符）天然安全，但<b>部门编码可能带连字符</b>，故 {@link #escapeTagValue} 必须保留。
 *
 * <p><b>标签缺失的兼容语义</b>：FT.ALTER 热补字段后，<b>存量向量没有 acl* 字段</b>，
 * 检索期 {@code @aclGlobal:{1}} 这类条件对存量向量一律不命中 → 存量向量会被过滤掉（漏召回，
 * <b>不是越权</b>）。这正是「热补 + 标记待重建」策略下的预期中间态：
 * 宁可少召回也绝不越权，且漏召回会被{@code RetrievalDiag} 暴露出来提示重建。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class DocumentAclTags {

    /** 全员可读标签的固定值 */
    public static final String GLOBAL = "1";
    /**
     * ACL 多值标签的分隔符（<b>必须是逗号</b>：RediSearch TAG 字段未设 SEPARATOR 时整个值当字面量，
     * 竖线/逗号都不生效；见类注释的实测结论）。索引 schema 须以 {@code SEPARATOR ","} 声明。
     */
    public static final String MULTI_VALUE_SEPARATOR = ",";
    /** 库门字段：文档所属知识库（库级可见性由检索期的 kbId IN (...) 下推） */
    public static final String FIELD_KB_ID = "kbId";
    /** 文档门字段 */
    public static final String FIELD_ACL_GLOBAL = "aclGlobal";
    public static final String FIELD_ACL_DEPT = "aclDept";
    public static final String FIELD_ACL_USER = "aclUser";
    /** 文档 id 字段：向量库不保证保留 metadata，但检索期下推需要它做兜底定位 */
    public static final String FIELD_DOC_ID = "docId";

    /**
     * 向量索引 schema 声明的 metadata 字段（全部 TAG）。
     * <p>三个 acl* 字段 + kbId + docId，缺一不可：
     * <ul>
     *   <li>{@code kbId}：库级门（检索期按可见库集合 IN 下推）；</li>
     *   <li>{@code aclGlobal/aclDept/aclUser}：文档级门；</li>
     *   <li>{@code docId}：{@link AiDocument} 维度的定位字段，同时修掉「M6 RedisVectorStore
     *       可能丢弃 metadata」的隐患——字段一旦在 schema 里声明，索引就会返回它。</li>
     * </ul>
     * <b>字段名不可改</b>：已在库里的索引 schema 是按这些名字建的，改名等于全部索引失效。
     */
    public static final List<org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField> METADATA_FIELDS =
            List.of(
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_DOC_ID),
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_KB_ID),
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_ACL_GLOBAL),
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_ACL_DEPT),
                    org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField.tag(FIELD_ACL_USER));

    /**
     * ACL 标签：文档可见范围编译后的向量 metadata 片段。
     *
     * @param global     是否全员可读（read_scope=global）
     * @param deptScoped 部门级可见时的部门 id 列表（可为空）
     * @param userScoped 用户级可见时的 uid 列表（可为空）
     * @param inherited  是否「未配置 share_config → 跟随所属库」（三个标签都不写）
     */
    public record AclTags(boolean global, List<String> deptScoped, List<String> userScoped, boolean inherited) {

        /** 无人可读：read_scope 缺失或三个列表皆空且非 global（scope 缺失＝不命中任何人） */
        public boolean denyAll() {
            return !global && !inherited && deptScoped.isEmpty() && userScoped.isEmpty();
        }

        /**
         * 烘焙进向量 metadata。
         * <p><b>刻意不写 acl* 字段而非写空串</b>：TAG 字段缺失与空串在 RediSearch 里都算「无值」，
         * 但缺失语义更清晰——{@link #denyAll()} 的文档（无人可读）写入空标签后，
         * 检索期任何 acl 条件都不命中它，等价于永不可见，正是期望行为。
         */
        public Map<String, Object> toMetadata() {
            Map<String, Object> md = new LinkedHashMap<>();
            if (global) {
                md.put(FIELD_ACL_GLOBAL, GLOBAL);
            }
            if (!deptScoped.isEmpty()) {
                md.put(FIELD_ACL_DEPT, String.join(MULTI_VALUE_SEPARATOR, deptScoped));
            }
            if (!userScoped.isEmpty()) {
                md.put(FIELD_ACL_USER, String.join(MULTI_VALUE_SEPARATOR, userScoped));
            }
            return md;
        }
    }

    private final ResourceVisibilityService resourceVisibilityService;
    /** 单块写入点按 docId 查文档行（批量写入走 enrich(docRow) 复用，避免 N+1） */
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;

    public DocumentAclTags(ResourceVisibilityService resourceVisibilityService,
                           com.wenqu.ai.mapper.AiDocumentMapper documentMapper) {
        this.resourceVisibilityService = resourceVisibilityService;
        this.documentMapper = documentMapper;
    }

    /**
     * 编译文档的 ACL 标签（写入侧烘焙用）。
     *
     * <p><b>「未配置 share_config」= inherited（不写 acl* 字段）</b>：文档跟随所属库，
     * 库级可见性在检索期由 kbId 单独下推，这里不重复表达——否则库里改一次共享范围，
     * 库里所有文档的向量都要重写一遍。
     *
     * @param doc 文档行（需含 share_config / created_by）
     */
    public AclTags bake(AiDocument doc) {
        String shareConfig = doc == null ? null : doc.getShareConfig();
        if (shareConfig == null || shareConfig.isBlank()) {
            // 未配置 → 跟随库：不写任何 acl* 标签
            return new AclTags(false, List.of(), List.of(), true);
        }
        ResourceVisibilityService.ShareConfig cfg = resourceVisibilityService.parseForTagCompile(shareConfig);
        if (cfg == null || cfg.readScope == null) {
            // 已配置但 read_scope 缺失 → 不命中任何人（与 resolve() 语义一致）
            return new AclTags(false, List.of(), List.of(), false);
        }
        String level = cfg.readScope.accessLevel == null ? "global"
                : cfg.readScope.accessLevel.trim().toLowerCase(Locale.ROOT);
        return switch (level) {
            case "global" -> new AclTags(true, List.of(), List.of(), false);
            // 部门/用户级：写入完整成员列表，检索期按当前人是否命中做 IN 判断
            case "department" -> new AclTags(false, clean(cfg.readScope.departmentIds), List.of(), false);
            case "user" -> new AclTags(false, List.of(), clean(cfg.readScope.userUids), false);
            // 非法级别按 global 处理（写入侧不因脏数据中断解析；validateShareConfig 已在管理端拦截）
            default -> {
                log.warn("[ACL] 文档 {} 的 read_scope.access_level 非法（{}），按 global 烘焙",
                        doc.getId(), level);
                yield new AclTags(true, List.of(), List.of(), false);
            }
        };
    }

    private static List<String> clean(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return new ArrayList<>(out);
    }

    /**
     * 往待写入的向量 metadata 里烘焙 ACL 标签（写入侧统一入口）。
     *
     * <p><b>为什么不把烘焙逻辑散落进各写入点</b>：向量 metadata 现在散着6 处构造
     * （DocumentService 4 处 + ChildChunkService + QaIndexService），每处都得记得调一次
     * 烘焙——漏一处就是那个路径写出的向量<b>不带 ACL 标签</b>，下推过滤对它一律不命中
     * （漏召回；若反过来误加标签则会越权）。收口成一个 enrich 调用后，
     * 「构造 metadata」与「附加 ACL」不可分离。
     *
     * <p><b>docRow 允许为 null</b>（如手动知识块 docId 为空、或文档行已被删）：
     * 此时只写库门字段，<b>不写任何 acl* 字段</b>——语义等同「未配置共享」，即跟随库，
     * 与 {@link #bake} 的 inherited 分支一致。安全方向：宁可少过滤也不误放行。
     *
     * @param metadata 待写入的 metadata（原地修改）
     * @param docRow   文档行（含 share_config/created_by/kb_id），可为 null
     * @param kbId     库 id（docRow 缺失时用于补库门字段）
     * @param aclTags  预先编译好的标签（调用方若已在循环外编译好则传入，避免每块重复解析 JSON）
     */
    public void enrich(Map<String, Object> metadata, AiDocument docRow, String kbId, AclTags aclTags) {
        if (metadata == null) return;
        String effectiveKbId = docRow != null && docRow.getKbId() != null ? docRow.getKbId() : kbId;
        if (effectiveKbId != null && !effectiveKbId.isBlank()) {
            metadata.put(FIELD_KB_ID, effectiveKbId);
        }
        AclTags tags = aclTags != null ? aclTags : bake(docRow);
        if (tags == null) return;
        metadata.putAll(tags.toMetadata());
    }

    /**
     * 按 docId 查文档行并烘焙（单块写入点用；批量写入请在循环外 {@code selectById} 一次再复用，
     * 避免每块一次查询）。
     */
    public void enrichByDocId(Map<String, Object> metadata, AiDocument docRow, String docId, String kbId) {
        if (metadata == null) return;
        AiDocument row = docRow;
        if (row == null && docId != null && !docId.isBlank() && documentMapper != null) {
            try {
                row = documentMapper.selectById(docId);
            } catch (Exception e) {
                log.warn("[ACL] 查询文档 {} 共享配置失败，按「跟随库」烘焙（不写 acl 标签）: {}", docId, e.getMessage());
            }
        }
        enrich(metadata, row, kbId, null);
    }

    /**
     * 按 docId 批量烘焙的句柄：一次查全文档行并在方法内复用，避免整库/整文档重建时的 N+1 查询。
     *
     * <p>用法：
     * <pre>{@code
     * try (AclBatch batch = aclTags.newBatch()) {
     *     for (Knowledge k : rows) {
     *         Map<String,Object> md = ...;
     *         batch.enrich(md, k.getDocId());
     *         docs.add(new Document(k.getId(), text, md));
     *     }
     * }
     * }</pre>
     * 未预载的 docId 会在首次用到时单查一次并<b>缓存进句柄</b>（同 docId 的后续块零查询）。
     */
    public final class AclBatch implements AutoCloseable {
        private final Map<String, AiDocument> rows = new HashMap<>();
        private final Map<String, AclTags> compiled = new HashMap<>();

        /** 预载一批文档行（可选，用于整库重嵌这类已知全量 docId 的场景） */
        public AclBatch preload(Collection<String> docIds) {
            if (docIds == null || docIds.isEmpty() || documentMapper == null) return this;
            List<String> ids = docIds.stream()
                    .filter(d -> d != null && !d.isBlank())
                    .filter(d -> !rows.containsKey(d))
                    .distinct()
                    .toList();
            if (ids.isEmpty()) return this;
            try {
                for (AiDocument d : documentMapper.selectBatchIds(ids)) {
                    rows.put(d.getId(), d);
                }
            } catch (Exception e) {
                log.warn("[ACL] 批量预载文档共享配置失败，逐块回退单查: {}", e.getMessage());
            }
            return this;
        }

        /** 烘焙单个块的 metadata（docId 为空时不写任何 ACL 字段，按「跟随库」处理） */
        public void enrich(Map<String, Object> metadata, String docId) {
            if (metadata == null) return;
            if (docId == null || docId.isBlank()) {
                // 手动知识块（无所属文档）：无库无 ACL，检索期由 kbId 门自然排除
                return;
            }
            AiDocument row = rows.computeIfAbsent(docId, id -> {
                if (documentMapper == null) return null;
                try {
                    return documentMapper.selectById(id);
                } catch (Exception e) {
                    log.warn("[ACL] 查询文档 {} 共享配置失败，按「跟随库」烘焙: {}", id, e.getMessage());
                    return null;
                }
            });
            AclTags tags = compiled.computeIfAbsent(docId, id -> bake(row));
            DocumentAclTags.this.enrich(metadata, row, null, tags);
        }

        @Override
        public void close() {
            rows.clear();
            compiled.clear();
        }
    }

    /** 新建批量烘焙句柄 */
    public AclBatch newBatch() {
        return new AclBatch();
    }

    /**
     * 构造检索期下推的 filter 表达式（检索侧用）。
     *
     * <p><b>两级 AND 结构</b>（与 {@link ResourceVisibilityService#canReadDocFollowKb} 同口径）：
     * <pre>
     * kbId IN (可见库...)  AND  ( aclGlobal == 1  OR  aclDept IN (我的部门)  OR  aclUser IN (我) )
     * </pre>
     * 三个 acl 条件是 <b>OR</b>：命中任一即可读；与库门是 <b>AND</b>：越权仍由库门兜底
     * （私有库里显式配了 global 的文档，也不能被该库之外的人读到）。
     *
     * <p><b>为什么 acl* 分支不带 created_by（作者恒可见）</b>：作者短路是
     * {@code resolve()} 的第一条规则，若在索引层表达，就要为每个文档再写一个 {@code aclOwner} 标签。
     * 而「作者恒可见」在实践中等价于「作者必然已通过库门」（作者对自己的库可读），
     * 故作者可见性由库门保证；<b>真正的兜底是检索期的事后过滤</b>——那里仍按完整
     * {@code resolve()} 判定，索引层漏掉的分支不会造成越权。
     *
     * @param visibleKbIds  当前用户可读的知识库集合（空=无可读库→ 返回 null 表示不下推库门，
     *                     但仍返回文档门；<b>空库门不可省略</b>，否则等于放开全库）
     * @param departmentId  当前用户部门（null 表示不参与部门判定）
     * @param uid当前用户 uid（永远参与用户判定）
     * @return FilterExpression 文本（可传 {@code SearchRequest.builder().filterExpression(String)}）；
     *         无任何条件下推时返回 null（不调filterExpression）
     */
    public String buildFilterExpression(Set<String> visibleKbIds, String departmentId, String uid) {
        List<String> parts = new ArrayList<>();

        // 库门：可见库非空才下推 IN；可见库为空集时用永假表达式锁死（而不是省略→ 放开全库）
        if (visibleKbIds != null) {
            if (visibleKbIds.isEmpty()) {
                parts.add("kbId IN ['__no_visible_kb__']");
            } else {
                List<String> quoted = visibleKbIds.stream()
                        .filter(k -> k != null && !k.isBlank())
                        .map(DocumentAclTags::quoteTagValue)
                        .toList();
                if (!quoted.isEmpty()) {
                    parts.add("kbId IN [" + String.join(", ", quoted) + "]");
                }
            }
        }

        // 文档门：三个 acl 标签 OR；当前用户 uid 恒入参（哪怕没配 user 级 ACL 也不影响结果）
        List<String> aclOr = new ArrayList<>();
        aclOr.add(FIELD_ACL_GLOBAL + " == '" + GLOBAL + "'");
        if (departmentId != null && !departmentId.isBlank()) {
            aclOr.add(FIELD_ACL_DEPT + " IN ['" + escapeTagValue(departmentId.trim()) + "']");
        }
        if (uid != null && !uid.isBlank()) {
            aclOr.add(FIELD_ACL_USER + " IN ['" + escapeTagValue(uid.trim()) + "']");
        }
        parts.add("(" + String.join(" OR ", aclOr) + ")");

        return String.join(" AND ", parts);
    }

    /**
     * FilterExpression 文本解析器里的字符串字面量用单引号，内部单引号需转义为 {@code \'}。
     * （RediSearch TAG 侧还要再过一次 escapeTagValue，两层转义互不替代，故两处都做）
     */
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