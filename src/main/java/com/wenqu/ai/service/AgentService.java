package com.wenqu.ai.service;

import com.wenqu.ai.util.RequestUser;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.AgentVersionMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentVersion;
import com.wenqu.ai.service.ResourceVisibilityService.Principal;
import com.wenqu.ai.service.ResourceVisibilityService.ResourceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体配置服务（P3：4.1 Agent 配置——模型/知识库/工具/提示词）。
 * <p>
 * 一个智能体把「模型 / 系统提示词 / 知识库范围 / 工具开关」打包成命名预设，对话页下拉切换；
 * 选中后该轮问答按智能体覆盖全局配置（未填维度继承全局）。工具开关三态：1=开 0=关 null=继承。
 * <p>
 * 内置「问渠」智能体是系统默认：<b>全局唯一</b>（启动时唯一性维护：多余降级、缺失播种），
 * 所有登录用户可读可用（{@link #readable} 豁免），仅管理员级可配置、不可删除。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 内置「问渠」智能体的固定名称（产品身份的一部分，不可修改） */
    public static final String BUILTIN_NAME = "问渠";

    /**
     * 内置「问渠」智能体的固定图标：问渠品牌标（不可修改）。
     * <p>与 {@link KnowledgeBaseService#ICON_BRAND} 同值但各表各义——一个是智能体头像、一个是知识库图标，
     * 各自单独声明常量，避免改动其中一处意外波及另一处。</p>
     */
    public static final String BUILTIN_ICON = "wenqu";

    /**
     * 内置「问渠」播种时写入的系统提示词。
     * <p>此前播种只写名称/描述/标记，{@code system_prompt} 留空 → 全新环境启动后，
     * 管理员在智能体配置页看到的是空框，且运行时回落 {@code AppProperties.systemPrompt}
     *（yml/env 兜底、不在配置页露出、不可编辑），等于产品自带的主助手没有任何人设。
     * 与 {@link AppProperties#systemPrompt} 的区别：这里是<b>可编辑的默认值</b>，
     * 管理员改后即以本行为准；留空才回落全局兜底。
     */
    public static final String BUILTIN_SYSTEM_PROMPT =
            "你是「问渠」智能体工作台的主助手，负责结合知识库回答用户问题。\n\n"
            + "回答原则：\n"
            + "1. 先查后答：涉及业务知识、文档内容的问题，优先使用知识检索工具，基于检索到的资料回答，并按要求标注引用来源。\n"
            + "2. 不编造：检索结果没有覆盖的，直接说明资料中未提及；不确定的明说不确定，绝不臆造细节。\n"
            + "3. 通用问题（常识、写作、代码等）可直接回答，不必强行检索。\n"
            + "4. 结构清晰：先给结论，再给依据；内容多时用短段落或列表，不用客套话开场。\n"
            + "5. 默认使用简体中文，专业术语首次出现时给出全称。";

    private final AgentMapper mapper;
    private final AgentVersionMapper versionMapper;
    private final ConfigService configService;
    private final ResourceVisibilityService resourceVisibilityService;
    private final com.wenqu.ai.mapper.WorkflowMapper workflowMapper;
    private final RoleService roleService;

    /**
     * 配置快照字段白名单（有序：字段 → 中文名），只快照「调优内容」——改动才产生新版本的字段。
     * <p>刻意不含 {@code isDefault}（全局下拉预选，非调优）、{@code shareConfig}（可见性，走独立接口）、
     * {@code isBuiltin}（系统标记）——这些不是"改砸了要回滚"的对象，回滚也不该顺带改动它们。
     * 新增可调优字段时记得在此登记（否则该字段的改动不进快照、也无法回滚）。</p>
     */
    private static final Map<String, String> VERSION_FIELDS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", "名称");
        m.put("icon", "图标");
        m.put("description", "描述");
        m.put("systemPrompt", "系统提示词");
        m.put("knowledgeScope", "文档范围");
        m.put("knowledgeBaseIds", "关联知识库");
        m.put("knowledgeDisabled", "不使用知识库");
        m.put("toolKnowledge", "知识库检索");
        m.put("toolBuiltin", "内置工具");
        m.put("toolSkill", "技能 Skills");
        m.put("toolArtifact", "产物交付");
        m.put("toolMcp", "MCP 外部工具");
        m.put("toolWebsearch", "联网搜索");
        m.put("toolApprovalMode", "工具审批模式");
        m.put("maxToolSteps", "工具步数上限");
        m.put("skills", "技能范围");
        m.put("mcps", "MCP 范围");
        m.put("builtinTools", "内置工具范围");
        m.put("isSubagent", "用途（主/子智能体）");
        m.put("subAgentIds", "可委派的子智能体");
        m.put("queryParams", "检索参数覆盖");
        m.put("workflowId", "绑定工作流");
        VERSION_FIELDS = Collections.unmodifiableMap(m);
    }

    /** 配置快照保留份数兜底（实际取 agent.versionKeep，设置页可调）；超出按版本号最小先删 */
    private static final int VERSION_KEEP_DEFAULT = 20;

    /**
     * 启动时维护内置「问渠」智能体的全局唯一性：多于一个时保留「默认优先、创建最早」的一条，
     * 其余降级为普通智能体（log.warn 留痕）；一个都没有（全新环境）时播种最小配置——
     * 各维度全部继承全局，无需任何手工建档。
     */
    @jakarta.annotation.PostConstruct
    public void maintainBuiltinAgent() {
        try {
            List<Agent> builtins = mapper.selectList(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getIsBuiltin, 1)
                    .orderByDesc(Agent::getIsDefault)
                    .orderByAsc(Agent::getCreateTime)
                    .orderByAsc(Agent::getId));
            if (builtins.size() > 1) {
                Agent keeper = builtins.get(0);
                for (int i = 1; i < builtins.size(); i++) {
                    Agent extra = builtins.get(i);
                    mapper.update(null, new LambdaUpdateWrapper<Agent>()
                            .eq(Agent::getId, extra.getId())
                            .set(Agent::getIsBuiltin, 0)
                            .set(Agent::getUpdateTime, LocalDateTime.now()));
                    log.warn("[AGENT] 内置智能体全局唯一，已将重复行降级为普通智能体: {}（{}）", extra.getName(), extra.getId());
                }
                log.info("[AGENT] 内置「问渠」唯一性维护完成: 保留 {}（{}），降级 {} 条", keeper.getName(), keeper.getId(), builtins.size() - 1);
                return;
            }
            if (builtins.size() == 1) {
                // 内置「问渠」可读性走 readable() 的内置豁免，shareConfig 永不参与判定——
                // 存量环境若残留过共享配置（历史上接口未拦），在此归一清掉，避免界面上出现误导性的共享范围标记
                Agent keeper = builtins.get(0);
                LambdaUpdateWrapper<Agent> fix = new LambdaUpdateWrapper<Agent>().eq(Agent::getId, keeper.getId());
                boolean dirty = false;
                if (keeper.getShareConfig() != null) {
                    fix.set(Agent::getShareConfig, null);
                    dirty = true;
                    log.info("[AGENT] 内置「问渠」共享范围不参与可见性判定，已清除残留配置: {}（{}）", keeper.getName(), keeper.getId());
                }
                // 用途/图标同为身份锁死项（配置页已隐藏、接口层已拒绝改动）：存量环境里若残留历史错值，
                // 在此归一回来——否则库里存着 is_subagent=1 / 非品牌标，界面上已无从纠正
                if (Integer.valueOf(1).equals(keeper.getIsSubagent())) {
                    fix.set(Agent::getIsSubagent, 0);
                    dirty = true;
                    log.warn("[AGENT] 内置「问渠」固定为主智能体，已修正遗留的子智能体标记: {}（{}）", keeper.getName(), keeper.getId());
                }
                if (!BUILTIN_ICON.equals(keeper.getIcon())) {
                    fix.set(Agent::getIcon, BUILTIN_ICON);
                    dirty = true;
                    log.warn("[AGENT] 内置「问渠」固定使用问渠品牌标，已修正遗留图标: {}（{}，原 {}）",
                            keeper.getName(), keeper.getId(), keeper.getIcon());
                }
                if (dirty) {
                    fix.set(Agent::getUpdateTime, LocalDateTime.now());
                    mapper.update(null, fix);
                }
                return;
            }
            Agent seed = new Agent();
            seed.setName(BUILTIN_NAME);
            seed.setIcon(BUILTIN_ICON);
            seed.setDescription("问渠内置的系统默认智能体：开箱即用，全员可用；仅管理员级可配置。");
            seed.setIsBuiltin(1);
            seed.setSystemPrompt(BUILTIN_SYSTEM_PROMPT);
            clearDefault(null); // 播种行尚未落库，无自身可排除
            seed.setIsDefault(1);
            seed.setCreatedBy("system");
            LocalDateTime now = LocalDateTime.now();
            seed.setCreateTime(now);
            seed.setUpdateTime(now);
            mapper.insert(seed);
            log.info("[AGENT] 已播种内置「问渠」系统默认智能体: {}（{}）", seed.getId(), seed.getName());
        } catch (Exception e) {
            log.warn("[AGENT] 内置「问渠」维护失败（不影响启动，下次启动重试）: {}", e.getMessage());
        }
    }

    /** 当前请求者（可见性/可管性判定的输入） */
    private Principal principal() {
        return new Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /**
     * 当前用户是否可读取该智能体。归属口径（谁建归谁）：未配置共享＝私有（仅创建者/管理员级）；
     * 内置「问渠」是系统默认——给每个人用，所有登录用户可读（配置仍只由管理员级维护）。
     */
    public boolean readable(Agent a) {
        if (a != null && Integer.valueOf(1).equals(a.getIsBuiltin())) return true;
        return resourceVisibilityService.canRead(principal(), a == null ? null : a.getShareConfig(),
                a == null ? null : a.getCreatedBy(), ResourceKind.AGENT);
    }

    /**
     * 当前用户是否可管理该智能体（可配置/设默认/改共享/删除；不可删除者由调用方按 isBuiltin 再拦）。
     * <p><b>与 {@link #readable} 的内置豁免对称</b>：内置「问渠」全员可读，<b>管理员级可配置</b>。
     * 此前 {@code ensureManageable} 只走通用判定，而 {@link ResourceVisibilityService#resolve}
     * 在「数据按 userId 隔离」之后<b>不再有任何角色的全量短路</b>（含 superadmin），
     * 且新环境播种的内置行 {@code created_by='system'} 永不等于任何登录 uid、
     * {@code share_config} 为空 → 判定恒为 NONE。结果新环境第一个注册的管理员
     * 反而改不了自己的内置智能体（存量库靠 created_by 恰好等于管理员 uid 的创建者短路侥幸正常）。
     * 故可管理判定在此收口，与 {@link #readable} 的产品语义对齐。</p>
     */
    public boolean manageable(Agent a) {
        if (a == null) return false;
        if (Integer.valueOf(1).equals(a.getIsBuiltin())) {
            return roleService.isAdminCode(RequestUser.role());
        }
        return resourceVisibilityService.canManage(principal(), a.getShareConfig(), a.getCreatedBy(), ResourceKind.AGENT);
    }

    /** 当前用户是否可管理该智能体（出现在管理端点前先过这道闸） */
    private void ensureManageable(Agent a) {
        if (!manageable(a)) {
            throw new BizException(403, "无权管理该智能体（不在其共享管理范围内）");
        }
    }

    /** 列表（默认智能体在前，其余按创建时间倒序） */
    public List<Agent> list() {
        return mapper.selectList(new LambdaQueryWrapper<Agent>()
                .orderByDesc(Agent::getIsDefault)
                .orderByDesc(Agent::getCreateTime));
    }

    /**
     * 对话页下拉用（普通用户可读）：只暴露「选择智能体」所需的最小字段。
     * <p>不返回 systemPrompt / knowledgeScope / 工具开关——那些是管理配置，
     * 不应经由只读接口外泄；对话页只需要 id 与展示名。</p>
     */
    public List<Map<String, Object>> available() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent a : list()) {
            // 子智能体不出现在对话页下拉：它只能被主智能体委派调用，不能当作问答角色直接选用
            if (Integer.valueOf(1).equals(a.getIsSubagent())) continue;
            // 共享范围之外的人不应在对话页看到该智能体（未配置共享＝私有，谁建归谁；内置「问渠」人人可读）
            if (!readable(a)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("description", a.getDescription());
            m.put("isDefault", a.getIsDefault() == null ? 0 : a.getIsDefault());
            // 头像展示字段：icon（wenqu=品牌标 / emoji）与内置标记（内置「问渠」未配图标时前端也按品牌标兜底）
            m.put("icon", a.getIcon());
            m.put("isBuiltin", Integer.valueOf(1).equals(a.getIsBuiltin()) ? 1 : 0);
            out.add(m);
        }
        return out;
    }

    /**
     * 自动派遣候选（agentId="auto" 时用）：可见的主智能体实体列表（isSubagent≠1 + 可读性过滤）。
     * 与 {@link #available()} 同口径，但返回实体供派遣路由读取名称/描述。
     */
    public List<Agent> dispatchCandidates() {
        List<Agent> out = new ArrayList<>();
        for (Agent a : list()) {
            if (Integer.valueOf(1).equals(a.getIsSubagent())) continue;
            if (!readable(a)) continue;
            out.add(a);
        }
        return out;
    }

    /**
     * 可委派的子智能体（供主智能体配置页勾选）。
     * 只返回子智能体（is_subagent=1），且是最小字段——配置页只需要 id 与展示名。
     */
    public List<Map<String, Object>> subAgents() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Agent> subs = mapper.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getIsSubagent, 1)
                .orderByDesc(Agent::getCreateTime));
        for (Agent a : subs) {
            if (!readable(a)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("description", a.getDescription());
            out.add(m);
        }
        return out;
    }

    /**
     * 按 ID 取（不存在返回 null，调用方据此降级为全局配置）。
     * <p>额外做可读校验：共享范围之外的人即使拿到 id，也不能把该智能体套用到自己的问答上——
     * 一律按「不存在」处理，直接回落全局配置（避免用 id 绕过可见性拿到别人的提示词/知识库范围）。</p>
     */
    public Agent get(String id) {
        if (!StringUtils.hasText(id)) return null;
        Agent a = mapper.selectById(id);
        return (a != null && readable(a)) ? a : null;
    }

    /**
     * 按 ID 直取，<b>刻意不套登录用户可读校验</b>：仅供分享 token 免登录通道
     * （{@code AgentShareService.resolveGuest}，同时承载 /s/{token} 与 /ai/mcp/{token}）使用。
     * <p>{@link #readable} 是「谁的资产对谁可见」的登录户口径，游客没有身份，套用后
     * 未配置共享范围（2026-10 起默认＝私有）的智能体即使已发布，链接也一律判成「分享不存在」。
     * 而发布端点已用 {@code canManage} 把关——能生成 token 的人本就有权公开该智能体，
     * 所以这里的授权语义是「持 token 即已获发布者授权」（见 ShareController 类注释）。</p>
     * <p>除分享通道外不要用本方法；需要可见性判定的地方一律用 {@link #get}。</p>
     */
    public Agent getForShare(String id) {
        if (!StringUtils.hasText(id)) return null;
        return mapper.selectById(id);
    }

    /** 默认智能体（无则返回 null） */
    public Agent defaultAgent() {
        return mapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getIsDefault, 1).last("LIMIT 1"));
    }

    /** 新建智能体；isDefault=true 时先清空其它默认 */
    public Agent create(Map<String, Object> body) {
        Agent a = toEntity(body, new Agent());
        // 内置标记不可经 API 产生（toEntity 本就不映射；这里再兜一道，保证「问渠」全局唯一只能由启动维护路径产生）
        a.setIsBuiltin(null);
        a.setCreatedBy(RequestUser.uid());
        a.setCreateTime(LocalDateTime.now());
        a.setUpdateTime(LocalDateTime.now());
        // 子智能体不参与「默认」：它只能被主智能体委派调用，不能作为对话页预选角色
        boolean isSub = Integer.valueOf(1).equals(a.getIsSubagent());
        if (!isSub && (Boolean.TRUE.equals(a.getIsDefault()) || Integer.valueOf(1).equals(a.getIsDefault()))) {
            clearDefault(null); // 新行尚未落库，无自身可排除
            a.setIsDefault(1);
        } else {
            a.setIsDefault(0);
        }
        mapper.insert(a);
        snapshot(a, "创建");
        log.info("[AGENT] 新建智能体 {}（{}）", a.getId(), a.getName());
        return a;
    }

    /** 更新智能体；isDefault 变化时同步处理唯一默认 */
    public Agent update(String id, Map<String, Object> body) {
        Agent existing = mapper.selectById(id);
        if (existing == null) throw new BizException(404, "智能体不存在");
        ensureManageable(existing);
        // 内置智能体（「问渠」）的名称是产品身份的一部分：请求体里出现 name 且与现值不一致时直接拒绝（fail-loud，
        // 不做静默忽略——静默会让用户以为改成功了，刷新后名称"自己变回去"更困惑）
        if (body != null && body.containsKey("name") && Integer.valueOf(1).equals(existing.getIsBuiltin())) {
            String requested = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (!existing.getName().equals(requested)) {
                throw new BizException("内置智能体「" + existing.getName() + "」的名称不可修改");
            }
        }
        // 同理锁死「用途」与「图标」：内置问渠恒为主智能体（改成子智能体会让它从对话页下拉消失，
        // 全局就没有默认角色了）、图标恒为问渠品牌标。配置页已隐藏这两项，这里做接口层兜底，
        // 防止绕过界面直接调接口改掉身份字段。
        if (body != null && Integer.valueOf(1).equals(existing.getIsBuiltin())) {
            if (body.containsKey("isSubagent") && Integer.valueOf(1).equals(toTri(body.get("isSubagent")))) {
                throw new BizException("内置智能体「" + existing.getName() + "」固定为主智能体，用途不可修改");
            }
            if (body.containsKey("icon")) {
                String requestedIcon = asText(body.get("icon"), 32);
                if (!BUILTIN_ICON.equals(requestedIcon)) {
                    throw new BizException("内置智能体「" + existing.getName() + "」固定使用问渠品牌标，图标不可修改");
                }
            }
            // 知识库范围同为身份锁死项：内置「问渠」固定检索使用者自己的默认库「问渠」＋官方内置手册库——智能体
            // 全局一只、默认库每人一个（运行时按使用者解析，见 RagService），没有可静态绑定的库 ID。与现值等价的
            // 提交放行（旧前端整单保存不受影响），真正改动 fail-loud 拒绝——静默忽略会让人以为改成功了。
            String curKb = asText(existing.getKnowledgeBaseIds(), 1000);
            String reqKb = body.containsKey("knowledgeBaseIds") ? asText(body.get("knowledgeBaseIds"), 1000) : curKb;
            boolean curOff = Integer.valueOf(1).equals(existing.getKnowledgeDisabled());
            boolean reqOff = body.containsKey("knowledgeDisabled")
                    ? Integer.valueOf(1).equals(toTri(body.get("knowledgeDisabled"))) : curOff;
            if (!java.util.Objects.equals(curKb, reqKb) || curOff != reqOff) {
                throw new BizException("内置智能体「" + existing.getName()
                        + "」固定检索默认知识库「问渠」及官方使用手册，知识库范围不可修改");
            }
        }
        // 改动前的快照串先算好：toEntity 会就地改写 existing（合并 body 字段），之后就拿不到原值了
        String beforeJson = snapshotJson(existing);
        Agent a = toEntity(body, existing);
        a.setUpdateTime(LocalDateTime.now());
        if (Integer.valueOf(1).equals(a.getIsDefault())) {
            // 本行原本可能已是默认：排除自身，否则 clearDefault 会把它清零而 updateColumns 又不写回该列
            clearDefault(id);
            a.setIsDefault(1);
        }
        // 存量智能体（版本表还空着）首次保存：先把改动前的状态补成 v1，这样本次改动本身也能一键回滚
        // （否则首版即当前态，改砸了没有"上一版"可退）
        if (versionCount(id) == 0) {
            recordVersion(id, beforeJson, "初始");
        }
        updateColumns(id, body, a);
        snapshot(a, "保存");
        log.info("[AGENT] 更新智能体 {}（{}）", a.getId(), a.getName());
        return a;
    }

    /**
     * 显式 set body 中出现过的字段（**含 null**），只更新这些列。
     * <p><b>不能用 {@code updateById}</b>：MyBatis-Plus 默认更新策略是 NOT_NULL，会跳过 null 列，
     * 于是「清空描述」「把能力改回跟随全局（tool*=null / *范围=null）」「知识库范围改回全部文档」
     * 这类操作都会静默不生效——与 {@link #updateShareConfig} 同因，故一律用显式 set。</p>
     */
    private void updateColumns(String id, Map<String, Object> body, Agent a) {
        Map<String, Object> b = body == null ? java.util.Collections.emptyMap() : body;
        LambdaUpdateWrapper<Agent> uw = new LambdaUpdateWrapper<Agent>().eq(Agent::getId, id);
        if (b.containsKey("name")) uw.set(Agent::getName, a.getName());
        // 图标（wenqu=问渠品牌标 / emoji；空 → null = 默认展示）
        if (b.containsKey("icon")) uw.set(Agent::getIcon, a.getIcon());
        if (b.containsKey("description")) uw.set(Agent::getDescription, a.getDescription());
        if (b.containsKey("systemPrompt")) uw.set(Agent::getSystemPrompt, a.getSystemPrompt());
        if (b.containsKey("knowledgeScope")) uw.set(Agent::getKnowledgeScope, a.getKnowledgeScope());
        if (b.containsKey("knowledgeBaseIds")) uw.set(Agent::getKnowledgeBaseIds, a.getKnowledgeBaseIds());
        if (b.containsKey("knowledgeDisabled")) uw.set(Agent::getKnowledgeDisabled, a.getKnowledgeDisabled());
        if (b.containsKey("toolKnowledge")) uw.set(Agent::getToolKnowledge, a.getToolKnowledge());
        if (b.containsKey("toolBuiltin")) uw.set(Agent::getToolBuiltin, a.getToolBuiltin());
        if (b.containsKey("toolSkill")) uw.set(Agent::getToolSkill, a.getToolSkill());
        if (b.containsKey("toolArtifact")) uw.set(Agent::getToolArtifact, a.getToolArtifact());
        if (b.containsKey("toolMcp")) uw.set(Agent::getToolMcp, a.getToolMcp());
        if (b.containsKey("toolWebsearch")) uw.set(Agent::getToolWebsearch, a.getToolWebsearch());
        // 工具执行审批三态（auto/ask/off；null=auto）
        if (b.containsKey("toolApprovalMode")) uw.set(Agent::getToolApprovalMode, a.getToolApprovalMode());
        // 单轮工具步数上限（null=继承全局）
        if (b.containsKey("maxToolSteps")) uw.set(Agent::getMaxToolSteps, a.getMaxToolSteps());
        if (b.containsKey("skills")) uw.set(Agent::getSkills, a.getSkills());
        if (b.containsKey("mcps")) uw.set(Agent::getMcps, a.getMcps());
        if (b.containsKey("builtinTools")) uw.set(Agent::getBuiltinTools, a.getBuiltinTools());
        if (b.containsKey("isSubagent")) uw.set(Agent::getIsSubagent, a.getIsSubagent());
        if (b.containsKey("subAgentIds")) uw.set(Agent::getSubAgentIds, a.getSubAgentIds());
        if (b.containsKey("isDefault")) uw.set(Agent::getIsDefault, a.getIsDefault());
        // M4：绑定的工作流（chatflow）；空串/null = 解绑（显式置空，NOT_NULL 策略下 updateById 不管）
        if (b.containsKey("workflowId")) uw.set(Agent::getWorkflowId, a.getWorkflowId());
        // 检索参数覆盖（可清空：null = 全部继承全局设置）
        if (b.containsKey("queryParams")) uw.set(Agent::getQueryParams, a.getQueryParams());
        uw.set(Agent::getUpdateTime, a.getUpdateTime());
        mapper.update(null, uw);
    }

    /** 删除智能体（内置标记的禁止删除） */
    public void delete(String id) {
        Agent existing = mapper.selectById(id);
        if (existing != null) {
            ensureManageable(existing);
            if (Integer.valueOf(1).equals(existing.getIsBuiltin())) {
                throw new BizException("内置智能体不可删除（如需调整请编辑其配置）");
            }
        }
        mapper.deleteById(id);
        // 版本快照随智能体一并清理（无独立价值，留着只是孤儿数据）
        versionMapper.delete(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id));
        log.info("[AGENT] 删除智能体 {}", id);
    }

    /** 设为默认（其余清零） */
    public void setDefault(String id) {
        Agent target = mapper.selectById(id);
        if (target == null) throw new BizException(404, "智能体不存在");
        ensureManageable(target);
        if (Integer.valueOf(1).equals(target.getIsSubagent())) {
            throw new BizException("子智能体不能设为默认：它只能被主智能体委派调用");
        }
        clearDefault(id);
        Agent a = new Agent();
        a.setId(id);
        a.setIsDefault(1);
        a.setUpdateTime(LocalDateTime.now());
        mapper.updateById(a);
        log.info("[AGENT] 设默认智能体 {}", id);
    }

    /**
     * 写入共享范围（空串 = 清空 → 回落私有：仅自己与管理员级可见）。
     * <p>校验口径与文档一致：必须 {@code version=2}，且管理范围不得宽于读取范围。</p>
     * <p><b>必须用 {@code set(..., null)} 显式置空</b>：MyBatis-Plus 默认更新策略是 NOT_NULL，
     * {@code updateById} 会跳过 null 字段，导致「恢复私有」静默不生效。</p>
     */
    public void updateShareConfig(String id, String shareConfigJson) {
        Agent existing = mapper.selectById(id);
        if (existing == null) throw new BizException(404, "智能体不存在");
        // 内置「问渠」readable() 直接豁免共享判定（人人可读），shareConfig 对它是不生效的死数据——fail-loud 拒绝
        if (Integer.valueOf(1).equals(existing.getIsBuiltin())) {
            throw new BizException("内置「问渠」为系统默认智能体，全员可用，无需配置共享范围");
        }
        ensureManageable(existing);
        resourceVisibilityService.validateShareConfig(shareConfigJson);
        String normalized = (shareConfigJson == null || shareConfigJson.isBlank()) ? null : shareConfigJson;
        mapper.update(null, new LambdaUpdateWrapper<Agent>()
                .eq(Agent::getId, id)
                .set(Agent::getShareConfig, normalized)
                .set(Agent::getUpdateTime, LocalDateTime.now()));
        log.info("[AGENT] 共享范围更新 id={} scope={}", id, normalized == null ? "私有（仅自己）" : "受限共享");
    }

    /** 把请求体字段映射到实体（仅覆盖 body 中出现的字段，其余保持原值） */
    private Agent toEntity(Map<String, Object> body, Agent a) {
        if (body == null) return a;
        if (body.containsKey("name")) {
            String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (!StringUtils.hasText(name)) throw new com.wenqu.ai.common.BizException("智能体名称不能为空");
            if (name.length() > 200) name = name.substring(0, 200);
            a.setName(name);
        }
        if (body.containsKey("description")) a.setDescription(asText(body.get("description"), 500));
        // 图标：'wenqu'=问渠品牌标 / emoji 字符；空串归一为 null（= 默认展示，前端按内置标记兜底品牌标）。
        // 品牌标为内置「问渠」专属：非内置智能体（含新建）不可使用，直接拒绝（fail-loud）
        if (body.containsKey("icon")) {
            String icon = asText(body.get("icon"), 32);
            if ("wenqu".equals(icon) && !Integer.valueOf(1).equals(a.getIsBuiltin())) {
                throw new com.wenqu.ai.common.BizException("问渠品牌标为内置「问渠」专属，其它智能体不可使用");
            }
            a.setIcon(icon);
        }
        if (body.containsKey("systemPrompt")) a.setSystemPrompt(asText(body.get("systemPrompt"), 60000));
        if (body.containsKey("knowledgeScope")) a.setKnowledgeScope(asText(body.get("knowledgeScope"), 2000));
        if (body.containsKey("knowledgeBaseIds")) a.setKnowledgeBaseIds(asText(body.get("knowledgeBaseIds"), 1000));
        // 「不使用知识库」：纯角色智能体（法律顾问/写作助手等）——1=跳过检索链路；null 视为 0
        if (body.containsKey("knowledgeDisabled")) a.setKnowledgeDisabled(toTri(body.get("knowledgeDisabled")));
        if (body.containsKey("toolKnowledge")) a.setToolKnowledge(toTri(body.get("toolKnowledge")));
        if (body.containsKey("toolBuiltin")) a.setToolBuiltin(toTri(body.get("toolBuiltin")));
        if (body.containsKey("toolSkill")) a.setToolSkill(toTri(body.get("toolSkill")));
        if (body.containsKey("toolArtifact")) a.setToolArtifact(toTri(body.get("toolArtifact")));
        if (body.containsKey("toolMcp")) a.setToolMcp(toTri(body.get("toolMcp")));
        if (body.containsKey("toolWebsearch")) a.setToolWebsearch(toTri(body.get("toolWebsearch")));
        // 工具执行审批三态（auto/ask/off；null 归一为 auto）
        if (body.containsKey("toolApprovalMode")) {
            String m = body.get("toolApprovalMode") == null ? null : String.valueOf(body.get("toolApprovalMode")).trim();
            a.setToolApprovalMode("ask".equals(m) || "off".equals(m) ? m : null);
        }
        // 单轮工具步数上限（NULL=继承全局；0=不限制；N=上限。负数归一 null）
        // 注意：不能用 toTri——它是三态解析器（任何非零数字都归一成 1），会把 15 存成 1，
        // 等于把"上限 15 步"变成"只准 1 步"，用工具的智能体直接被卡死
        if (body.containsKey("maxToolSteps")) {
            Integer steps = toIntOrNull(body.get("maxToolSteps"));
            a.setMaxToolSteps(steps != null && steps < 0 ? null : steps);
        }
        // 具体项范围（技能 / MCP Server / 内置工具）：null=跟随全局、空串=不使用、逗号串=仅这些
        if (body.containsKey("skills")) a.setSkills(toScopeText(body.get("skills"), 1000));
        if (body.containsKey("mcps")) a.setMcps(toScopeText(body.get("mcps"), 1000));
        if (body.containsKey("builtinTools")) a.setBuiltinTools(toScopeText(body.get("builtinTools"), 500));
        if (body.containsKey("isSubagent")) a.setIsSubagent(toTri(body.get("isSubagent")));
        // 委派列表为空串/空时归一为 null（= 不启用委派，编排走原有多视角策略）
        if (body.containsKey("subAgentIds")) a.setSubAgentIds(asText(body.get("subAgentIds"), 1000));
        if (body.containsKey("isDefault")) a.setIsDefault(toTri(body.get("isDefault")));
        // M4：绑定工作流（chatflow）——非空时校验「存在 + 对当前用户可读 + 已发布」，
        // 未发布直接拒绝：绑定后对话才报错不如配置时就说清楚（fail-loud，不做运行时静默回退）
        if (body.containsKey("workflowId")) a.setWorkflowId(assertBindableWorkflow(body.get("workflowId")));
        if (body.containsKey("queryParams")) a.setQueryParams(asText(body.get("queryParams"), 2000));
        return a;
    }

    /** 可绑定的工作流 id：空/null = 解绑；非空必须是存在、可读且已发布的工作流 */
    private String assertBindableWorkflow(Object v) {
        String id = asText(v, 50);
        if (id == null) return null;
        var wf = workflowMapper.selectById(id);
        if (wf == null) throw new BizException("工作流不存在：" + id);
        if (!resourceVisibilityService.canRead(principal(), wf.getShareConfig(), wf.getUid(), ResourceKind.WORKFLOW)) {
            throw new BizException(403, "无权绑定该工作流（不在其共享范围内）");
        }
        if (!"published".equals(wf.getStatus())) {
            throw new BizException("工作流「" + wf.getName() + "」尚未发布：请先在画布发布，再绑定到智能体");
        }
        return id;
    }

    /**
     * 清掉其它智能体上的默认标记，为把 {@code keepId} 设为默认做准备。
     * <p><b>必须排除 {@code keepId} 本身</b>：本行若原本就是默认，清零后若请求体没带 isDefault，
     * {@link #updateColumns} 不会把该列写回去（只写 body 里出现过的字段）——本行就永久停在 0，
     * 系统默认智能体凭空消失（2026-10-06 实测：改个描述就把内置问渠的默认标记弄丢了）。</p>
     */
    private void clearDefault(String keepId) {
        List<Agent> all = mapper.selectList(new LambdaQueryWrapper<Agent>().eq(Agent::getIsDefault, 1));
        for (Agent a : all) {
            if (a.getId().equals(keepId)) continue;
            mapper.update(null, new LambdaUpdateWrapper<Agent>()
                    .eq(Agent::getId, a.getId())
                    .set(Agent::getIsDefault, 0)
                    .set(Agent::getUpdateTime, LocalDateTime.now()));
        }
    }

    // ==================================================================================================
    // 配置版本管理：每次保存落一版（快照 = VERSION_FIELDS 白名单），可查看字段级 diff、一键回滚。
    // 口径对齐工作流版本：回滚 = 把历史配置"再存一版"（历史不被改写，回滚动作本身也可再回滚）。
    // ==================================================================================================

    /**
     * 版本列表（新→旧）：每版带 config（配置对象）、changes（与上一版的字段级差异）、
     * current（是否最新版记录）、identical（内容是否与当前配置一致，回滚过去等于没变）。
     */
    public List<Map<String, Object>> listVersions(String id) {
        Agent agent = mapper.selectById(id);
        if (agent == null) throw new BizException(404, "智能体不存在");
        List<AgentVersion> rows = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id)
                .orderByDesc(AgentVersion::getVersion));
        Map<String, Object> currentCfg = configOf(agent);
        int latest = rows.isEmpty() ? 0 : rows.get(0).getVersion();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            AgentVersion v = rows.get(i);
            Map<String, Object> cfg = parseConfig(v.getConfig());
            Map<String, Object> prev = i + 1 < rows.size() ? parseConfig(rows.get(i + 1).getConfig()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.getVersion());
            m.put("reason", v.getReason());
            m.put("operator", v.getOperator());
            m.put("createTime", v.getCreateTime() == null ? null : v.getCreateTime().toString());
            m.put("config", cfg);
            m.put("changes", diffConfig(prev, cfg));
            // 「当前版本」= 最新一条版本记录（配置只经 create/update/rollback 写入，每次写入都落版，
            // 故最新版即当前配置）。**不能按"内容与现状一致"判**：回滚到 v1 后 v1 与新版内容相同，
            // 会把两版同时标成「当前」。
            m.put("current", v.getVersion() == latest);
            // 内容与现状一致（回滚过去等于没变）——前端据此收敛"回滚到此版本"按钮，避免点了没反应
            m.put("identical", diffConfig(cfg, currentCfg).isEmpty());
            out.add(m);
        }
        return out;
    }

    /** 回滚到指定历史版本：把该版配置重新应用并落一版新快照（reason 记「回滚自 vX」），返回新版本号 */
    public Map<String, Object> rollback(String id, int targetVersion) {
        Agent agent = mapper.selectById(id);
        if (agent == null) throw new BizException(404, "智能体不存在");
        ensureManageable(agent);
        AgentVersion target = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id)
                .eq(AgentVersion::getVersion, targetVersion));
        if (target == null) throw new BizException(404, "版本 v" + targetVersion + " 不存在");
        applyConfig(id, parseConfig(target.getConfig()), Integer.valueOf(1).equals(agent.getIsBuiltin()));
        Agent updated = mapper.selectById(id);
        // 版本记录 best-effort：配置已应用，快照写失败不应把回滚报成失败（否则用户以为没回滚、实际已回滚）
        recordVersion(id, snapshotJson(updated), "回滚自 v" + targetVersion);
        log.info("[AGENT] 回滚智能体 {}（{}）至 v{} → 新版本 uid={}", id, updated.getName(), targetVersion, RequestUser.uid());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("appliedVersion", targetVersion);
        r.put("agent", updated);
        return r;
    }

    /** 落一版快照（best-effort：版本记录失败不影响本次保存本身） */
    private void snapshot(Agent a, String reason) {
        recordVersion(a.getId(), snapshotJson(a), reason);
    }

    /** 落一版快照（best-effort）：快照属审计辅助，任何失败只告警，不拖垮调用方的主流程 */
    private void recordVersion(String agentId, String json, String reason) {
        try {
            insertVersion(agentId, json, reason);
        } catch (Exception e) {
            log.warn("[AGENT] 版本快照写入失败（不影响主流程）id={} reason={}: {}", agentId, reason, e.getMessage());
        }
    }

    /** 插入版本行：内容与最新一版相同则跳过（避免重复保存刷版本）；插入后按保留份数裁剪 */
    private void insertVersion(String agentId, String json, String reason) {
        AgentVersion latest = latestVersion(agentId);
        if (latest != null && json != null && json.equals(latest.getConfig())) return;
        AgentVersion row = new AgentVersion();
        row.setAgentId(agentId);
        row.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        row.setConfig(json);
        row.setOperator(RequestUser.uid());
        row.setReason(reason);
        row.setCreateTime(LocalDateTime.now());
        versionMapper.insert(row);
        trimVersions(agentId);
    }

    private AgentVersion latestVersion(String agentId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .orderByDesc(AgentVersion::getVersion)
                .last("LIMIT 1"));
    }

    private int versionCount(String agentId) {
        Long n = versionMapper.selectCount(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId));
        return n == null ? 0 : n.intValue();
    }

    /** 只保留最近 N 份（N 可配：agent.versionKeep，隐藏参数） */
    private void trimVersions(String agentId) {
        int keep = Math.max(1, configService.getInt("agent.versionKeep", VERSION_KEEP_DEFAULT));
        List<AgentVersion> rows = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, agentId)
                .orderByDesc(AgentVersion::getVersion));
        for (int i = keep; i < rows.size(); i++) {
            versionMapper.deleteById(rows.get(i).getId());
        }
    }

    /** 智能体行 → 快照串（只取白名单字段；显式写出 null，否则"改回空"这一变更在 diff 里看不见） */
    private String snapshotJson(Agent a) {
        JSONObject all = (JSONObject) JSON.toJSON(a);
        JSONObject o = new JSONObject();
        for (String f : VERSION_FIELDS.keySet()) o.put(f, all.get(f));
        return o.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }

    /** 智能体行 → 快照对象（与 snapshotJson 同源，供列表比对当前态） */
    private Map<String, Object> configOf(Agent a) {
        return parseConfig(snapshotJson(a));
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            JSONObject o = JSON.parseObject(json);
            return new LinkedHashMap<>(o);
        } catch (Exception e) {
            log.warn("[AGENT] 版本快照解析失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 字段级 diff：逐字段比较（按 VERSION_FIELDS 顺序），返回 [{field,label,from,to}]，空 = 无差异 */
    private List<Map<String, Object>> diffConfig(Map<String, Object> from, Map<String, Object> to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : VERSION_FIELDS.entrySet()) {
            String f = e.getKey();
            String a = norm(from == null ? null : from.get(f));
            String b = norm(to == null ? null : to.get(f));
            if (a.equals(b)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("field", f);
            c.put("label", e.getValue());
            c.put("from", a);
            c.put("to", b);
            out.add(c);
        }
        return out;
    }

    private static String norm(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 把快照配置写回智能体行：逐列显式 set（含 null——回滚到"未设置"是合法目标，NOT_NULL 策略会漏掉）
     *  <p>内置「问渠」跳过 icon / isSubagent：这两项是身份锁死项，历史快照里可能存着旧值
     *  （本次锁死之前允许改），回滚若照写会把身份字段改回去，而配置页已无入口可纠正。</p> */
    private void applyConfig(String agentId, Map<String, Object> cfg, boolean builtin) {
        LambdaUpdateWrapper<Agent> uw = new LambdaUpdateWrapper<Agent>().eq(Agent::getId, agentId);
        String name = raw(cfg.get("name"));
        // 名称是 NOT NULL 且内置智能体名称不可变：快照里缺名称时保留现值，不写入 null
        if (StringUtils.hasText(name)) uw.set(Agent::getName, name);
        if (builtin) {
            uw.set(Agent::getIcon, BUILTIN_ICON).set(Agent::getIsSubagent, 0);
        } else {
            uw.set(Agent::getIcon, raw(cfg.get("icon")));
        }
        uw.set(Agent::getDescription, raw(cfg.get("description")));
        uw.set(Agent::getSystemPrompt, raw(cfg.get("systemPrompt")));
        uw.set(Agent::getKnowledgeScope, raw(cfg.get("knowledgeScope")));
        uw.set(Agent::getKnowledgeBaseIds, raw(cfg.get("knowledgeBaseIds")));
        uw.set(Agent::getKnowledgeDisabled, asInt(cfg.get("knowledgeDisabled")));
        uw.set(Agent::getToolKnowledge, asInt(cfg.get("toolKnowledge")));
        uw.set(Agent::getToolBuiltin, asInt(cfg.get("toolBuiltin")));
        uw.set(Agent::getToolSkill, asInt(cfg.get("toolSkill")));
        uw.set(Agent::getToolArtifact, asInt(cfg.get("toolArtifact")));
        uw.set(Agent::getToolMcp, asInt(cfg.get("toolMcp")));
        uw.set(Agent::getToolWebsearch, asInt(cfg.get("toolWebsearch")));
        uw.set(Agent::getToolApprovalMode, raw(cfg.get("toolApprovalMode")));
        uw.set(Agent::getMaxToolSteps, asInt(cfg.get("maxToolSteps")));
        uw.set(Agent::getSkills, raw(cfg.get("skills")));
        uw.set(Agent::getMcps, raw(cfg.get("mcps")));
        uw.set(Agent::getBuiltinTools, raw(cfg.get("builtinTools")));
        if (!builtin) uw.set(Agent::getIsSubagent, asInt(cfg.get("isSubagent")));
        uw.set(Agent::getSubAgentIds, raw(cfg.get("subAgentIds")));
        uw.set(Agent::getQueryParams, raw(cfg.get("queryParams")));
        uw.set(Agent::getWorkflowId, raw(cfg.get("workflowId")));
        uw.set(Agent::getUpdateTime, LocalDateTime.now());
        mapper.update(null, uw);
    }

    private static String raw(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 文本字段：null/空返回 null；超长截断（空字符串也视为未设置→null，避免存空串干扰"继承"判定） */
    private String asText(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 「具体项范围」字段解析（与主流智能体平台资源选择语义一致）：
     * null → null（跟随全局）；空串 → ""（显式一个都不用）；"a,b" → 归一化后的 "a,b"。
     * <p>与 asText 的关键区别：**保留空串语义**——空串表示"显式不使用"，
     * 若像 asText 那样归一成 null 就变成"跟随全局"，两者含义正好相反。</p>
     */
    private String toScopeText(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "";
        String joined = Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.isEmpty()) return "";
        return joined.length() > max ? joined.substring(0, max) : joined;
    }

    /** 三态解析：true/1 → 1，false/0 → 0，null/其它 → null（继承） */
    private Integer toTri(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Number n) return n.intValue() == 0 ? 0 : 1;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return 1;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return 0;
        return null;
    }

    /** 整数字段解析（保留任意值，不做 0/1 归一）：null/空/非法 → null */
    private Integer toIntOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
