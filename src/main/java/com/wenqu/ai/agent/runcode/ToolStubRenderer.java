package com.wenqu.ai.agent.runcode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 由本轮工具描述符生成沙盒侧的 Python 客户端（P3b 的「descriptor → stub」那一步）。
 * <p>
 * DeepSeek Harness 生成的是 TS {@code .d.ts}（宿主同进程，运行时是 Node）；问渠的代码跑在
 * provisioner 容器里，镜像内的解释器是 python3，所以这里生成一个可直接 import 的模块，
 * 函数体就是一次 HTTP 回调。刻意<b>不</b>只做类型声明——容器里没有平台，声明了也调不到。
 * <p>
 * 每个函数对应本轮真实挂上的一个工具（不在工具表里的东西，脚本也不该调得到）；
 * 参数名与 {@code inputSchema} 的属性名一一对应，说明写进 docstring，让模型不必猜。
 * 名字不合 Python 标识符规矩的工具只走通用 {@code call_tool(tool, **kwargs)}。
 * <p>
 * 模板里 Python 的 docstring 一律用三单引号：Java 文本块的定界符就是三双引号，
 * 模板里出现 {@code """} 会把整块截断——而这个语法错误要到运行时生成模块才被发现的话，
 * 表现只是"脚本 import 失败"，排查方向会被带去网络与凭据上。
 *
 * @author yuanke
 */
@Component
public class ToolStubRenderer {

    /** 沙盒侧回调端点（相对平台 base URL）；与 {@code SandboxToolCallbackController} 同源，改一处要改两处 */
    public static final String CALLBACK_PATH = "/api/ai/sandbox/tool-call";

    /**
     * Python 关键字：工具入参叫 {@code from}／{@code to}／{@code lambda} 是完全可能的
     * （单位换算工具真有 from/to 这两个参数，本机实测生成的模块因此整份语法错，
     * 而现象只是"脚本 import 失败"，看着像回调或凭据坏了）。
     * <p>处理方式是把形参改名（加下划线）但**键名保持原样**下发：工具侧看到的还是 from，
     * 脚本里写的是 from_。改名而不是跳过，是因为跳过会让这个工具在脚本里整个不可用。
     */
    private static final Set<String> PY_KEYWORDS = Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
            "return", "try", "while", "with", "yield");

    /** 骨架模板里替换用的占位符 */
    private static final String PREAMBLE = """
            # 由问渠平台按本轮已挂载的工具自动生成，不要手改这一份文件。
            # 每个函数都是一次跨网络回调：子调用逐个走平台的守卫链（本轮叫停、权限档位、
            # 引用登记、工具卡片），与模型直接调用同一条判据——脚本绕不过去这些。
            # 凭据走 X-Run-Token 请求头，不占用 Authorization：那个头在本平台专指登录令牌，
            # 混用会让一次调试请求先被登录门禁 401 掉，看起来像"工具坏了"（本机实测踩过）。
            # 用法：只有 print 出来的内容会回到对话里，函数返回值本身不会自动出现。
            # 失败：便捷函数抛 ToolError；call_tool() 返回 {'ok':bool,'output':str,'error':str} 不抛。

            import json
            import threading
            import urllib.error
            import urllib.request

            BASE = @BASE@
            API_PATH = @API_PATH@
            TOKEN = @TOKEN@
            MAX_CALLS = @MAX_CALLS@
            MAX_PARALLEL = @MAX_PARALLEL@
            CALL_TIMEOUT = @CALL_TIMEOUT@

            _lock = threading.Lock()
            _used = [0]
            _sem = threading.BoundedSemaphore(MAX_PARALLEL)


            class ToolError(RuntimeError):
                '''子调用被平台挡下或执行失败（超上限、本轮已叫停、路径越权等）。'''


            def _require(tool, args):
                r = _post(tool, args)
                if not r["ok"]:
                    raise ToolError("%s: %s" % (tool, r["error"] or "调用失败"))
                return r["output"]


            def call_tool(tool, **args):
                '''按名字调用本轮已挂载的任意工具，返回 {'ok','output','error'}（不抛异常）。

                参数名与工具自己的入参名一致；工具本轮没挂上时平台直接拒，不会因为名字像就放行。
                '''
                return _post(tool, args)


            def calls_used():
                '''本轮已发生的子调用次数（含被拒的），便于脚本自己收手。'''
                return _used[0]


            def _post(tool, args):
                with _lock:
                    _used[0] += 1
                    n = _used[0]
                if n > MAX_CALLS:
                    raise ToolError("本轮子调用上限 %d 已用满，请改用已经取回的结果作答" % MAX_CALLS)
                body = json.dumps({"tool": tool, "args": args}).encode("utf-8")
                req = urllib.request.Request(
                    BASE + API_PATH,
                    data=body,
                    headers={"Content-Type": "application/json", "X-Run-Token": TOKEN},
                    method="POST",
                )
                with _sem:
                    try:
                        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT) as resp:
                            payload = json.loads(resp.read().decode("utf-8", "replace"))
                    except urllib.error.HTTPError as e:
                        detail = e.read().decode("utf-8", "replace")[:400]
                        return {"ok": False, "output": "", "error": "HTTP %s %s" % (e.code, detail)}
                    except Exception as e:
                        return {"ok": False, "output": "", "error": "%s: %s" % (type(e).__name__, e)}
                if not payload.get("success"):
                    return {"ok": False, "output": "", "error": str(payload.get("msg") or "平台拒绝该子调用")}
                d = payload.get("data") or {}
                return {"ok": bool(d.get("ok")),
                        "output": d.get("output") or "",
                        "error": d.get("error") or ""}


            """;

    /**
     * JavaScript（Node）侧的客户端骨架。
     * <p>用 CommonJS 的 {@code module.exports}：脚本以 {@code main.js} 直接运行，同目录
     * {@code require('./wenqu_tools')} 就能拿到，不需要 package.json 与 ESM 扩展名这些额外条件。
     * <p>并发靠 Promise 队列而不是线程：Node 是单线程的，信号量在这里挡的是"同时在途的请求数"，
     * 与 Python 那份挡"同时占用平台线程数"语义一致——两边扇出的规模才可比。
     */
    private static final String PREAMBLE_JS = """
            // 由问渠平台按本轮已挂载的工具自动生成，不要手改这一份文件。
            // 每个函数都是一次跨网络回调：子调用逐个走平台的守卫链（本轮叫停、权限档位、
            // 引用登记），与模型直接调用同一条判据。凭据走 X-Run-Token 请求头。
            // 用法：只有 console.log 出来的内容会回到对话里。
            // 失败：便捷函数 reject Error；callTool() resolve {'ok','output','error'} 不抛。
            'use strict';

            const BASE = @BASE@;
            const API_PATH = @API_PATH@;
            const TOKEN = @TOKEN@;
            const MAX_CALLS = @MAX_CALLS@;
            const MAX_PARALLEL = @MAX_PARALLEL@;
            const CALL_TIMEOUT = @CALL_TIMEOUT@;

            let _used = 0;
            let _active = 0;
            const _queue = [];

            class ToolError extends Error {}

            async function _slot() {
              if (_active >= MAX_PARALLEL) {
                await new Promise(resolve => _queue.push(resolve));
              }
              _active += 1;
            }

            function _free() {
              _active -= 1;
              const next = _queue.shift();
              if (next) next();
            }

            async function _post(tool, args) {
              _used += 1;
              if (_used > MAX_CALLS) {
                return { ok: false, output: '', error: `本轮子调用上限 ${MAX_CALLS} 已用满，请改用已经取回的结果作答` };
              }
              const ctrl = new AbortController();
              const timer = setTimeout(() => ctrl.abort(), CALL_TIMEOUT);
              await _slot();
              try {
                const resp = await fetch(BASE + API_PATH, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', 'X-Run-Token': TOKEN },
                  body: JSON.stringify({ tool, args: args || {} }),
                  signal: ctrl.signal,
                });
                const payload = await resp.json().catch(() => null);
                if (!payload) return { ok: false, output: '', error: `HTTP ${resp.status} 平台返回不可解析` };
                if (!payload.success) {
                  return { ok: false, output: '', error: String(payload.msg || '平台拒绝该子调用') };
                }
                const d = payload.data || {};
                return { ok: !!d.ok, output: d.output || '', error: d.error || '' };
              } catch (e) {
                const name = e && e.name ? e.name : 'Error';
                return { ok: false, output: '', error: `${name}: ${e && e.message ? e.message : e}` };
              } finally {
                clearTimeout(timer);
                _free();
              }
            }

            async function _require(tool, args) {
              const r = await _post(tool, args);
              if (!r.ok) throw new ToolError(`${tool}: ${r.error || '调用失败'}`);
              return r.output;
            }

            // 按名字调用本轮已挂载的任意工具，返回 {'ok','output','error'}（不抛异常）
            async function callTool(tool, args = {}) { return _post(tool, args); }

            // 本轮已发生的子调用次数（含被拒的），便于脚本自己收手
            function callsUsed() { return _used; }

            """;

    /** 生成 JavaScript（Node）模块 */
    public String renderJs(List<RunCodeBridge.Spec> specs, String callbackBase, String token,
                           int maxCalls, int maxParallel, long timeoutMs) {
        String head = PREAMBLE_JS
                .replace("@BASE@", lit(callbackBase))
                .replace("@API_PATH@", lit(CALLBACK_PATH))
                .replace("@TOKEN@", lit(token))
                .replace("@MAX_CALLS@", String.valueOf(Math.max(1, maxCalls)))
                .replace("@MAX_PARALLEL@", String.valueOf(Math.max(1, maxParallel)))
                .replace("@CALL_TIMEOUT@", String.valueOf(Math.max(1000, timeoutMs)));
        StringBuilder out = new StringBuilder(head);
        Set<String> defined = new LinkedHashSet<>(List.of("callTool", "callsUsed"));
        for (RunCodeBridge.Spec s : specs) {
            appendJsFunction(out, s, defined);
        }
        out.append("module.exports = { callTool, callsUsed");
        for (String n : defined) {
            if ("callTool".equals(n) || "callsUsed".equals(n)) continue;
            out.append(", ").append(n);
        }
        out.append(" };\n");
        return out.toString();
    }

    /**
     * JS 侧一个工具一个 async 函数：入参就是<b>一个对象</b>，键名与工具自己的参数名一致。
     * <p>因此 JS 这边没有 Python 那个"关键字做形参"的问题（对象键可以是 from、class），
     * 也就不需要改名与键映射——少一层映射就少一处可能错位的地方。
     * <p>必填项只写进注释：漏传交给工具自己报（与 Python 那份同一个理由）。
     */
    private void appendJsFunction(StringBuilder out, RunCodeBridge.Spec s, Set<String> defined) {
        if (s == null || s.name() == null || s.name().isBlank()) return;
        String fn = jsIdent(s.name());
        if (JS_RESERVED.contains(fn)) fn = fn + "_";
        if (!defined.add(fn)) {
            out.append("// 跳过 ").append(s.name()).append("：与已导出的函数同名，请用 callTool(\"")
                    .append(s.name()).append("\", { ... })\n\n");
            return;
        }
        out.append("// ").append(doc(s.cardLabel())).append("｜").append(doc(paramDoc(s,
                s.required() == null ? List.of() : s.required()))).append('\n');
        out.append("async function ").append(fn).append("(args = {}) {\n");
        out.append("  return _require(\"").append(s.name()).append("\", args);\n");
        out.append("}\n\n");
    }

    /** JS 标识符（数字不能作为首字符，其余与 Python 同规矩） */
    static String jsIdent(String name) {
        if (name == null || name.isBlank()) return "_";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c == '_') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && c >= '0' && c <= '9');
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** JavaScript 保留字（与 Python 关键字分开列：冲突面不同，合并一张表迟早漏项） */
    private static final Set<String> JS_RESERVED = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
            "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if",
            "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this",
            "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let",
            "static", "await", "arguments");

    /** 供执行侧选择文件名与解释器 */
    public enum Lang {
        /** Python 3：main.py + wenqu_tools.py */
        PYTHON("python", "main.py", "wenqu_tools.py", "python3 main.py"),
        /** JavaScript（Node）：main.js + wenqu_tools.js */
        JAVASCRIPT("javascript", "main.js", "wenqu_tools.js", "node main.js");

        private final String code;
        private final String scriptFile;
        private final String stubFile;
        private final String command;

        Lang(String code, String scriptFile, String stubFile, String command) {
            this.code = code;
            this.scriptFile = scriptFile;
            this.stubFile = stubFile;
            this.command = command;
        }

        public String code() {
            return code;
        }

        public String scriptFile() {
            return scriptFile;
        }

        public String stubFile() {
            return stubFile;
        }

        public String command() {
            return command;
        }

        /** 认不出按 Python（默认语言），不猜第二种 */
        public static Lang of(String v) {
            if (v == null) return PYTHON;
            String t = v.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.startsWith("js") || t.startsWith("node") || t.startsWith("javascript")) return JAVASCRIPT;
            return PYTHON;
        }
    }

    /**
     * 生成 Python 模块。
     *
     * @param specs        本轮可子调用的工具（已排除 run_code 自己与要人在场的工具）
     * @param callbackBase 平台对外地址（容器可达，如 {@code http://host.docker.internal:8090/ai}）
     * @param token        本次运行的一次性凭据
     * @param maxCalls     子调用总上限
     * @param maxParallel  脚本内并发上限
     * @param timeoutMs    单个子调用等待上限
     */
    public String render(List<RunCodeBridge.Spec> specs, String callbackBase, String token,
                         int maxCalls, int maxParallel, long timeoutMs) {
        String head = PREAMBLE
                .replace("@BASE@", lit(callbackBase))
                .replace("@API_PATH@", lit(CALLBACK_PATH))
                .replace("@TOKEN@", lit(token))
                .replace("@MAX_CALLS@", String.valueOf(Math.max(1, maxCalls)))
                .replace("@MAX_PARALLEL@", String.valueOf(Math.max(1, maxParallel)))
                .replace("@CALL_TIMEOUT@", round2(timeoutMs / 1000.0));
        StringBuilder out = new StringBuilder(head);
        Set<String> defined = new LinkedHashSet<>(List.of("call_tool", "calls_used", "_post", "_require"));
        for (RunCodeBridge.Spec s : specs) {
            appendToolFunction(out, s, defined);
        }
        return out.toString();
    }

    /**
     * 一个工具一个便捷函数：成功返回文本、失败抛 {@code ToolError}。
     * <p><b>生成的形参一律可选</b>（必填项只写进 docstring），漏传时由工具自己的校验给出"缺哪个参数"，
     * 而不是在 Python 签名层就抛 {@code TypeError}。差别不是洁癖：真机上模型按工具描述写
     * {@code wq.searchKnowledge(q)}，签名要求它连 topK 一起传，于是整段脚本在 {@code ThreadPoolExecutor}
     * 里炸掉、一行 print 都没有——报错从"少了什么参数"变成"脚本没输出"，模型只能猜。
     * 现在可选参数不传就不下发，必填项交给工具原样的校验文案，失败信息始终是工具那一层的话。
     */
    private void appendToolFunction(StringBuilder out, RunCodeBridge.Spec s, Set<String> defined) {
        if (s == null || s.name() == null || s.name().isBlank()) return;
        // 形参名 → 工具真实键名（关键字参数改名，其余一一对应）
        Map<String, String> localToKey = new java.util.LinkedHashMap<>();
        for (String p : s.params()) {
            if (p == null || p.isBlank()) continue;
            String local = pyIdent(p) + (PY_KEYWORDS.contains(p) ? "_" : "");
            if (!local.equals(pyIdent(local))) {
                out.append("# 跳过 ").append(s.name())
                        .append("：入参名 \"").append(p)
                        .append("\" 不是合法的 Python 标识符，请改用 call_tool(\"")
                        .append(s.name()).append("\", ...)\n\n");
                return;
            }
            localToKey.put(local, p);
        }
        List<String> required = s.required() == null ? List.of() : s.required();
        String fn = pyIdent(s.name());
        if (PY_KEYWORDS.contains(fn)) fn = fn + "_";
        if (!defined.add(fn)) {
            out.append("# 跳过 ").append(s.name()).append("：与已生成的函数同名，请用 call_tool(\"")
                    .append(s.name()).append("\", ...)\n\n");
            return;
        }
        if (localToKey.isEmpty()) {
            out.append("def ").append(fn).append("(**args):\n");
            out.append("    '''").append(doc(s.cardLabel())).append("（无声明入参，按关键字传即可）'''\n");
            out.append("    return _require(\"").append(s.name()).append("\", args)\n\n");
            return;
        }
        List<String> locals = new ArrayList<>(localToKey.keySet());
        locals.sort((a, b) -> {
            boolean ra = required.contains(localToKey.get(a));
            boolean rb = required.contains(localToKey.get(b));
            return ra == rb ? 0 : (ra ? -1 : 1);
        });
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < locals.size(); i++) {
            if (i > 0) sig.append(", ");
            // 全部可选（见本方法注释）：漏传必填项交给工具自己报，报错才有"缺哪个参数"这句话
            sig.append(locals.get(i)).append("=None");
        }
        sig.append(", **extra");
        out.append("def ").append(fn).append("(").append(sig).append("):\n");
        out.append("    '''").append(doc(s.cardLabel())).append("｜").append(doc(paramDoc(s, required)))
                .append("'''\n");
        out.append("    _a = {}\n");
        for (String p : locals) {
            String key = localToKey.get(p);
            out.append("    if ").append(p).append(" is not None: _a[\"").append(key)
                    .append("\"] = ").append(p).append('\n');
        }
        out.append("    _a.update(extra)\n");
        out.append("    return _require(\"").append(s.name()).append("\", _a)\n\n");
    }

    /** 参数说明写进 docstring：必填的标出来（签名里都可选，但工具会自己校验，模型得知道该传哪些） */
    private static String paramDoc(RunCodeBridge.Spec s, List<String> required) {
        Map<String, String> docs = s.docs() == null ? Map.of() : s.docs();
        if (s.params() == null || s.params().isEmpty()) return "无需入参";
        StringBuilder sb = new StringBuilder("入参 ");
        for (int i = 0; i < s.params().size(); i++) {
            String p = s.params().get(i);
            if (i > 0) sb.append("、");
            sb.append(p).append(required != null && required.contains(p) ? "(必填)" : "");
            String d = docs.get(p);
            if (d != null && !d.isBlank()) sb.append('(').append(doc(truncate(d, 60))).append(')');
        }
        return sb.toString();
    }

    /** Python 标识符化：不合规矩的字符换成下划线（工具名里出现 '-' 或点分时才用得到） */
    static String pyIdent(String name) {
        if (name == null || name.isBlank()) return "_";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c == '_') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && c >= '0' && c <= '9');
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /** 字符串字面量（Python 语法）：借 JSON 的转义规矩，保证不会出现截断语句的引号 */
    private static String lit(String s) {
        return com.alibaba.fastjson2.JSON.toJSONString(s == null ? "" : s);
    }

    /** 折进一行并去掉引号：docstring 里留着换行或引号会让整个模块语法错，而那看起来像网络问题 */
    private static String doc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'''", "'").replace("'", "").replace("\"", "'")
                .replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String round2(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
