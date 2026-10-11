package com.wenqu.ai.agent.runcode;

import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@code run_code} 的执行侧（P3b 第一刀：Python 一条语言）。
 * <p>
 * 这件事的形态与 DeepSeek Harness 不同，差别全部来自"代码不在本进程"：
 * <ol>
 *   <li>DSH 的子调用是一次函数调用；这里必须<b>跨网络回调平台</b>，所以发一枚只活本次运行的凭据
 *       （{@link RunTokenRegistry}）并开一条只认这枚凭据的回程；</li>
 *   <li>DSH 直接读自己的工具注册表；这里要把本轮已挂上的工具<b>生成成 Python 客户端</b>
 *       （{@link ToolStubRenderer}）——工具没挂上就不该在模块里出现，脚本也就调不到；</li>
 *   <li>往返延迟：子调用并行要有界（模块里的信号量），单调用与整段运行各有一道超时。</li>
 * </ol>
 * 换来的隔离比 DSH 强（模型写的代码在容器里，不在 JVM 里），代价就是这三件它不需要造的东西。
 * <p>
 * <b>为什么值得做</b>：用户说"把这 30 份文档逐一汇总"，native 形态是 30 次模型来回并撞上单轮步数闸；
 * run_code 是一段脚本扇出、只把 print 出来的结果回上下文——省的是模型往返，不是省守卫。
 * 子调用逐个走与模型直调同一条守卫链（{@link RunCodeBridge#invoke}），这是本功能的前提而不是附属品。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RunCodeService {

    /** 脚本本体的字符上限：超过就该拆成多次运行或落文件，而不是塞进一次回调 */
    private static final int MAX_SCRIPT_CHARS = 20_000;

    private final ConfigService configService;
    private final RunTokenRegistry tokens;
    private final ToolStubRenderer stubs;

    public RunCodeService(ConfigService configService, RunTokenRegistry tokens, ToolStubRenderer stubs) {
        this.configService = configService;
        this.tokens = tokens;
        this.stubs = stubs;
    }

    /**
     * 跑一段脚本并把结果还给模型。
     *
     * @param bridge     本轮的子调用入口（按轮，见 {@link RunCodeBridge}）
     * @param backend    本轮的沙盒后端（由 {@code SandboxTools} 按 toolContext 解析，归属与档位都在那儿判过）
     * @param script     模型写的 Python 脚本
     * @param outputSink 实时输出回调（有则走流式执行，用户在工具卡上看得到它一行行出来）
     * @return 给模型看的运行结果文本（失败也是文本，不抛——抛出会掐断整轮流）
     */
    public String run(RunCodeBridge bridge, ProvisionerSandboxBackend backend, String script,
                      Consumer<String> outputSink) {
        if (backend == null) return "沙盒不可用：本轮没能拿到沙盒执行环境，run_code 未执行。";
        if (script == null || script.isBlank()) return "脚本不能为空。";
        if (script.length() > MAX_SCRIPT_CHARS) {
            return "脚本过长（" + script.length() + " 字 > " + MAX_SCRIPT_CHARS
                    + "）。请拆成几段依次运行，或先把处理逻辑写成文件再执行那个文件。";
        }
        int maxCalls = Math.max(1, configService.getInt("tool.runcode.maxSubCalls", 50));
        int maxParallel = Math.max(1, configService.getInt("tool.runcode.maxParallel", 8));
        int timeoutSeconds = Math.max(10, configService.getInt("tool.runcode.timeoutSeconds", 120));
        String base = callbackBase();
        if (base == null) {
            // 没配可达地址时不猜：猜出来的 URL 在容器里十有八九连不上，表现为每个子调用超时，
            // 排查的人只会以为是工具坏了
            log.warn("[FAIL-LOUD] run_code 缺少 sandbox.callbackBaseUrl，本轮不提供子调用");
            return "沙盒回调地址未配置（系统设置「沙盒 → 平台回调地址」），脚本里的子调用连不到平台。"
                    + "请改用已有的工具逐步完成，或让管理员补这一项。";
        }
        // 凭据 TTL = 脚本运行上限 + 富余：脚本卡住到点被沙盒杀掉，凭据也随之作废，不留一条活着的回程
        long ttlMs = timeoutSeconds * 1000L + 30_000L;
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String dir = workDir() + "/" + runId;
        String token = tokens.issue(bridge.sessionId(), bridge.turnId(), bridge.userId(), bridge, ttlMs);
        try {
            String stub = stubs.render(bridge.callableSpecs(), base, token, maxCalls, maxParallel,
                    bridge.subCallTimeoutMs());
            ProvisionerSandboxBackend.WriteResult w1 = backend.write(dir + "/wenqu_tools.py", stub);
            if (w1.error != null) return "写入工具客户端失败：" + w1.error + "（run_code 未执行）";
            ProvisionerSandboxBackend.WriteResult w2 = backend.write(dir + "/main.py", script + "\n");
            if (w2.error != null) return "写入脚本失败：" + w2.error + "（run_code 未执行）";

            String command = "cd " + dir + " && python3 main.py";
            long t0 = System.currentTimeMillis();
            ProvisionerSandboxBackend.ExecuteResponse resp = outputSink != null
                    ? backend.executeStreaming(command, timeoutSeconds, outputSink)
                    : backend.execute(command, timeoutSeconds);
            long ms = System.currentTimeMillis() - t0;
            StringBuilder out = new StringBuilder();
            if (resp.infraError) {
                out.append("沙盒不可用（不是脚本执行失败）：").append(nvl(resp.output)).append('\n');
            } else if (resp.exitCode != null && resp.exitCode != 0) {
                out.append("退出码 ").append(resp.exitCode).append("（脚本可能未跑完）\n");
            }
            String stdout = nvl(resp.output);
            out.append(stdout.isBlank() ? "(脚本没有任何 print 输出——结果只有 print 出来的东西才会回到对话里)" : stdout);
            if (resp.truncated) out.append("\n…（输出过长已截断）");
            out.append("\n[运行 ] 耗时 ").append(ms).append("ms，子调用 ").append(bridge.callsUsed())
                    .append("/").append(maxCalls).append(" 次");
            if (resp.infraError) {
                log.warn("[P3B] run_code 基础设施故障 run={} session={}", runId, bridge.sessionId());
            } else {
                log.info("[P3B] run_code 完成 run={} exit={} 子调用 {} 次 耗时 {}ms",
                        runId, resp.exitCode, bridge.callsUsed(), ms);
            }
            return out.toString();
        } catch (Exception e) {
            log.warn("[P3B] run_code 执行异常 run={}: {}", runId, e.toString());
            return "run_code 执行失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            tokens.revoke(token);
            // 清掉本次的临时目录：里面那份 wenqu_tools.py 带着凭据，留在用户可见的工作区里
            // 既会被人当成自己的文件，也白留一条已失效但形状正确的凭据样本
            try {
                backend.execute("rm -rf " + dir, 30);
            } catch (Exception ignored) {
                // 清理失败不影响结果文本；容器按会话回收，兜底在沙盒生命周期那层
            }
        }
    }

    /** 本轮可子调用的工具名（供提示词与运行状态面板说明"这一轮脚本能碰什么"） */
    public List<String> callableNames(RunCodeBridge bridge) {
        return bridge.callableSpecs().stream().map(RunCodeBridge.Spec::name).toList();
    }

    /**
     * 容器可达的平台地址。
     * <p>沙盒容器各自挂在自己的 bridge 网络上，默认路由到宿主是通的（本机实测
     * {@code host.docker.internal} 可解析且能打到 8090）；生产环境的网络拓扑不同，
     * 所以这一项必须显式配置而不是写死。留空 = 不提供子调用（见 {@link #run}）。
     */
    private String callbackBase() {
        String v = configService.get("sandbox.callbackBaseUrl");
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /**
     * 脚本与客户端落在会话工作区里的一个临时子目录（工作区根由档位判过，越界写不进来）。
     * <p>公开是给 {@code SandboxTools.run_code} 用的：写入前的档位判据要与 write/edit 同一条，
     * 而不是在这里再抄一遍"根目录怎么算"。
     */
    public String workDir() {
        String root = configService.get("sandbox.virtualPathPrefix");
        String r = root == null || root.isBlank() ? "/home/gem/user-data" : root.trim();
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r + "/.wenqu-run";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
