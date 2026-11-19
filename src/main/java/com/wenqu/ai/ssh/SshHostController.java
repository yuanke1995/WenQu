package com.wenqu.ai.ssh;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SSH 远程主机（工作区的远端执行后端）。
 *
 * <p>归属一律按 {@link RequestUser#uid()}；这是“个人资产”，与 {@code executor:self} 同一授权点。
 * 凭据出参永远脱敏（见 {@link SshHostService#toDto}）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/executor/ssh")
@Tag(name = "SSH 远程主机", description = "工作区的远端执行后端：主机管理 / 测试连接 / 远端目录浏览 / PTY 终端")
public class SshHostController {

    private final SshHostService hostService;
    private final SshTerminalTicketService terminalTickets;

    public SshHostController(SshHostService hostService, SshTerminalTicketService terminalTickets) {
        this.hostService = hostService;
        this.terminalTickets = terminalTickets;
    }

    @Operation(summary = "SSH 主机清单", description = "当前用户托管的主机（不含任何凭据明文/密文）")
    @Permission("executor:self")
    @GetMapping("/hosts")
    public ResultJson list() {
        List<Map<String, Object>> rows = hostService.listDtos(RequestUser.uid());
        return ResultJson.ok(rows);
    }

    @Operation(summary = "新建 SSH 主机",
            description = "body: name/host/port/username/authType(password|private_key)/password|privateKey/passphrase?/hostKeyFingerprint?")
    @Permission("executor:self")
    @PostMapping("/hosts")
    public ResultJson create(@RequestBody SshHostService.Input body) {
        try {
            SshHost host = hostService.create(RequestUser.uid(), body);
            return ResultJson.ok(hostService.toDto(host), "已添加");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("[SSH] 新建主机失败: {}", e.getMessage());
            return ResultJson.error(500, "保存失败：" + e.getMessage());
        }
    }

    @Operation(summary = "更新 SSH 主机", description = "密码/私钥/口令留空=不改；地址或端口变化且未给新指纹时，指纹会被清空重走首次信任")
    @Permission("executor:self")
    @PutMapping("/hosts/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody SshHostService.Input body) {
        try {
            SshHost host = hostService.update(RequestUser.uid(), id, body);
            return ResultJson.ok(hostService.toDto(host), "已保存");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (Exception e) {
            log.warn("[SSH] 更新主机失败 id={}: {}", id, e.getMessage());
            return ResultJson.error(500, "保存失败：" + e.getMessage());
        }
    }

    @Operation(summary = "删除 SSH 主机", description = "有工作区正在使用时拒绝（409），并断开连接池")
    @Permission("executor:self")
    @DeleteMapping("/hosts/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        try {
            hostService.delete(RequestUser.uid(), id);
            return ResultJson.ok(true, "已删除");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(409, e.getMessage());
        }
    }

    @Operation(summary = "测试临时 SSH 参数", description = "新建前拨测：返回 serverVersion/fingerprint/algorithm/home/os，不落库")
    @Permission("executor:self")
    @PostMapping("/hosts/test")
    public ResultJson testAdhoc(@RequestBody SshHostService.Input body) {
        try {
            return ResultJson.ok(hostService.testAdhoc(body));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (SshOpException e) {
            return ResultJson.error(502, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "测试失败：" + e.getMessage());
        }
    }

    @Operation(summary = "测试已存 SSH 主机", description = "成功会更新最近探测结果；首次连接会 TOFU 记录主机指纹")
    @Permission("executor:self")
    @PostMapping("/hosts/{id}/test")
    public ResultJson test(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(hostService.test(RequestUser.uid(), id));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (SshOpException e) {
            return ResultJson.error(502, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "测试失败：" + e.getMessage());
        }
    }

    @Operation(summary = "浏览远端目录", description = "单层，只列目录；path 为空=登录用户 home。用于新建工作区时选择远端源文件夹")
    @Permission("executor:self")
    @GetMapping("/hosts/{id}/browse")
    public ResultJson browse(@PathVariable("id") String id,
                             @RequestParam(value = "path", required = false) String path) {
        try {
            return ResultJson.ok(hostService.browse(RequestUser.uid(), id, path));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (SshOpException e) {
            return ResultJson.error(502, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "浏览失败：" + e.getMessage());
        }
    }

    @Operation(summary = "远端新建目录", description = "body: {path}。目录选择器里的“新建文件夹”")
    @Permission("executor:self")
    @PostMapping("/hosts/{id}/mkdir")
    public ResultJson mkdir(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        try {
            hostService.mkdir(RequestUser.uid(), id, body == null ? null : body.get("path"));
            return ResultJson.ok(true);
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (SshOpException e) {
            return ResultJson.error(502, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "新建失败：" + e.getMessage());
        }
    }

    @Operation(summary = "申请终端入场券", description = "返回一次性 ticket（60 秒）；用它连 /api/ai/ssh-terminal?ticket=...")
    @Permission("executor:self")
    @PostMapping("/hosts/{id}/terminal-ticket")
    public ResultJson terminalTicket(@PathVariable("id") String id) {
        try {
            String uid = RequestUser.uid();
            // 校验归属（别人的主机换不到票）
            hostService.owned(uid, id);
            return ResultJson.ok(Map.of("ticket", terminalTickets.issue(uid, id)));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }
}
