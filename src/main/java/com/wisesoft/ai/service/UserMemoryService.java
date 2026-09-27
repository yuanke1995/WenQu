package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.UserMemoryMapper;
import com.wisesoft.ai.model.UserMemory;
import com.wisesoft.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务（跨会话个性化）
 * <p>
 * 写入两条路 + 一条注入：
 * <ul>
 *   <li><b>自动提取</b>（{@link #maybeExtract}）：每轮问答完成后**异步**调 LLM 提炼值得长期
 *       记住的事实（偏好/项目背景/明确要求记住的事），最多 3 条；best-effort 失败仅日志；</li>
 *   <li><b>手动管理</b>（addManual/updateContent/delete）：个人设置页增删改，自动提取的条目
 *       记录来源会话便于溯源删除；</li>
 *   <li><b>注入</b>（{@link #injectText}）：每轮问答把本人记忆按预算拼进 system prompt，
 *       并累加使用度（hit_count/last_hit_at）供管理页看哪些记忆真被用上。</li>
 * </ul>
 * 边界：游客分享会话不注入也不提取（发布者的个人记忆不外泄给匿名访客）；anonymous 池对话不提取。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryMapper memoryMapper;
    private final ConfigService configService;
    /** 对话模型（DynamicOpenAiChatModel；迁移后无全局兜底模型，模型名由调用方 per-request 显式传入） */
    private final ChatModel chatModel;

    /** 单条内容长度上限（列宽 500） */
    private static final int MAX_CONTENT_CHARS = 500;
    /** 送入提取 LLM 的截断：用户 2000 / 回答 3000（提取要的是持久事实，不需要全文） */
    private static final int EXTRACT_USER_CHARS = 2000;
    private static final int EXTRACT_ANSWER_CHARS = 3000;

    private boolean enabled() {
        return configService.getBoolean("memory.enabled");
    }

    private int maxPerUser() {
        return Math.max(1, configService.getInt("memory.maxPerUser", 50));
    }

    private int maxInjectCount() {
        return Math.max(1, configService.getInt("memory.maxInjectCount", 30));
    }

    private int injectBudgetChars() {
        return Math.max(200, configService.getInt("memory.injectBudgetChars", 1500));
    }

    // ==================== 注入 ====================

    /**
     * 取该用户记忆并拼注入文本（null=无记忆/未开启，零影响）。
     * 顺带累加使用度（hit_count+1、last_hit_at=now）：管理页据此看哪些记忆真被用上。
     */
    public String injectText(String uid) {
        if (!enabled() || uid == null || uid.isBlank() || RequestUser.ANONYMOUS.equals(uid)) return null;
        List<UserMemory> list = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUid, uid)
                .orderByDesc(UserMemory::getUpdateTime)
                .last("limit " + maxInjectCount()));
        if (list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("【用户长期记忆】以下是系统此前为你记住的关于该用户的信息，供个性化回答参考；"
                + "与当前对话内容冲突时以对话为准：\n");
        int budget = injectBudgetChars();
        int used = 0;
        List<String> hitIds = new ArrayList<>();
        for (UserMemory m : list) {
            String line = "- " + m.getContent() + "\n";
            if (used + line.length() > budget) break;
            sb.append(line);
            used += line.length();
            hitIds.add(m.getId());
        }
        if (hitIds.isEmpty()) return null;
        // 使用度累加（best-effort；失败不影响本轮问答）
        try {
            for (String id : hitIds) {
                memoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                        .eq(UserMemory::getId, id)
                        .setSql("hit_count = COALESCE(hit_count, 0) + 1")
                        .set(UserMemory::getLastHitAt, LocalDateTime.now()));
            }
        } catch (Exception e) {
            log.warn("[Memory] 使用度累加失败: {}", e.getMessage());
        }
        return sb.toString();
    }

    // ==================== 自动提取 ====================

    /**
     * 问答完成后的记忆提取入口（调用方在 done 后触发，本方法自行判断是否值得跑）。
     * 提取调用跟随本轮生效模型（model，done 时已由问答入口 fail-loud 保证非空——提取服务于本轮对话，
     * 与问答同源，无需独立配置）。异步 daemon 线程执行，绝不阻塞/影响问答主链路；任何失败仅日志。
     */
    public void maybeExtract(String uid, String sessionId, String question, String answer, boolean guestMode, String model) {
        if (guestMode) return;                       // 游客会话：不注入也不提取
        if (!enabled()) return;
        if (uid == null || uid.isBlank() || RequestUser.ANONYMOUS.equals(uid)) return;
        if (question == null || question.isBlank()) return;
        String q = question.length() > EXTRACT_USER_CHARS ? question.substring(0, EXTRACT_USER_CHARS) : question;
        String a = answer == null ? "" : (answer.length() > EXTRACT_ANSWER_CHARS ? answer.substring(0, EXTRACT_ANSWER_CHARS) : answer);
        Thread t = new Thread(() -> {
            try {
                extract(uid, sessionId, q, a, model);
            } catch (Exception e) {
                log.warn("[Memory] 自动提取失败（不影响问答）: uid={} {}", uid, e.getMessage());
            }
        }, "memory-extract");
        t.setDaemon(true);
        t.start();
    }

    /** 同步提取主体：LLM 判断 + 解析 + 去重落库（由异步线程调用） */
    private void extract(String uid, String sessionId, String question, String answer, String model) {
        // DynamicOpenAiChatModel 迁移后 default options 为空（模型名均由 per-request options 提供），
        // 不带 model 的请求体会被网关以 400 "Required parameter model missing" 拒绝；
        // 本轮模型缺失说明上游模型解析异常（问答入口 fail-loud，理论上到不了这），显式暴露而非静默吞
        if (model == null || model.isBlank()) {
            log.warn("[Memory] 自动提取跳过：本轮模型缺失（上游模型解析异常）: uid={}", uid);
            return;
        }
        String sys = "你是记忆提取器。判断这段对话是否包含值得长期记住的用户信息（用户身份/职责、偏好、"
                + "项目背景、明确要求记住的事）。只提取对【未来对话】有用的持久事实，"
                + "不要提取一次性的任务内容、临时问题和回答正文。"
                + "只输出 JSON 数组，格式：[{\"content\":\"一句独立可读的事实\",\"category\":\"fact|instruction|project\"}]，最多 3 条；没有值得记的就输出 []。不要输出任何其他内容。";
        String user = "用户：" + question + "\n\n助手：" + answer;
        String out = chatModel.call(new Prompt(List.of(new SystemMessage(sys), new UserMessage(user)),
                        OpenAiChatOptions.builder().model(model).build()))
                .getResult().getOutput().getText();
        String json = out == null ? "" : out.trim().replaceAll("^```(json)?\\s*|\\s*```$", "");
        JSONArray arr = JSON.parseArray(json);
        if (arr == null || arr.isEmpty()) return;

        int cap = maxPerUser();
        long existing = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUid, uid));
        int saved = 0;
        for (int i = 0; i < arr.size() && saved < 3; i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o == null) continue;
            String content = o.getString("content");
            if (content == null) continue;
            content = content.trim();
            if (content.length() < 8) continue;          // 太短的不值得存
            if (content.length() > MAX_CONTENT_CHARS) content = content.substring(0, MAX_CONTENT_CHARS);
            String category = o.getString("category");
            if (!"instruction".equals(category) && !"project".equals(category)) category = "fact";
            // 去重：同用户同内容精确匹配跳过（语义去重留给后续向量召回版本）
            Long dup = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUid, uid).eq(UserMemory::getContent, content));
            if (dup != null && dup > 0) continue;
            if (existing >= cap) {
                log.info("[Memory] 用户 {} 记忆已达上限 {} 条，跳过自动写入（请在个人设置清理）", uid, cap);
                break;
            }
            UserMemory m = new UserMemory();
            m.setUid(uid);
            m.setContent(content);
            m.setCategory(category);
            m.setSource("auto");
            m.setSourceSessionId(sessionId);
            m.setHitCount(0);
            memoryMapper.insert(m);
            existing++;
            saved++;
        }
        if (saved > 0) log.info("[Memory] 用户 {} 新增记忆 {} 条", uid, saved);
    }

    // ==================== 手动管理（个人设置页） ====================

    /** 本人记忆列表（按更新时间倒序） */
    public List<UserMemory> list(String uid) {
        return memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUid, uid)
                .orderByDesc(UserMemory::getUpdateTime));
    }

    /** 手动添加（source=manual） */
    public UserMemory addManual(String uid, String content, String category) {
        String c = normalize(content);
        long existing = memoryMapper.selectCount(new LambdaQueryWrapper<UserMemory>().eq(UserMemory::getUid, uid));
        if (existing >= maxPerUser()) throw new BizException("记忆已达上限 " + maxPerUser() + " 条，请先清理再添加");
        UserMemory m = new UserMemory();
        m.setUid(uid);
        m.setContent(c);
        m.setCategory("instruction".equals(category) || "project".equals(category) ? category : "fact");
        m.setSource("manual");
        m.setHitCount(0);
        memoryMapper.insert(m);
        return m;
    }

    /** 编辑内容（仅本人） */
    public void updateContent(String id, String uid, String content) {
        UserMemory m = mustOwn(id, uid);
        m.setContent(normalize(content));
        m.setUpdateTime(LocalDateTime.now());
        memoryMapper.updateById(m);
    }

    /** 删除（仅本人） */
    public void delete(String id, String uid) {
        mustOwn(id, uid);
        memoryMapper.deleteById(id);
    }

    private UserMemory mustOwn(String id, String uid) {
        UserMemory m = id == null ? null : memoryMapper.selectById(id);
        if (m == null || uid == null || !uid.equals(m.getUid())) throw new BizException(404, "记忆不存在");
        return m;
    }

    private String normalize(String content) {
        if (content == null || content.isBlank()) throw new BizException("记忆内容不能为空");
        String c = content.trim();
        if (c.length() > MAX_CONTENT_CHARS) c = c.substring(0, MAX_CONTENT_CHARS);
        return c;
    }
}
