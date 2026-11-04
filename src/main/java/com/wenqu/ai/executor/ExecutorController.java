package com.wenqu.ai.executor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机执行器通道：设备登记（登录态） + 轮询/回传（设备令牌）。
 *
 * <p>为什么是长轮询而不是 WebSocket：执行器在用户内网，服务端不能反连；长轮询用普通 HTTP 就够，
 * 穿任何反代都不需要额外配置。执行器取走调用后立刻发起下一次轮询，空转成本只有一次挂起请求。
 */
@RestController
@RequestMapping("/api/ai/executor")
@RequiredArgsConstructor
@Tag(name = "本机执行器", description = "用户电脑上的执行器：登记设备、长轮询取工具调用、回传结果")
public class ExecutorController {

    private final ExecutorService executorService;
    private final ExecutorRegistry registry;

    @Operation(summary = "登记本机执行器", description = "返回一次性设备令牌（只回这一次）；令牌只存哈希")
    @PostMapping("/device")
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body) {
        String name = body == null || body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String os = body == null || body.get("os") == null ? "" : String.valueOf(body.get("os"));
        ExecutorRegistry.Paired paired = executorService.register(RequestUser.uid(), name, os);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", paired.device().id());
        data.put("name", paired.device().name());
        data.put("token", paired.token());
        return ok(data);
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
        data.put("path", path == null ? "" : path);
        data.put("dirs", dirs);
        return ok(data);
    }

    @Operation(summary = "删除执行器", description = "撤销令牌，立即失效")
    @DeleteMapping("/device/{id}")
    public Map<String, Object> revoke(@PathVariable("id") String id) {
        executorService.revoke(RequestUser.uid(), id);
        return ok(true);
    }

    /** 执行器长轮询：没有任务时挂起最多 wait 秒。设备令牌自证，不走登录态。 */
    @GetMapping("/poll")
    public ResponseEntity<String> poll(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam(value = "wait", defaultValue = "25") int wait) throws InterruptedException {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth));
        if (device == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ExecutorRegistry.Call call = registry.poll(device.id(), wait);
        if (call == null) return ResponseEntity.noContent().build();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", call.id);
        data.put("op", call.op);
        data.put("args", call.args);
        return ResponseEntity.ok(JSON.toJSONString(data));
    }

    /** 执行器回传结果。 */
    @PostMapping("/result")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> result(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody String body) {
        ExecutorRegistry.Device device = executorService.authenticate(bearer(auth));
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
        boolean accepted = registry.complete(id, result);
        return ResponseEntity.ok(JSON.toJSONString(Map.of("accepted", accepted)));
    }

    private static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        return h.startsWith("Bearer ") ? h.substring(7).trim() : h;
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
