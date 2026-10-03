package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.SessionShareMapper;
import com.wenqu.ai.model.SessionShare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话只读分享：把一段对话以链接形式分享出去，链接持有者可看、不可续聊。
 *
 * <p><b>公开面收窄</b>（链接即凭据，泄露面要可控）：只读页只给消息正文与引用来源的
 * 文档名/章节/相关度，**不给知识块全文**——片段全文等于把知识库正文带出去，而对话本身
 * 是分享者自愿公开的。工具调用过程、思考全文、Token 用量等内部信息一律不下发。
 *
 * <p><b>停止后重新开启会换新 token</b>：否则"先停用、后开启"会让此前流转在外的旧链接
 * 恢复访问——用户停用的意图就是"不想再被看到"。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionShareService {

    private final SessionShareMapper sessionShareMapper;
    private final SessionService sessionService;

    /** 分享记录（未分享返回 null） */
    public SessionShare get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionShareMapper.selectOne(new LambdaQueryWrapper<SessionShare>()
                .eq(SessionShare::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    /**
     * 开启分享（幂等）：无记录则新建；已有记录则重新启用并**换新 token**（旧链接立即失效）。
     * 调用方须先校验会话归属。
     */
    public SessionShare enable(String sessionId, String uid) {
        SessionShare exist = get(sessionId);
        String token = newToken();
        if (exist == null) {
            SessionShare s = new SessionShare();
            s.setSessionId(sessionId);
            s.setToken(token);
            s.setEnabled(1);
            s.setCreatedBy(uid);
            s.setVisitCount(0);
            sessionShareMapper.insert(s);
            log.info("[SHARE] 会话分享已开启 session={} by={}", sessionId, uid);
            return s;
        }
        sessionShareMapper.update(null, new LambdaUpdateWrapper<SessionShare>()
                .eq(SessionShare::getId, exist.getId())
                .set(SessionShare::getToken, token)
                .set(SessionShare::getEnabled, 1)
                .set(SessionShare::getCreatedBy, uid)
                .set(SessionShare::getVisitCount, 0)
                .set(SessionShare::getLastVisitAt, null));
        exist.setToken(token);
        exist.setEnabled(1);
        exist.setCreatedBy(uid);
        exist.setVisitCount(0);
        log.info("[SHARE] 会话分享已重新开启（换新 token，旧链接失效）session={} by={}", sessionId, uid);
        return exist;
    }

    /** 停止分享（保留记录；下次开启会换新 token）。返回是否停止了一条有效记录 */
    public boolean disable(String sessionId) {
        SessionShare exist = get(sessionId);
        if (exist == null) return false;
        sessionShareMapper.update(null, new LambdaUpdateWrapper<SessionShare>()
                .eq(SessionShare::getId, exist.getId())
                .set(SessionShare::getEnabled, 0));
        log.info("[SHARE] 会话分享已停止 session={}", sessionId);
        return true;
    }

    /**
     * 公开解析：token 有效且 enabled=1 才返回（否则 null——调用方按 404 处理，不泄露存在性），
     * 顺带累计访问量（best-effort：统计失败不影响访问）。
     */
    public SessionShare resolvePublic(String token) {
        if (token == null || token.isBlank()) return null;
        SessionShare s = sessionShareMapper.selectOne(new LambdaQueryWrapper<SessionShare>()
                .eq(SessionShare::getToken, token)
                .last("LIMIT 1"));
        if (s == null || s.getEnabled() == null || s.getEnabled() != 1) return null;
        try {
            sessionShareMapper.update(null, new LambdaUpdateWrapper<SessionShare>()
                    .eq(SessionShare::getId, s.getId())
                    .setSql("visit_count = visit_count + 1")
                    .set(SessionShare::getLastVisitAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[SHARE] 访问量累计失败（不影响访问）: token={} - {}", token, e.getMessage());
        }
        return s;
    }

    /**
     * 公开消息列表：裁剪到"可对外"的字段——正文 + 角色的时间；助手消息带回引用来源的
     * 文档名/章节/相关度（不带 snippet 与知识块 id）。
     */
    public List<Map<String, Object>> publicHistory(String sessionId) {
        List<Map<String, Object>> history = sessionService.getHistory(sessionId);
        if (history == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : history) {
            String role = m.get("role") == null ? "" : String.valueOf(m.get("role"));
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String content = m.get("content") == null ? "" : String.valueOf(m.get("content"));
            if (content.isBlank()) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("role", role);
            one.put("content", content);
            one.put("time", m.get("time"));
            Object srcs = m.get("sources");
            if ("assistant".equals(role) && srcs instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> slim = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> s)) continue;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ref", s.get("ref"));
                    r.put("fileName", s.get("fileName"));
                    r.put("title", s.get("title"));
                    r.put("score", s.get("rerankScore") != null ? s.get("rerankScore") : s.get("score"));
                    slim.add(r);
                }
                if (!slim.isEmpty()) one.put("sources", slim);
            }
            out.add(one);
        }
        return out;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 会话标题（只读页头部展示；取不到返回空串——分享页头部会回退成"对话分享"） */
    public String sessionTitle(String sessionId) {
        try {
            var s = sessionService.sessionById(sessionId);
            return s == null || s.getTitle() == null ? "" : s.getTitle();
        } catch (Exception e) {
            return "";
        }
    }
}
