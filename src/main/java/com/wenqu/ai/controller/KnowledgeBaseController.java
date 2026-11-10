package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.AuthService;
import com.wenqu.ai.service.DocumentService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.ResourceVisibilityService;
import com.wenqu.ai.service.RoleService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 知识库接口：**用户可自建自管**。
 * <p>
 * 数据隔离口径（{@link ResourceVisibilityService}）——谁建归谁：
 * <ul>
 *   <li>创建者 → 可管理自己的库（新建/编辑/删除/移文档/传文档）；非创建者不可见不可管；</li>
 *   <li>个人默认知识库（问渠，每人一张、系统懒创建）是归属人的私有库。</li>
 * </ul>
 * 官方内置库（问渠使用手册，builtin=1）例外：管理页（列表/详情/文档/知识块/回收站）仅管理员级可见——
 * 普通用户的消费入口是帮助中心（/manual 独立只读端点）与对话检索（消费侧登录即可读），不受影响。
 * 管理员照旧全量可见可管。检索链路的可见性过滤由既有检索层负责，与本接口口径同源。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/kb")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "文档的容器；检索按库隔离，检索参数随库")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final DocumentService documentService;
    private final ResourceVisibilityService visibility;
    /** 管理员级判定（含自定义 admin_flag=1 角色）：官方内置库的管理放行走它 */
    private final RoleService roleService;
    /** 读取知识库参数的当前全局值（/param-defaults 模板预填用） */
    private final com.wenqu.ai.service.ConfigService configService;

    private final com.wenqu.ai.service.KbTransferService kbTransferService;
    /** 知识库洞察/试检索/引用反馈/归档 */
    private final com.wenqu.ai.service.KbInsightsService kbInsightsService;
    /** 参数问号文案（/param-tips；与系统设置页同一份定义源，防两处解释漂移） */
    private final com.wenqu.ai.config.ConfigSchemaService configSchemaService;
    /** 个人设置值（/param-defaults 对 personal 键取「本人生效值」预填，与检索实际生效口径一致） */
    private final com.wenqu.ai.service.UserConfigService userConfigService;

    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 校验"能管理该库"（谁建归谁：仅创建者），否则抛业务异常（前端显示具体原因，不触发全局 403 提示） */
    private void requireManage(KnowledgeBase kb) {
        if (kb == null) throw new BizException("仅可管理自己创建的知识库");
        // 官方内置库（builtin=1）：createdBy=system，谁建归谁判定下任何人都拿不到 MANAGE。
        // 但它同样需要维护检索/解析参数（如绑定重排模型——不绑则引用门退化到融合分门
        // （minFusionScore，默认 0.5），词面重叠的弱相关块更容易进引用面板），故对管理员级单独放行；
        // 能改哪些字段由服务层白名单兜住（KnowledgeBaseService.update），内容/删除仍不可动。
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1) {
            if (!roleService.isAdminAny(com.wenqu.ai.util.RequestUser.roles())) {
                throw new BizException("官方内置知识库仅管理员可维护检索/解析设置与向量模型（文档内容随版本自动同步）");
            }
            return;
        }
        if (!visibility.canManage(principal(), kb.getCreatedBy())) {
            throw new BizException("仅可管理自己创建的知识库");
        }
    }

    private KnowledgeBase mustGet(String id) {
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) throw new BizException("知识库不存在");
        return kb;
    }

    /**
     * 知识库参数默认值（**普通用户可读**）：新建知识库时以当前全局值为模板预填表单。
     * <p>为什么不复用 {@code GET /config}：那是管理端点（普通用户 403），而知识库自建已对普通用户开放，
     * 普通用户却看不到"当前全局值是多少"——正是"设置页写的是新建库预填、实际只有管理员能预填"的根因。
     * 这里只回库级可覆盖的检索参数 + 解析参数（不含 gateway 地址与任何密钥）。
     */
    @Operation(summary = "知识库参数默认值", description = "库级检索/解析参数的当前生效默认值（新建库模板预填 + 表单占位符展示）；"
            + "重排模型返回「本人个人值（未设即空，personalOnly 无全局层）」；rerank.enabled 供智能体表单展示"
            + "「跟随全局」的当前状态；rerank.platformRef 为平台兜底层，原样返回供前端点名默认模型")
    @Permission("kb:read")
    @GetMapping("/param-defaults")
    public ResultJson paramDefaults() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : DocumentService.PARSE_PARAM_KEYS) out.put(k, effectiveDefault(k));
        // 库级唯一真源：阈值/两路权重/重排模型（召回池那一层）
        for (String k : com.wenqu.ai.service.KbParamCodec.KEYS) out.put(k, effectiveDefault(k));
        // 重排开关归智能体（场景性），但智能体表单要展示「跟随全局」的当前状态，故一并下发（非敏感布尔值）
        out.put("rerank.enabled", configService.get("rerank.enabled"));
        // 平台默认重排模型：库级覆盖与个人设置都没绑重排模型时的兜底层（ModelRegistryService.rerankRoute
        // 值链的第三跳）。不在库级可覆盖白名单，单独补发——前端「重排模型」槽位空值时据此点名
        // "默认用的哪个模型"，否则用户无从得知重排到底会不会跑、用谁的模型。
        // 仅模型引用串，不含网关地址与密钥，同 rerank.model 的外发口径。
        out.put("rerank.platformRef", configService.get("rerank.platformRef"));
        return ResultJson.ok(out);
    }

    /**
     * 参数对请求者的「生效默认值」：personal 键取本人个人值（未设回落全局）；
     * personalOnly 键（重排模型归库级绑定，无个人设置入口）同样取本人个人值——
     * 未设即空（personalOnly 无全局层，{@link ConfigService#get} 对其恒返回空，回落分支只为类型统一）。
     * 个人值与检索链路的个人层同源，预填/展示与其一致：
     * 预填全局旧值会把个人默认"固化"成库级覆盖，反而覆盖掉用户自己的设置。
     */
    private String effectiveDefault(String key) {
        if (configSchemaService.isPersonal(key) || configSchemaService.isPersonalOnly(key)) {
            String pv = userConfigService.personalValue(RequestUser.uid(), key);
            if (pv != null && !pv.isBlank()) return pv;
        }
        String v = configService.get(key);
        if (v == null || v.isBlank()) {
            // KB-override keys have no platform setting; fall back to constants
            return switch (key) {
                case "chunk.maxChunks" -> String.valueOf(ConfigDefaults.CHUNK_MAX_CHUNKS);
                case "chunk.maxImages" -> String.valueOf(ConfigDefaults.CHUNK_MAX_IMAGES);
                case "parse.childSize" -> String.valueOf(ConfigDefaults.PARSE_CHILD_SIZE);
                case "parse.ocrMinText" -> String.valueOf(ConfigDefaults.PARSE_OCR_MIN_TEXT);
                case "parse.ocrDpi" -> String.valueOf(ConfigDefaults.PARSE_OCR_DPI);
                default -> v;
            };
        }
        return v;
    }

    /**
     * 知识库参数问号文案（**普通用户可读**）：键为 config-schema.json 的 backendKey，
     * 与系统设置页字段说明同一份定义源（改一处两处同步）。
     * 说明：普通用户进不了管理端设置页（/config/schema 403），但知识库弹窗的「高级参数」
     * 需要给小白看人话解释（这是什么 / 什么时候才需要调），故经本端点下发。
     */
    @Operation(summary = "知识库参数说明", description = "检索/解析参数的人话解释（弹窗问号 tooltip 数据源；仅文案，不含任何配置值）")
    @Permission("kb:read")
    @GetMapping("/param-tips")
    public ResultJson paramTips() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(DocumentService.PARSE_PARAM_KEYS);
        // 库级检索参数（阈值/两路权重/重排模型）：弹窗只展示这几项的问号文案
        keys.addAll(com.wenqu.ai.service.KbParamCodec.KEYS);
        Map<String, String> out = new java.util.LinkedHashMap<>(configSchemaService.tipsByKey(keys));
        // 平台层为固定常量的解析键没有 schema 字段定义，tipsByKey 解析不到——按映射直接取文案名
        for (Map.Entry<String, String> e : DocumentService.PARSE_PARAM_TIPS.entrySet()) {
            if (out.containsKey(e.getKey())) continue;
            String text = configSchemaService.tipsOf(java.util.List.of(e.getValue())).get(e.getValue());
            if (text != null && !text.isBlank()) out.put(e.getKey(), text);
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "知识库列表", description = "含每个库的文档数量；个人默认库（问渠）排最前；只返回自己创建的（含个人默认库），谁建归谁")
    @Permission("kb:read")
    @GetMapping("/list")
    public ResultJson list() {
        // 进知识库页即确保本人已有个人默认库（问渠）：懒创建只挂在写路径的话，
        // 新用户在页面上看不到自己的问渠，也就无从绑定向量模型（首次触达即建档）
        String uid = RequestUser.uid();
        if (uid != null && !uid.isBlank()) kbService.defaultId(uid);
        List<Map<String, Object>> all = kbService.listWithCounts();
        var p = principal();
        all = all.stream().filter(m -> visibility.canRead(p, (String) m.get("createdBy"))).toList();
        // 官方内置库（问渠使用手册）：知识库管理页仅管理员可见——它是系统资源而非用户资产，
        // 普通用户在这里看到只会误以为可翻可改。消费入口不受影响：帮助中心（/manual 独立只读端点）
        // 与对话检索链路（read_scope=global）仍全员可读。
        if (!roleService.isAdminAny(RequestUser.roles())) {
            all = all.stream().filter(m -> !Integer.valueOf(1).equals(m.get("builtin"))).toList();
        }
        return ResultJson.ok(all);
    }

    @Operation(summary = "知识库详情", description = "仅自己创建的（含个人默认库）可查（不可见按不存在处理，不泄露存在性）")
    @Permission("kb:read")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable("id") String id) {
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) return ResultJson.error("知识库不存在");
        // 官方内置库：详情与列表同口径仅管理员（不可见按不存在处理，不泄露存在性）
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1
                && !roleService.isAdminAny(RequestUser.roles())) {
            return ResultJson.error("知识库不存在");
        }
        if (!visibility.canRead(principal(), kb.getCreatedBy())) {
            return ResultJson.error("知识库不存在");
        }
        return ResultJson.ok(kb);
    }

    @Operation(summary = "新建知识库", description = "body: name(必填)/description/icon(emoji 字符，省略=默认库图标；"
            + "wenqu 品牌标为个人默认库专属，新建不可用)/embeddingRef(必填,绑定向量模型)/queryParams/parseParams；"
            + "queryParams/parseParams 为 JSON 字符串，留空表示继承全局检索/解析设置；创建人=当前用户。"
            + "默认知识库（问渠）每人一个、系统自动创建，不经此接口")
    @Permission("kb:write")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        if (body.get("name") == null || String.valueOf(body.get("name")).isBlank()) {
            return ResultJson.error("知识库名称不能为空");
        }
        return ResultJson.ok(kbService.create(body, RequestUser.uid()));
    }

    @Operation(summary = "编辑知识库", description = "仅更新 body 中出现的字段；queryParams/parseParams 传 null/空串表示清空并恢复继承全局；"
            + "icon（wenqu 品牌标为默认库专属）与 name 仅默认库不可修改（默认库恒为 wenqu 品牌标，名称/图标改值报错）；"
            + "embeddingRef（绑定向量模型）必填，变更时自动按库重嵌入（异步，模型不可达则保持原绑定）；仅创建者可改")
    @Permission("kb:write")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        KnowledgeBase before = mustGet(id);
        requireManage(before);
        Object kb = kbService.update(id, body);
        // 向量模型绑定变更：异步按库重嵌（旧向量随旧索引清除，全部块按新模型写回）
        if (before != null) {
            String oldRef = before.getEmbeddingRef() == null ? "" : before.getEmbeddingRef();
            String newRef = ((KnowledgeBase) kb).getEmbeddingRef() == null ? "" : ((KnowledgeBase) kb).getEmbeddingRef();
            if (!oldRef.equals(newRef)) {
                List<String> docIds = new java.util.ArrayList<>(kbService.docIdsOf(java.util.List.of(id)));
                documentService.reembedKbAsync(id, docIds, oldRef, newRef);
            }
        }
        return ResultJson.ok(kb);
    }

    @Operation(summary = "删除知识库", description = "先级联彻底删除本库下全部文档（含回收站）与库级图谱，再逻辑删除知识库自身；默认库与官方内置库不可删；仅创建者可删")
    @Permission("kb:delete")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        requireManage(mustGet(id));
        KnowledgeBase kb = mustGet(id);
        if (kb.getIsDefault() != null && kb.getIsDefault() == 1) {
            return ResultJson.error("默认知识库不可删除");
        }
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1) {
            return ResultJson.error("官方内置知识库不可删除（内容随版本自动同步）");
        }
        // 先级联彻底删除本库全部文档与库级图谱，再删库本体：
        // 避免「库删了、文档还在」导致文档失去归属、检索范围突变、向量成孤儿索引。
        documentService.purgeByKb(id);
        String reason = kbService.delete(id);
        if (reason != null) return ResultJson.error(reason);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "导出知识库", description = "打包为 zip（manifest.json + 各文档源文件；url 文档带源地址不带文件）。"
            + "只带源料不带产物——向量按导入库的绑定重嵌，知识块随解析重算。仅创建者可导")
    @Permission("kb:export")
    @GetMapping("/{id}/export")
    public void export(@PathVariable("id") String id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var kb = mustGet(id);
        requireManage(kb);
        String safeName = (kb.getName() == null ? "kb" : kb.getName()).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(safeName + "-" + id.substring(0, 8) + ".zip", java.nio.charset.StandardCharsets.UTF_8));
        String summary = kbTransferService.exportZip(id, response.getOutputStream());
        response.setHeader("X-Export-Summary", java.net.URLEncoder.encode(summary, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Operation(summary = "导入文档包到知识库", description = "上传导出 zip → 目标库逐文档走既有上传/URL 导入管线（解析/分块/向量化/索引全自动，"
            + "向量按目标库绑定重嵌）；同名 upsert，重复导入安全。仅创建者可导")
    @Permission("kb:import")
    @PostMapping("/{id}/import")
    public ResultJson importZip(@PathVariable("id") String id,
                                @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                @org.springframework.web.bind.annotation.RequestParam(value = "conflictStrategy", required = false) String conflictStrategy) {
        requireManage(mustGet(id));
        return ResultJson.ok(kbTransferService.importZip(file, id, conflictStrategy));
    }

    @Operation(summary = "预览导入文档包", description = "只解析 manifest 返回文档清单与同名冲突，不落库、不触发解析；"
            + "前端据此展示清单并让用户选择冲突策略（replace/skip/keepBoth）。仅创建者可导")
    @Permission("kb:import")
    @PostMapping("/{id}/import/preview")
    public ResultJson importPreview(@PathVariable("id") String id,
                                    @org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        requireManage(mustGet(id));
        return ResultJson.ok(kbTransferService.previewZip(file, id));
    }

    @Operation(summary = "知识库概览", description = "本人可见知识库 + 体检数据：文档状态分布、知识块、90 天命中、"
            + "最近使用、绑定智能体数、图谱规模；含已归档库（archived 标记）")
    @Permission("kb:read")
    @GetMapping("/overview")
    public ResultJson overview() {
        return ResultJson.ok(kbInsightsService.overview());
    }

    @Operation(summary = "库洞察", description = "单库近 N 天：健康分布、检索趋势、Top 命中文档、未命中问题、最近活动；只读聚合")
    @Permission("kb:read")
    @GetMapping("/{id}/insights")
    public ResultJson insights(@PathVariable("id") String id,
                               @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResultJson.ok(kbInsightsService.insights(id, days));
    }

    @Operation(summary = "未命中问题", description = "该库近 N 天检索为空/低分的问题 + 用户引用差评；可一键加入评测集")
    @Permission("kb:read")
    @GetMapping("/{id}/unmatched")
    public ResultJson unmatched(@PathVariable("id") String id,
                                @RequestParam(value = "days", defaultValue = "30") int days) {
        return ResultJson.ok(kbInsightsService.unmatched(id, days));
    }

    @Operation(summary = "绑定该库的智能体", description = "当前用户可见、且 knowledgeBaseIds 含该库的智能体；用于删除影响面与卡片引用数")
    @Permission("kb:read")
    @GetMapping("/{id}/agents")
    public ResultJson agents(@PathVariable("id") String id) {
        return ResultJson.ok(kbInsightsService.agents(id));
    }

    @Operation(summary = "试检索", description = "body: {query, topK(1-20,默认8), rerank(true/false)}；"
            + "只走检索/重排链路，不调用聊天模型；返回 topK 命中、融合/重排分、命中判定与检索诊断")
    @Permission("kb:read")
    @PostMapping("/{id}/probe")
    public ResultJson probe(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(kbInsightsService.probe(id, body));
    }

    @Operation(summary = "引用反馈", description = "body: {knowledgeId, question, useful(true/false)}；"
            + "差评回流到该库未命中问题，供评测集沉淀。kbId 由知识块反查，不接受前端指定")
    @Permission("kb:read")
    @PostMapping("/{id}/feedback")
    public ResultJson feedback(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(kbInsightsService.feedback(id, body));
    }

    @Operation(summary = "对话引用反馈", description = "body: {knowledgeId, docId, question, useful(true/false)}；"
            + "与 /{id}/feedback 同义，但 kbId 由文档归属反查，供对话引用条直接调用")
    @Permission("kb:read")
    @PostMapping("/feedback")
    public ResultJson feedbackByChunk(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(kbInsightsService.feedbackByChunk(body));
    }

    @Operation(summary = "记录试检索未命中", description = "body: {question}；只存问题不绑定知识块，"
            + "进入该库未命中列表，供评测集沉淀")
    @Permission("kb:read")
    @PostMapping("/{id}/miss")
    public ResultJson recordMiss(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(kbInsightsService.recordMiss(id, body));
    }

    @Operation(summary = "归档知识库", description = "保留文档与向量，从列表默认视图、智能体绑定与检索中摘除；可恢复。"
            + "默认库与官方内置库不可归档；仅创建者可操作")
    @Permission("kb:write")
    @PostMapping("/{id}/archive")
    public ResultJson archive(@PathVariable("id") String id) {
        requireManage(mustGet(id));
        return ResultJson.ok(kbInsightsService.archive(id, true));
    }

    @Operation(summary = "恢复归档知识库", description = "恢复后重新出现在默认列表；不会自动恢复智能体绑定。仅创建者可操作")
    @Permission("kb:write")
    @PostMapping("/{id}/unarchive")
    public ResultJson unarchive(@PathVariable("id") String id) {
        requireManage(mustGet(id));
        return ResultJson.ok(kbInsightsService.archive(id, false));
    }

    @Operation(summary = "移动文档到知识库", description = "body: {kbId}——传空表示移回自己的默认知识库（问渠）；"
            + "前后两库向量模型不同时自动异步迁移该文档向量；需要对源、目标两个库都有管理权；"
            + "官方内置知识库不接受外部文档移入（内容随版本自动同步）")
    @Permission("kb:write")
    @PutMapping("/doc/{docId}")
    public ResultJson moveDoc(@PathVariable("docId") String docId, @RequestBody Map<String, Object> body) {
        Object kbId = body.get("kbId");
        String toKbId = kbId == null ? null : String.valueOf(kbId);
        // 记录迁移前归属，移动后按前后两库的向量模型判断是否迁移向量
        com.wenqu.ai.model.AiDocument before = documentService.getDoc(docId);
        if (before == null) return ResultJson.error("文档不存在");
        String fromKbId = before.getKbId();
        // 文档本身也要有管理权（创建者），防止拿别人的文档当搬运工
        if (!visibility.canManage(principal(), before.getCreatedBy())) {
            throw new BizException("仅可管理自己上传的文档");
        }
        // 源库/目标库都要有管理权（kbId 为空=自己的默认库，同样按归属判定）
        requireManage(fromKbId == null ? null : kbService.get(fromKbId));
        String defId = kbService.defaultId(RequestUser.uid());
        KnowledgeBase toKb = toKbId == null ? kbService.get(defId) : kbService.get(toKbId);
        // 官方内置库（问渠使用手册）：内容随版本自动同步，不接受外部文档移入。
        // 必须单独拦：requireManage 对管理员放行内置库（那是给「改检索/解析配置」留的口），
        // 不拦的话管理员一挪，外部文档就混进随版本同步的库、期间可被检索到。
        if (toKb != null && toKb.getBuiltin() != null && toKb.getBuiltin() == 1) {
            throw new BizException("「" + toKb.getName() + "」为官方内置知识库，内容由系统随版本同步，不接受文档移入");
        }
        requireManage(toKb);
        boolean ok = kbService.moveDoc(docId, toKbId, RequestUser.uid());
        if (ok) {
            documentService.migrateDocAsync(docId, fromKbId, toKbId);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docId", docId);
        r.put("kbId", kbId);
        return ok ? ResultJson.ok(r) : ResultJson.error("文档不存在");
    }
}
