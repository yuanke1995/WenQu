package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.dto.SessionInfo;
import com.wenqu.ai.dto.SessionPage;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.mapper.SessionShareMapper;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.SessionShare;
import com.wenqu.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.wenqu.ai.util.RequestUser;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 会话管理（MySQL + Redis 双层存储）
 * <p>
 * 写入：MySQL 持久化 → Redis 缓存（写穿透）
 * 读取：MySQL 优先 → Redis 兜底
 * 删除：MySQL 软删除 + Redis 清理
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String KEY_PREFIX = "ai-doc:session:";

    /**
     * 原子追加：RPUSH 消息 → LTRIM 保留最近 max 条 → 重置 EXPIRE
     */
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RPUSH', KEYS[1], ARGV[1]);" +
                    "redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1);" +
                    "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]));" +
                    "return 1;", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final SessionShareMapper sessionShareMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建新会话（MySQL + Redis）
     *
     * @param userId 归属用户（null/空白归入 anonymous 历史兼容池）
     */
    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        try {
            Session session = new Session();
            session.setId(sessionId);
            session.setUserId(normalizeUser(userId));
            session.setMessageCount(0);
            sessionMapper.insert(session);
        } catch (Exception e) {
            log.warn("创建会话 MySQL 写入失败: {}", e.getMessage());
        }
        return sessionId;
    }

    /**
     * 「新建对话」入口：先复用当前用户已存在的空会话（无消息、未绑定智能体、未置顶），没有才真正新建。
     * <p>
     * 空会话数量恒定为 1（前端侧栏本就隐藏空会话，堆积的空会话不出现在界面上，只会在游标分页里
     * 挤占真实会话的名额——首屏 20 条里大半是空会话时，侧栏首屏看起来就只剩几条）。
     * 复用条件与前端 useChatEngine 的本地复用口径一致：未绑定智能体的空会话才复用（已绑定的带着
     * 自己的绑定，复用会让「切换智能体」看起来没生效）。定时任务 / 工作流 / MCP 建会话走
     * createSession，不参与复用——它们的会话有各自语义，不该占用用户待用的空会话。
     * <p>
     * 并发：两次「新建对话」同时进来会各自查不到空会话、各建一个，故按用户加短锁；
     * 抢不到锁时稍等再查一次（持锁方已落库即可命中复用）。
     */
    public String createOrReuseEmptySession(String userId) {
        final String uid = normalizeUser(userId);
        final String lockKey = KEY_PREFIX + "empty-lock:" + uid;
        Boolean locked = null;
        try {
            locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(locked)) {
                // 并发点击：等持锁方写完再查，避免两边都扑空各建一个
                Thread.sleep(150);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 加锁失败不阻断建会话（Redis 抖动不该让「新建对话」失败），退化为无锁查询
            log.warn("空会话复用加锁失败: {}", e.getMessage());
        }
        try {
            Session empty = findEmptySession(uid);
            return empty != null ? empty.getId() : createSession(uid);
        } finally {
            if (Boolean.TRUE.equals(locked)) {
                try {
                    redisTemplate.delete(lockKey);
                } catch (Exception e) {
                    log.warn("空会话复用锁释放失败: {}", e.getMessage());
                }
            }
        }
    }

    /** 取当前用户可复用的空会话（无消息、未绑定智能体、未置顶；取最近更新的那条） */
    private Session findEmptySession(String uid) {
        LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
        q.eq(Session::getUserId, uid)
                .eq(Session::getMessageCount, 0)
                .isNull(Session::getAgentId)
                .and(w -> w.isNull(Session::getIsPinned).or().ne(Session::getIsPinned, 1))
                .orderByDesc(Session::getUpdateTime)
                .last("LIMIT 1");
        List<Session> rows = sessionMapper.selectList(q);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 归属校验：会话不存在抛 404；存在但不属于该用户则抛 403。
     * anonymous 名下的存量会话仅未登录（anonymous）调用方可访问；登录用户一律严格隔离，
     * 不允许读取他人（含历史匿名）会话。
     *
     * @return 校验通过的会话实体
     */
    public Session assertOwned(String sessionId, String userId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wenqu.ai.common.BizException(404, "会话不存在或已被删除");
        }
        String owner = session.getUserId() == null || session.getUserId().isBlank()
                ? RequestUser.ANONYMOUS : session.getUserId();
        String uid = normalizeUser(userId);
        boolean anonymousSession = RequestUser.ANONYMOUS.equals(owner);
        if (!owner.equals(uid) && !(anonymousSession && canAccessAnonymousPool(userId))) {
            throw new com.wenqu.ai.common.BizException(403, "无权访问该会话");
        }
        return session;
    }

    /** 按 id 取会话（**不做归属校验**，调用方负责）：会话只读分享页按 token 取标题用 */
    public Session sessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionMapper.selectById(sessionId);
    }

    /** anonymous 池访问门槛：仅调用方自身为 anonymous（未登录）时可访问 */
    private boolean canAccessAnonymousPool(String userId) {
        return RequestUser.ANONYMOUS.equals(normalizeUser(userId));
    }

    /** 用户标识规范化：空值归 anonymous */
    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank()
                ? RequestUser.ANONYMOUS : userId;
    }

    /**
     * 确保指定 ID 的会话存在且归属当前用户（chat 传入不存在/已被删除的 sessionId 时补建，兼容旧客户端行为）
     *
     * @return 校验/补建后的会话 ID
     */
    public String ensureSession(String sessionId, String userId) {
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(normalizeUser(userId));
        session.setMessageCount(0);
        try {
            sessionMapper.insert(session);
        } catch (Exception e) {
            // 并发下同 ID 重复插入等异常：仅记录，聊天流程继续（消息追加不依赖会话记录存在）
            log.warn("补建会话记录失败 (session={}): {}", sessionId, e.getMessage());
        }
        return sessionId;
    }

    /**
     * 查询会话列表（游标分页版；仅当前用户；未登录调用方额外并入 anonymous 存量会话；支持关键词搜索）。
     * <p>
     * 排序口径与旧版一致：置顶优先、其余按更新时间倒序（id 兜底同刻排序）。分页设计：
     * <ul>
     *   <li>置顶会话数量由用户行为天然有界（个人手动置顶），首页整表返回、不参与游标；</li>
     *   <li>非置顶会话按 (update_time, id) 复合值游标翻页：cursor = Base64url("epochMillis|会话ID")。
     *       值比较式（非偏移量），翻页间隙发生增删也不会漏页/重页；</li>
     *   <li>groupCounts 返回与前端分组口径完全一致的各桶总数（置顶/今天/7天内/更早，仅统计有消息的会话），
     *       侧栏组头展示全量数字，不随列表递增加载漂移；桶界按服务器本地时区计算（自部署场景服务器与用户同时区）。</li>
     * </ul>
     * 异常不做静默吞并：查询失败直接向上抛（前端展示错误），避免"DB 故障伪装成空列表"。
     *
     * @param keyword 可选，按标题或消息内容模糊匹配；空/空白返回全量
     * @param cursor  分页游标（首页传 null/空白；后续页传上一页返回的 nextCursor）
     * @param size    每页条数（1~100，服务端强制收敛）
     */
    public SessionPage listSessions(String userId, String keyword, String cursor, int size) {
        final String uid = normalizeUser(userId);
        // anonymous 兼容池口径：仅调用方自身为 anonymous 时并入（等价于旧 canAccessAnonymousPool 判定）
        final boolean anonPool = RequestUser.ANONYMOUS.equals(uid);
        final int pageSize = Math.max(1, Math.min(size, 100));
        final String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        // ---------- 分组计数：窄列查询（is_pinned/update_time/message_count）+ Java 分桶，一次查询覆盖全部桶 ----------
        LambdaQueryWrapper<Session> countQ = baseWrapper(uid, anonPool, kw);
        countQ.select(Session::getIsPinned, Session::getUpdateTime, Session::getMessageCount);
        LocalDateTime startOfToday = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime weekStart = startOfToday.minusDays(6);
        long pinned = 0, today = 0, week = 0, earlier = 0;
        for (Session s : sessionMapper.selectList(countQ)) {
            if (s.getMessageCount() == null || s.getMessageCount() <= 0) continue;
            if (s.getIsPinned() != null && s.getIsPinned() == 1) { pinned++; continue; }
            LocalDateTime t = s.getUpdateTime();
            if (t != null && !t.isBefore(startOfToday)) today++;
            else if (t != null && !t.isBefore(weekStart)) week++;
            else earlier++;
        }
        Map<String, Long> groupCounts = new LinkedHashMap<>();
        groupCounts.put("pinned", pinned);
        groupCounts.put("today", today);
        groupCounts.put("week", week);
        groupCounts.put("earlier", earlier);

        // ---------- 本页条目：置顶（仅首页） + 非置顶游标页 ----------
        List<SessionInfo> items = new ArrayList<>();
        if (cursor == null || cursor.isBlank()) {
            LambdaQueryWrapper<Session> pinnedQ = baseWrapper(uid, anonPool, kw);
            pinnedQ.eq(Session::getIsPinned, 1)
                    .orderByDesc(Session::getUpdateTime);
            sessionMapper.selectList(pinnedQ).forEach(s -> items.add(toInfo(s)));
        }
        LambdaQueryWrapper<Session> pageQ = baseWrapper(uid, anonPool, kw);
        pageQ.eq(Session::getIsPinned, 0);
        boolean hasCursor = cursor != null && !cursor.isBlank();
        if (hasCursor) {
            PageCursor c = decodeCursor(cursor);
            // (update_time, id) 复合键续页：严格小于上一页末条，保证同刻会话不重不漏
            pageQ.and(w -> w.lt(Session::getUpdateTime, c.time())
                    .or(w2 -> w2.eq(Session::getUpdateTime, c.time()).lt(Session::getId, c.id())));
        }
        pageQ.orderByDesc(Session::getUpdateTime)
                .orderByDesc(Session::getId)
                .last("LIMIT " + (pageSize + 1)); // 多取一条探测 hasMore；pageSize 已收敛 1~100，无注入面
        List<Session> rows = sessionMapper.selectList(pageQ);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) rows = rows.subList(0, pageSize);
        rows.forEach(s -> items.add(toInfo(s)));
        markShared(items);

        SessionPage page = new SessionPage();
        page.setItems(items);
        if (hasMore && !rows.isEmpty()) {
            page.setNextCursor(encodeCursor(rows.get(rows.size() - 1)));
            // 极端防御：末条 update_time 为 null 时无法生成游标，按无更多收尾（DATETIME 非空默认，理论不可达）
            hasMore = page.getNextCursor() != null;
        }
        page.setHasMore(hasMore);
        page.setGroupCounts(groupCounts);
        page.setTotal(pinned + today + week + earlier);
        return page;
    }

    /** 会话列表公共过滤条件：归属（+anonymous 兼容池）与关键词，供计数/置顶/分页三路查询复用（避免 OR 分组被重复拼装） */
    private LambdaQueryWrapper<Session> baseWrapper(String uid, boolean anonPool, String kw) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> {
            w.eq(Session::getUserId, uid);
            if (anonPool) w.or().eq(Session::getUserId, RequestUser.ANONYMOUS);
        });
        if (kw != null) {
            String esc = escapeLike(kw);
            wrapper.and(w -> w.like(Session::getTitle, escapeLikeWildcard(kw))
                    .or().inSql(Session::getId,
                            "SELECT DISTINCT session_id FROM c_ai_message WHERE deleted=0 AND content LIKE '%"
                                    + esc + "%'"));
        }
        return wrapper;
    }

    /**
     * 给列表项打「正在对外分享」标记（侧栏行内小图标用）。
     *
     * <p>一次 {@code in} 查询覆盖整页，不逐行查——列表是首屏必走的路径，逐行打会变成 N+1。
     * 直连 {@link SessionShareMapper} 而非注入 {@code SessionShareService}：后者依赖本类，
     * 反向注入会构成循环依赖。查询失败按"无标记"处理：标记是辅助信息，不该让整个列表打不开。
     */
    private void markShared(List<SessionInfo> items) {
        if (items == null || items.isEmpty()) return;
        try {
            Set<String> active = sessionShareMapper.selectList(
                            new LambdaQueryWrapper<SessionShare>()
                                    .in(SessionShare::getSessionId,
                                            items.stream().map(SessionInfo::getId).toList())
                                    .eq(SessionShare::getEnabled, 1))
                    .stream().map(SessionShare::getSessionId).collect(Collectors.toSet());
            for (SessionInfo info : items) {
                info.setShared(active.contains(info.getId()));
            }
        } catch (Exception e) {
            log.warn("会话列表分享标记查询失败（降级为无标记）: {}", e.getMessage());
        }
    }

    /** 实体 → 列表项 DTO（含会话级智能体绑定：NULL=未绑定不发字段） */
    private SessionInfo toInfo(Session s) {
        SessionInfo info = new SessionInfo();
        info.setId(s.getId());
        info.setTitle(s.getTitle());
        info.setMessageCount(s.getMessageCount());
        info.setUpdateTime(s.getUpdateTime());
        info.setIsPinned(s.getIsPinned());
        info.setIsFavorite(s.getIsFavorite());
        if (s.getAgentId() != null) {
            info.setAgentId(s.getAgentId());
            info.setAgentName(s.getAgentName() == null ? "" : s.getAgentName());
        }
        return info;
    }

    /**
     * 游客分享页的历史会话：按访客 uid + 智能体取最近若干条（只含有消息的）。
     * <p>
     * 不按 uid 一把列出：访客 uid 是浏览器级的（同一浏览器打开多个分享链接共用一个 uid），
     * 不带 agentId 过滤会把别个智能体的会话标题也暴露出来。
     * 单页取满即止、不做游标——一个访客在某个智能体下的会话数量由手动点击天然有界。
     */
    public List<SessionInfo> listSessionsForAgent(String userId, String agentId, int limit) {
        if (agentId == null || agentId.isBlank()) return List.of();
        LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
        q.eq(Session::getUserId, normalizeUser(userId))
                .eq(Session::getAgentId, agentId)
                .gt(Session::getMessageCount, 0)
                .orderByDesc(Session::getUpdateTime)
                .orderByDesc(Session::getId)
                // limit 已收敛进 1~100 的整数，无注入面
                .last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        return sessionMapper.selectList(q).stream().map(this::toInfo).toList();
    }

    // ---------- 游标编解码：Base64url("epochMillis|会话ID")，解码失败一律 400（fail-loud，不给静默重启翻页） ----------

    private static final Base64.Encoder CURSOR_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder CURSOR_DEC = Base64.getUrlDecoder();

    private record PageCursor(LocalDateTime time, String id) {}

    private PageCursor decodeCursor(String cursor) {
        try {
            String raw = new String(CURSOR_DEC.decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0) throw new IllegalArgumentException("cursor 缺少分隔符");
            long millis = Long.parseLong(raw.substring(0, sep));
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            return new PageCursor(time, raw.substring(sep + 1));
        } catch (Exception e) {
            throw new com.wenqu.ai.common.BizException(400, "会话列表游标无效，请刷新后重新加载");
        }
    }

    private String encodeCursor(Session s) {
        if (s == null || s.getUpdateTime() == null) return null;
        long millis = s.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return CURSOR_ENC.encodeToString((millis + "|" + s.getId()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 置顶/取消置顶会话（校验归属）
     */
    public void updatePin(String userId, String sessionId, boolean pinned) {
        assertOwned(sessionId, userId);
        updateFlag(sessionId, pinned, "is_pinned", Session::setIsPinned);
    }

    /**
     * 收藏/取消收藏会话（校验归属）
     */
    public void updateFavorite(String userId, String sessionId, boolean favorite) {
        assertOwned(sessionId, userId);
        updateFlag(sessionId, favorite, "is_favorite", Session::setIsFavorite);
    }

    /**
     * 重命名会话（校验归属；标题去空白并限长 50 字）
     */
    public void renameSession(String userId, String sessionId, String title) {
        assertOwned(sessionId, userId);
        String t = title.trim();
        if (t.length() > 50) t = t.substring(0, 50).trim();
        Session update = new Session();
        update.setId(sessionId);
        update.setTitle(t);
        sessionMapper.updateById(update);
    }

    /**
     * 通用布尔标志更新（置顶/收藏）
     */
    private void updateFlag(String sessionId, boolean flag, String fieldName,
                            java.util.function.BiConsumer<Session, Integer> setter) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wenqu.ai.common.BizException("会话不存在");
        }
        Session update = new Session();
        update.setId(sessionId);
        setter.accept(update, flag ? 1 : 0);
        sessionMapper.updateById(update);
    }

    /**
     * 转义 LIKE 特殊字符（% _ \）+ SQL 单引号（''），防止搜索词干扰匹配或注入 inSql 拼接
     */
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * MP 参数化 {@code like()} 的通配符转义：只转 LIKE 元字符（% _ \），单引号由参数化处理——
     * 复用 {@link #escapeLike} 的引号加大会改变语义（搜「'」变成搜「''」）。与 inSql 内容搜索
     * 路径保持同一通配符口径。
     */
    private String escapeLikeWildcard(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 清除某访客在某个智能体下的全部会话（分享页「清除我的对话记录」），含还没说过话的空会话。
     * <p>
     * 待删范围只在 SQL 里按 (user_id, agent_id) 圈定，<b>不接受调用方传入会话 ID 列表</b>：
     * 游客身份是客户端自报的（visitorId 存 localStorage），能圈定的范围必须严格等于
     * "这个访客在这个智能体名下的"，否则一次请求就能捎带上别人的会话。
     * 逐条走 {@link #deleteSession} 是为了复用它已经做的两件事：停用该会话对外的分享、清 Redis 里的消息缓存。
     *
     * @return 删除的会话数
     */
    public int deleteSessionsOfVisitor(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) return 0;
        final String uid = normalizeUser(userId);
        LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
        q.select(Session::getId)
                .eq(Session::getUserId, uid)
                .eq(Session::getAgentId, agentId);
        List<String> ids = sessionMapper.selectList(q).stream().map(Session::getId).toList();
        int ok = 0;
        for (String id : ids) {
            try {
                deleteSession(uid, id);
                ok++;
            } catch (Exception e) {
                log.warn("[SHARE] 游客清除会话失败 session={}: {}", id, e.getMessage());
            }
        }
        return ok;
    }

    /**
     * 软删除会话（校验归属；MySQL 逻辑删除会话+消息 + Redis 清理）
     */
    public void deleteSession(String userId, String sessionId) {
        assertOwned(sessionId, userId);
        // 停用对外分享：分享表与会话表之间没有外键级联，不主动停就会留下一条 enabled=1 的
        // 悬空记录——token 仍有效、列表标"生效中"，访客打开却是空页（消息已随会话软删）。
        // best-effort：清理失败只记日志，不能让"删除会话"这个用户主意图失败。
        try {
            sessionShareMapper.update(null, new LambdaUpdateWrapper<SessionShare>()
                    .eq(SessionShare::getSessionId, sessionId)
                    .eq(SessionShare::getEnabled, 1)
                    .set(SessionShare::getEnabled, 0));
        } catch (Exception e) {
            log.warn("删除会话时停用分享失败 session={}: {}", sessionId, e.getMessage());
        }
        try {
            sessionMapper.deleteById(sessionId);
            // 同步软删除该会话下的所有消息
            LambdaUpdateWrapper<Message> wrapper = new LambdaUpdateWrapper<>();
            wrapper.set(Message::getDeleted, 1)
                    .eq(Message::getSessionId, sessionId);
            messageMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("删除会话 MySQL 操作失败: {}", e.getMessage());
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("删除会话 Redis 清理失败: {}", e.getMessage());
        }
    }

    /**
     * 批量软删除会话（逐个校验归属，单条失败不中断；返回成功数）
     */
    public int batchDelete(String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int ok = 0;
        for (String id : ids) {
            try {
                deleteSession(userId, id);
                ok++;
            } catch (Exception e) {
                log.warn("[batchDelete] 会话删除失败 id={}: {}", id, e.getMessage());
            }
        }
        return ok;
    }

    /**
     * 获取会话完整历史（MySQL 优先；MySQL 非空时合并 Redis 中未落库的降级消息，空时 Redis 兜底）
     */
    public List<Map<String, Object>> getHistory(String sessionId) {
        List<Map<String, Object>> fromMysql = readFromMysql(sessionId);
        if (!fromMysql.isEmpty()) {
            // MySQL 非空也可能缺最近消息：写侧瞬时失败降级 Redis 的消息标记 mysqlPending，此处补合并并异步补写，
            // 避免"MySQL 有旧数据即不回读 Redis"导致降级窗口内的新消息对用户永久不可见（Redis 30 分钟过期后彻底丢失）
            return mergeRedisPending(sessionId, fromMysql);
        }
        // MySQL 无数据，从 Redis 读取
        List<Map<String, Object>> fromRedis = readRange(sessionId, 0, -1);
        if (!fromRedis.isEmpty()) {
            // 异步 backfill 到 MySQL（新线程，不阻塞当前请求）
            backfillToMysql(sessionId, fromRedis);
        }
        return fromRedis;
    }

    /**
     * 获取最近 N 轮对话（MySQL 直读最近 N 轮，Redis 兜底/合并）
     * 返回 null 表示"读取失败"（fail-loud：调用方需区分"无历史"与"历史读取失败"，不得静默当无历史）
     */
    public List<Map<String, Object>> getRecentHistory(String sessionId, int rounds) {
        List<Map<String, Object>> recent = readRecentFromMysql(sessionId, rounds);
        if (recent == null) return null; // 读失败：显式失败信号，调用方 fail-loud
        if (!recent.isEmpty()) {
            return mergeRedisPending(sessionId, recent);
        }
        // MySQL 无数据，从 Redis 读取（Redis 已按 maxHistory 裁剪）
        List<Map<String, Object>> fromRedis = readRange(sessionId, 0, -1);
        if (!fromRedis.isEmpty()) {
            // 异步 backfill 到 MySQL（新线程，不阻塞当前请求）
            backfillToMysql(sessionId, fromRedis);
        }
        return fromRedis;
    }

    /**
     * 合并 Redis 降级消息：仅追加标记了 mysqlPending 且尚未补写进 MySQL 的条目。
     * 判定基准为 msgId（新格式降级消息自带全局唯一 ID）——已补写（msgId 已落库）跳过；
     * 旧格式（无 msgId 的历史降级消息）退回 role+content+images 判重兜底。
     * 相比纯文本判重，带 msgId 后"用户复读完全相同问题"的两次降级消息各自独立补写，不再误丢。
     * 补写异步幂等执行（backfillInsert 按 msgId 判重，多副本并发合并同一批也不会重复插入）。
     */
    private List<Map<String, Object>> mergeRedisPending(String sessionId, List<Map<String, Object>> fromMysql) {
        try {
            List<Map<String, Object>> cached = readRange(sessionId, 0, -1);
            if (cached.isEmpty()) return fromMysql;
            List<Map<String, Object>> pending = cached.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("mysqlPending")))
                    .collect(Collectors.toList());
            if (pending.isEmpty()) return fromMysql;

            // MySQL 已有消息的 messageId 集合（toMessageMap 输出字段）与旧格式内容判重键
            Set<String> mysqlIds = fromMysql.stream()
                    .map(m -> m.get("messageId"))
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
            Set<String> contentKeys = fromMysql.stream()
                    .map(m -> messageKey(m.get("role"), m.get("content"), m.get("images")))
                    .collect(Collectors.toSet());
            List<Map<String, Object>> merged = new ArrayList<>(fromMysql);
            List<Map<String, Object>> toBackfill = new ArrayList<>();
            for (Map<String, Object> m : pending) {
                String msgId = m.get("msgId") == null ? null : String.valueOf(m.get("msgId"));
                if (msgId != null) {
                    if (mysqlIds.contains(msgId)) continue; // 已补写：不重复展示/补写
                    m.put("messageId", msgId);              // 展示侧暴露与 SSE done 一致的消息 ID
                } else {
                    // 旧格式降级消息：按内容判重（保持历史行为）
                    String ck = messageKey(m.get("role"), m.get("content"), m.get("images"));
                    if (contentKeys.contains(ck)) continue;
                    contentKeys.add(ck);
                }
                merged.add(m);
                toBackfill.add(m);
                if (msgId != null) mysqlIds.add(msgId);
            }
            if (!toBackfill.isEmpty()) {
                List<Map<String, Object>> need = toBackfill;
                ThreadPoolManager.execute(() -> backfillToMysql(sessionId, need));
            }
            return merged;
        } catch (Exception e) {
            log.warn("合并 Redis 降级消息失败 (session={}): {}", sessionId, e.getMessage());
            return fromMysql;
        }
    }

    /** 消息判重键：role + content，content 为空（纯图消息）时附加图片序列，防止同文本合法重复被误判 */
    static String messageKey(Object role, Object content, Object images) {
        String img = images instanceof List<?> list ? JSON.toJSONString(list)
                : (images == null ? "" : String.valueOf(images));
        return String.valueOf(role == null ? "" : role) + '\u0001'
                + String.valueOf(content == null ? "" : content) + '\u0001' + img;
    }

    /**
     * 按 sequence 升序取摘要覆盖点之后的会话消息（历史压缩用）：
     * 只取 sequence &gt; afterSeq 的消息（更早的已被摘要吸收），最多 limit 条。
     * 返回 null 表示读取失败（调用方 fail-loud），空列表表示无新消息。
     */
    public List<Map<String, Object>> getHistoryAfter(String sessionId, long afterSeq, int limit) {
        try {
            LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Message::getSessionId, sessionId)
                    .gt(Message::getSequence, afterSeq)
                    .orderByAsc(Message::getSequence)
                    .last("LIMIT " + Math.max(1, limit));
            List<Message> messages = messageMapper.selectList(wrapper);
            return messages.stream().map(this::toMessageMap).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] MySQL 按序号读取会话历史失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 写回滚动历史摘要（历史压缩）：摘要文本 + 已覆盖的最大消息 sequence。
     * 用 UpdateWrapper 只改这两列，避免整行覆盖（并发轮次里其它列可能刚被更新）。
     */
    public void updateHistorySummary(String sessionId, String summary, long untilSeq) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .set(Session::getHistorySummary, summary)
                    .set(Session::getSummaryUntilSeq, untilSeq));
        } catch (Exception e) {
            log.warn("[CTX] 历史摘要写回失败 (session={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 直读最近 N 轮对话：ORDER BY sequence DESC LIMIT rounds*2 后反转，
     * 避免全量读取该会话所有消息（每次问答该路径被调 2~3 次，会话越长差异越大）
     */
    private List<Map<String, Object>> readRecentFromMysql(String sessionId, int rounds) {
        int limit = Math.max(1, rounds * 2);
        try {
            LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Message::getSessionId, sessionId)
                    .orderByDesc(Message::getSequence)
                    .last("LIMIT " + limit);
            List<Message> messages = messageMapper.selectList(wrapper);
            if (messages.isEmpty()) return Collections.emptyList();
            Collections.reverse(messages); // 恢复时间正序（最新在后）
            return messages.stream().map(this::toMessageMap).collect(Collectors.toList());
        } catch (Exception e) {
            // M6 fail-loud：读失败返回 null（区分"无历史"与"读取失败"），调用方上报而非静默跳过记忆
            log.warn("[FAIL-LOUD] MySQL 读取最近会话历史失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 追加消息（MySQL 持久化 → Redis 缓存），返回消息 ID（用于反馈关联）
     *
     * @param images   该轮回答关联的图片 URL 列表（可为空）
     * @param sources  引用来源 JSON 数组字符串（可为空），如 [{"ref":1,"knowledgeId":..,"docId":..,"fileName":..,"title":..,"snippet":..}]
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources) {
        return appendMessage(sessionId, role, content, images, sources, null, null);
    }

    /**
     * 追加消息（含深度思考全文）：
     * MySQL 优先持久化，失败降级 Redis 缓存（Lua 原子追加 + 上限裁剪 + TTL）
     *
     * @param thinking 思考过程全文（深度思考，可为空）
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources, String thinking) {
        return appendMessage(sessionId, role, content, images, sources, thinking, null);
    }

    /**
     * 追加消息（含检索状态行数据）：retrieved 为 JSON（keywords/refs/terms），随消息持久化使状态行刷新后仍在
     * <p>
     * MySQL 事务写入（会话行锁 + 物理 max 序号，防并发同号与软删复用）→ 瞬时失败延时重试一次 →
     * 仍失败降级 Redis（标记 mysqlPending，读侧合并并异步补写，保证降级窗口消息不永久不可见）
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources, String thinking, String retrieved) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, null);
    }

    /**
     * 追加消息（含产物交付清单）：artifacts 为 JSON 数组字符串（[{url,filename,size,description}]），
     * 存原始 URL（展示层签名），随消息持久化使产物卡片刷新后仍在。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources, String thinking, String retrieved, String artifacts) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts, null);
    }

    /**
     * 追加消息（含工具调用过程）：toolCalls 为 JSON 数组字符串（[{name,status,elapsedMs,args,result}]），
     * 随消息持久化使工具调用状态刷新后仍可回显（工具调用状态展示与留存）。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts, toolCalls, null);
    }

    /**
     * 追加消息（含附件元信息）：attachments 为 JSON 数组字符串（[{name,mime,size}]，不含内容本体），
     * 随用户消息持久化使附件标签刷新后仍可回显。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, null);
    }

    /**
     * 追加消息（含 Token 用量）：tokens 为 JSON（context/budget/hits/output/prompt/outputIsReal/total），
     * 随助手消息持久化使「本次用量/会话累计」刷新与历史会话回看时仍可用（此前仅随 done 事件下发、不落库）。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, tokens, null, null);
    }

    /**
     * 追加消息（含回答时间线）：timeline 为 JSON 数组字符串（正文区间段/工具下标段/产物下标段），
     * 与 content、toolCalls、artifacts 同源落库，刷新与历史会话据此还原正文与工具/产物的交错顺序。
     * 不落库则历史回看只能整段正文 + 底部汇总（过程感丢失）。
     *
     * @param processText 过程独白全文（&lt;process&gt; 标签内，与正文分流；时间线 process 段区间指向它，可为空）
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens, String timeline, String processText) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, tokens, timeline, processText, null, null);
    }

    /**
     * 追加消息（含智能体归属）：agentId/agentName 为本轮生效智能体的快照（助手消息专用）。
     * 落库意义：智能体是会话级绑定的，但归属要随消息留存——改名/删除后历史仍如实回显「这条是谁答的」。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens, String timeline, String processText,
                                String agentId, String agentName) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, tokens, timeline, processText, agentId, agentName, null);
    }

    /**
     * 追加消息（含生效模型）：model 为本轮生效模型引用（助手消息专用，随 tokens 同时落库）。
     * 用途：使用统计按模型分组聚合（每日趋势/模型用量占比）；存量行 model 为 NULL 归入「未知」。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens, String timeline, String processText,
                                String agentId, String agentName, String model) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, tokens, timeline, processText, agentId, agentName, model, null, null, null);
    }

    /**
     * 追加消息（含相关推荐）：related 为 JSON 数组字符串（「接下来可以」的推荐问句清单）。
     * 落库意义：此前只随 done 事件下发、不入库，刷新后推荐整块消失；而模型把正文误写进
     * &lt;related&gt; 时剥离即等于内容永久丢失（RagService 的 related 护栏要靠这一列留痕）。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens, String timeline, String processText,
                                String agentId, String agentName, String model, String related,
                                String mentions, String historyRefs) {
        return appendMessage(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                toolCalls, attachments, tokens, timeline, processText, agentId, agentName, model, related,
                mentions, historyRefs, null);
    }

    /**
     * 追加消息（含计划批准卡）：plan 为 JSON（{plan,status}，计划模式本轮的执行计划与裁决结果）。
     * 落库意义：计划卡此前只活在实时流里——刷新/切会话后所有已批准/未批准的计划卡全部消失，
     * 只剩「还在等确认」的那张能从批准记录重建；随助手消息落库后每轮计划各留一张。
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources,
                                String thinking, String retrieved, String artifacts, String toolCalls,
                                String attachments, String tokens, String timeline, String processText,
                                String agentId, String agentName, String model, String related,
                                String mentions, String historyRefs, String plan) {
        // 1. MySQL 持久化
        try {
            return appendToMysql(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                    toolCalls, attachments, tokens, timeline, processText, agentId, agentName, model, related,
                    mentions, historyRefs, plan);
        } catch (Exception e) {
            log.warn("MySQL 追加消息失败 (session={}): {}", sessionId, e.getMessage());
        }
        // 瞬时故障（连接中断/主备切换等）短延时重试一次，多数抖动可自愈，避免直接进入 Redis 降级分叉
        try {
            Thread.sleep(300);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        try {
            return appendToMysql(sessionId, role, content, images, sources, thinking, retrieved, artifacts,
                    toolCalls, attachments, tokens, timeline, processText, agentId, agentName, model, related,
                    mentions, historyRefs, plan);
        } catch (Exception e) {
            log.warn("MySQL 追加消息重试仍失败 (session={}): {}", sessionId, e.getMessage());
        }

        // 2. Redis 缓存（MySQL 持续不可用也不影响前端体验；mysqlPending 标记使读侧能合并补写，防消息丢失）
        try {
            Map<String, Object> redisMsg = new HashMap<>();
            redisMsg.put("role", role);
            redisMsg.put("content", content);
            redisMsg.put("mysqlPending", true);
            // 降级消息自带全局唯一 msgId：补写 MySQL 时按 id 幂等（同一消息不重复插；
            // 且不再被"与历史消息同文"误判为重复——用户复读完全相同的问题也各自补写，不丢失）
            redisMsg.put("msgId", UUID.randomUUID().toString().replace("-", ""));
            if (thinking != null && !thinking.isBlank()) {
                redisMsg.put("thinking", thinking);
            }
            if (images != null && !images.isEmpty()) {
                redisMsg.put("images", images);
            }
            if (sources != null && !sources.isBlank()) {
                try {
                    redisMsg.put("sources", JSON.parseArray(sources, Map.class));
                } catch (Exception ignored) {
                }
            }
            if (retrieved != null && !retrieved.isBlank()) {
                try {
                    redisMsg.put("retrieved", JSON.parse(retrieved));
                } catch (Exception ignored) {
                }
            }
            if (related != null && !related.isBlank()) {
                try {
                    redisMsg.put("related", JSON.parseArray(related, String.class));
                } catch (Exception ignored) {
                }
            }
            if (artifacts != null && !artifacts.isBlank()) {
                try {
                    redisMsg.put("artifacts", JSON.parse(artifacts));
                } catch (Exception ignored) {
                }
            }
            if (toolCalls != null && !toolCalls.isBlank()) {
                try {
                    redisMsg.put("toolCalls", JSON.parse(toolCalls));
                } catch (Exception ignored) {
                }
            }
            if (attachments != null && !attachments.isBlank()) {
                try {
                    redisMsg.put("attachments", JSON.parseArray(attachments, Map.class));
                } catch (Exception ignored) {
                }
            }
            if (tokens != null && !tokens.isBlank()) {
                try {
                    redisMsg.put("tokens", JSON.parse(tokens));
                } catch (Exception ignored) {
                }
            }
            if (timeline != null && !timeline.isBlank()) {
                try {
                    redisMsg.put("timeline", JSON.parseArray(timeline, Map.class));
                } catch (Exception ignored) {
                }
            }
            if (processText != null && !processText.isBlank()) {
                redisMsg.put("processText", processText); // 过程独白全文（降级缓存也带，读侧原样透出）
            }
            if (agentId != null && !agentId.isBlank()) {
                redisMsg.put("agentId", agentId);   // 智能体归属（降级缓存同样带：补写 MySQL 后归属不丢）
                redisMsg.put("agentName", agentName == null ? "" : agentName);
            }
            if (model != null && !model.isBlank()) {
                redisMsg.put("model", model);       // 生效模型（与 tokens 同口径随降级缓存携带）
            }
            if (mentions != null && !mentions.isBlank()) {
                try {
                    redisMsg.put("mentions", JSON.parseArray(mentions, Map.class)); // @ 引用（降级缓存也带，补写 MySQL 后不丢）
                } catch (Exception ignored) {
                }
            }
            if (historyRefs != null && !historyRefs.isBlank()) {
                try {
                    redisMsg.put("historyRefs", JSON.parseArray(historyRefs, Map.class)); // # 历史引用（同上）
                } catch (Exception ignored) {
                }
            }
            if (plan != null && !plan.isBlank()) {
                try {
                    redisMsg.put("plan", JSON.parse(plan)); // 计划批准卡（降级缓存也带，补写 MySQL 后计划不丢）
                } catch (Exception ignored) {
                }
            }
            String json = objectMapper.writeValueAsString(redisMsg);
            int max = properties.getSession().getMaxHistory() * 2;
            long expireSeconds = properties.getSession().getExpireMinutes() * 60L;
            redisTemplate.execute(APPEND_SCRIPT, Collections.singletonList(KEY_PREFIX + sessionId),
                    json, String.valueOf(max), String.valueOf(expireSeconds));
        } catch (Exception e) {
            log.warn("Redis 追加消息失败 (session={}): {}", sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * 事务内写 MySQL 一条消息：锁会话行（不存在则补建占位后重取锁）串行化同会话并发写 →
     * 序号取物理 max+1（含软删行，删除轮次后不复用，撤销按 seq-1 配对可靠）→ 插入 → 同步会话计数/首问标题。
     * 任一失败抛异常回滚，由调用方决定降级。
     */
    private String appendToMysql(String sessionId, String role, String content, List<String> images,
                                 String sources, String thinking, String retrieved, String artifacts,
                                 String toolCalls, String attachments, String tokens, String timeline,
                                 String processText, String agentId, String agentName, String model,
                                 String related, String mentions, String historyRefs, String plan) {
        return transactionTemplate.execute(status -> {
            Session locked = sessionMapper.selectForUpdate(sessionId);
            if (locked == null) {
                Session placeholder = new Session();
                placeholder.setId(sessionId);
                placeholder.setMessageCount(0);
                try {
                    sessionMapper.insert(placeholder);
                } catch (Exception ignored) {
                    // 并发补建冲突：忽略，重取锁即可
                }
                locked = sessionMapper.selectForUpdate(sessionId);
            }
            if (locked == null) {
                throw new IllegalStateException("会话行不可用: " + sessionId);
            }

            int seq = messageMapper.maxSequencePhysical(sessionId) + 1;
            Message msg = new Message();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setThinking(thinking);
            msg.setImages(images != null && !images.isEmpty() ? JSON.toJSONString(images) : null);
            msg.setSources(sources);
            msg.setRetrieved(retrieved);
            msg.setRelated(related);
            msg.setArtifacts(artifacts);
            msg.setToolCalls(toolCalls);
            msg.setAttachments(attachments);
            msg.setTokens(tokens);
            msg.setModel(model);
            msg.setTimeline(timeline);
            msg.setProcessText(processText);
            msg.setAgentId(agentId);
            msg.setAgentName(agentName);
            msg.setMentions(mentions);         // @ 引用（轮级注入 → 消息级常驻标注，刷新/历史回显保留）
            msg.setHistoryRefs(historyRefs);   // # 历史引用（同上）
            msg.setPlan(plan);                 // 计划批准卡（刷新/历史按轮重建执行计划卡）
            msg.setSequence(seq);
            messageMapper.insert(msg);

            // 更新会话：message_count=seq、title（首条用户消息前 50 字）；软删行由 updateById 自动过滤不更新
            Session update = new Session();
            update.setId(sessionId);
            update.setMessageCount(seq);
            if ("user".equals(role) && content != null && !content.isBlank()
                    && (locked.getTitle() == null || locked.getTitle().isBlank())) {
                String title = content.length() > 50 ? content.substring(0, 50) : content;
                update.setTitle(title.trim());
            }
            sessionMapper.updateById(update);
            return msg.getId();
        });
    }

    /**
     * 会话级智能体绑定（首问锁定）：仅当会话尚未绑定（agent_id IS NULL）时写入，已绑定则原样不动
     * ——这正是「一次锁定、全程一致」的实现点：后续轮次即使请求里带了别的 agentId 也不再改变本会话。
     * <p>
     * 取值语义：有值=锁定该智能体；空串=已决定不绑定（走全局配置）；NULL=尚未决定（新会话未发过消息）。
     * 空串与 NULL 必须区分，否则"用户明确选了不用智能体"会被当成未绑定而每轮重新路由。
     *
     * @return true=本次调用完成绑定；false=已绑定过或写库失败（写库失败由调用方按 fail-loud 处理）
     */
    public boolean bindAgent(String sessionId, String agentId, String agentName) {
        try {
            // 注意：全局逻辑删除字段 deleted 使得 updateById 无法置空/改部分字段的场景受限，
            // 这里按项目约定走 LambdaUpdateWrapper.set（显式 SET，且带 isNull 条件保证只锁一次）
            int rows = sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .isNull(Session::getAgentId)
                    .set(Session::getAgentId, agentId == null ? "" : agentId)
                    .set(Session::getAgentName, agentName == null ? "" : agentName));
            return rows > 0;
        } catch (Exception e) {
            log.warn("会话绑定智能体失败 (session={}): {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 读取会话已锁定的智能体。返回 null 表示「尚未绑定」（列仍为 NULL、会话不存在或查询失败）。
     * 返回的 agentId 可能为空串（已决定不绑定智能体）。
     */
    public AgentBinding getAgentBinding(String sessionId) {
        try {
            Session s = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .select(Session::getAgentId, Session::getAgentName)
                    .last("LIMIT 1"));
            if (s == null || s.getAgentId() == null) return null;
            return new AgentBinding(s.getAgentId(), s.getAgentName());
        } catch (Exception e) {
            log.warn("读取会话智能体绑定失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 会话级智能体绑定快照（agentId 空串 = 已决定不绑定智能体） */
    public record AgentBinding(String agentId, String agentName) {
    }

    /**
     * 清除 Redis 缓存（保留 MySQL 数据）
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    /**
     * 清空当前用户的会话（anonymous 用户清空匿名池；MySQL 软删 + Redis 清理）
     */
    public void clearAll(String userId) {
        String uid = normalizeUser(userId);
        try {
            // 先取该用户名下的会话 ID（Redis 按 sessionId 清理需要）
            List<Session> own = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                    .eq(Session::getUserId, uid));
            List<String> ownIds = own.stream().map(Session::getId).toList();
            // 逻辑删除该用户的会话与其下所有消息
            if (!ownIds.isEmpty()) {
                messageMapper.delete(new LambdaQueryWrapper<Message>()
                        .in(Message::getSessionId, ownIds));
            }
            sessionMapper.delete(new LambdaQueryWrapper<Session>()
                    .eq(Session::getUserId, uid));
            // 清理该用户的 Redis 会话 key
            if (!ownIds.isEmpty()) {
                Set<String> keys = ownIds.stream().map(KEY_PREFIX::concat).collect(java.util.stream.Collectors.toSet());
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清空会话 MySQL 操作失败: {}", e.getMessage());
        }
    }

    /**
     * 删除一轮对话（对话组）：指定回答（assistant）消息 ID，连同其前面的用户问题一起软删除。
     * 序号约定：同轮用户问题 seq=k、回答 seq=k+1 连续；按 seq-1+role=user 精确配对防误删。
     * 同步：会话消息计数递减（best-effort）+ Redis 兜底缓存失效（下次读回退 MySQL）。
     *
     * @return 实际删除条数
     */
    /**
     * 软删单条消息（重新生成本轮回答时替换旧回答用）。
     * <p>不删的后果：历史里同一问题会留下两条答案（新的那条是重新生成的结果），刷新后用户看到重复回答。
     * 软删保留在撤销窗口内可恢复（与按组删除同一口径）。
     */
    public void deleteMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        try {
            messageMapper.deleteById(messageId);
        } catch (Exception e) {
            // 删不掉只影响"历史去重"，不该拖垮本次落库；但必须留痕（历史会出现两条答案）
            log.warn("[FAIL-LOUD] 旧回答消息软删失败（历史将保留两条答案）: id={} - {}", messageId, e.getMessage());
        }
    }

    /**
     * 按 ID 查单条消息，且必须属于指定会话（# 历史引用的取数入口）。
     * 不存在 / 跨会话 / 已软删 一律返回 null——调用方（控制器同步段）据此 fail-loud，
     * 不静默忽略：用户显式引用了一条消息却没生效，比报错更糟。
     * 内容一律以库为准，客户端传的任何文本不采信。
     */
    public Message findMessageInSession(String sessionId, String messageId) {
        if (sessionId == null || sessionId.isBlank()
                || messageId == null || messageId.isBlank()) return null;
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getId, messageId)
                .eq(Message::getSessionId, sessionId)
                .last("LIMIT 1"));
    }

    // ==================== 消息编辑重发 / 分支（复用软删机制同源路由） ====================
    // 模型：线性存储 + 版本组。每个版本的代表消息（编辑分支=新用户消息；重新生成分支=新回答）
    // 挂 variantGroup 组键，同组代表按 sequence 排序即版本序列，deleted=0 的是当前激活版本。
    // 被替换时把当时的可见尾部消息 ID 快照进 variant_tail——切回时按快照精确恢复整条分支
    // （嵌套子分支一并还原）。软删行在撤销窗口内可恢复，sessionRetentionDays 到期物理清理，
    // 清理后切换恢复 0 条=「已过保留期」，与按组删除/撤销同一兜底。

    /** 设置消息的分支版本组标记（appendMessage 不为此扩参：新代表落库返回 ID 后补挂组） */
    public void setMessageVariant(String messageId, String group) {
        if (messageId == null || messageId.isBlank() || group == null || group.isBlank()) return;
        try {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .set(Message::getVariantGroup, group));
        } catch (Exception e) {
            // 标记写不进去只影响「刷新后还能不能看到这版切换器」，不阻断本轮落库，留痕即可
            log.warn("[BRANCH] 版本组标记写入失败 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    /**
     * 编辑重发的分支手术（控制器同步段调用，先于本轮问答执行，fail-loud）：
     * 被编辑的用户消息及其后的整段可见分支软删让位（旧分支整体留档），并留下版本组键与尾部快照；
     * 返回的组键贯穿本轮挂到新用户消息上，与旧消息构成可切换的版本序列。
     *
     * @return 版本组键（新用户消息落库后 setMessageVariant 用）
     */
    public String prepareEditBranch(String sessionId, String editMessageId) {
        Message target = messageMapper.selectById(editMessageId);
        if (target == null || !sessionId.equals(target.getSessionId())) {
            throw new com.wenqu.ai.common.BizException(404, "被编辑的消息不存在或不属于当前会话");
        }
        if (!"user".equals(target.getRole())) {
            throw new com.wenqu.ai.common.BizException(400, "只能编辑用户消息");
        }
        String group = target.getVariantGroup() == null || target.getVariantGroup().isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : target.getVariantGroup();
        List<String> tailIds = visibleTailIds(sessionId, target.getSequence());
        // 快照写在软删前：软删行的更新走自定义 SQL（@TableLogic 会把逻辑删 update 的 WHERE 过滤掉已删行）
        messageMapper.updateVariantById(editMessageId, group, JSON.toJSONString(tailIds));
        softDeleteIds(tailIds);
        syncCountBestEffort(sessionId);
        clearSession(sessionId);
        return group;
    }

    /**
     * 重新生成的分支手术（回答落库闸内调用，替代原先的裸 deleteMessage）：
     * 旧回答软删让位并登记组键/尾部快照；新回答落库后 setMessageVariant 挂同一组，
     * 重新生成的多版本由此跨刷新留存。旧口径语义保持：旧消息缺失/已删只记日志不阻断新回答落库。
     *
     * @return 版本组键（null=旧消息不可定位，仅跳过分组，行为与旧版「软删失败留两条答案」一致）
     */
    public String beginAnswerReplace(String oldMessageId) {
        Message old = messageMapper.selectById(oldMessageId);
        if (old == null) {
            log.warn("[FAIL-LOUD] 旧回答消息软删失败（历史将保留两条答案）: id={} - 消息不存在", oldMessageId);
            return null;
        }
        String group = old.getVariantGroup() == null || old.getVariantGroup().isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : old.getVariantGroup();
        List<String> tailIds = visibleTailIds(old.getSessionId(), old.getSequence());
        messageMapper.updateVariantById(oldMessageId, group, JSON.toJSONString(tailIds));
        softDeleteIds(tailIds);
        clearSession(old.getSessionId());
        return group;
    }

    /**
     * 分支切换：当前激活版本整体软删（留尾部快照），按目标版本的快照恢复旧分支。
     *
     * @param messageId 当前可见的版本代表消息 ID（编辑分支=用户消息；重新生成分支=回答消息）
     * @param delta     版本序列偏移（±1；越界=0 条，前端按 variantIndex/variantCount 禁用箭头）
     * @return 恢复的消息条数（0=已是边界版本）
     */
    public int switchVariant(String sessionId, String messageId, int delta) {
        if (delta == 0) return 0;
        Message active = messageMapper.selectById(messageId);
        if (active == null || !sessionId.equals(active.getSessionId())) {
            throw new com.wenqu.ai.common.BizException(404, "消息不存在或不属于当前会话");
        }
        String group = active.getVariantGroup();
        if (group == null || group.isBlank()) {
            throw new com.wenqu.ai.common.BizException(400, "该消息没有可切换的历史版本");
        }
        // 组内代表按序号升序即版本序列（含已软删的历史版本）
        List<Message> reps = messageMapper.selectVariantMessages(sessionId).stream()
                .filter(m -> group.equals(m.getVariantGroup()))
                .toList();
        int idx = -1;
        for (int i = 0; i < reps.size(); i++) {
            if (messageId.equals(reps.get(i).getId())) { idx = i; break; }
        }
        if (idx < 0) {
            throw new com.wenqu.ai.common.BizException(404, "版本定位失败，请刷新后重试");
        }
        int ti = idx + delta;
        if (ti < 0 || ti >= reps.size()) return 0;
        Message target = reps.get(ti);
        List<String> restoreIds = parseTailIds(target.getVariantTail());
        // 恢复成员必须完整在库（未被 retention 物理清理）：缺任何一个都按「已过保留期」拒绝，
        // 不做部分恢复——半新半旧的拼接分支比切换失败更糟
        if (restoreIds.isEmpty()
                || !messageMapper.selectExistingIdsIncludingDeleted(restoreIds).containsAll(restoreIds)) {
            throw new com.wenqu.ai.common.BizException(410, "旧版本已过保留期，无法切换");
        }
        // 顺序敏感：先快照当前激活分支（此刻可见集合还干净），再恢复目标分支，最后按快照软删当前分支。
        // 软删按 ID 精确操作与序号无关，恢复进来的旧分支行不会被误删
        List<String> activeTailIds = visibleTailIds(sessionId, active.getSequence());
        messageMapper.updateVariantById(active.getId(), group, JSON.toJSONString(activeTailIds));
        int restored = 0;
        for (String id : restoreIds) {
            restored += messageMapper.restoreById(id);
        }
        if (restored == 0) {
            throw new com.wenqu.ai.common.BizException(410, "旧版本已过保留期，无法切换");
        }
        softDeleteIds(activeTailIds);
        syncCountBestEffort(sessionId);
        clearSession(sessionId);
        return restored;
    }

    /**
     * 历史回填分支版本信息：对带版本组的可见消息补 variantCount（组内版本总数）与
     * variantIndex（按序号排位的当前版本序号，1 起）——前端 ‹ n/N › 切换器的数据源。
     * 一次轻量查询（仅带组标记的行），无分组消息的存量会话零开销。
     */
    public void attachVariantInfo(String sessionId, List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()
                || sessionId == null || sessionId.isBlank()) return;
        try {
            List<Message> variants = messageMapper.selectVariantMessages(sessionId);
            if (variants.isEmpty()) return;
            Map<String, List<Message>> byGroup = new LinkedHashMap<>();
            for (Message m : variants) {
                if (m.getVariantGroup() != null && !m.getVariantGroup().isBlank()) {
                    byGroup.computeIfAbsent(m.getVariantGroup(), k -> new ArrayList<>()).add(m);
                }
            }
            for (Map<String, Object> msg : history) {
                Object mid = msg.get("messageId");
                if (mid == null) continue;
                for (Map.Entry<String, List<Message>> e : byGroup.entrySet()) {
                    List<Message> reps = e.getValue();
                    for (int i = 0; i < reps.size(); i++) {
                        if (String.valueOf(mid).equals(reps.get(i).getId())) {
                            msg.put("variantGroup", e.getKey());
                            msg.put("variantCount", reps.size());
                            msg.put("variantIndex", i + 1);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BRANCH] 历史版本信息回填失败 (session={}): {}", sessionId, e.getMessage());
        }
    }

    /** 会话内 seq &gt;= fromSeq 的可见消息 ID（升序）——分支尾部快照的取数口径 */
    private List<String> visibleTailIds(String sessionId, int fromSeq) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .ge(Message::getSequence, fromSeq)
                        .orderByAsc(Message::getSequence))
                .stream().map(Message::getId).toList();
    }

    /** 按 ID 精确软删一批消息；删不齐留痕（历史可能出现两条并存的回答，与旧口径一致不阻断） */
    private void softDeleteIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        int n = messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .in(Message::getId, ids)
                .set(Message::getDeleted, 1));
        if (n < ids.size()) {
            log.warn("[FAIL-LOUD] 分支软删不完整 ({}/{}): 历史可能出现并存分支", n, ids.size());
        }
    }

    /** 会话消息计数同步（best-effort，仅侧栏展示口径）：取物理最大序号，分支恢复/让位后不缩水 */
    private void syncCountBestEffort(String sessionId) {
        try {
            Session upd = new Session();
            upd.setId(sessionId);
            upd.setMessageCount(messageMapper.maxSequencePhysical(sessionId));
            sessionMapper.updateById(upd);
        } catch (Exception ignored) {
        }
    }

    /** 尾部快照 JSON → 消息 ID 列表（脏数据按空处理，切换接口据此报「已过保留期」） */
    private List<String> parseTailIds(String tailJson) {
        if (tailJson == null || tailJson.isBlank()) return List.of();
        try {
            List<String> ids = JSON.parseArray(tailJson, String.class);
            return ids == null ? List.of()
                    : ids.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public int deleteRound(String sessionId, String assistantMessageId) {        Message assistant = messageMapper.selectById(assistantMessageId);
        if (assistant == null || !sessionId.equals(assistant.getSessionId())) return 0;
        List<String> ids = new ArrayList<>();
        ids.add(assistant.getId());
        Message userMsg = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .eq(Message::getSequence, assistant.getSequence() - 1)
                .eq(Message::getRole, "user")
                .last("LIMIT 1"));
        if (userMsg != null) ids.add(userMsg.getId());
        int deleted = 0;
        for (String id : ids) {
            deleted += messageMapper.deleteById(id); // @TableLogic 软删除
        }
        // 会话消息计数递减（best-effort，仅影响侧边栏展示）
        try {
            Session session = sessionMapper.selectById(sessionId);
            if (session != null && session.getMessageCount() != null) {
                Session upd = new Session();
                upd.setId(sessionId);
                upd.setMessageCount(Math.max(0, session.getMessageCount() - ids.size()));
                sessionMapper.updateById(upd);
            }
        } catch (Exception ignored) {
        }
        clearSession(sessionId);
        return deleted;
    }

    /**
     * 撤销删除一轮对话：按回答消息 ID 恢复该轮（回答 + 同组用户问题）。
     * 自定义 SQL 绕过 @TableLogic 定位/恢复已软删消息；同步计数加回 + Redis 失效。
     *
     * @return 实际恢复条数（消息可能已被物理清理，恢复 0 条时前端提示已过撤销期）
     */
    public int undoDeleteRound(String sessionId, String assistantMessageId) {
        Message assistant = messageMapper.selectByIdIgnoreDeleted(assistantMessageId);
        if (assistant == null || !sessionId.equals(assistant.getSessionId())) return 0;
        // 已处于未删除状态 = 撤销已被执行过（重复撤销防御，防计数重复加回）
        if (assistant.getDeleted() == null || assistant.getDeleted() == 0) return 0;
        int restored = messageMapper.restoreById(assistant.getId());
        Message userMsg = messageMapper.selectBySeqIgnoreDeleted(sessionId,
                assistant.getSequence() - 1, "user");
        if (userMsg != null) {
            restored += messageMapper.restoreById(userMsg.getId());
        }
        // 计数加回（best-effort）
        try {
            Session session = sessionMapper.selectById(sessionId);
            if (session != null) {
                Session upd = new Session();
                upd.setId(sessionId);
                int base = session.getMessageCount() == null ? 0 : session.getMessageCount();
                upd.setMessageCount(base + (userMsg != null ? 2 : 1));
                sessionMapper.updateById(upd);
            }
        } catch (Exception ignored) {
        }
        clearSession(sessionId);
        return restored;
    }

    // ==================== 私有方法 ====================

    /**
     * 从 MySQL 读取会话历史
     */
    private List<Map<String, Object>> readFromMysql(String sessionId) {
        try {
            LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Message::getSessionId, sessionId)
                    .orderByAsc(Message::getSequence);
            List<Message> messages = messageMapper.selectList(wrapper);
            if (messages.isEmpty()) return Collections.emptyList();

            return messages.stream().map(this::toMessageMap).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("MySQL 读取会话历史失败 (session={}): {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 消息实体 → 前端展示 Map（含思考/图片/引用来源；字段名与 SSE done 事件一致）
     */
    private Map<String, Object> toMessageMap(Message m) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", m.getRole());
        map.put("content", m.getContent());
        map.put("messageId", m.getId()); // 与 SSE done 事件字段名一致，供前端反馈/导出等操作
        map.put("sequence", m.getSequence()); // 消息序号（历史压缩的摘要覆盖点推进用）
        map.put("createTime", m.getCreateTime()); // 气泡下方时间展示
        // 智能体归属（随消息落库的当轮快照）：历史回显「这条是谁答的」；无值表示本轮未使用智能体
        if (m.getAgentId() != null && !m.getAgentId().isBlank()) {
            map.put("agentId", m.getAgentId());
            map.put("agentName", m.getAgentName() == null ? "" : m.getAgentName());
        }
        if (m.getThinking() != null && !m.getThinking().isBlank()) {
            map.put("thinking", m.getThinking());
        }
        if (m.getImages() != null && !m.getImages().isBlank()) {
            try {
                map.put("images", JSON.parseArray(m.getImages(), String.class));
            } catch (Exception e) {
                // images 解析失败忽略
            }
        }
        if (m.getSources() != null && !m.getSources().isBlank()) {
            try {
                map.put("sources", JSON.parseArray(m.getSources(), Map.class));
            } catch (Exception e) {
                // sources 解析失败忽略
            }
        }
        if (m.getRetrieved() != null && !m.getRetrieved().isBlank()) {
            map.put("retrieved", m.getRetrieved()); // 检索状态行（前端 JSON.parse）
        }
        if (m.getRelated() != null && !m.getRelated().isBlank()) {
            try {
                map.put("related", JSON.parseArray(m.getRelated(), String.class)); // 「接下来可以」推荐（历史回显）
            } catch (Exception e) {
                // related 解析失败忽略
            }
        }
        if (m.getArtifacts() != null && !m.getArtifacts().isBlank()) {
            try {
                map.put("artifacts", JSON.parseArray(m.getArtifacts(), Map.class)); // 产物卡片（前端按需签名下载）
            } catch (Exception e) {
                // artifacts 解析失败忽略
            }
        }
        if (m.getToolCalls() != null && !m.getToolCalls().isBlank()) {
            try {
                map.put("toolCalls", JSON.parseArray(m.getToolCalls(), Map.class)); // 工具调用过程（历史回显）
            } catch (Exception e) {
                // toolCalls 解析失败忽略
            }
        }
        if (m.getPlan() != null && !m.getPlan().isBlank()) {
            try {
                map.put("plan", JSON.parse(m.getPlan())); // 计划批准卡（历史回显：每轮计划各留一张，前端重建气泡计划卡）
            } catch (Exception e) {
                // plan 解析失败忽略
            }
        }
        if (m.getTokens() != null && !m.getTokens().isBlank()) {
            try {
                map.put("tokens", JSON.parse(m.getTokens())); // Token 用量（历史回看「本次用量/会话累计」）
            } catch (Exception e) {
                // tokens 解析失败忽略
            }
        }
        // 本轮生效模型引用（助手消息落库）：前端「模型已切换」分隔记录按相邻助手消息的模型比对渲染
        if (m.getModel() != null && !m.getModel().isBlank()) {
            map.put("model", m.getModel());
        }
        if (m.getTimeline() != null && !m.getTimeline().isBlank()) {
            try {
                map.put("timeline", JSON.parseArray(m.getTimeline(), Map.class)); // 回答时间线（历史回显交错顺序）
            } catch (Exception e) {
                // timeline 解析失败忽略
            }
        }
        if (m.getProcessText() != null && !m.getProcessText().isBlank()) {
            map.put("processText", m.getProcessText()); // 过程独白全文（历史回显灰字过程段）
        }
        if (m.getAttachments() != null && !m.getAttachments().isBlank()) {
            try {
                map.put("attachments", JSON.parseArray(m.getAttachments(), Map.class)); // 附件标签（历史回显）
            } catch (Exception e) {
                // attachments 解析失败忽略
            }
        }
        // @ 引用（用户消息常驻标注）：刷新/历史回显保留，前端气泡渲染 chip
        if (m.getMentions() != null && !m.getMentions().isBlank()) {
            try {
                map.put("mentions", JSON.parseArray(m.getMentions(), Map.class));
            } catch (Exception e) {
                // mentions 解析失败忽略
            }
        }
        // # 历史引用（用户消息常驻标注）：同上
        if (m.getHistoryRefs() != null && !m.getHistoryRefs().isBlank()) {
            try {
                map.put("historyRefs", JSON.parseArray(m.getHistoryRefs(), Map.class));
            } catch (Exception e) {
                // historyRefs 解析失败忽略
            }
        }
        return map;
    }

    /**
     * 从 Redis 读取指定范围的消息
     */
    private List<Map<String, Object>> readRange(String sessionId, long start, long end) {
        List<String> jsons = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, start, end);
        if (jsons == null || jsons.isEmpty()) return Collections.emptyList();
        try {
            return jsons.stream().map(j -> {
                try {
                    return objectMapper.readValue(j,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    return null;
                }
            }).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("Failed to read session history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 Redis 数据异步补写到 MySQL（线程池后台执行，不阻塞会话读取）
     */
    private void backfillToMysql(String sessionId, List<Map<String, Object>> messages) {
        ThreadPoolManager.execute(() -> {
            try {
                int added = backfillInsert(sessionId, messages);
                if (added > 0) {
                    log.info("Backfill 完成: session={}, messages={}", sessionId, added);
                }
            } catch (Exception e) {
                log.warn("Backfill 失败 (session={}): {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 幂等补写消息：优先按 msgId（降级消息自带全局唯一 ID）判重——已落库跳过，同一消息并发补写不重复；
     * 无 msgId 的旧格式回退按 (role, content, images) 判重（空库回填共用此方法）。
     * 事务内锁会话行取物理 max 序号，与并发 append 不撞号；序号/标题随插随更。
     *
     * @return 实际插入条数
     */
    private int backfillInsert(String sessionId, List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        try {
            return transactionTemplate.execute(status -> {
                Session locked = sessionMapper.selectForUpdate(sessionId);
                if (locked == null) {
                    Session placeholder = new Session();
                    placeholder.setId(sessionId);
                    placeholder.setMessageCount(0);
                    try {
                        sessionMapper.insert(placeholder);
                    } catch (Exception ignored) {
                    }
                    locked = sessionMapper.selectForUpdate(sessionId);
                }

                // 判重基准：1) 带 msgId 的消息一次性查库（含软删行——软删消息撤销窗口内不得同 ID 重插撞主键，
                // 已落库的跳过，保证幂等）；2) 旧格式（无 msgId）沿用 role+content+images 键
                List<String> msgIds = messages.stream()
                        .map(m -> m.get("msgId"))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.toList());
                Set<String> existingIds = msgIds.isEmpty() ? Collections.emptySet()
                        : new HashSet<>(messageMapper.selectExistingIdsIncludingDeleted(msgIds));
                Set<String> contentKeys = new HashSet<>(messageMapper.selectList(
                                new LambdaQueryWrapper<Message>().eq(Message::getSessionId, sessionId))
                        .stream()
                        .map(m -> messageKey(m.getRole(), m.getContent(), m.getImages()))
                        .toList());
                String title = locked == null || locked.getTitle() == null || locked.getTitle().isBlank()
                        ? null : locked.getTitle();
                int seq = messageMapper.maxSequencePhysical(sessionId);
                int added = 0;
                for (Map<String, Object> m : messages) {
                    String role = String.valueOf(m.getOrDefault("role", ""));
                    String content = String.valueOf(m.getOrDefault("content", ""));
                    Object imagesObj = m.get("images");
                    String imagesJson = imagesObj instanceof List<?> list && !list.isEmpty()
                            ? JSON.toJSONString(list) : null;
                    String msgId = m.get("msgId") == null ? null : String.valueOf(m.get("msgId"));
                    if (msgId != null) {
                        if (existingIds.contains(msgId)) continue; // 同一消息已补写：幂等跳过
                    } else if (contentKeys.contains(messageKey(role, content, imagesJson))) {
                        continue; // 旧格式无 ID：按内容判重（历史行为）
                    }

                    Message msg = new Message();
                    if (msgId != null) {
                        msg.setId(msgId); // 与 Redis 侧 ID 一致：后续合并/幂等可追溯
                    }
                    msg.setSessionId(sessionId);
                    msg.setRole(role);
                    msg.setContent(content);
                    msg.setImages(imagesJson);
                    Object thinkingObj = m.get("thinking");
                    if (thinkingObj != null) {
                        msg.setThinking(String.valueOf(thinkingObj));
                    }
                    Object sourcesObj = m.get("sources");
                    if (sourcesObj != null) {
                        msg.setSources(JSON.toJSONString(sourcesObj));
                    }
                    Object mentionsObj = m.get("mentions");
                    if (mentionsObj != null) {
                        msg.setMentions(JSON.toJSONString(mentionsObj)); // @ 引用（Redis 降级消息补写 MySQL 时带回）
                    }
                    Object historyRefsObj = m.get("historyRefs");
                    if (historyRefsObj != null) {
                        msg.setHistoryRefs(JSON.toJSONString(historyRefsObj)); // # 历史引用（同上）
                    }
                    Object planObj = m.get("plan");
                    if (planObj != null) {
                        msg.setPlan(JSON.toJSONString(planObj)); // 计划批准卡（Redis 降级消息补写 MySQL 时带回）
                    }
                    Object retrievedObj = m.get("retrieved");
                    if (retrievedObj != null) {
                        msg.setRetrieved(String.valueOf(retrievedObj));
                    }
                    msg.setSequence(++seq);
                    messageMapper.insert(msg);
                    if (msgId != null) {
                        existingIds.add(msgId);
                    } else {
                        contentKeys.add(messageKey(role, content, imagesJson));
                    }
                    added++;
                    if (title == null && "user".equals(role) && !content.isBlank()) {
                        title = content.length() > 50 ? content.substring(0, 50).trim() : content.trim();
                    }
                }

                // 同步会话计数/标题（仅非软删行；计数取 max 防止合并场景回写缩水——仅展示用）
                if (added > 0 && locked != null && (locked.getDeleted() == null || locked.getDeleted() == 0)) {
                    Session update = new Session();
                    update.setId(sessionId);
                    int base = locked.getMessageCount() == null ? 0 : locked.getMessageCount();
                    update.setMessageCount(Math.max(base, seq));
                    if (title != null) update.setTitle(title);
                    sessionMapper.updateById(update);
                }
                return added;
            });
        } catch (Exception e) {
            log.warn("补写消息到 MySQL 失败 (session={}): {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 过期数据清理（ScheduleCenter 周期触发）：物理清除逻辑删除标记且超过保留期的会话与消息，
     * 以及**已停用**且超期的分享记录。保留期即"撤销删除"窗口——物理清除后撤销必然返回 0
     * （前端提示已过撤销期）。幂等可并发（多副本各自触发互不冲突）；单类失败仅告警，另一类继续。
     *
     * @param retentionDays 保留天数（<=0 表示停用）
     * @return 清理统计 {sessions, messages, shares}，供日志与观测
     */
    public Map<String, Object> purgeExpired(int retentionDays) {
        Map<String, Object> stat = new HashMap<>();
        if (retentionDays <= 0) return stat;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        try {
            stat.put("sessions", sessionMapper.purgeDeletedOlderThan(cutoff));
        } catch (Exception e) {
            log.warn("[CLEANUP] 过期会话物理清理失败: {}", e.getMessage());
        }
        try {
            stat.put("messages", messageMapper.purgeDeletedOlderThan(cutoff));
        } catch (Exception e) {
            log.warn("[CLEANUP] 过期消息物理清理失败: {}", e.getMessage());
        }
        // 已停用的分享记录：与消息同保留期清掉。停用 = 用户关闭了这件事，
        // 不清就会永久留存（链接凭据虽然失效了，但"谁在何时分享过什么"的档案会一直留着），
        // 分享管理面板也会被早已失效的记录越撑越长。生效中的（enabled=1）绝不在此列。
        try {
            stat.put("shares", sessionShareMapper.purgeDisabledOlderThan(cutoff));
        } catch (Exception e) {
            log.warn("[CLEANUP] 过期分享记录清理失败: {}", e.getMessage());
        }
        int sessions = ((Number) stat.getOrDefault("sessions", 0)).intValue();
        int messages = ((Number) stat.getOrDefault("messages", 0)).intValue();
        int shares = ((Number) stat.getOrDefault("shares", 0)).intValue();
        if (sessions > 0 || messages > 0 || shares > 0) {
            log.info("[CLEANUP] 过期数据清理完成：会话 {} 条、消息 {} 条、已停用分享 {} 条"
                    + "（保留 {} 天，截止 {}）", sessions, messages, shares, retentionDays, cutoff);
        }
        return stat;
    }

    /** 游客会话回收的单批量与轮次上限（一轮最多 2000 条，跑不完下次接着跑） */
    private static final int VISITOR_SWEEP_BATCH = 200;
    private static final int VISITOR_SWEEP_ROUNDS = 10;

    /**
     * 游客会话闲置回收（与 {@link #purgeExpired} 挂在同一个周期任务上）：
     * 把闲置超过 idleDays 的分享页/MCP 访客会话连同消息一起软删。
     * <p>
     * 为什么单独一条口径：这类会话<b>没有人能去删它</b>——访客侧刚有"清除我的对话记录"，
     * 但访客不会每次都去点（尤其公用电脑上的临时一问），发布者侧也不看这些会话。
     * 不回收就是只增不减；而它们存的是"以发布者身份检索到的内容"，留得越久，
     * 一条被转发出去的链接能被翻出的历史信息越多。
     * <p>
     * 与 {@link #purgeExpired} 的区别必须分清：那边硬删的是**已软删**的行（撤销窗口到期），
     * 这里软删的是**仍活跃**的行，判据是会话的最后活跃时间（update_time）。
     * uid 前缀严格限定访客池，真实用户的会话一条都不碰；软删后仍走 purgeExpired 的保留期，
     * 也就是说访客会话被回收后还有 {@code cleanup.sessionRetentionDays} 天可以被捞回来。
     *
     * @param idleDays 闲置天数（<=0 表示停用该回收）
     * @return 回收的会话数
     */
    public int purgeStaleVisitorSessions(int idleDays) {
        if (idleDays <= 0) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(idleDays);
        int total = 0;
        for (int round = 0; round < VISITOR_SWEEP_ROUNDS; round++) {
            LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
            q.select(Session::getId)
                    .lt(Session::getUpdateTime, cutoff)
                    .and(w -> w.likeRight(Session::getUserId, AgentShareService.VISITOR_UID_PREFIX)
                            .or().likeRight(Session::getUserId, McpServerService.MCP_VISITOR_UID_PREFIX))
                    .orderByAsc(Session::getUpdateTime)
                    // batch 是本类的常量，无注入面
                    .last("LIMIT " + VISITOR_SWEEP_BATCH);
            List<String> ids = sessionMapper.selectList(q).stream().map(Session::getId).toList();
            if (ids.isEmpty()) break;
            try {
                // @TableLogic 下 delete(wrapper) 即软删；先消息后会话，中途失败最多留下"列表看不到的孤儿消息"
                messageMapper.delete(new LambdaQueryWrapper<Message>().in(Message::getSessionId, ids));
                sessionMapper.delete(new LambdaQueryWrapper<Session>().in(Session::getId, ids));
            } catch (Exception e) {
                log.warn("[CLEANUP] 游客会话回收失败（本批 {} 条）: {}", ids.size(), e.getMessage());
                break;
            }
            try {
                redisTemplate.delete(ids.stream().map(id -> KEY_PREFIX + id).toList());
            } catch (Exception e) {
                log.warn("[CLEANUP] 游客会话 Redis 缓存清理失败: {}", e.getMessage());
            }
            total += ids.size();
            if (ids.size() < VISITOR_SWEEP_BATCH) break;
        }
        if (total > 0) {
            log.info("[CLEANUP] 游客会话闲置回收 {} 段（闲置超过 {} 天，截止 {}）", total, idleDays, cutoff);
        }
        return total;
    }
}
