package com.wenqu.ai.service.httptool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.ConfigCryptoService;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 工具服务：保存期归一化、导入预览、每轮装配（Agent 配置 → 对话工具回调）。
 * <p>
 * 装配与 {@code McpClientService.toolCallbacks} 同一形状——provider 只做挂载判据
 * （三态/游客/黑名单），工具本体的构建收在 service 层。鉴权密文在<b>装配期</b>解出
 * （JWE 解密在内存里完成、随回调闭包用完即弃），执行器不接触密文形态。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpToolService {

    private final AgentMapper agentMapper;
    private final ConfigService configService;
    private final ConfigCryptoService cryptoService;

    /** 每轮装配产物：{@code problem} 非空 = 配置坏了，provider 据此走轮内可见降级 */
    public record Mount(List<ToolCallback> tools, String problem) {
    }

    /**
     * 按智能体当前配置构建本轮的 HTTP 工具回调。
     * 未配置/未启用 = 空表（与「没有这个能力」不可区分，不在模型面前出现）。
     */
    public Mount mount(String agentId) {
        if (agentId == null || agentId.isBlank()) return new Mount(List.of(), null);
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) return new Mount(List.of(), null);
        List<HttpToolModels.HttpToolset> sets = HttpToolModels.parseStored(agent.getHttpTools());
        if (sets == null) {
            return new Mount(List.of(), "智能体的 HTTP 工具集配置解析失败，本轮未挂载（请重新编辑保存一次）");
        }
        if (sets.isEmpty()) return new Mount(List.of(), null);
        boolean allowPrivate = configService.getBoolean("httpTool.allowPrivateNetwork", true);
        int fallbackTimeout = ConfigDefaults.HTTP_TOOL_TIMEOUT_MS;
        List<ToolCallback> out = new ArrayList<>();
        for (HttpToolModels.HttpToolset set : sets) {
            if (set.getEnabled() != null && !set.getEnabled()) continue;
            HttpToolExecutor.AuthSpec auth = resolveAuth(set);
            for (HttpToolModels.HttpOp op : set.getOps()) {
                out.add(new HttpApiToolCallback(set, op, auth, allowPrivate, fallbackTimeout));
            }
        }
        return new Mount(List.copyOf(out), null);
    }

    /** 鉴权装配：密文解出、按类型落头（bearer 补前缀；解不出按无凭据调用，与 MCP 同口径） */
    private HttpToolExecutor.AuthSpec resolveAuth(HttpToolModels.HttpToolset set) {
        HttpToolModels.HttpAuth a = set.getAuth();
        if (a == null || a.getValue() == null || a.getValue().isBlank() || "none".equals(a.getType())) {
            return null;
        }
        String plain = cryptoService.decrypt(a.getValue());
        if (plain == null || plain.isBlank()) {
            log.warn("[HTTP-TOOL] 工具集「{}」的鉴权凭据解密失败，按无凭据调用", set.getName());
            return null;
        }
        if ("bearer".equals(a.getType())) {
            return new HttpToolExecutor.AuthSpec("Authorization",
                    plain.startsWith("Bearer ") ? plain : "Bearer " + plain);
        }
        return new HttpToolExecutor.AuthSpec(
                a.getHeader() == null || a.getHeader().isBlank() ? "X-Api-Key" : a.getHeader(), plain);
    }

    /** 导入预览：解析规格但不落库，前端据此勾选操作（与保存同一解析器，预览即所见） */
    public Map<String, Object> preview(String spec) {
        OpenApiSpecParser.ParseResult r = OpenApiSpecParser.parse(spec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestedName", r.suggestedName());
        out.put("suggestedBaseUrl", r.suggestedBaseUrl() == null ? "" : r.suggestedBaseUrl());
        out.put("warnings", r.warnings());
        List<Map<String, Object>> ops = new ArrayList<>();
        for (OpenApiSpecParser.ParsedOp op : r.ops()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("operationId", op.operationId());
            o.put("method", op.method());
            o.put("path", op.path());
            o.put("summary", op.summary());
            o.put("hasBody", op.hasBody());
            o.put("params", op.params());
            o.put("inputSchema", op.inputSchema());
            ops.add(o);
        }
        out.put("ops", ops);
        return out;
    }

    /** 保存期归一化（校验+上限+凭据加密），见 {@link HttpToolModels#normalizeFromSave} */
    public String normalizeFromSave(Object raw, String existingJson) {
        return HttpToolModels.normalizeFromSave(raw, existingJson, cryptoService::encrypt);
    }

    /** 回显脱敏（value 清空、留 hasValue），见 {@link HttpToolModels#mask} */
    public String mask(String json) {
        return HttpToolModels.mask(json);
    }

    /** 既有配置的全部对话工具名（toolDeny 校验白名单扩展用） */
    public Set<String> opNames(String json) {
        return HttpToolModels.opNames(json);
    }

    /** 单把对话工具：描述=摘要+方法路径锚点，入参=导入时算定的 JSON Schema */
    private final class HttpApiToolCallback implements ToolCallback {

        private final HttpToolModels.HttpToolset set;
        private final HttpToolModels.HttpOp op;
        private final HttpToolExecutor.AuthSpec auth;
        private final boolean allowPrivate;
        private final int fallbackTimeout;

        HttpApiToolCallback(HttpToolModels.HttpToolset set, HttpToolModels.HttpOp op,
                            HttpToolExecutor.AuthSpec auth, boolean allowPrivate, int fallbackTimeout) {
            this.set = set;
            this.op = op;
            this.auth = auth;
            this.allowPrivate = allowPrivate;
            this.fallbackTimeout = fallbackTimeout;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            String summary = op.getSummary() == null || op.getSummary().isBlank()
                    ? "调用宿主 HTTP 接口" : op.getSummary();
            String desc = summary + "。接口：" + op.getMethod() + " " + op.getPath()
                    + "（工具集 " + set.getName() + "）";
            return ToolDefinition.builder()
                    .name(op.getName())
                    .description(desc)
                    .inputSchema(JSON.toJSONString(op.getInputSchema()))
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args;
            try {
                args = toolInput == null || toolInput.isBlank()
                        ? Map.of() : JSON.parseObject(toolInput);
            } catch (Exception e) {
                throw new HttpToolExecutor.HttpToolException("工具参数不是合法 JSON：" + e.getMessage());
            }
            long begin = System.currentTimeMillis();
            HttpToolExecutor.Result r = HttpToolExecutor.execute(set, op, args, auth, allowPrivate);
            long cost = System.currentTimeMillis() - begin;
            log.info("[HTTP-TOOL] 工具集={} 操作={} 状态={} 耗时={}ms", set.getName(), op.getName(), r.status(), cost);
            String body = r.body() == null || r.body().isBlank() ? "（无响应体）" : r.body();
            if (body.length() > HttpToolModels.MODEL_RESULT_CHARS) {
                body = body.substring(0, HttpToolModels.MODEL_RESULT_CHARS) + "\n…（响应过长已截断）";
            }
            if (r.status() >= 200 && r.status() < 300) {
                return body;
            }
            // 4xx/5xx 是业务数据（模型要看到状态码与响应体才能解释或改参重试），按结果返回不抛
            return "HTTP " + r.status() + "（调用未成功）\n" + body;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }
}
