package com.wenqu.ai.agentx.turn;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮的显示投影：把正文、思考、过程、工具、插话、产物压成有序、自包含的 blocks。
 * <p>blocks 的每个文本块自带全文，不再用「字符区间 + 并行数组下标」表达交错；工具块按条目身份原地更新，
 * 位置固定在首次出现处。答案边界按 assistant 轮次判定：最后一条无工具轮为答案，无候选轮才退回末尾正文；
 * 前端不再自行推断。
 * <p>本类只做投影，不发送 SSE、不落库；调用方在收尾时取一次 JSON。
 */
public final class TurnTranscriptRecorder {

    private static final int VERSION = 1;

    private final List<Block> blocks = new ArrayList<>();
    /** 工具条目对象 → 块：同一条目后续更新只改块内容，不新增块、不移动位置。 */
    private final Map<Map<String, Object>, Block> toolBlocks = new IdentityHashMap<>();
    /** 每次 assistant 消息落定时的块边界：轮内正文属于这一轮，hasToolCalls 决定它是不是过程。 */
    private final List<RoundMark> rounds = new ArrayList<>();

    private Block openText;
    private Block openProcess;
    private Block openThink;
    /** 收尾对账补进来的正文起点：只标记从这一段到末尾为答案（不把后续工具轮的正文算进来）。 */
    private int tailFromBlock = -1;
    /** 正文被整体替换（引用自检/回退）：轮次区间已失效，最终正文整体就是答案。 */
    private boolean replacedBody;
    private int nextId = 1;

