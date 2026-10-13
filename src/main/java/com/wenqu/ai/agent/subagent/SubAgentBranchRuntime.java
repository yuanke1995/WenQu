package com.wenqu.ai.agent.subagent;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.DocumentMetaCache;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.SubAgentOrchestrator;
import com.wenqu.ai.service.CollectingSseEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子智能体分支的簿记（P6 刀 3b，自 {@code ChatService} 抽出）：派一支、收一支的回报、
 * 把子轮用过的引用与图片回填进父轮编号表、给下一层准备继承用的父轮上下文。
 * <p><b>「跑一整轮」仍然是交进来的函数</b>（{@link TurnRunner}）。分支是一条完整的问答轮，
 * 而流水线还在问答服务里（刀 5 才搬进门面）——注入进来就是运行时候选之间的一根环。
 * 这也是卡里拦路点 2 的真正判据：<b>一族能不能做成 bean，看它要不要回头调用问答入口</b>；
 * 子调用通道（刀 3a）不需要，所以它是 bean；这一族需要，所以只有簿记搬得动。
 * <p>回填为什么要在这里做而不能在编排处做：编排那一刻父轮的编号还没定稿，先编必撞号。
 * 范围外的那几块只摘编号不注册——库范围是能力边界，不能因为「分支跑在另一个会话里」绕过去。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SubAgentBranchRuntime {

    /** 跑一整轮的口子：由问答侧按轮传入（通常是 {@code ChatService::chat}） */
    public interface TurnRunner {
        void run(ChatCommand cmd, SseEmitter sink);
    }

    private final ConfigService configService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;
    private final DocumentMetaCache documentMetaCache;
    private final ChatRunRuntime runtime;

    public SubAgentBranchRuntime(ConfigService configService,
                                SessionService sessionService,
                                ArtifactService artifactService,
                                DocumentMetaCache documentMetaCache,
                                ChatRunRuntime runtime) {
        this.configService = configService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.documentMetaCache = documentMetaCache;
        this.runtime = runtime;
    }

    /**
     * 派分支时带过去的父轮上下文（对标 DeepSeek Harness 的 fork-in-process：分支继承父历史）。
     * <p>不做这件事的代价很具体：主智能体问"上面那份流程里第三步是什么，把它写成脚本"，分支只拿到
     * 后半句，检索不到任何东西，回报回来是废的——委派越常见，这个缺口越像 bug。
     * <p>预算<b>逐层递减</b>（{@code agent.subagentBriefChars / depth}）：孙辈不需要整段父历史，
     * 层数一深就变成每层都重复搬一遍全文，那是纯成本。
     * <p>历史取不到（{@code getRecentHistory} 明确返回 null=读失败）时只带问题，不谎称"没有历史"。
     */
    public String branchBrief(String sessionId, String question, int depth) {
        int nextDepth = depth + 1;
        int budget = Math.max(200, configService.getInt("agent.subagentBriefChars", 1200) / nextDepth);
        // 三档继承（agent.subagentBriefMode）：brief=最近 4 条、各截 220 字（本项引入前的行为，逐字不变）；
        // recent=按配置的轮数带、每条放宽；full=尽量整条带、只在总预算处夹。
        // 为什么默认还是 brief：分支是按支数乘成本的，「多带前情」在单支上是收益、在多支上是纯加钱，
        // 而且带多了会把子智能体自己的知识库范围挤没——所以放开必须是显式选择。
        String mode = configService.get("agent.subagentBriefMode");
        mode = mode == null ? "brief" : mode.trim().toLowerCase();
        boolean briefMode = !"recent".equals(mode) && !"full".equals(mode);
        int turns = Math.max(2, configService.getInt("agent.subagentBriefTurns", 6));
        // 一问一答算一轮，所以条数是轮数的两倍；full 不逐条截（保留换行与表格），只在末尾按预算夹
        int maxLines = briefMode ? 4 : turns * 2;
        int perLine = briefMode ? 220 : "recent".equals(mode) ? Math.max(320, budget / 3) : budget;
        StringBuilder sb = new StringBuilder();
        sb.append("用户最初的问题：").append(truncateForBrief(question, budget));
        int lineCount = 0;
        try {
            List<Map<String, Object>> history = sessionService.getRecentHistory(sessionId, maxLines + 1);
            if (history == null) {
                log.warn("[SUBAGENT] 父轮历史读取失败（分支只带问题，不带上下文）session={}", sessionId);
            } else if (!history.isEmpty()) {
                // 最近一轮通常是本问题自己（刚即时落库），倒序取、跳过它，才拿到真正的前情
                List<String> lines = new ArrayList<>();
                for (int i = history.size() - 1; i >= 0 && lines.size() < maxLines; i--) {
                    Map<String, Object> m = history.get(i);
                    String content = m.get("content") == null ? "" : String.valueOf(m.get("content")).trim();
                    if (content.isBlank()) continue;
                    String role = String.valueOf(m.getOrDefault("role", ""));
                    if (content.equals(question == null ? "" : question.trim())) continue;
                    String body = "full".equals(mode) ? clipForBrief(content, perLine)
                            : truncateForBrief(content, perLine);
                    lines.add(("user".equalsIgnoreCase(role) ? "问：" : "答：") + body);
                }
                lineCount = lines.size();
                if (!lines.isEmpty()) {
                    sb.append("\n此前的对话（从新到旧）：\n");
                    for (String line : lines) sb.append("- ").append(line).append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("[SUBAGENT] 父轮上下文整理失败（分支只带问题）: {}", e.getMessage());
        }
        String brief = sb.toString().trim();
        if (brief.length() > budget) brief = brief.substring(0, budget);
        // 每次派分支留一行：档位、预算、带了几条、成文多长。分支回报里那句「没随附原文」是真是假，
        // 只有这一行能对得上——否则「继承档没生效」和「子智能体自己没读」在现场完全分不开
        log.info("[SUBAGENT] 父轮交代：档={} 预算 {} 带 {} 条 成文 {} 字 session={}",
                mode, budget, lineCount, brief.length(), sessionId);
        return brief;
    }

    /** full 档用的夹取：只限长度，不动空白（表格与列表的换行就是内容，压成一行会把它读散） */
    private static String clipForBrief(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static String truncateForBrief(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /**
     * 全循环分支的执行器（{@link BranchRunner} 的实现，按轮回传）。
     * <p>形状与工作流的 subagent 节点同一条通路（临时会话 + 收集型通道 + 完整 {@code chat} 管线），
     * 区别在归属：这一条要<b>继承父轮</b>的深度与上下文、要把产物收养进父会话、并且失败绝不能外抛——
     * 分支是增强项，一支炸了不能让用户这一轮整个报错。
     * <p>为什么用临时会话：{@code chat()} 的轮级互斥（{@code ChatRunRuntime#tryBeginTurn}，按会话加锁）
     * 借父会话就是父轮自己撞自己的坑（派出去立刻失败）；而分支之间用不同会话天然并行、
     * 彼此不互斥。临时会话跑完即删（软删可审计），不污染会话列表。
     */
    public BranchRunner branchRunner(TurnRunner turn, String parentSessionId, String parentTurnId, String userId,
                                     boolean guestMode, SseEmitter parentEmitter) {
        return req -> {
            long start = System.currentTimeMillis();
            String childSession = null;
            CollectingSseEmitter sink = new CollectingSseEmitter();
            // 分支的实时过程（P3a 刀2）：那一支在父轮卡片上原本只有「跑了 N 步工具」这个事后计数，
            // 一支跑两三分钟时用户对着一个静止的数字等。这里把它自己那几路过程事件原样转给父轮通道，
            // 事件名换成 subagent_step、带上分支序号，前端归到对应那一行下面（不重组协议、不改内容）。
            // 只转不发完就没了：这份实时步骤不落库，刷新后看到的仍是步数与回报全文。
            final int branchIndex = Math.max(0, req.branchIndex());
            final boolean[] first = {false};
            sink.forwardProcess((type, content) -> {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("i", branchIndex);
                step.put("agent", req.childAgentName());
                step.put("type", type);
                step.put("content", content == null ? "" : content);
                boolean sent = runtime.sendSseEvent(parentEmitter, "subagent_step", JSON.toJSONString(step), parentSessionId);
                if (!first[0]) {
                    // 只在首帧留一行：接通与否是"这一支有没有实时过程"的分水岭，而静默失败最难查
                    first[0] = true;
                    log.info("[SUBAGENT] 分支实时过程首帧：branch={} type={} 父轮接收={} session={}",
                            branchIndex, type, sent, parentSessionId);
                }
            });
            try {
                childSession = sessionService.createSession(req.userId());
                turn.run(ChatCommand.builder()
                        .sessionId(childSession)
                        .question(req.question())
                        .userId(req.userId())
                        .userImages(List.of())
                        .attachments(List.of())
                        .skills(List.of())
                        .agentId(req.childAgentId())
                        .modelOverride(req.modelRef())
                        .guestMode(guestMode)
                        .subagentDepth(req.depth())
                        .parentBrief(req.parentBrief())
                        .parentSessionId(req.parentSessionId())
                        .build(), sink);
                if (!sink.awaitDone(req.timeoutMs())) {
                    // 超时必须把那一支叫停：它还在流水线里跑，而父轮已经不等它了。
                    // 不叫停的后果是"删了一条还在被写的会话"+"一条线程被派出去的任务占住"，
                    // 并发几轮之后问答池就被没人等的分支填满。
                    runtime.stopTurn(childSession, req.userId());
                    return BranchReport.failed(BranchReport.TIMEOUT,
                            "分支超时（" + req.timeoutMs() / 1000 + " 秒）", System.currentTimeMillis() - start);
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, sink.lastError(),
                            System.currentTimeMillis() - start);
                }
                String answer = sink.answer() == null ? "" : sink.answer().strip();
                if (answer.isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, "子智能体未产生回答",
                            System.currentTimeMillis() - start);
                }
                // 子轮的引用/图片表是父轮续编编号的依据（done 载荷里那份是<b>已签名</b>的展示副本，
                // 存进回报前先把签名参数剥掉，回到与主链路一致的原始 URL）
                List<Map<String, Object>> childSources = new ArrayList<>();
                List<String> childImages = new ArrayList<>();
                int steps = 0;
                try {
                    // done 的 content 在 runtime.sendSseEvent 里是被当**字符串**再序列化一次的（外层 {"content":"{...}"}），
                    // 所以这里要解两层：直接 parseObject 会在内层引号处报 illegal input（真机实测踩到，
                    // 表现是引用与工具步数静默为 0，只有日志能看出来）
                    Object parsed = sink.donePayload() == null ? null : com.alibaba.fastjson2.JSON.parse(sink.donePayload());
                    if (parsed instanceof String s) parsed = com.alibaba.fastjson2.JSON.parse(s);
                    com.alibaba.fastjson2.JSONObject done = parsed instanceof com.alibaba.fastjson2.JSONObject o ? o : null;
                    if (done != null) {
                        com.alibaba.fastjson2.JSONArray ss = done.getJSONArray("sources");
                        if (ss != null) {
                            for (int i = 0; i < ss.size(); i++) {
                                com.alibaba.fastjson2.JSONObject o = ss.getJSONObject(i);
                                if (o == null) continue;
                                Map<String, Object> m = new LinkedHashMap<>(o);
                                m.put("images", rawUrlsOf(o.getList("images", String.class)));
                                childSources.add(m);
                            }
                        }
                        childImages = rawUrlsOf(done.getList("finalImages", String.class));
                        // 「这一支跑了 N 步工具」只数真执行过的：被守卫挡下的那几次工具体一次没跑，
                        // 按台账条数算会把"平台只让它试了 4 次"读成"它试了 8 次还没找到"
                        com.alibaba.fastjson2.JSONArray tc = done.getJSONArray("toolCalls");
                        if (tc != null) {
                            for (int i = 0; i < tc.size(); i++) {
                                com.alibaba.fastjson2.JSONObject o = tc.getJSONObject(i);
                                if (o != null && !o.getBooleanValue("denied")) steps++;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支回报解析失败（正文照常带回，引用与图片不续编）: {}", e.getMessage());
                }
                // 产物收养：子轮交付的东西归到父会话，用户在父轮里就点得到（跑完即删的临时会话留不住它）
                List<String> artifactFiles = new ArrayList<>();
                try {
                    for (Map<String, Object> a : artifactService.adoptFromSession(childSession, parentSessionId)) {
                        Object f = a.get("filename");
                        if (f != null) artifactFiles.add(String.valueOf(f));
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支产物收养失败（回答仍有效，产物留在子会话）: {}", e.getMessage());
                }
                long ms = System.currentTimeMillis() - start;
                log.info("[SUBAGENT] 全循环分支完成 {}：正文 {}字 引用 {} 条 工具步 {} 产物 {} 个 耗时 {}ms",
                        req.describe(), answer.length(), childSources.size(), steps, artifactFiles.size(), ms);
                return new BranchReport(BranchReport.DONE, answer, List.copyOf(childSources),
                        List.copyOf(childImages), steps, List.copyOf(artifactFiles), ms, null);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("[SUBAGENT] 全循环分支异常（按失败降级，不影响主链路）{}: {}",
                        req.describe(), cause.toString());
                return BranchReport.failed(BranchReport.FAILED, cause.toString(), System.currentTimeMillis() - start);
            } finally {
                // 收集型通道不触发 onCompletion，登记必须主动清（否则整轮台账与断开表挂着引用）
                ChatRunRuntime.forgetSseChannel(sink);
                if (childSession != null) {
                    try {
                        sessionService.deleteSession(req.userId(), childSession);
                    } catch (Exception ignored) {
                        // 删失败只多留一条临时会话，不影响回答；硬抛会把已跑完的分支报成失败
                    }
                }
            }
        };
    }

    /** 展示层签名 URL → 原始 URL（与库内/上下文里存的那份同源，才能与主链路的编号表对上） */
    private static List<String> rawUrlsOf(List<String> urls) {
        if (urls == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) continue;
            int q = u.indexOf('?');
            out.add(q > 0 ? u.substring(0, q) : u);
        }
        return List.copyOf(out);
    }

    /**
     * 回报段文本（占位形态）：每条分支一段，正文里的引用/图片先写成 {@code ⟦b<支>:r<号>⟧}。
     * <p>预算：全部回报共享 {@code agent.digestMaxChars}，按支数均分——一支写完一篇文章把
     * 主链路的上下文挤光，用户看到的就不是主智能体的回答而是它的拼接稿。
     * <p>失败分支照样留一行（沿用要点段那条「失败要看得见」的口径）：不写，主模型会把
     * "这一支没结果"当成"这件事不用做"。
     */
    public String branchReportsText(List<SubAgentOrchestrator.FullBranch> fulls) {
        int per = Math.max(300, configService.getInt("agent.digestMaxChars", 1500) / Math.max(1, fulls.size()));
        StringBuilder sb = new StringBuilder();
        for (SubAgentOrchestrator.FullBranch fb : fulls) {
            BranchReport r = fb.report();
            if (sb.length() > 0) sb.append("\n");
            if (!r.usable()) {
                sb.append("· 【").append(fb.name()).append("】未办成（")
                        .append(BranchReport.TIMEOUT.equals(r.status()) ? "超时" : "失败").append("：")
                        .append(truncateForBrief(r.error() == null ? "无详情" : r.error(), 60)).append("）\n");
                continue;
            }
            sb.append("· 【").append(fb.name()).append("】");
            if (!r.artifactFilesOrEmpty().isEmpty()) {
                sb.append("已交付：").append(String.join("、", r.artifactFilesOrEmpty())).append("；");
            }
            sb.append(r.toolSteps() > 0 ? "用了 " + r.toolSteps() + " 步工具：\n" : "结论：\n");
            sb.append(placeholderize(r, fb.idx(), per)).append("\n");
        }
        return sb.toString().strip();
    }

    /**
     * 把子轮正文里的 {@code [N]} / {@code [图片N]} 换成占位记号，等父轮编号定稿后回填。
     * <p>只认子轮真发过的编号（超出其引用数/图片数的编号是模型编的，直接去掉——与主链路对
     * 模型越界编号的处理同口径，不能让分支里的假引用比主链路更好混）。
     */
    private static String placeholderize(BranchReport r, int branchIdx, int budget) {
        String text = r.answerOrEmpty().replaceAll("\\s+", " ").strip();
        if (text.length() > budget) {
            log.warn("[SUBAGENT] 分支回报超预算已截断：{} 字 > {} 字（分支 {}）", text.length(), budget, branchIdx);
            text = text.substring(0, budget) + "…";
        }
        int maxRef = r.sourcesOrEmpty().size();
        int maxImg = r.imagesOrEmpty().size();
        Matcher cm = Pattern.compile("\\[(\\d{1,3})]").matcher(text);
        StringBuffer cited = new StringBuffer();
        while (cm.find()) {
            int n = Integer.parseInt(cm.group(1));
            cm.appendReplacement(cited, n >= 1 && n <= maxRef
                    ? Matcher.quoteReplacement("⟦b" + branchIdx + ":r" + n + "⟧") : "");
        }
        // appendTail 必须有：只调 appendReplacement 时，最后一段（以及一处都没命中时的整段正文）
        // 不会进 StringBuffer——那等于把子智能体的回报悄悄吞成空串，界面上是"派了，什么都没有"
        cm.appendTail(cited);
        cm = Pattern.compile("\\[图片(\\d{1,3})]").matcher(cited.toString());
        StringBuffer imgs = new StringBuffer();
        while (cm.find()) {
            int n = Integer.parseInt(cm.group(1));
            cm.appendReplacement(imgs, n >= 1 && n <= maxImg
                    ? Matcher.quoteReplacement("⟦b" + branchIdx + ":i" + n + "⟧") : "");
        }
        cm.appendTail(imgs);
        return imgs.toString();
    }

    /**
     * 回填父轮编号（P3a 的"引用继承"落点）。
     * <ul>
     *   <li>子轮引用过的块<b>多数已在父轮参考资料里</b>（分支命中的那些本就并进了主链路填充）——
     *       按 knowledgeId 复用父轮已有编号，绝不重复注册同号；</li>
     *   <li>父轮没带上的（分支自己调检索工具命中、没进主链路的）补注册为新来源，origin=SUBAGENT，
     *       续在父轮编号尾部——用户点得开，才算"分支的结论有出处"；</li>
     *   <li>超出本轮资料范围（智能体库范围之外）的那几块<b>不注册</b>：只把编号摘掉并在段末说明。
     *       范围是智能体配置的能力边界，不能因为"分支跑在另一个会话里"就把本该看不到的资料
     *       顺带引到主轮来；</li>
     *   <li>图片按 URL 归并：同一张图在父子两轮共用一个编号，否则回答里会出现两张同一截图。</li>
     * </ul>
     */
    public String resolveBranchReportRefs(String section, List<SubAgentOrchestrator.FullBranch> fulls,
                                           List<Map<String, Object>> sources, Map<Integer, String> imgIndex,
                                           Map<Integer, String> imgDescIndex, Map<String, String> fileNameMap,
                                           Set<String> scopeDocIds, List<Map<String, String>> degradations,
                                           Set<String> degradedCodes) {
        Map<Integer, Map<Integer, Integer>> refMap = new HashMap<>();     // 支 → (子引用号 → 父号)
        Map<Integer, Map<Integer, Integer>> imgMap = new HashMap<>();     // 支 → (子图片号 → 父图片号)
        int dropped = 0;
        for (SubAgentOrchestrator.FullBranch fb : fulls) {
            BranchReport r = fb.report();
            if (!r.usable()) continue;
            Map<Integer, Integer> rm = new HashMap<>();
            Map<Integer, Integer> im = new HashMap<>();
            // 本轮被接受的那些来源里的图片 URL：图片编号只从这些里续编。
            // 不加这道限制就会把"父轮范围外那块资料上的截图"编进父轮图片表——正文里的编号摘掉了，
            // 图却仍然随 image 事件下发给了前端，等于绕过了上面那条范围判定。
            Set<String> acceptedImgUrls = new HashSet<>();
            for (Map<String, Object> src : r.sourcesOrEmpty()) {
                Integer childRef = intOf(src.get("ref"));
                if (childRef == null) continue;
                String kid = src.get("knowledgeId") == null ? null : String.valueOf(src.get("knowledgeId"));
                String docId = src.get("docId") == null ? null : String.valueOf(src.get("docId"));
                if (kid == null || kid.isBlank()) continue;
                Integer parent = findSourceRef(sources, kid);
                if (parent == null) {
                    if (scopeDocIds != null && (docId == null || !scopeDocIds.contains(docId))) {
                        dropped++;
                        continue;   // 范围外：宁可不编号，也不把父轮不该用的资料引出来
                    }
                    parent = registerBranchSource(src, sources, imgIndex, imgDescIndex, fileNameMap, fb.name());
                }
                if (parent == null) continue;
                rm.put(childRef, parent);
                if (src.get("images") instanceof List<?> l) {
                    for (Object u : l) if (u != null) acceptedImgUrls.add(String.valueOf(u));
                }
            }
            List<String> childImgs = r.imagesOrEmpty();
            for (int i = 0; i < childImgs.size(); i++) {
                String url = childImgs.get(i);
                if (url == null || !acceptedImgUrls.contains(url)) continue;
                Integer seq = parentImageSeq(url, imgIndex, imgDescIndex);
                if (seq != null) im.put(i + 1, seq);   // 子轮 [图片1] = 下标 0
            }
            refMap.put(fb.idx(), rm);
            imgMap.put(fb.idx(), im);
        }
        String out = fillTokens(section, refMap, imgMap);
        if (dropped > 0) {
            out = out + "\n（注：该分支有 " + dropped + " 处引用超出本轮资料范围，未编号，回答时不要引用它们。）";
            runtime.addDegradation(degradations, degradedCodes, "subagentSourceOutOfScope",
                    "子智能体回报中有 " + dropped + " 处资料不在本轮检索范围内，未作为引用");
            log.warn("[SUBAGENT] 分支回报中 {} 处引用超出本轮资料范围（未编号）", dropped);
        }
        return out;
    }

    /** 把占位记号换成父轮编号；换不到（对应资料没进来）的一律摘掉，不留一个指不回来源的编号 */
    private static String fillTokens(String text, Map<Integer, Map<Integer, Integer>> refMap,
                                     Map<Integer, Map<Integer, Integer>> imgMap) {
        Matcher m = Pattern.compile("⟦b(\\d+):([ri])(\\d{1,4})⟧").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int branch = Integer.parseInt(m.group(1));
            int n = Integer.parseInt(m.group(3));
            Map<Integer, Integer> map = "r".equals(m.group(2)) ? refMap.get(branch) : imgMap.get(branch);
            Integer mapped = map == null ? null : map.get(n);
            String rep = mapped == null ? ""
                    : ("r".equals(m.group(2)) ? "[" + mapped + "]" : "[图片" + mapped + "]");
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 父轮引用表里是否已有这一块（按 knowledgeId，与主链路填充的判重同一口径） */
    private static Integer findSourceRef(List<Map<String, Object>> sources, String knowledgeId) {
        for (Map<String, Object> s : sources) {
            if (s == null || knowledgeId == null) continue;
            if (knowledgeId.equals(String.valueOf(s.get("knowledgeId")))) {
                return intOf(s.get("ref"));
            }
        }
        return null;
    }

    /** 子轮来源补注册进父轮：续编号、带原文片段与图片，origin=SUBAGENT 供前端标出来路 */
    private Integer registerBranchSource(Map<String, Object> src, List<Map<String, Object>> sources,
                                         Map<Integer, String> imgIndex, Map<Integer, String> imgDescIndex,
                                         Map<String, String> fileNameMap, String agentName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        int ref = sources.size() + 1;
        entry.put("ref", ref);
        entry.put("knowledgeId", src.get("knowledgeId"));
        entry.put("docId", src.get("docId"));
        String docId = src.get("docId") == null ? null : String.valueOf(src.get("docId"));
        String fileName = fileNameMap == null || docId == null ? null : fileNameMap.get(docId);
        if (fileName == null && docId != null && !docId.isBlank() && documentMetaCache != null) {
            fileName = documentMetaCache.getFileNames(Set.of(docId)).get(docId);
        }
        entry.put("fileName", fileName);
        entry.put("title", src.get("title"));
        entry.put("snippet", src.get("snippet"));
        List<String> images = src.get("images") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        entry.put("images", images);
        entry.put("origin", "SUBAGENT");
        entry.put("agent", agentName);
        Object score = src.get("score");
        if (score != null) {
            try {
                entry.put("score", Math.round(Double.parseDouble(String.valueOf(score)) * 1000) / 1000.0);
            } catch (NumberFormatException ignored) {
                // 子轮没给分数就不写一个假分数
            }
        }
        sources.add(entry);
        for (String url : images) {
            parentImageSeq(url, imgIndex, imgDescIndex);
        }
        return ref;
    }

    /** 图片按原始 URL 归并进父轮编号表；没有则续编一个（描述未知留空，图片相关性校验对空描述放行） */
    private static Integer parentImageSeq(String rawUrl, Map<Integer, String> imgIndex,
                                          Map<Integer, String> imgDescIndex) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        for (Map.Entry<Integer, String> e : imgIndex.entrySet()) {
            if (rawUrl.equals(e.getValue())) return e.getKey();
        }
        int seq = imgIndex.size() + 1;
        imgIndex.put(seq, rawUrl);
        imgDescIndex.put(seq, "");
        return seq;
    }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
