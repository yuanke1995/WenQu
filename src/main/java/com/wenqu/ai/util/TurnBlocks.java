package com.wenqu.ai.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * 分享视图的 blocks 裁剪：只保留 answer 文本块。
 * <p>完整 blocks 里有工具入参/输出、过程独白与思考；分享页只展示最终答案，
 * 这些内容既不该渲染，也不该随游客流或公开历史发到未登录访客的网络上。
 */
public final class TurnBlocks {

    private TurnBlocks() {
    }

    /** 入参可以是 blocks JSON 字符串或已解析的 Map；没有 answer 文本块时返回 null。 */
    public static Map<String, Object> answerOnly(Object blocks) {
        if (blocks == null) return null;
        JSONObject root;
        if (blocks instanceof String json) {
            if (json.isBlank()) return null;
            try {
                root = JSON.parseObject(json);
            } catch (Exception e) {
                return null;
            }
        } else if (blocks instanceof Map<?, ?>) {
            try {
                root = JSON.parseObject(JSON.toJSONString(blocks));
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
        if (root == null) return null;
        JSONArray all = root.getJSONArray("blocks");
        if (all == null || all.isEmpty()) return null;

        JSONArray keep = new JSONArray();
        for (int i = 0; i < all.size(); i++) {
            JSONObject block = all.getJSONObject(i);
            if (block == null) continue;
            if (!"text".equals(block.getString("kind"))) continue;
            if (!"answer".equals(block.getString("channel"))) continue;
            String text = block.getString("text");
            if (text == null || text.isEmpty()) continue;
            JSONObject one = new JSONObject();
            one.put("id", block.getString("id"));
            one.put("kind", "text");
            one.put("channel", "answer");
            one.put("text", text);
            keep.add(one);
        }
        if (keep.isEmpty()) return null;

        JSONObject out = new JSONObject();
        out.put("v", root.getIntValue("v"));
        out.put("blocks", keep);
        return out;
    }

    /** 字符串形态的便捷口；没有可外发块时返回 null。 */
    public static String answerOnlyJson(String blocksJson) {
        Map<String, Object> out = answerOnly(blocksJson);
        return out == null ? null : JSON.toJSONString(out);
    }

    /** 从 {@link #answerOnly} 的结果里拼出答案正文；没有 answer 块返回空串。 */
    public static String answerText(Map<String, Object> blocks) {
        if (blocks == null || !(blocks.get("blocks") instanceof JSONArray arr)) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject block = arr.getJSONObject(i);
            if (block == null) continue;
            if (!"text".equals(block.getString("kind"))) continue;
            if (!"answer".equals(block.getString("channel"))) continue;
            String text = block.getString("text");
            if (text != null) sb.append(text);
        }
        return sb.toString();
    }
}
