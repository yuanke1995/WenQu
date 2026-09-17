package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.dto.SessionInfo;
import com.wisesoft.ai.mapper.MessageMapper;
import com.wisesoft.ai.mapper.SessionMapper;
import com.wisesoft.ai.model.Message;
import com.wisesoft.ai.model.Session;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import com.wisesoft.ai.util.RequestUser;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

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
     * 归属校验：会话不存在抛 404；存在但不属于该用户则抛 403。
     * anonymous 名下的存量会话仅未登录（anonymous）调用方可访问；登录用户一律严格隔离，
     * 不允许读取他人（含历史匿名）会话。
     *
     * @return 校验通过的会话实体
     */
    public Session assertOwned(String sessionId, String userId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wisesoft.ai.common.BizException(404, "会话不存在或已被删除");
        }
        String owner = session.getUserId() == null || session.getUserId().isBlank()
                ? RequestUser.ANONYMOUS : session.getUserId();
        String uid = normalizeUser(userId);
        boolean anonymousSession = RequestUser.ANONYMOUS.equals(owner);
        if (!owner.equals(uid) && !(anonymousSession && canAccessAnonymousPool(userId))) {
            throw new com.wisesoft.ai.common.BizException(403, "无权访问该会话");
        }
        return session;
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
     * 查询会话列表（仅当前用户；未登录调用方额外并入 anonymous 存量会话；支持关键词搜索），置顶优先、按更新时间倒序
     *
     * @param keyword 可选，按标题或消息内容模糊匹配；空/空白返回全量
     */
    public List<SessionInfo> listSessions(String userId, String keyword) {
        try {
            LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
            // 只看自己的会话（+ anonymous 历史兼容池，仅池访问放行时并入，防跨用户捞取）
            wrapper.eq(Session::getUserId, normalizeUser(userId));
            if (canAccessAnonymousPool(userId)) {
                wrapper.or().eq(Session::getUserId, RequestUser.ANONYMOUS);
            }
            if (keyword != null && !keyword.isBlank()) {
                String esc = escapeLike(keyword.trim());
                wrapper.and(w -> w.like(Session::getTitle, keyword.trim())
                        .or().inSql(Session::getId,
                                "SELECT DISTINCT session_id FROM c_ai_message WHERE deleted=0 AND content LIKE '%"
                                        + esc + "%'"));
            }
            wrapper.orderByDesc(Session::getIsPinned)
                    .orderByDesc(Session::getUpdateTime);
            return sessionMapper.selectList(wrapper).stream().map(s -> {
                SessionInfo info = new SessionInfo();
                info.setId(s.getId());
                info.setTitle(s.getTitle());
                info.setMessageCount(s.getMessageCount());
                info.setUpdateTime(s.getUpdateTime());
                info.setIsPinned(s.getIsPinned());
                info.setIsFavorite(s.getIsFavorite());
                return info;
            }).toList();
        } catch (Exception e) {
            log.warn("查询会话列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
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
            throw new com.wisesoft.ai.common.BizException("会话不存在");
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
     * 软删除会话（校验归属；MySQL 逻辑删除会话+消息 + Redis 清理）
     */
    public void deleteSession(String userId, String sessionId) {
        assertOwned(sessionId, userId);
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
        // 1. MySQL 持久化
        try {
            return appendToMysql(sessionId, role, content, images, sources, thinking, retrieved, artifacts, toolCalls);
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
            return appendToMysql(sessionId, role, content, images, sources, thinking, retrieved, artifacts, toolCalls);
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
                                 String toolCalls) {
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
            msg.setArtifacts(artifacts);
            msg.setToolCalls(toolCalls);
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
    public int deleteRound(String sessionId, String assistantMessageId) {
        Message assistant = messageMapper.selectById(assistantMessageId);
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
        map.put("createTime", m.getCreateTime()); // 气泡下方时间展示
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
     * 过期数据清理（ScheduleCenter 周期触发）：物理清除逻辑删除标记且超过保留期的会话与消息。
     * 保留期即"撤销删除"窗口——物理清除后撤销必然返回 0（前端提示已过撤销期）。
     * 幂等可并发（多副本各自触发互不冲突）；单类失败仅告警，另一类继续。
     *
     * @param retentionDays 保留天数（<=0 表示停用）
     * @return 清理统计 {sessions, messages}，供日志与观测
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
        int sessions = ((Number) stat.getOrDefault("sessions", 0)).intValue();
        int messages = ((Number) stat.getOrDefault("messages", 0)).intValue();
        if (sessions > 0 || messages > 0) {
            log.info("[CLEANUP] 过期数据清理完成：会话 {} 条、消息 {} 条（保留 {} 天，截止 {}）",
                    sessions, messages, retentionDays, cutoff);
        }
        return stat;
    }
}
