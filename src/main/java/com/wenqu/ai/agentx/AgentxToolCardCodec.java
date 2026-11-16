package com.wenqu.ai.agentx;

import java.util.List;
import java.util.Map;

/**
 * 框架事件/工具卡文本的纯编解码函数集：无实例状态、无 I/O，输入进输出出。
 * <p>从 {@code AgentscopeChatEngine} 原样搬出（原先是它的一组 private static 方法），逐字不变。
 * 引擎以静态导入沿用原来的裸名调用，调用点零改动，行为完全等价。
 */
public final class AgentxToolCardCodec {
    private AgentxToolCardCodec() {}

    /**
     * 取工具名。两条事件把名字放在不同层级：{@code tool/requested} 在 {@code call.name}，
     * {@code tool/result} 在 {@code message.content[].name}——而 {@code message.name} 是<b>智能体名</b>，
     * 先取它会得到 "wenqu" 这种空白归属，工具卡就认不出是哪把工具收的尾。
     */
    /**
     * 工具结果是否失败。定性只认「有没有真的拿到结果」，分两层：
     * <p>① 结果带 {@code executionDetails}（execute 一类）：只看退出码。框架的
     * {@code ShellExecuteTool} 把<b>任何非 0 退出码</b>都标 {@code ToolResultState.ERROR}，
     * 但那是业务结果——命令跑了、退出码与输出都原样交给了模型，模型据此调整即可，
     * 卡片不该红；只有退出码缺失（执行器/沙盒没回包，结果根本没拿到）才是工具失败。
     * <p>② 其余工具：读 ToolResultBlock 结构化的 {@code state} 字段（JSON 值 {@code "error"}），
     * 退化路径才用旧 {@code Map.toString()} 拼出的 {@code STATE=ERROR} 形状兜底
     * （序列化实现一变就漏判，正是原实现的隐患）。executionDetails 存在但读不出 exitCode 键时
     * 同样退回 state 判定——判据缺字段时保持现状口径，不猜测。
     */
    public static boolean toolResultFailed(Map<String, Object> data) {
        Boolean byExitCode = exitCodeVerdict(data);
        if (byExitCode != null) return !byExitCode;
        Object top = data.get("state");
        if (top != null && "error".equalsIgnoreCase(String.valueOf(top))) return true;
        Object message = data.get("message");
        if (message instanceof Map<?, ?> msg) {
            Object mt = msg.get("state");
            if (mt != null && "error".equalsIgnoreCase(String.valueOf(mt))) return true;
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Object s = bm.get("state");
                        if (s != null && "error".equalsIgnoreCase(String.valueOf(s))) return true;
                    }
                }
            }
        }
        return str(data).toUpperCase().contains("STATE=ERROR");
    }

    /**
     * 按 {@code executionDetails.exitCode} 定性：{@code TRUE}=拿到了退出码（业务结果，含非 0）、
     * {@code FALSE}=带了 executionDetails 但没拿到码（工具失败）、{@code null}=没有
     * executionDetails（交给 state 判定）。
     */
    public static Boolean exitCodeVerdict(Map<String, Object> data) {
        for (Object block : resultBlocks(data)) {
            if (block instanceof Map<?, ?> bm && bm.get("executionDetails") instanceof Map<?, ?> d) {
                return d.get("exitCode") instanceof Number ? Boolean.TRUE : Boolean.FALSE;
            }
        }
        return null;
    }

    /** tool/result 载荷里可能挂着结果块的几层：顶层、message、message.content 里的块。 */
    public static java.util.List<Object> resultBlocks(Map<String, Object> data) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        out.add(data);
        Object message = data.get("message");
        if (message instanceof Map<?, ?> msg) {
            out.add(msg);
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) out.addAll(blocks);
        }
        return out;
    }

    /** 被权限档位/重复闸/步数闸拒掉的一步：结果不是干活的产出，不能算作前进。 */
    public static boolean toolResultDenied(Map<String, Object> data) {
        Object top = data.get("state");
        if (top != null && "denied".equalsIgnoreCase(String.valueOf(top))) return true;
        Object message = data.get("message");
        if (message instanceof Map<?, ?> msg) {
            Object mt = msg.get("state");
            if (mt != null && "denied".equalsIgnoreCase(String.valueOf(mt))) return true;
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Object s = bm.get("state");
                        if (s != null && "denied".equalsIgnoreCase(String.valueOf(s))) return true;
                    }
                }
            }
        }
        return false;
    }

    public static String toolNameOf(Object node) {
        if (!(node instanceof Map<?, ?> map)) return "";
        Object content = map.get("content");
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("name") != null) return String.valueOf(bm.get("name"));
            }
        }
        Object name = map.get("name");
        return name == null ? "" : String.valueOf(name);
    }

    /** 从 tool_use 块里取本次调用的入参（确认卡要展示「要执行什么」，否则用户只能凭空批）。 */
    public static Object toolInputOf(Object node) {
        if (node instanceof Map<?, ?> map) {
            Object input = map.get("input");
            if (input != null) return input;
            Object content = map.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm && bm.get("input") != null) return bm.get("input");
                }
            }
        }
        return null;
    }

    /** 框架原生工具里会被污点护栏强制确认的那三把；其余（MCP/脚本等）不归它管。 */
    public static AgentxNativeToolGuard.Kind nativeGuardKind(String tool) {
        if (FrameworkToolLabels.FilesystemToolNames.EXECUTE.equals(tool)) return AgentxNativeToolGuard.Kind.EXEC;
        if (FrameworkToolLabels.FilesystemToolNames.WRITE_FILE.equals(tool)) return AgentxNativeToolGuard.Kind.WRITE;
        if (FrameworkToolLabels.FilesystemToolNames.EDIT_FILE.equals(tool)) return AgentxNativeToolGuard.Kind.EDIT;
        return null;
    }

    /**
     * 一次调用的标识：{@code tool/requested} 在顶层带 {@code toolCallId}，{@code tool/result} 只在
     * 结果块里（{@code content[].id}，与它对应的那个 ToolUseBlock 同一个 id）。两侧都拿不到时返回空串，
     * 调用方按 {@link AgentxTurnRecording#toolCallKey} 退回按工具名配对。
     */
    public static String toolCallIdOf(Map<String, Object> data) {
        Object top = data.get("toolCallId");
        if (top != null && !String.valueOf(top).isBlank()) return String.valueOf(top);
        if (data.get("message") instanceof Map<?, ?> msg && msg.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("name") != null && bm.get("id") != null
                        && !String.valueOf(bm.get("id")).isBlank()) {
                    return String.valueOf(bm.get("id"));
                }
            }
        }
        return "";
    }

    /**
     * 从 {@code tool/result} 事件里取出工具输出文本（{@code message.content[].output[].text}），
     * 截断到 2000 字符——与手写侧 SSE 的输出摘要同量级，整份塞进卡片会把会话行撑爆。
     */
    public static String toolResultText(Map<String, Object> data) {
        Object message = data.get("message");
        if (!(message instanceof Map<?, ?> msg)) return null;
        if (!(msg.get("content") instanceof List<?> blocks)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> bm)) continue;
            Object output = bm.get("output");
            if (output instanceof List<?> outs) {
                for (Object o : outs) {
                    if (o instanceof Map<?, ?> om) {
                        Object t = om.get("text") != null ? om.get("text") : om.get("content");
                        if (t != null) sb.append(t);
                    }
                }
            } else if (bm.get("text") != null) {
                sb.append(bm.get("text"));
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return null;
        // 工具卡展示的是结果本身，不是防护措辞：信封/数据声明只服务模型上下文
        s = com.wenqu.ai.util.UntrustedContent.displayText(s);
        if (s.isEmpty()) return null;
        return s.length() > 2000 ? s.substring(0, 2000) + "…（已截断）" : s;
    }

    public static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 入参布尔位：模型偶尔把布尔写成字符串（"true"），两种形状都认，写歪了按「不勾」处理 */
    public static boolean boolOf(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }

    public static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    public static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // **JSON 规范要求 U+0000–U+001F 全部转义**，不只是 \n \r \t。
                    // 控制字符（如 U+001A）漏出去会让整份 done 载荷变成非法 JSON，下游拿不到来源清单，
                    // 且这类字符在终端里显示不出来，排查成本极高。
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
