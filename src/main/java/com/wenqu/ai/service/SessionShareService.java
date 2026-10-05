package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.SessionShareMapper;
import com.wenqu.ai.model.Session;
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

    /**
     * 列出我分享过的全部会话（分享管理页数据源）。
     *
     * <p><b>为什么按 created_by 而不是 join 会话表</b>：会话可以被改名、也可以被删除。
     * join 现查的话，改名后列表跟着变（还算合理），但会话一删这条分享记录就整条消失——
     * 用户看到的不是"这条链接已经失效"，而是"我好像没分享过"，恰好在最需要知道的时候失去感知。
     * 所以按 created_by 枚举本��人的分享记录，标题取快照（缺失时回落到当前会话标题）。
     *
     * <p><b>悬空检测</b>：{@code orphaned=true} 表示会话已不存在。这类的 enabled 会被
     * {@link #disable} 收敛成 0——列表页据此强制显示"已停止"，避免用户看到一个
     * 实际已打不开（getHistory 过滤 deleted 后为空页）的链接还标着生效中。
     */
    public List<Map<String, Object>> listMine(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        List<SessionShare> rows = sessionShareMapper.selectList(new LambdaQueryWrapper<SessionShare>()
                .eq(SessionShare::getCreatedBy, uid)
                .orderByDesc(SessionShare::getUpdateTime)
                .orderByDesc(SessionShare::getCreateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionShare s : rows) {
            Session live = null;
            try {
                live = sessionService.sessionById(s.getSessionId());
            } catch (Exception e) {
                log.warn("[SHARE] 列表取会话失败 session={} - {}", s.getSessionId(), e.getMessage());
            }
            boolean orphaned = live == null;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("sessionId", s.getSessionId());
            String title = live != null && live.getTitle() != null ? live.getTitle() : null;
            if (title == null || title.isBlank()) title = s.getTitleSnapshot();
            one.put("title", (title == null || title.isBlank()) ? "（无标题会话）" : title);
            one.put("token", s.getToken());
            // 悬空记录一律按已停用呈现：链接实际已打不开，报"生效中"是误导
            boolean on = !orphaned && s.getEnabled() != null && s.getEnabled() == 1;
            one.put("enabled", on);
            one.put("orphaned", orphaned);
            one.put("visitCount", s.getVisitCount() == null ? 0 : s.getVisitCount());
            one.put("lastVisitAt", s.getLastVisitAt());
            one.put("createTime", s.getCreateTime());
            one.put("updateTime", s.getUpdateTime());
            out.add(one);
        }
        return out;
    }

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
        // 标题快照：会话改名/删除后列表页仍能认出"分享过的是哪一段"
        String snap = null;
        try {
            var sess = sessionService.sessionById(sessionId);
            snap = (sess == null || sess.getTitle() == null) ? null : sess.getTitle();
        } catch (Exception e) {
            log.warn("[SHARE] 取标题快照失败 session={} - {}", sessionId, e.getMessage());
        }
        if (exist == null) {
            SessionShare s = new SessionShare();
            s.setSessionId(sessionId);
            s.setToken(token);
            s.setEnabled(1);
            s.setCreatedBy(uid);
            s.setTitleSnapshot(snap);
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
                .set(SessionShare::getTitleSnapshot, snap)
                .set(SessionShare::getVisitCount, 0)
                .set(SessionShare::getLastVisitAt, null));
        exist.setToken(token);
        exist.setEnabled(1);
        exist.setCreatedBy(uid);
        exist.setTitleSnapshot(snap);
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
     * 强制停用（会话被删除时调用，best-effort）。
     *
     * <p>会话软删除不会级联到分享表，不处理的话这条记录会以 enabled=1 永远悬着：
     * token 仍在、列表页还会标"生效中"，而访客打开的是一个空页（消息已被 deleted 过滤）。
     * 这里不抛异常——会话删除是用户的主意图，分享记录清理失败只该记日志不该让删除失败。
     */
    public void disableQuietly(String sessionId) {
        try {
            sessionShareMapper.update(null, new LambdaUpdateWrapper<SessionShare>()
                    .eq(SessionShare::getSessionId, sessionId)
                    .eq(SessionShare::getEnabled, 1)
                    .set(SessionShare::getEnabled, 0));
        } catch (Exception e) {
            log.warn("[SHARE] 随会话删除停用分享失败 session={} - {}", sessionId, e.getMessage());
        }
    }

    /**
     * 公开解析：token 有效、enabled=1 **且会话仍在**才返回（否则 null——调用方按 404 处理，
     * 不泄露存在性），顺带累计访问量（best-effort：统计失败不影响访问）。
     *
     * <p>会话存活性是第二道闸：{@code deleteSession} 已会联动停用，但那是 best-effort，
     * 且存量脏数据（本次上线前删掉的会话）不会自己好。少一次会话查询换"绝不把空页当有效分享
     * 链接发出去"——访客拿到空白页却没报错，只会以为链接坏了再去问分享者。
     */
    public SessionShare resolvePublic(String token) {
        if (token == null || token.isBlank()) return null;
        SessionShare s = sessionShareMapper.selectOne(new LambdaQueryWrapper<SessionShare>()
                .eq(SessionShare::getToken, token)
                .last("LIMIT 1"));
        if (s == null || s.getEnabled() == null || s.getEnabled() != 1) return null;
        boolean sessionAlive;
        try {
            sessionAlive = sessionService.sessionById(s.getSessionId()) != null;
        } catch (Exception e) {
            // 取不到不等于没有：宁可放过一次空页，也不要在库抖动时把有效链接判死
            log.warn("[SHARE] 会话存活性核查失败，按有效处理 token={} - {}", token, e.getMessage());
            sessionAlive = true;
        }
        if (!sessionAlive) {
            log.info("[SHARE] 分享对应会话已删除，就地停用 session={}", s.getSessionId());
            disableQuietly(s.getSessionId());
            return null;
        }
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
     * 彻底清除一条分享记录（分享管理页的"清除此记录"）。
     *
     * <p><b>硬约束：只允许删已停用（enabled=0）的</b>。生效中的链接随时可能被访客打开，
     * 直接删会让访问量统计断档、也让用户失去"这条还在外发"的感知——必须先走停止分享。
     * 会话已删除的悬空记录天然是停用态，所以这是它们唯一的清理出口。
     *
     * <p>调用方须先按 created_by 校验归属（见 ChatController 的对应端点）。
     *
     * @return 是否真的删掉了一条（false = 不存在、不是本人的、或仍在生效中）
     */
    public boolean purge(String sessionId, String uid) {
        SessionShare exist = get(sessionId);
        if (exist == null) return false;
        if (exist.getCreatedBy() == null || !exist.getCreatedBy().equals(uid)) return false;
        if (exist.getEnabled() != null && exist.getEnabled() == 1) return false;
        sessionShareMapper.delete(new LambdaQueryWrapper<SessionShare>()
                .eq(SessionShare::getId, exist.getId())
                .eq(SessionShare::getEnabled, 0)); // 二次确认：并发下若刚被重新开启就不删
        log.info("[SHARE] 分享记录已清除 session={} by={}", sessionId, uid);
        return true;
    }

    /**
     * 公开消息列表：裁剪到"可对外"的字段——正文 + 角色的时间；助手消息带回引用来源的
     * 文档名/章节/相关度（不带 snippet 与知识块 id）。
     * <p>
     * 外发来源必须带 origin：分享页靠 {@code origin === 'WEB'} 区分联网/库内来源，
     * 联网来源天然没有 fileName（那是库内文档名），裁掉 origin 会被当成库内来源
     * 渲染成"来源文档不可用"。siteName/url 一并带上：前者是联网来源的展示名，
     * 后者供页面给出原网页入口（两者都是公开信息，不含库内容）。
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
                    r.put("origin", s.get("origin"));
                    r.put("fileName", s.get("fileName"));
                    r.put("siteName", s.get("siteName"));
                    r.put("url", s.get("url"));
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
