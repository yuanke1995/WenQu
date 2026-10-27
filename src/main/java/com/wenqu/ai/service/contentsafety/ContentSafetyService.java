package com.wenqu.ai.service.contentsafety;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.mapper.ContentRuleMapper;
import com.wenqu.ai.model.ContentRule;
import com.wenqu.ai.service.AuditService;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容安全网关（Phase 3 · P12）。
 *
 * <p><b>两道闸的位置</b>：
 * <ul>
 *   <li><b>输入闸</b>在问答入口（{@code ChatService#chat} 与运行中插话）——命中 block 规则就不发起这一轮，
 *       一次模型调用都不花，用户当场看到「哪条规则拦的」；</li>
 *   <li><b>出口闸</b>在<b>两处模型出口的流式响应上</b>（框架路 {@code MeteredModel}、Spring AI 路
 *       {@code DynamicOpenAiChatModel}），与 Phase 2 的配额闸同形、同一份判据。挂在模型出口而不是
 *       推送出口，是因为要拦的东西必须在<b>离开供应商那一刻</b>就被处置：下游有正文缓冲、过程台账、
 *       框架会话状态、事件日志、接流快照五条拷贝，在推送侧拦等于违规内容照样落库，只是不显示。</li>
 * </ul>
 *
 * <p><b>辅助调用不判</b>：上下文压缩摘要、长期记忆提炼、引用自检这类调用的产物不直接给用户看，
 * 掐断它们只会让上下文更糟。判据是 kind 清单（{@link #VISIBLE_KINDS}），与配额同一档口径。
 *
 * <p><b>规则是数据不是代码</b>：拦截词表属于部署方的合规判断，全在 {@code c_ai_content_rule} 里由管理员维护；
 * 本类不内置任何一条拦截词。外部服务（{@code contentSafety.provider=generic}）是<b>追加</b>的第二层，
 * 内置规则永远先跑；外部服务故障时按内置结论放行并记审计——让合规检查器的宕机把整个问答陪葬掉，
 * 是更坏合规。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ContentSafetyService {

    /** 规则缓存刷新周期（毫秒）：跨副本改规则最多晚这么久生效，与配额用量的 30 秒缓存同一档 */
    private static final long RULE_REFRESH_MS = 15_000;
    /**
     * 出口闸一次判定的回看窗口上限（字符）。跨块的命中靠「每次多带一段上一轮的尾巴一起判」收口；
     * 尾巴取多长由 {@code contentSafety.outputHoldChars} 定，窗口上限只是兜住正则病态回溯的成本。
     */
    private static final int MAX_LOOKBACK = 512;
    /** 片段进审计的长度上限（合规要看得到证据，但不能把整篇违规文抄进审计表） */
    private static final int SNIPPET_CHARS = 120;
    /** 外部服务故障的告警节流（60 秒一条，别把日志刷满） */
    private static final long PROVIDER_LOG_MS = 60_000;

    /** 判用户可见三条路：对话 / 子智能体 / 工作流（与配额闸同一份 kind 清单口径） */
    private static final List<String> VISIBLE_KINDS = List.of("chat", "subagent", "workflow");

    private final ContentRuleMapper ruleMapper;
    private final ConfigService configService;
    private final AuditService audit;
    private final List<ContentSafetyProvider> providers;

    /**
     * 编译后的规则表（按侧分开缓存，判定路径上零编译）。
     * <p>规则缓存按<b>租户</b>分格（Phase 4 多租户）：{@code c_ai_content_rule} 是租户归属的表，
     * 共用一份全局缓存等于「谁最后刷新算谁的」——A 租户的屏蔽词会在 15 秒内审 B 租户的回答，
     * 反向也一样（B 的合规词表在 A 这里变成空表＝该拦的没拦）。两类错都是静默的，只能从键设计上堵掉。
     */
    private record RuleSet(List<Compiled> input, List<Compiled> output, List<String> broken, long loadedAt) {
    }

    /** 未绑定线程单独一格：不把系统线程读到的全平台行喂给任何租户 */
    private static final String UNBOUND_TENANT = "\u0000unbound";
    private final Map<String, RuleSet> ruleSets = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong lastProviderWarn = new AtomicLong();

    public ContentSafetyService(ContentRuleMapper ruleMapper, ConfigService configService,
                               AuditService audit, List<ContentSafetyProvider> providers) {
        this.ruleMapper = ruleMapper;
        this.configService = configService;
        this.audit = audit;
        this.providers = providers == null ? List.of() : providers;
    }

    /** 总开关（关掉即两道闸都不判，规则表与页面照常在） */
    public boolean enabled() {
        return configService.getBoolean("contentSafety.enabled", true);
    }

    /** 输出侧是否有任何 block 规则或外部闸在跑：没有就走零开销的直通路径 */
    public boolean outputActive() {
        RuleSet set = ruleSet();
        return enabled() && (!set.output().isEmpty() || remoteOnOutput());
    }

    public boolean remoteOnInput() {
        return remoteActive() && configService.getBoolean("contentSafety.remoteOnInput", true);
    }

    public boolean remoteOnOutput() {
        return remoteActive() && configService.getBoolean("contentSafety.remoteOnOutput", false);
    }

    private boolean remoteActive() {
        return enabled() && provider() != null;
    }

    /** 配置选中的外部服务；local（或配了不认识的 id）返回 null＝只用内置规则 */
    private ContentSafetyProvider provider() {
        String id = configService.get("contentSafety.provider");
        if (id == null || id.isBlank() || "local".equalsIgnoreCase(id.trim())) return null;
        for (ContentSafetyProvider p : providers) {
            if (p.id().equalsIgnoreCase(id.trim())) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- 输入闸

    /**
     * 判一句用户打进来的话。
     *
     * @return null=放行；否则是给用户看的拒绝文案（点名哪条规则）
     */
    public String checkInput(String userId, String sessionId, String text) {
        if (!enabled() || text == null || text.isBlank()) return null;
        String scan = cap(text);
        for (Compiled c : rules(Stage.INPUT)) {
            Hit hit = c.find(scan, 0, scan.length());
            if (hit == null) continue;
            if (c.block()) {
                audit.record("[AUDIT] 内容安全拦截 侧=input 处置=拒绝 规则={} 类目={} uid={} session={} 片段={}",
                        c.rule.getName(), nullToEmpty(c.rule.getCategory()), nullToEmpty(userId),
                        nullToEmpty(sessionId), snippet(scan, hit));
                return refusalOf(c.rule.getName(), null);
            }
            audit.warn("[AUDIT] 内容安全放行告警 侧=input 规则={} 类目={} uid={} session={} 片段={}",
                    c.rule.getName(), nullToEmpty(c.rule.getCategory()), nullToEmpty(userId),
                    nullToEmpty(sessionId), snippet(scan, hit));
        }
        if (remoteOnInput()) {
            SafetyVerdict v = remoteCheck(scan, "input", userId, sessionId);
            if (v != null && v.blocked()) {
                audit.record("[AUDIT] 内容安全拦截 侧=input 处置=拒绝 规则=外部服务 类目={} uid={} session={} 原因={}",
                        nullToEmpty(v.category()), nullToEmpty(userId), nullToEmpty(sessionId),
                        nullToEmpty(v.reason()));
                return refusalOf("内容安全服务", v.reason());
            }
        }
        return null;
    }

    /** 拒绝文案：点名规则，让用户知道改哪句话能过（只说「内容不合规」是没法追问的） */
    private String refusalOf(String ruleName, String reason) {
        String tpl = configService.get("contentSafety.inputRefusal");
        String base = tpl == null || tpl.isBlank()
                ? "这一问题涉及平台内容安全策略拦截的规则「{规则}」，请换个说法再问。"
                : tpl;
        String text = base.replace("{规则}", ruleName == null ? "" : ruleName);
        return reason == null || reason.isBlank() ? text : text + "（" + reason + "）";
    }

    /** 回答被截断时追加的那句标注（与「已停止本轮」「回答在此处被中断」同一族尾注） */
    public String outputSuffix() {
        String tpl = configService.get("contentSafety.outputSuffix");
        return tpl == null || tpl.isBlank()
                ? "\n\n> ⚠ 回答触及平台内容安全策略，已在该处截断。"
                : tpl;
    }

    // ---------------------------------------------------------------- 出口闸

    /**
     * 起一道本轮模型调用的出口闸。
     * <p>不判的场景（总开关关闭 / 该 kind 不判 / 一条规则都没有且没接外部服务）返回
     * {@link OutputGate#noop()}，调用方拿到的是零成本直通。
     *
     * @param kind 本次模型调用的用途（chat/subagent/workflow/memory/compress…）
     */
    public OutputGate outputGate(String userId, String sessionId, String kind) {
        if (!enabled()) return OutputGate.noop();
        if (kind != null && !kind.isBlank() && !VISIBLE_KINDS.contains(kind)) return OutputGate.noop();
        List<Compiled> rules = rules(Stage.OUTPUT);
        boolean remote = remoteOnOutput();
        if (rules.isEmpty() && !remote) return OutputGate.noop();
        RemoteCheck remoteFn = remote ? seg -> remoteCheck(seg, "output", userId, sessionId) : null;
        return new OutputGate(rules, remoteFn, audit, userId, sessionId,
                Math.max(0, configService.getInt("contentSafety.outputHoldChars", 24)),
                Math.max(200, configService.getInt("contentSafety.remoteCheckChars", 400)),
                Math.max(1000, configService.getInt("contentSafety.maxScanChars", 20000)),
                configService.getBoolean("contentSafety.scanThinking", true),
                outputSuffix());
    }

    /** 出口闸对外部服务的唯一依赖（按段送检，返回 null＝没接或这次失败） */
    private interface RemoteCheck {
        SafetyVerdict check(String segment);
    }

    /** 出口闸：把一次模型调用的流式文本按规则改写，命中即在命中处收尾。 */
    public static final class OutputGate {

        private static final OutputGate NOOP = new OutputGate();

        /** 正文与思考各一本累计（同一份规则、各自独立判定） */
        private final Channel text;
        private final Channel thinking;
        private final boolean passive;
        private final boolean scanThinking;

        private OutputGate() {
            this.passive = true;
            this.scanThinking = false;
            this.text = null;
            this.thinking = null;
        }

        private OutputGate(List<Compiled> rules, RemoteCheck remote, AuditService audit,
                           String userId, String sessionId, int holdChars, int remoteChars,
                           int maxScan, boolean scanThinking, String suffix) {
            this.passive = false;
            this.scanThinking = scanThinking;
            this.text = new Channel("text", rules, remote, audit, userId, sessionId,
                    holdChars, remoteChars, maxScan, suffix);
            this.thinking = new Channel("thinking", rules, remote, audit, userId, sessionId,
                    holdChars, remoteChars, maxScan, suffix);
        }

        public static OutputGate noop() {
            return NOOP;
        }

        /** 直通实例（不参与判定）：调用方据此跳过重建响应块的开销 */
        public boolean isPassive() {
            return passive;
        }

        /** 是否还压着没放出去的文本（末块要把尾巴一起交出去） */
        public boolean hasHeld() {
            if (passive) return false;
            return text.hasHeld() || (scanThinking && thinking.hasHeld());
        }

        /**
         * 交一段增量进闸，返回此刻可以放出去的那段（可能为空串）。
         *
         * @return null 表示这一段（及此后所有）文本已被拦下，调用方应丢弃该块
         */
        public String transform(String channel, String delta) {
            if (passive || delta == null || delta.isEmpty()) return delta;
            boolean isThinking = "thinking".equals(channel);
            if (isThinking && !scanThinking) return delta;
            return (isThinking ? thinking : text).feed(delta);
        }

        /** 本次模型调用收尾：把留存未判的尾巴放出去（已在命中后收尾则返回空串） */
        public String drain(String channel) {
            if (passive) return "";
            boolean isThinking = "thinking".equals(channel);
            if (isThinking && !scanThinking) return "";
            return (isThinking ? thinking : text).drain();
        }
    }

    /**
     * 一条通道（正文或思考）的增量判定器。
     *
     * <p><b>为什么要留尾巴</b>：违规词可能正好被切成两块下发（如关键词「内容安全」先到「内容」、
     * 再到「安全」）。所以每次只放行到「已判定长度 − holdChars」，把末尾 holdChars 个字符压着，
     * 下一块带着一起判；流结束时再统一放出。holdChars 至少要等于最长关键词的长度，
     * 取不到时按配置的固定值办（设置页 tips 里写明这个依据）。
     */
    private static final class Channel {
        private final String name;
        private final List<Compiled> rules;
        /** 实际压住不放的长度：配置值与「最长关键词」取大，再按 {@link #MAX_LOOKBACK} 收顶 */
        private final int hold;
        private final int remoteChars;
        private final int maxScan;
        /** 外部服务送检口（null＝这一道闸不接外部服务） */
        private final RemoteCheck remote;
        private final AuditService audit;
        private final String userId;
        private final String sessionId;
        private final String suffix;
        private final StringBuilder buf = new StringBuilder();
        /** 已放出的长度（buf 前 released 个字符已交下游） */
        private int released;
        /** 已判定到的长度（窗口判到这儿为止，之后的还没判） */
        private int scanned;
        private boolean blocked;
        private boolean finished;

        private Channel(String name, List<Compiled> rules, RemoteCheck remote, AuditService audit,
                        String userId, String sessionId, int holdChars, int remoteChars, int maxScan,
                        String suffix) {
            this.name = name;
            this.rules = rules;
            this.hold = Math.min(MAX_LOOKBACK, Math.max(holdChars, longestKeyword(rules)));
            this.remoteChars = remoteChars;
            this.maxScan = maxScan;
            this.remote = remote;
            this.audit = audit;
            this.userId = userId;
            this.sessionId = sessionId;
            this.suffix = suffix;
        }

        private static int longestKeyword(List<Compiled> rules) {
            int max = 0;
            for (Compiled c : rules) max = Math.max(max, c.maxKeywordChars);
            return max;
        }

        /** 压着未放的长度（尾巴） */
        boolean hasHeld() {
            return !blocked && !finished && buf.length() > released;
        }

        String feed(String delta) {
            if (blocked) return null;
            if (buf.length() >= maxScan) {
                // 超出扫描上限：不再逐块判（长回答全量重扫会把 CPU 吃掉），直接放行余量并记一次告警
                if (scanned < maxScan) {
                    scanned = buf.length();
                    audit.warn("[AUDIT] 内容安全扫描触顶 侧=output 通道={} uid={} session={} 上限={}",
                            name, nullToEmpty(userId), nullToEmpty(sessionId), maxScan);
                }
                buf.append(delta);
                return delta;
            }
            buf.append(delta);
            int from = Math.max(0, scanned - hold);
            int to = Math.min(buf.length(), maxScan);
            Hit hit = firstHit(rules, buf, from, to);
            scanned = to;
            if (hit != null) {
                blocked = true;
                String safe = hit.start() > released ? buf.substring(released, hit.start()) : "";
                released = buf.length();
                String notice = suffix + "（规则：" + hit.rule().getName() + "）";
                audit.record("[AUDIT] 内容安全截断 侧=output 处置=截断 通道={} 规则={} 类目={} uid={} session={} 片段={}",
                        name, hit.rule().getName(), nullToEmpty(hit.rule().getCategory()),
                        nullToEmpty(userId), nullToEmpty(sessionId), snippet(buf.toString(), hit));
                return safe.isEmpty() ? notice : safe + notice;
            }
            if (remote != null && buf.length() - released >= remoteChars + hold) {
                String seg = buf.substring(released, buf.length() - hold);
                SafetyVerdict v = remote.check(seg);
                if (v != null && v.blocked()) {
                    blocked = true;
                    released = buf.length();
                    audit.record("[AUDIT] 内容安全截断 侧=output 处置=截断 通道={} 规则=外部服务 类目={} uid={} session={} 原因={}",
                            name, nullToEmpty(v.category()), nullToEmpty(userId), nullToEmpty(sessionId),
                            nullToEmpty(v.reason()));
                    return suffix + "（外部内容安全服务：" + nullToEmpty(v.reason()) + "）";
                }
                if (seg.isEmpty()) return "";
                released += seg.length();
                return seg;
            }
            int allow = buf.length() - hold;
            if (allow <= released) return "";
            String out = buf.substring(released, allow);
            released = allow;
            return out;
        }

        /** 流收尾：把压着的尾巴判一遍再放出（已拦下则无内容可放） */
        String drain() {
            if (blocked || finished) return "";
            finished = true;
            if (buf.length() > scanned) {
                int from = Math.max(0, scanned - hold);
                Hit hit = firstHit(rules, buf, from, buf.length());
                scanned = buf.length();
                if (hit != null) {
                    blocked = true;
                    String safe = hit.start() > released ? buf.substring(released, hit.start()) : "";
                    released = buf.length();
                    audit.record("[AUDIT] 内容安全截断 侧=output 处置=截断 通道={} 规则={} 类目={} uid={} session={} 片段={}",
                            name, hit.rule().getName(), nullToEmpty(hit.rule().getCategory()),
                            nullToEmpty(userId), nullToEmpty(sessionId), snippet(buf.toString(), hit));
                    return safe + suffix + "（规则：" + hit.rule().getName() + "）";
                }
            }
            if (released >= buf.length()) return "";
            String out = buf.substring(released);
            released = buf.length();
            return out;
        }
    }

    // ---------------------------------------------------------------- 规则表

    /** 判定侧 */
    public enum Stage {
        INPUT, OUTPUT;

        /** 表里 stage 的取值：input / output / both */
        public boolean covers(String stage) {
            if (stage == null || stage.isBlank()) return true;   // 脏数据按「两侧都判」处理，别静默漏
            String s = stage.trim().toLowerCase(Locale.ROOT);
            return s.equals("both") || s.equals(name().toLowerCase(Locale.ROOT));
        }
    }

    /** 一条规则编译后的样子 */
    private static final class Compiled {
        final ContentRule rule;
        final Pattern regex;
        final List<String> keywords;
        final boolean ci;
        /** 关键词里最长的一条（决定出口闸至少要留多长的尾巴） */
        final int maxKeywordChars;

        Compiled(ContentRule rule, Pattern regex, List<String> keywords, boolean ci) {
            this.rule = rule;
            this.regex = regex;
            this.keywords = keywords;
            this.ci = ci;
            int max = 0;
            for (String k : keywords) max = Math.max(max, k.length());
            this.maxKeywordChars = max;
        }

        boolean block() {
            return !"warn".equalsIgnoreCase(nullToEmpty(rule.getAction()));
        }

        /** 在 text 的 [from,to) 区间里找这条规则的第一处命中 */
        Hit find(String text, int from, int to) {
            if (to <= from) return null;
            String window = text.substring(from, to);
            if (regex != null) {
                Matcher m = regex.matcher(window);
                return m.find() ? new Hit(rule, m.group(), from + m.start(), from + m.end()) : null;
            }
            String hay = ci ? window.toLowerCase(Locale.ROOT) : window;
            for (String kw : keywords) {
                int at = hay.indexOf(kw);
                if (at >= 0) return new Hit(rule, window.substring(at, at + kw.length()), from + at, from + at + kw.length());
            }
            return null;
        }
    }

    /** 一处命中 */
    record Hit(ContentRule rule, String matched, int start, int end) {
    }

    private static Hit firstHit(List<Compiled> rules, StringBuilder buf, int from, int to) {
        if (rules.isEmpty() || to <= from) return null;
        String window = buf.substring(from, to);
        for (Compiled c : rules) {
            Hit h = c.find(window, 0, window.length());
            if (h != null) return new Hit(h.rule(), h.matched(), from + h.start(), from + h.end());
        }
        return null;
    }

    /** 取某一侧生效中的规则（本租户那一格，最多 15 秒重读一次表） */
    private List<Compiled> rules(Stage stage) {
        RuleSet set = ruleSet();
        return stage == Stage.INPUT ? set.input() : set.output();
    }

    private RuleSet ruleSet() {
        String t = com.wenqu.ai.util.TenantContext.current();
        return ruleSet(t == null ? UNBOUND_TENANT : t);
    }

    private RuleSet ruleSet(String key) {
        long now = System.currentTimeMillis();
        RuleSet hit = ruleSets.get(key);
        if (hit != null && now - hit.loadedAt() < RULE_REFRESH_MS) return hit;
        return reload(key, now);
    }

    private synchronized RuleSet reload(String key, long now) {
        RuleSet hit = ruleSets.get(key);
        if (hit != null && now - hit.loadedAt() < RULE_REFRESH_MS) return hit;   // 等锁期间别人已补上
        // 读表失败也推进时间戳：别让一条坏 SQL 让每次问答都去重读
        if (hit == null) {
            ruleSets.put(key, new RuleSet(List.of(), List.of(), List.of(), now));
        } else {
            ruleSets.put(key, new RuleSet(hit.input(), hit.output(), hit.broken(), now));
        }
        try {
            List<ContentRule> rows = ruleMapper.selectList(new QueryWrapper<ContentRule>()
                    .eq("enabled", 1).orderByAsc("priority").orderByAsc("create_time"));
            List<Compiled> in = new ArrayList<>();
            List<Compiled> out = new ArrayList<>();
            List<String> broken = new ArrayList<>();
            for (ContentRule r : rows) {
                Compiled c = compile(r, broken);
                if (c == null) continue;
                if (Stage.INPUT.covers(r.getStage())) in.add(c);
                if (Stage.OUTPUT.covers(r.getStage())) out.add(c);
            }
            RuleSet set = new RuleSet(List.copyOf(in), List.copyOf(out), List.copyOf(broken), now);
            ruleSets.put(key, set);
            return set;
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 内容安全规则读取失败（沿用上一份缓存）: {}", e.toString());
            return ruleSets.get(key);
        }
    }

    /** 强制重读规则表（保存/删除规则后调用，本副本立刻生效；各租户那一格一起丢） */
    public void invalidateRules() {
        ruleSets.clear();
        ruleSet();
    }

    private static Compiled compile(ContentRule r, List<String> broken) {
        boolean ci = r.getCaseSensitive() == null || r.getCaseSensitive() == 0;
        String raw = r.getPattern() == null ? "" : r.getPattern();
        if ("regex".equalsIgnoreCase(nullToEmpty(r.getMatchType()))) {
            try {
                Pattern p = Pattern.compile(ci
                        ? raw
                        : "(?i)" + raw);
                return new Compiled(r, p, List.of(), ci);
            } catch (PatternSyntaxException e) {
                broken.add("规则「" + r.getName() + "」正则无法编译，已跳过：" + e.getDescription());
                log.warn("[FAIL-LOUD] 内容安全规则正则非法: rule={} pattern={} {}", r.getName(), raw, e.getDescription());
                return null;
            }
        }
        List<String> kws = new ArrayList<>();
        for (String part : raw.split("[,，\\n]+")) {
            String k = ci ? part.trim().toLowerCase(Locale.ROOT) : part.trim();
            if (!k.isEmpty()) kws.add(k);
        }
        if (kws.isEmpty()) {
            broken.add("规则「" + r.getName() + "」没有有效的关键词，已跳过");
            return null;
        }
        return new Compiled(r, null, kws, ci);
    }

    /** 规则清单（含未启用的，管理页用） */
    public List<ContentRule> listRules() {
        return ruleMapper.selectList(new QueryWrapper<ContentRule>().orderByAsc("priority").orderByAsc("create_time"));
    }

    public ContentRule save(ContentRule rule) {
        normalize(rule);
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setCreateTime(null);
            rule.setUpdateTime(null);
            ruleMapper.insert(rule);
        } else {
            rule.setUpdateTime(null);
            ruleMapper.updateById(rule);
        }
        invalidateRules();
        audit.record("[AUDIT] 内容安全规则保存 规则={} 侧={} 方式={} 动作={} 启用={}",
                rule.getName(), rule.getStage(), rule.getMatchType(), rule.getAction(), rule.getEnabled());
        return rule;
    }

    public void delete(String id) {
        ContentRule r = id == null || id.isBlank() ? null : ruleMapper.selectById(id);
        if (r != null) ruleMapper.deleteById(id);
        invalidateRules();
        audit.record("[AUDIT] 内容安全规则删除 规则={}", r == null ? id : r.getName());
    }

    private static void normalize(ContentRule r) {
        if (r.getStage() == null || r.getStage().isBlank()) r.setStage("both");
        if (r.getMatchType() == null || r.getMatchType().isBlank()) r.setMatchType("keyword");
        if (r.getAction() == null || r.getAction().isBlank()) r.setAction("block");
        if (r.getCaseSensitive() == null) r.setCaseSensitive(0);
        if (r.getEnabled() == null) r.setEnabled(1);
        if (r.getPriority() == null) r.setPriority(100);
        if (r.getName() == null || r.getName().isBlank()) {
            String p = r.getPattern() == null ? "" : r.getPattern();
            r.setName("规则 " + (p.length() <= 12 ? p : p.substring(0, 12) + "…"));
        }
    }

    /**
     * 试跑：给一段文本与侧别，返回命中的规则清单（不写审计、不改任何东西）。
     * <p>管理员改词表时最需要这一步——不试跑就只能靠真发一句话去猜哪条规则咬住了。
     */
    public List<Map<String, Object>> dryRun(String text, String stage) {
        List<Map<String, Object>> hits = new ArrayList<>();
        if (text == null || text.isBlank() || !enabled()) return hits;
        String scan = cap(text);
        for (Compiled c : rules("input".equalsIgnoreCase(nullToEmpty(stage)) ? Stage.INPUT : Stage.OUTPUT)) {
            Hit h = c.find(scan, 0, scan.length());
            if (h != null) {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("rule", c.rule.getName());
                one.put("category", nullToEmpty(c.rule.getCategory()));
                one.put("action", c.block() ? "block" : "warn");
                one.put("outcome", c.block() ? "拦下" : "只告警");
                one.put("matched", snippet(h));
                hits.add(one);
            }
        }
        return hits;
    }

    /** 坏正则清单（设置页/管理页显示，避免「配了规则却静默不生效」） */
    public List<String> brokenRules() {
        return ruleSet().broken();
    }

    /** 生效规则计数（诊断用：管理页要能说清「此刻两侧各有几条在跑」——说的是本租户那一份） */
    public Map<String, Object> status() {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        RuleSet set = ruleSet();
        out.put("enabled", enabled());
        out.put("inputRules", set.input().size());
        out.put("outputRules", set.output().size());
        out.put("provider", nullToEmpty(configService.get("contentSafety.provider")));
        out.put("remoteOnInput", remoteOnInput());
        out.put("remoteOnOutput", remoteOnOutput());
        out.put("broken", set.broken());
        return out;
    }

    // ---------------------------------------------------------------- 外部服务

    private SafetyVerdict remoteCheck(String text, String stage, String userId, String sessionId) {
        ContentSafetyProvider p = provider();
        if (p == null) return null;
        try {
            return p.check(new ContentSafetyProvider.SafetyRequest(cap(text), stage, userId, sessionId,
                    configService.get("contentSafety.baseUrl"), configService.get("contentSafety.apiKey"),
                    configService.getInt("contentSafety.timeoutMs", 3000)));
        } catch (Exception e) {
            // 外部检查器宕机不放行违规内容的前提是「内置规则已经判过」；这一层只是追加的第二道闸，
            // 它挂了就在这一层失去一份保险，但整轮问答陪葬不是更好的合规结果。必须留痕。
            long last = lastProviderWarn.get();
            long now = System.currentTimeMillis();
            if (now - last >= PROVIDER_LOG_MS && lastProviderWarn.compareAndSet(last, now)) {
                log.warn("[FAIL-LOUD] 外部内容安全检查失败（按内置规则结论继续）: provider={} {}", p.id(), e.toString());
            }
            audit.warn("[AUDIT] 内容安全外部服务失败 服务={} 侧={} 原因={}", p.id(), stage, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private String cap(String s) {
        int max = Math.max(1000, configService.getInt("contentSafety.maxScanChars", 20000));
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String snippet(String text, Hit hit) {
        int from = Math.max(0, hit.start() - 20);
        int to = Math.min(text.length(), hit.end() + 40);
        String s = text.substring(from, to).replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_CHARS ? s : s.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String snippet(Hit hit) {
        String s = hit.matched() == null ? "" : hit.matched().replaceAll("\\s+", " ").trim();
        return s.length() <= SNIPPET_CHARS ? s : s.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
