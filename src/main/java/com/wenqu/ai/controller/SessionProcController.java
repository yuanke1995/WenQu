package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.SessionProcService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话后台进程：本机执行器 + 服务器沙盒两路合成后的清单与停止动作。
 *
 * <p>为什么放在会话域而不是 {@code /sandbox}：工作区会话的进程根本不经过沙盒，
 * 按「来源」分端点会让前端被迫先判断来源；这里按「会话」一次给全，来源只是条目上的一个字段。
 *
 * <p>授权点沿用 {@code sandbox:read} / {@code sandbox:run}：这两个动作就是「看进程、停进程」，
 * 与沙盒那张卡是同一个业务语义；新建授权点会让存量角色默认拿不到（fail-closed）而看不见自己的进程。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/session/procs")
@RequiredArgsConstructor
@Tag(name = "会话后台进程", description = "本机执行器与沙盒两路的后台进程清单、侧栏计数与停止")
public class SessionProcController {

    private final SessionProcService procService;

    @Operation(summary = "会话后台进程清单", description = "本机执行器与沙盒两路合成；"
            + "sides 里分别给 available/reason，读不到不会退成「没有进程」")
    @Permission("sandbox:read")
    @GetMapping
    public ResultJson list(@RequestParam("sessionId") String sessionId) {
        return ResultJson.ok(procService.list(sessionId, RequestUser.uid()));
    }

    @Operation(summary = "各会话后台进程数", description = "两路汇总；partial=true 表示本轮没扫全，结果只多不少")
    @Permission("sandbox:read")
    @GetMapping("/live")
    public ResultJson live() {
        return ResultJson.ok(procService.live(RequestUser.uid()));
    }

    @Operation(summary = "停止一棵后台进程树", description = "side=local 走本机执行器、side=sandbox 走沙盒；"
            + "按标记里编的会话再验一次归属，跨会话停不动")
    @Permission("sandbox:run")
    @PostMapping("/kill")
    public ResultJson kill(@RequestBody Map<String, Object> body) {
        String sessionId = str(body, "sessionId");
        String side = str(body, "side");
        String tag = str(body, "tag");
        if (sessionId.isBlank() || side.isBlank() || tag.isBlank()) {
            return ResultJson.error(400, "缺少会话、来源或进程标识");
        }
        try {
            return ResultJson.ok(procService.kill(sessionId, RequestUser.uid(), side, tag));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    @Operation(summary = "停止本会话全部后台进程", description = "本机侧停全部登记进程组；"
            + "沙盒侧只停本会话自己起的（同容器里别的会话不动）")
    @Permission("sandbox:run")
    @PostMapping("/killAll")
    public ResultJson killAll(@RequestBody Map<String, Object> body) {
        String sessionId = str(body, "sessionId");
        String side = str(body, "side");
        if (sessionId.isBlank() || side.isBlank()) {
            return ResultJson.error(400, "缺少会话或来源");
        }
        try {
            return ResultJson.ok(procService.killAll(sessionId, RequestUser.uid(), side));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    private String str(Map<String, Object> body, String key) {
        if (body == null) return "";
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