    public synchronized void textDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        openProcess = null;
        openThink = null;
        if (openText == null) openText = add("text");
        openText.text.append(delta);
    }

    public synchronized void processDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        openText = null;
        openThink = null;
        if (openProcess == null) openProcess = add("process");
        openProcess.text.append(delta);
    }

    public synchronized void thinkingStart() {
        openText = null;
        openProcess = null;
        if (openThink == null) openThink = add("think");
    }

    public synchronized void thinkingDelta(String delta) {
        if (delta == null || delta.isEmpty()) return;
        openText = null;
        openProcess = null;
        if (openThink == null) openThink = add("think");
        openThink.text.append(delta);
    }

    public synchronized void thinkingEnd() {
        openThink = null;
    }

    /**
     * 一条 assistant 消息落定：此前产生的块属于这一轮。
     * <p>{@code hasToolCalls=false} 且正文非空的最后一轮才是答案；这比「末尾连续正文」更接近 DSH 口径，
     * 也能避免「工具轮末尾恰好有正文」被误判成答案。
     */
    public synchronized void assistantRound(boolean hasToolCalls) {
        // 轮边界必须切断开口块：两轮连续正文若并成一块，轮次区间就分不开，后一轮会被误标成答案
        flushText();
        rounds.add(new RoundMark(blocks.size(), hasToolCalls));
    }

    public synchronized void tool(Map<String, Object> entry) {
        if (entry == null) return;
        openText = null;
        openProcess = null;
        openThink = null;
        Block block = toolBlocks.get(entry);
        if (block == null) {
            block = add("tool");
            toolBlocks.put(entry, block);
        }
        block.data = new LinkedHashMap<>(entry);
    }

    /**
     * 终态提示块：停止/等待超时/预算用尽/空转的原因结构化入块。
     * <p>双写窗口内正文尾标照旧留在 `content` 里（旧客户端只读正文），所以这里不进 text 通道、
     * 也不参与答案归属判定；P6 退役尾标时前端改渲染这一块。
     */
    public synchronized void notice(String tone, String text) {
        if (text == null || text.isBlank()) return;
        openText = null;
        openProcess = null;
        openThink = null;
        Block block = add("notice");
        block.data = new LinkedHashMap<>();
        block.data.put("tone", tone == null ? "" : tone);
        block.data.put("text", text.trim());
    }

    /** 终态原因的结构化口径：与正文尾标同一判据顺序与同一兜底，两边不能各排一次优先级。 */
    public static String toneOf(boolean stopped, boolean timedOut, boolean budget, boolean stalled) {
        if (stopped) return "stopped";
        if (budget) return "budget";
        if (stalled) return "stalled";
        if (timedOut) return "timeout";
        return "truncated";
    }

    public synchronized void steer(String text, String messageId) {
        if (text == null || text.isEmpty()) return;
        openText = null;
        openProcess = null;
        openThink = null;
        Block block = add("steer");
        block.data = new LinkedHashMap<>();
        block.data.put("text", text);
        block.data.put("messageId", messageId == null ? "" : messageId);
    }

    public synchronized void artifacts(List<Map<String, Object>> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return;
        openText = null;
        openProcess = null;
        openThink = null;
        for (Map<String, Object> artifact : artifacts) {
            if (artifact == null || artifact.isEmpty()) continue;
            Block block = add("artifact");
            // 块序列化时 "id" 是块号（b1/b2…），会把产物真实 id 盖掉：前端拿不到 id 就只能
            // 退化成按工作区路径读内容（projectId 为空 → 404），而下载仍走签名 URL 所以看起来正常。
            // 产物 id 改存 artifactId，前端投影时再还原。
            block.data = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : artifact.entrySet()) {
                block.data.put("id".equals(e.getKey()) ? "artifactId" : e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 收尾投影：把最终正文与既有块对齐后序列化。
     * <p>正文可能来自三处与流不一致的来源：推荐块并回、引用自检整体替换、终态标注追加。
     * 与流内文本同前缀时只补后缀；被整体替换时丢掉旧正文块、把最终正文接在末尾（工具/过程/思考块保留），
     * 与旧 timeline 的「只丢 text 段」口径一致。
     */
    public synchronized String toJson(String turnId, String body) {
        flushText();
        reconcileBody(body);
        assignAnswerChannels(true);
        return serialize(turnId);
    }

    /**
     * 接流快照专用：不按最终正文对账、不关闭开口块，只按当前块序列化。
     * <p>快照线程与流水线线程并行，若在这里拿 answerText 做前缀对账，可能把「已进正文、还没进记录器」
     * 的一个增量补成新块，随后流水线再追加一次，正文就重复了。blocks 自己就是当前时刻的真值。
     */
    public synchronized String snapshotJson(String turnId) {
        assignAnswerChannels(false);
        return serialize(turnId);
    }

    private String serialize(String turnId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Block block : blocks) {
            Map<String, Object> map = block.toMap();
            if (map != null) list.add(map);
        }
        if (list.isEmpty()) return null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", VERSION);
        if (turnId != null && !turnId.isBlank()) out.put("turnId", turnId);
        out.put("blocks", list);
        return JSON.toJSONString(out);
    }

    private void flushText() {
        openText = null;
        openProcess = null;
        openThink = null;
    }

    private void reconcileBody(String body) {
        String finalBody = body == null ? "" : body;
        tailFromBlock = -1;
        replacedBody = false;
        StringBuilder all = new StringBuilder();
        for (Block block : blocks) {
            if ("text".equals(block.kind)) all.append(block.text);
        }
        if (all.toString().equals(finalBody)) return;

        if (finalBody.startsWith(all.toString())) {
            String suffix = finalBody.substring(all.length());
            if (!suffix.isEmpty()) {
                tailFromBlock = blocks.size();
                Block block = add("text");
                block.text.append(suffix);
            }
            return;
        }

        // 整体替换（引用自检/回退正文）：旧正文块作废，最终正文接在末尾；非文本块保留原位。
        blocks.removeIf(block -> "text".equals(block.kind));
        replacedBody = true;
        if (!finalBody.isEmpty()) {
            tailFromBlock = blocks.size();
            Block block = add("text");
            block.text.append(finalBody);
        }
    }

    private void assignAnswerChannels(boolean finalize) {
        for (Block block : blocks) {
            if ("text".equals(block.kind)) block.channel = "narration";
        }
        if (finalize && replacedBody) {
            for (Block block : blocks) {
                if ("text".equals(block.kind) && !block.isBlank()) block.channel = "answer";
            }
            return;
        }
        // 快照时刻还没有终态答案边界：按当前末尾正文先标，接流方看到的与流中一致
        if (finalize && !rounds.isEmpty()) {
            int prevEnd = 0;
            int candidateStart = -1;
            int candidateEnd = -1;
            for (RoundMark mark : rounds) {
                int start = prevEnd;
                int end = Math.min(mark.endBlock, blocks.size());
                if (!mark.hasToolCalls && hasNonBlankText(start, end)) {
                    candidateStart = start;
                    candidateEnd = end;
                }
                prevEnd = end;
            }
            if (candidateStart >= 0) {
                markAnswer(candidateStart, candidateEnd);
                if (tailFromBlock >= 0) markAnswer(tailFromBlock, blocks.size());
                return;
            }
        }

        // 没有轮次信息（旧路径/停在工具轮）：退回末尾连续正文，保证答案不为空
        int runStart = -1;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if ("artifact".equals(block.kind) || "notice".equals(block.kind)) continue;
            if (!"text".equals(block.kind)) break;
            if (!block.isBlank()) runStart = i;
        }
        if (runStart < 0) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Block block = blocks.get(i);
                if ("text".equals(block.kind) && !block.isBlank()) {
                    runStart = i;
                    break;
                }
            }
        }
        if (runStart < 0) return;
        markAnswer(runStart, blocks.size());
    }

    private boolean hasNonBlankText(int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, blocks.size()); i++) {
            Block block = blocks.get(i);
            if ("text".equals(block.kind) && !block.isBlank()) return true;
        }
        return false;
    }

    private void markAnswer(int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, blocks.size()); i++) {
            Block block = blocks.get(i);
            if ("text".equals(block.kind) && !block.isBlank()) block.channel = "answer";
        }
    }

    private Block add(String kind) {
        Block block = new Block("b" + nextId++, kind);
        blocks.add(block);
        return block;
    }

    private static final class RoundMark {
        final int endBlock;
        final boolean hasToolCalls;

        RoundMark(int endBlock, boolean hasToolCalls) {
            this.endBlock = endBlock;
            this.hasToolCalls = hasToolCalls;
        }
    }

    private static final class Block {
        final String id;
        final String kind;
        final StringBuilder text = new StringBuilder();
        Map<String, Object> data;
        String channel;

        Block(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        boolean isBlank() {
            return text.toString().trim().isEmpty();
        }

        Map<String, Object> toMap() {
            if ("text".equals(kind)) {
                if (isBlank()) return null;
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", kind);
                map.put("channel", channel);
                map.put("text", text.toString());
                map.put("id", id);
                return map;
            }
            if ("think".equals(kind) || "process".equals(kind)) {
                if (isBlank()) return null;
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("kind", kind);
                map.put("text", text.toString());
                map.put("id", id);
                return map;
            }
            if (data == null) return null;
            Map<String, Object> map = new LinkedHashMap<>(data);
            map.put("kind", kind);
            map.put("id", id);
            return map;
        }
    }
}
