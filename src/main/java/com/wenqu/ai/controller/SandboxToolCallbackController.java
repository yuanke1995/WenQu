package com.wenqu.ai.controller;

import com.wenqu.ai.agent.runcode.RunCodeBridge;
import com.wenqu.ai.agent.runcode.RunLimitException;
import com.wenqu.ai.agent.runcode.RunTokenRegistry;
import com.wenqu.ai.dto.ResultJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 沙盒脚本回调平台的回程（P3b 的「跨网络回调」端点）。
 * <p>
 * 容器里的 Python 代码要调本轮已挂载的工具，只能走 HTTP 回来。这条通道的设计取向全在<b>收窄</b>上：
 * <ol>
 *   <li><b>凭据即鉴权，且只活一次运行</b>：{@link RunTokenRegistry} 发的 32 字节随机数，
 *       脚本结束或本轮退场即撤；不带用户登录令牌、不带 API Key、不读请求头里的自报身份——
 *       容器里没有任何长期凭据（这是与"把用户 token 塞进沙盒"那条路的根本区别）。</li>
 *   <li><b>只认本轮工具名册</b>：不在本轮工具表里的名字直接拒。名字像也不放行——
 *       工具是否挂载是这一轮的权限决定，脚本改不了它。</li>
 *   <li><b>执行一律走守卫链</b>（{@link RunCodeBridge#invoke}）：叫停闸、权限档位、引用登记、
 *       工具卡片观测，与模型直接调用同一条判据。绕过守卫的 run_code 等于给一段模型现写的代码
 *       开了条无人看守的后门，那就不是"少几次来回"而是"少一层管控"。</li>
 *   <li><b>失败是字段不是 5xx</b>：被挡下/执行失败都在 {@code ok=false + error} 里回去，
 *       脚本能看懂为什么没跑成并继续（与 DeepSeek Harness 的 {@code PtcRunResult} 同口径）。
 *       HTTP 状态只用来表达"这枚凭据不行"。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Tag(name = "沙盒脚本回调", description = "run_code 在容器里回调本轮已挂载工具的回程（凭据为一次性运行令牌）")
@RestController
@RequestMapping("/api/ai/sandbox")
public class SandboxToolCallbackController {

    /** 请求体上限（字节）：子调用入参是工具自己的参数，正常远小于这个数；超了就是脚本在拿它传资料 */
    private static final int MAX_ARGS_CHARS = 20_000;

    private final RunTokenRegistry tokens;

    public SandboxToolCallbackController(RunTokenRegistry tokens) {
        this.tokens = tokens;
    }

    /**
     * @param runToken {@code X-Run-Token}：本次运行的一次性凭据
     * @param body     {@code {"tool": "read_file", "args": {"path": "..."}}}
     *     <p>刻意不用 {@code Authorization: Bearer}：那个头在本平台专指登录令牌，
     *     {@code UserContextInterceptor} 见到它就一定要验成 JWT，验不过直接 401——请求根本到不了这里，
     *     而现象是"脚本里每次调用都失败"，看着像工具坏了（本机实测踩过这一坑）。
     */
    @Operation(summary = "子调用一次本轮已挂载的工具",
            description = "凭据无效/过期 401；工具不在本轮名册、或被守卫挡下都返回 ok=false（不是 5xx）")
    @PostMapping("/tool-call")
    public ResultJson<Map<String, Object>> toolCall(
            @org.springframework.web.bind.annotation.RequestHeader(name = "X-Run-Token", required = false)
            String runToken,
            @RequestBody Map<String, Object> body) {
        RunTokenRegistry.Handle h = tokens.resolve(runToken == null ? null : runToken.trim());
        if (h == null) {
            // 不写进日志正文（凭据是 bearer，日志会留在盘上）
            log.warn("[P3B] 子调用被拒：运行凭据无效或已过期");
            return ResultJson.error(401, "运行凭据无效或已过期（脚本的运行已结束或超时）");
        }
        String tool = body.get("tool") == null ? "" : String.valueOf(body.get("tool")).trim();
        if (tool.isEmpty()) return ok(false, "", "缺少 tool 字段");
        String argsJson = argsJsonOf(body.get("args"));
        if (argsJson == null || argsJson.length() > MAX_ARGS_CHARS) {
            return ok(false, "", "子调用入参过大（上限 " + MAX_ARGS_CHARS + " 字），请把资料先写进沙盒文件再处理");
        }
        Set<String> allowed = h.bridge().callableSpecs().stream()
                .map(RunCodeBridge.Spec::name).collect(Collectors.toSet());
        if (!allowed.contains(tool)) {
            // fail-closed：本轮没挂上的工具，脚本调不到，也不会因为"名字像"被放行
            log.warn("[P3B] 子调用被拒：工具 {} 不在本轮工具名册（session={}）", tool, h.sessionId());
            return ok(false, "", "本轮没有挂载工具「" + tool + "」，脚本调不到模型也用不了的能力");
        }
        try {
            String out = h.bridge().invoke(tool, argsJson);
            return ok(true, out, "");
        } catch (RunLimitException e) {
            log.info("[P3B] 子调用被限制挡下：tool={} {} session={}", tool, e.getMessage(), h.sessionId());
            return ok(false, "", e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[P3B] 子调用执行失败：tool={} {} session={}", tool, cause.toString(), h.sessionId());
            return ok(false, "", cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private static ResultJson<Map<String, Object>> ok(boolean ok, String output, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("output", output == null ? "" : output);
        m.put("error", error == null ? "" : error);
        return ResultJson.ok(m);
    }

    /** args 允许是对象（脚本里传 kwargs）或 JSON 文本；统一成工具入参 JSON */
    private static String argsJsonOf(Object args) {
        if (args == null) return "{}";
        if (args instanceof String s) return s.isBlank() ? "{}" : s;
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(args);
        } catch (Exception e) {
            return null;
        }
    }
}
