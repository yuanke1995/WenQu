package com.wenqu.ai.executor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机执行器通道：设备授权码配对 + 轮询/回传（设备令牌）。
 *
 * <p>配对方向是<b>执行器发起</b>（与 gh auth login / aws sso login 同形）：执行器匿名申请一个
 * 6 位码并打印链接，用户在浏览器里登录自己的账号确认，令牌才一次性回给执行器——
 * 令牌不经过命令行、shell 历史和环境变量。
 *
 * <p>为什么是长轮询而不是 WebSocket：执行器在用户内网，服务端不能反连；长轮询用普通 HTTP 就够，
 * 穿任何反代都不需要额外配置。执行器取走调用后立刻发起下一次轮询，空转成本只有一次挂起请求。
 */
@RestController
@RequestMapping("/api/ai/executor")
@RequiredArgsConstructor
@Tag(name = "本机执行器", description = "用户电脑上的执行器：授权码配对、长轮询取工具调用、回传结果")
public class ExecutorController {

    private final ExecutorService executorService;
    private final ExecutorRegistry registry;
    private final ExecutorPairingService pairing;

    @Operation(summary = "执行器申请授权码", description = "匿名；返回 deviceCode（执行器轮询用）与 userCode（用户在浏览器里输的 6 位码）")
    @PostMapping("/device-code")
    public Map<String, Object> createDeviceCode(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        String name = body == null || body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String os = body == null || body.get("os") == null ? "" : String.valueOf(body.get("os"));
        // 只认 TCP 对端地址：X-Forwarded-For 客户端可伪造，用它做限流等于没有限流
        String ip = request == null ? null : request.getRemoteAddr();
        try {
            return ok(pairing.create(name, os, ip));
        } catch (ExecutorPairingService.PairingException e) {
            return fail(e.code(), e.getMessage());
        }
    }

    @Operation(summary = "执行器轮询授权结果", description = "deviceCode 自证；用户确认后一次性返回设备令牌")
    @PostMapping("/device-code/token")
    public Map<String, Object> deviceCodeToken(@RequestBody(required = false) Map<String, Object> body) {
        String deviceCode = body == null || body.get("deviceCode") == null ? "" : String.valueOf(body.get("deviceCode"));
        ExecutorPairingService.Redeemed redeemed = pairing.redeem(deviceCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", redeemed.status().name().toLowerCase());
        data.put("name", redeemed.name());
        if (redeemed.token() != null) {
            data.put("token", redeemed.token());
            data.put("executorId", redeemed.executorId());
        }
        return ok(data);
    }

    @Operation(summary = "查看待确认的授权码", description = "登录态；返回这台电脑的名字与系统，供确认页展示")
    @GetMapping("/device-code")
    public Map<String, Object> readDeviceCode(@RequestParam("code") String code) {
        try {
            return ok(infoData(pairing.lookup(code, RequestUser.uid())));
        } catch (ExecutorPairingService.PairingException e) {
            return fail(e.code(), e.getMessage());
        }
    }

    @Operation(summary = "确认授权这台电脑", description = "登录态；确认后执行器即可取走设备令牌")
    @PostMapping("/device-code/approve")
    public Map<String, Object> approveDeviceCode(@RequestBody(required = false) Map<String, Object> body) {
        String code = body == null || body.get("code") == null ? "" : String.valueOf(body.get("code"));
        try {
            return ok(infoData(pairing.approve(code, RequestUser.uid())));
        } catch (ExecutorPairingService.PairingException e) {
            return fail(e.code(), e.getMessage());
        }
    }

    private static Map<String, Object> infoData(ExecutorPairingService.Info info) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userCode", info.userCode());
        data.put("name", info.name());
        data.put("os", info.os());
        data.put("expiresAt", info.expiresAt());
        data.put("status", info.status().name().toLowerCase());
        return data;
    }

    @Operation(summary = "已登记的执行器", description = "当前用户的设备清单（不含令牌）")
    @GetMapping("/devices")
    public Map<String, Object> devices() {
        List<Map<String, Object>> rows = executorService.list(RequestUser.uid());
        rows.forEach(r -> r.put("online", registry.online(String.valueOf(r.get("id")))));
        return ok(rows);
    }

