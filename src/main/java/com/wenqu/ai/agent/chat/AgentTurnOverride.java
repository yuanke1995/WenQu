package com.wenqu.ai.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 本轮智能体配置覆盖：只读、白名单、可复现。
 * <p>为什么单独有这个类：评测的历史版本重测与「本轮临时换库/换工具」需要同一条执行路径，
 * 但不能回滚线上智能体（那会改变其他正在跑的会话）。override 只作用在解析出的 Agent 视图上，
 * <b>不写库</b>；调用变量仍走用户消息侧，不进 sysPrompt（保住常驻实例的前缀缓存）。
 * <p>本期仅开放：
 * <ul>
 *   <li>{@code revision}：智能体版本快照号；null=当前配置；</li>
 *   <li>{@code knowledgePatch}：本轮换知识库/标签/文档条件/禁用检索。</li>
 * </ul>
 * 其余字段后续按同一形状扩充；未知字段一律不接受（调用方内部构造，不是 HTTP DTO）。
 */
public final class AgentTurnOverride {

    /** 版本快照号；null=当前配置。 */
    private final Integer revision;
    /** 知识范围覆盖；null=不覆盖。 */
    private final KnowledgePatch knowledge;

    private AgentTurnOverride(Integer revision, KnowledgePatch knowledge) {
        if (revision != null && revision < 1) throw new BizException("override.revision 必须为正整数");
        this.revision = revision;
        this.knowledge = knowledge;
    }

    public static AgentTurnOverride none() {
        return new AgentTurnOverride(null, null);
    }

    public static AgentTurnOverride of(Integer revision, KnowledgePatch knowledge) {
        if (revision == null && knowledge == null) return none();
        return new AgentTurnOverride(revision, knowledge);
    }

    public static AgentTurnOverride revision(int revision) {
        return of(revision, null);
    }

    public static AgentTurnOverride knowledge(KnowledgePatch patch) {
        return of(null, patch);
    }

    public Integer revision() {
        return revision;
    }

    public KnowledgePatch knowledge() {
        return knowledge;
    }

    public boolean isEmpty() {
        return revision == null && knowledge == null;
    }

    /** 稳定指纹：相同配置任意构造顺序都得到同一串；无 override 返回空串（旧实例键逐字节不变）。 */
    public String fingerprint() {
        if (isEmpty()) return "";
        StringBuilder out = new StringBuilder("rev=").append(revision == null ? "-" : revision);
        out.append("|kb=");
        if (knowledge == null) {
            out.append("-");
        } else {
            knowledge.appendTo(out);
        }
        return out.toString();
    }

    /** 知识范围覆盖：null 字段=维持代理当前值；空集合=显式「不使用」（与 UI 的「指定但一项不选」同口径）。 */
    public static final class KnowledgePatch {
        private final LinkedHashSet<String> kbIds;
        private final LinkedHashSet<String> tags;
        private final String filterJson;
        private final Integer disabled;

        public KnowledgePatch(List<String> kbIds, List<String> tags, String filterJson, Integer disabled) {
            this.kbIds = copy(kbIds, "knowledgeBaseIds", 500);
            this.tags = copy(tags, "knowledgeTags", 500);
            this.filterJson = trimToNull(filterJson, 2000);
            if (filterJson != null && !isValidFilterJson(filterJson)) {
                throw new BizException("knowledgeFilter 必须是 JSON 对象，且 fileTypes 为字符串数组、updatedWithinDays 为正整数");
            }
            if (disabled != null && disabled != 0 && disabled != 1) {
                throw new BizException("knowledgeDisabled 只能是 0 或 1");
            }
            this.disabled = disabled;
        }

        public LinkedHashSet<String> kbIds() {
            return kbIds;
        }

        public LinkedHashSet<String> tags() {
            return tags;
        }

        public String filterJson() {
            return filterJson;
        }

        public Integer disabled() {
            return disabled;
        }

        public boolean hasKbIds() {
            return kbIds != null;
        }

        private void appendTo(StringBuilder out) {
            out.append(ids(kbIds)).append('/').append(ids(tags))
                    .append('/').append(filterJson == null ? "-" : filterJson.replaceAll("[|/]", "_"))
                    .append('/').append(disabled == null ? "-" : disabled);
        }

        private static String ids(LinkedHashSet<String> set) {
            if (set == null) return "-";
            if (set.isEmpty()) return "[]";
            // 指纹里排序：kbIds/tags 是集合语义，顺序不应让同一配置生成不同实例键。
            return set.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        }

        private static LinkedHashSet<String> copy(List<String> values, String field, int maxItems) {
            if (values == null) return null;
            if (values.size() > maxItems) throw new BizException(field + " 最多 " + maxItems + " 项");
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (String v : values) {
                String s = v == null ? null : v.trim();
                if (s == null || s.isEmpty()) continue;
                if (s.length() > 200) throw new BizException(field + " 单项不能超过 200 字符");
                if (s.contains(",")) throw new BizException(field + " 单项不能包含逗号");
                out.add(s);
            }
            return out;
        }

        private static String trimToNull(String s, int max) {
            if (s == null) return null;
            String v = s.trim();
            if (v.isEmpty()) return null;
            if (v.length() > max) throw new BizException("knowledgeFilter 不能超过 " + max + " 字符");
            return v;
        }

        private static boolean isValidFilterJson(String json) {
            try {
                JSONObject o = JSON.parseObject(json);
                if (o == null) return false;
                var arr = o.getJSONArray("fileTypes");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (!(arr.get(i) instanceof String)) return false;
                    }
                }
                Integer days = o.getInteger("updatedWithinDays");
                return days == null || days > 0;
            } catch (Exception e) {
                return false;
            }
        }

        /** 写回 Agent 视图的字段：null=不动，空集合=空串（显式不使用）。 */
        public void applyTo(com.wenqu.ai.model.Agent a) {
            Objects.requireNonNull(a, "agent");
            if (kbIds != null) a.setKnowledgeBaseIds(String.join(",", new ArrayList<>(kbIds)));
            if (tags != null) a.setKnowledgeTags(String.join(",", new ArrayList<>(tags)));
            if (filterJson != null) a.setKnowledgeFilter(filterJson);
            if (disabled != null) a.setKnowledgeDisabled(disabled);
        }
    }
}
