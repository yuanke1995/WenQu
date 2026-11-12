package com.wenqu.ai.service.workflowtool;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.WorkflowMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.Workflow;
import com.wenqu.ai.model.WorkflowRun;
import com.wenqu.ai.service.UserRoleService;
import com.wenqu.ai.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流工具服务：保存期归一化（校验存在性/发布态/上限）、每轮装配（智能体配置 → 对话工具回调）、
 * 嵌套深度上限（防环）。
 * <p>
 * 装配与 {@code HttpToolService.mount} 同一形状——provider 只做挂载判据（三态/游客/分支/黑名单），
 * 工具本体的构建收在 service 层。与 HTTP 工具的两处差异都是防环上的收紧：
 * <ol>
 *   <li><b>嵌套深度上限</b>（{@link #MAX_WF_DEPTH}）：工作流的子智能体节点会以触发者身份起完整
 *       一轮问答，那轮若再调工作流工具（或它绑定了另一条工作流被分派）就形成
 *       智能体→工作流→智能体→工作流 的递归——深度随每次嵌套 +1，到顶后工具不再挂载、
 *       分派入口 fail-loud，两道闸同源这一个常量；</li>
 *   <li><b>分支轮不挂</b>：框架派遣出去的分支里再调工作流，等于把乘数扇出接进确定性流程
 *       （分支本身已不能派遣、不能挂审批工具，工作流工具按同一口径收口）。</li>
 * </ol>
 * <p>
 * 执行身份在<b>装配期</b>定死：工具回调跑在框架的工具线程上，ThreadLocal 身份（RequestUser）不可靠，
 * 故 mount 时按触发者 uid 现查部门/角色构造 {@link WorkflowService.Principal} 随闭包携带。
 * 实例按 (uid, sessionId) 常驻且工具面在 build 期固化，同一实例内身份恒为同一人，无漂移窗口。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowToolService {

    /** 每智能体最多挂载的工作流个数（工具表体积与误触面控制，对齐 HTTP 工具集 ≤4 的量级口径） */
    public static final int MAX_SELECTED = 8;

    /** 给模型的工具结果截断长度（与 HttpToolModels.MODEL_RESULT_CHARS 同口径 16KB） */
    private static final int MODEL_RESULT_CHARS = 16_000;

    /**
     * 工作流嵌套深度上限（含本次运行）：1=智能体轮直接调工作流、2=工作流的子智能体再调一层。
     * 与工作流事件链深 {@code WorkflowEventChainRegistry.MAX_CHAIN_DEPTH} 同档——
     * 深一层就允许，再深一律拒绝。改动它要同时过一遍防环 E2E（工具路径）。
     */
    public static final int MAX_WF_DEPTH = 2;

    /** 工具名前缀（避免与静态注册工具、MCP 的 wen_qu 前缀冲突） */
    private static final String NAME_PREFIX = "wf_";

    private final AgentMapper agentMapper;
    private final WorkflowMapper workflowMapper;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final ObjectProvider<WorkflowService> workflowServiceProvider;

    /** 每轮装配产物：{@code problem} 非空 = 配置坏了，provider 据此走轮内可见降级 */
    public record Mount(List<ToolCallback> tools, String problem) {
    }

    /**
     * 按智能体当前配置构建本轮的工作流工具回调。
     * 未配置/深度到顶 = 空表（与「没有这个能力」不可区分，不在模型面前出现）；
     * 引用的工作流已删除/下线 = 静默跳过（下线是正常生命周期，不该每轮报警）。
     */
    public Mount mount(String agentId, String userId, int wfDepth) {
        return mount(agentId, userId, wfDepth, false);
    }

    /**
     * 游客放行：{@code guest=true} 时只挂「只读工作流」——节点全部落在
     * start/end/llm/retrieval/condition/loop/template 之内。含代码执行、HTTP、MCP、知识写入、
     * 人工审核、子智能体、技能注入的一律不挂；执行入口还会再校验一次（fail-closed）。
     */
    public Mount mount(String agentId, String userId, int wfDepth, boolean guest) {
        if (agentId == null || agentId.isBlank()) return new Mount(List.of(), null);
        if (wfDepth >= MAX_WF_DEPTH) return new Mount(List.of(), null);   // 防环：到顶不再挂（见类注释）
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) return new Mount(List.of(), null);
        List<String> ids = parseStored(agent.getWorkflowTools());
        if (ids == null) {
            return new Mount(List.of(), "智能体的工作流工具配置解析失败，本轮未挂载（请重新编辑保存一次）");
        }
        if (ids.isEmpty()) return new Mount(List.of(), null);
        // 嵌套深度随调用逐层 +1：本层调工作流，工作流内部的子智能体轮会带着 ctx.wfDepth 继续计数
        int nextDepth = wfDepth + 1;
        WorkflowService.Principal principal = principalOf(userId);
        List<ToolCallback> out = new ArrayList<>();
        for (String id : ids) {
            Workflow row = workflowMapper.selectById(id);
            if (row == null || !"published".equals(row.getStatus())) {
                log.debug("[WF-TOOL] 智能体引用的工作流不可用（已删除或未发布），本轮跳过: agent={} workflow={}", agentId, id);
                continue;
            }
            if (guest && !isGuestSafe(row)) {
                log.info("[WF-TOOL] 游客轮跳过含副作用节点的工作流: agent={} workflow={}", agentId, id);
                continue;
            }
            out.add(new WorkflowToolCallback(row, startInputSchema(row), nextDepth, principal, guest));
        }
        return new Mount(List.copyOf(out), null);
    }

    /** 游客可调用的节点集合：纯读取/生成，不写库、不出站、不执行命令、不等人裁决 */
    private static final Set<String> GUEST_SAFE_NODES =
            Set.of("start", "end", "llm", "retrieval", "condition", "loop", "template");

    /**
     * 是否游客安全：已发布 DSL 的全部节点类型都在只读集合内。
     * <p>解析失败按不安全处理（fail-closed）；空节点表也按不安全（没有 start/end 的图本就不该发布）。
     */
    public static boolean isGuestSafe(Workflow row) {
        if (row == null || row.getPublishedDsl() == null || row.getPublishedDsl().isBlank()) return false;
        try {
            com.alibaba.fastjson2.JSONObject root = JSON.parseObject(row.getPublishedDsl());
            com.alibaba.fastjson2.JSONArray nodes = root == null ? null : root.getJSONArray("nodes");
            if (nodes == null || nodes.isEmpty()) return false;
            for (int i = 0; i < nodes.size(); i++) {
                com.alibaba.fastjson2.JSONObject n = nodes.getJSONObject(i);
                String type = n == null ? null : n.getString("type");
                if (type == null || !GUEST_SAFE_NODES.contains(type)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 触发者身份（部门/角色现查库，不依赖 ThreadLocal——工具回调线程上身份不可靠，见类注释） */
    private WorkflowService.Principal principalOf(String userId) {
        try {
            return WorkflowService.Principal.ofUser(userId, userMapper, userRoleService);
        } catch (Exception e) {
            log.warn("[WF-TOOL] 触发者身份解析失败，按仅 uid 校验可见性: {}: {}", userId, e.getMessage());
            return new WorkflowService.Principal(userId, null, null);
        }
    }

    /**
     * 保存期归一化：id 数组去重、截上限、逐个校验「存在且已发布」（fail-loud——存一个不存在的 id
     * 只会静默不生效，「我明明挂了工作流它怎么不调」从界面上看不出来）。空数组归一 null（与
     * httpTools 的「[] 与 null 不归一会报假变更」同口径）。
     */
    public String normalizeFromSave(Object raw) {
        List<String> ids = new ArrayList<>();
        if (raw instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o == null) continue;
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) ids.add(s);
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            // 容错：前端偶发直接传 JSON 字符串
            try {
                Object parsed = JSON.parse(s);
                if (parsed instanceof Iterable<?> it) {
                    for (Object o : it) ids.add(String.valueOf(o).trim());
                } else {
                    ids.add(s.trim());
                }
            } catch (Exception e) {
                throw new com.wenqu.ai.common.BizException("工作流工具集格式无法解析：" + e.getMessage());
            }
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>(ids);
        if (dedup.size() > MAX_SELECTED) {
            throw new com.wenqu.ai.common.BizException("工作流工具最多挂 " + MAX_SELECTED + " 个（当前 " + dedup.size() + " 个）");
        }
        for (String id : dedup) {
            Workflow row = workflowMapper.selectById(id);
            if (row == null) throw new com.wenqu.ai.common.BizException("工作流不存在：" + id);
            if (!"published".equals(row.getStatus())) {
                throw new com.wenqu.ai.common.BizException(
                        "工作流「" + row.getName() + "」尚未发布：请先在画布发布后再挂为工具");
            }
        }
        return dedup.isEmpty() ? null : JSON.toJSONString(new ArrayList<>(dedup));
    }

    /** 既有配置的全部对话工具名（toolDeny 校验白名单扩展用；不可用的条目算不出名字，跳过） */
    public Set<String> opNames(String json) {
        Set<String> out = new LinkedHashSet<>();
        List<String> ids = parseStored(json);
        if (ids == null || ids.isEmpty()) return out;
        List<Workflow> rows = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .in(Workflow::getId, ids));
        for (Workflow row : rows) out.add(toolName(row));
        return out;
    }

    // --------------------------------------------------------------------------------------------------
    // 单把对话工具
    // --------------------------------------------------------------------------------------------------

    /**
     * 存量 JSON → 工作流 id 列表。null = 原始配置解析失败（调用方走轮内可见降级）；
     * 空/未配置 = 空表（该族无工具可挂）。
     */
    private static List<String> parseStored(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (Object o : JSON.parseArray(json)) {
                String s = o == null ? null : String.valueOf(o).trim();
                if (s != null && !s.isEmpty()) out.add(s);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 工具名：wf_{清洗名}_{id 前 8 位}——名字含 id 后缀，重名工作流也互不撞名 */
    private static String toolName(Workflow row) {
        String slug = (row.getName() == null ? "" : row.getName())
                .toLowerCase()
                .replaceAll("[^a-z0-9\\-_]+", "_");
        slug = slug.replaceAll("^_+|_+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        if (slug.isEmpty()) slug = "flow";
        String idShort = row.getId() == null ? "" : row.getId().replaceAll("[^a-zA-Z0-9]", "");
        if (idShort.length() > 8) idShort = idShort.substring(0, 8);
        return NAME_PREFIX + slug + "_" + idShort;
    }

    /** start 节点声明 → JSON Schema（key/required 之外，可选 type/desc；无 type 缺省 string） */
    private static String startInputSchema(Workflow row) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        try {
            WorkflowDslView dsl = parseDslView(row.getPublishedDsl());
            for (Map<String, Object> p : dsl.startInputs()) {
                String key = String.valueOf(p.get("key"));
                if (key == null || key.isBlank() || "null".equals(key)) continue;
                Map<String, Object> prop = new LinkedHashMap<>();
                Object type = p.get("type");
                prop.put("type", type == null || String.valueOf(type).isBlank() ? "string" : String.valueOf(type));
                Object desc = p.get("desc");
                if (desc != null && !String.valueOf(desc).isBlank()) prop.put("description", String.valueOf(desc));
                properties.put(key, prop);
                if (Boolean.TRUE.equals(p.get("required"))) required.add(key);
            }
        } catch (Exception e) {
            log.warn("[WF-TOOL] 工作流入参 schema 解析失败，按无参工具挂载: {}: {}", row.getId(), e.getMessage());
        }
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return JSON.toJSONString(schema);
    }

    /** publishedDsl 的最小视图（只取 start 节点的 inputs 列表；解析失败抛 BizException 由调用方兜） */
    private static WorkflowDslView parseDslView(String dslText) {
        com.alibaba.fastjson2.JSONObject root = JSON.parseObject(dslText);
        if (root == null) throw new com.wenqu.ai.common.BizException("工作流定义为空");
        com.alibaba.fastjson2.JSONArray nodes = root.getJSONArray("nodes");
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (nodes != null) {
            for (int i = 0; i < nodes.size(); i++) {
                com.alibaba.fastjson2.JSONObject n = nodes.getJSONObject(i);
                if (n == null || !"start".equals(n.getString("type"))) continue;
                com.alibaba.fastjson2.JSONObject cfg = n.getJSONObject("config");
                com.alibaba.fastjson2.JSONArray arr = cfg == null ? null : cfg.getJSONArray("inputs");
                if (arr != null) {
                    for (int j = 0; j < arr.size(); j++) {
                        Map<String, Object> m = arr.getJSONObject(j);
                        if (m != null) inputs.add(m);
                    }
                }
                break;   // start 节点唯一
            }
        }
        return new WorkflowDslView(inputs);
    }

    private record WorkflowDslView(List<Map<String, Object>> startInputs) {
    }

    /**
     * 单把对话工具：描述=工作流名+说明锚点，入参=start 节点声明生成的 schema；
     * 执行=同步跑已发布版本（无审批节点、无流式），结束节点出参整体作为工具结果返回——
     * 运行失败（status!=success）按结果返回给模型自行反应（与 HTTP 工具的 4xx/5xx 同口径），
     * 配置类拒绝（未发布/嵌套过深/不可见）才抛。
     */
    private final class WorkflowToolCallback implements ToolCallback {

        private final Workflow row;
        private final String inputSchema;
        private final int wfDepth;
        private final WorkflowService.Principal principal;
        private final boolean guest;

        WorkflowToolCallback(Workflow row, String inputSchema, int wfDepth,
                             WorkflowService.Principal principal, boolean guest) {
            this.row = row;
            this.inputSchema = inputSchema;
            this.wfDepth = wfDepth;
            this.principal = principal;
            this.guest = guest;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            String desc = (row.getDescription() == null || row.getDescription().isBlank())
                    ? "运行一条已发布的工作流（多步骤确定性流程），返回结束节点的出参" : row.getDescription();
            return ToolDefinition.builder()
                    .name(toolName(row))
                    .description("运行工作流「" + row.getName() + "」：" + desc
                            + "（需要按 start 入参传参；工具结果是工作流的最终出参）")
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            // 执行入口二次校验（fail-closed）：配置在装配后被改、或快照含副作用节点，一律拒绝
            if (guest && !isGuestSafe(row)) {
                throw new com.wenqu.ai.common.BizException("该工作流包含副作用节点，游客不可调用");
            }
            Map<String, Object> args;
            try {
                args = toolInput == null || toolInput.isBlank()
                        ? Map.of() : JSON.parseObject(toolInput);
            } catch (Exception e) {
                throw new com.wenqu.ai.common.BizException("工具参数不是合法 JSON：" + e.getMessage());
            }
            long begin = System.currentTimeMillis();
            WorkflowRun run = workflowServiceProvider.getObject()
                    .runForAgent(row.getId(), args, principal, null, null, wfDepth);
            long cost = System.currentTimeMillis() - begin;
            log.info("[WF-TOOL] 工作流={} 状态={} 耗时={}ms 嵌套深度={}", row.getName(), run.getStatus(), cost, wfDepth);
            if ("success".equals(run.getStatus())) {
                String outputs = run.getOutputs() == null || run.getOutputs().isBlank() ? "{}" : run.getOutputs();
                String body = "工作流「" + row.getName() + "」执行完成（v" + run.getVersion()
                        + "，耗时 " + cost / 1000.0 + "s）。出参：\n" + outputs;
                return cap(body);
            }
            String reason = run.getError() == null || run.getError().isBlank()
                    ? "状态 " + run.getStatus() : run.getError();
            return cap("工作流「" + row.getName() + "」运行失败（" + reason + "）——可据实向用户说明，不要臆造结果");
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }

        private String cap(String s) {
            if (s.length() <= MODEL_RESULT_CHARS) return s;
            return s.substring(0, MODEL_RESULT_CHARS) + "\n…（结果过长已截断）";
        }
    }
}