    @Operation(summary = "浏览执行器上的目录", description = "只列目录（单层）。path 为空 = 列出执行器授权的根目录。用于「选择工作区目录」")
    @GetMapping("/browse")
    public Map<String, Object> browse(@RequestParam("executorId") String executorId,
                                      @RequestParam(value = "path", required = false) String path) {
        if (!executorService.belongsTo(RequestUser.uid(), executorId)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", 403);
            err.put("msg", "这台执行器不属于你");
            err.put("data", null);
            return err;
        }
        if (!registry.online(executorId)) {
            // 离线必须立刻说，别挂满 25 秒再超时——那在界面上表现为永远"加载中…"
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", 503);
            err.put("msg", "执行器不在线：先在那台电脑上启动 wenqu-agent 再试");
            err.put("data", null);
            return err;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", path == null ? "" : path);
        ExecutorRegistry.Result r = registry.call(executorId, "list", args, 25_000);
        if (!r.ok()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("code", 502);
            err.put("msg", r.message() == null ? "执行器没有返回" : r.message());
            err.put("data", null);
            return err;
        }
        List<Map<String, Object>> dirs = new java.util.ArrayList<>();
        Object raw = r.data().get("entries");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                boolean isDir = Boolean.TRUE.equals(m.get("isDir"));
                if (!isDir) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("path", m.get("path"));
                d.put("name", m.get("name"));
                dirs.add(d);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", r.data().get("path") == null ? (path == null ? "" : path) : r.data().get("path"));
        data.put("dirs", dirs);
        return ok(data);
    }

    @Operation(summary = "在宿主上弹原生目录选择框", description = "远程浏览器够不到宿主桌面时由执行器代弹系统选择框，返回绝对路径")
    @PostMapping("/pick-dir")
    public Map<String, Object> pickDir(@RequestBody(required = false) Map<String, Object> body) {
        String executorId = body == null || body.get("executorId") == null ? "" : String.valueOf(body.get("executorId"));
        if (!executorService.belongsTo(RequestUser.uid(), executorId)) {
            return fail(403, "这台执行器不属于你");
        }
        if (!registry.online(executorId)) {
            return fail(503, "执行器不在线：先在那台电脑上启动 wenqu-agent 再试");
        }
        ExecutorRegistry.Result r = registry.call(executorId, "pickDir", new LinkedHashMap<>(), 120_000);
        if (!r.ok()) return fail(502, r.code() + "：" + r.message());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", r.data().get("path"));
        return ok(data);
    }

    @Operation(summary = "删除执行器", description = "撤销令牌，立即失效")
    @DeleteMapping("/device/{id}")
    public Map<String, Object> revoke(@PathVariable("id") String id) {
        executorService.revoke(RequestUser.uid(), id);
        return ok(true);
    }

    /** 执行器确认收到调用：取走后的响应可能丢，没 ack 的调用会被重投。 */
    @Operation(summary = "执行器确认收到调用", description = "设备令牌自证；只标记投递成功，不执行任何操作")
    @PostMapping("/ack")
    public ResponseEntity<String> ack(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody(required = false) Map<String, Object> body) {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth), false);
        if (device == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String id = body == null || body.get("id") == null ? "" : String.valueOf(body.get("id"));
        boolean accepted = registry.ack(device.id(), id);
        return ResponseEntity.ok(JSON.toJSONString(Map.of("accepted", accepted)));
    }

    /** 执行器心跳：命令执行期间独立刷新在线时间，避免长命令被 70s 在线窗口误判离线。 */
    @Operation(summary = "执行器心跳", description = "设备令牌自证；只刷新在线时间，不取任务")
    @PostMapping("/heartbeat")
    public ResponseEntity<String> heartbeat(@RequestHeader(value = "Authorization", required = false) String auth) {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth));
        if (device == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        registry.heartbeat(device.id());
        return ResponseEntity.ok(JSON.toJSONString(Map.of("accepted", true)));
    }

    /** 执行器长轮询：没有任务时挂起最多 wait 秒。设备令牌自证，不走登录态。 */
    @GetMapping("/poll")
    public ResponseEntity<String> poll(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestHeader(value = "X-Wenqu-Executor-Version", required = false) String agentVersion,
                                       @RequestParam(value = "wait", defaultValue = "25") int wait) throws InterruptedException {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth));
        if (device == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // v2 起有 ack 租约；旧 agent 不带版本头，不能因未 ack 重投（否则会重复执行）
        boolean ackCapable = false;
        if (agentVersion != null && !agentVersion.isBlank()) {
            try {
                ackCapable = Integer.parseInt(agentVersion.trim()) >= 2;
            } catch (NumberFormatException ignore) {
                ackCapable = false;
            }
        }
        ExecutorRegistry.Call call = registry.poll(device.id(), wait, ackCapable);
        if (call == null) return ResponseEntity.noContent().build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", call.id);
        data.put("op", call.op);
        data.put("args", call.args);
        data.put("deadline", call.deadlineMs);
        return ResponseEntity.ok(JSON.toJSONString(data));
    }

    /** 执行器回传结果。 */
    @PostMapping("/result")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> result(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody String body) {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth), false);
        if (device == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        JSONObject payload = JSON.parseObject(body);
        String id = payload.getString("id");
        boolean ok = Boolean.TRUE.equals(payload.getBoolean("ok"));
        ExecutorRegistry.Result result;
        if (ok) {
            JSONObject data = payload.getJSONObject("data");
            result = ExecutorRegistry.Result.ok(data == null ? Map.of() : (Map<String, Object>) data);
        } else {
            JSONObject error = payload.getJSONObject("error");
            result = ExecutorRegistry.Result.fail(
                    error == null ? "E_IO" : error.getString("code"),
                    error == null ? "执行失败" : error.getString("message"));
        }
        boolean accepted = registry.complete(device.id(), id, result);
        return ResponseEntity.ok(JSON.toJSONString(Map.of("accepted", accepted)));
    }

    private static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        return h.startsWith("Bearer ") ? h.substring(7).trim() : h;
    }

    private static Map<String, Object> fail(int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("code", code);
        m.put("msg", msg);
        m.put("data", null);
        return m;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("code", 200);
        m.put("msg", "ok");
        m.put("data", data);
        return m;
    }
}
