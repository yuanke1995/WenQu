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
import com.wenqu.ai.model.Project;
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

    private static final String KEY_PREFIX = "wenqu:session:";

    /** 会话种类：用户发起的对话（默认值，进会话列表） */
    public static final String KIND_CHAT = "chat";
    /**
     * 会话种类：平台内部临时会话——派遣出去的子轮（框架的 agent_spawn 驱使）与工作流的子智能体节点
     * 各要跑一整轮问答，需要一个独立 sessionId 来占轮次互斥锁、落消息、当沙盒目录名。
     * <p>它跑在<b>真实用户</b>名下（分支要按调用者的权限检索、产物随后收养进父会话），所以
     * 只靠"跑完即删"挡不住用户看见它：一支跑几十秒，这期间任何一次列表刷新（切页回聊天页、
     * 搜索、置顶/删除别的会话）都会把「用户想开发一个智能报表系统…」这种内部提问当成一个新会话
     * 抬进侧栏，而侧栏整表刷新只在首条消息 done 时发生——那条幽灵会话会一直挂到下次刷新为止，
     * 点进去还是半条别人的对话。故显式打标，用户侧的查询一律排除。
     */
    public static final String KIND_TEMP = "temp";

    /**
     * 会话列表的「不属于任何真实工作区」过滤占位符。
     *
     * <p>与 {@link ArtifactService#UNASSIGNED_PROJECT}（{@code __unassigned__}，仅
     * {@code project_id IS NULL}）不同：兜底容器（implicit 容器）是后端的兜底容器，
     * 前端侧栏不把它当成一个「工作区」展示，它的会话要和真正没挂工作区的存量会话一起收进
     * 顶部「会话」。故这里额外并入该用户 implicit 容器的 id，而不是改
     * {@code __unassigned__} 的语义——产物页仍需要「真没挂工作区」那一档。
     */
    public static final String NO_SPACE_PROJECT = "__nospace__";

    /**
     * 「显式不挂工作区」占位符（{@code /session/new?projectId=__none__}）。
     *
     * <p>与「不传 projectId」区分开：不传仍按默认口径落 implicit 兜底容器；
     * 显式传这个值才创建 {@code project_id=NULL} 的会话。侧栏顶部「新建对话」用它，
     * 这样「对话」才真正不属于任何工作区，而不是悄悄挂到兜底容器。
     */
    public static final String NO_PROJECT = "__none__";

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
    /** 移动会话归属时，其产物要跟着一起走（产物归属本由会话反推）——与 ProjectService 删工作区时同口径，直连 Mapper */
    private final com.wenqu.ai.mapper.ArtifactMapper artifactMapper;
    private final TransactionTemplate transactionTemplate;
    /** 会话级模型覆盖的可用的判定（这一列存的是一个模型引用，存进去之后供应商可能被停用、模型可能被移除） */
    private final ModelRegistryService modelRegistryService;
    /** 工作区容器：新建会话时挂到该用户的 implicit 兜底容器（不建工作区也能问，只是产物无归属） */
    private final ProjectService projectService;
    /** 本机执行器在线状态：聊天页要如实说「那台电脑现在连没连上」，不能等一轮跑失败才知道 */
    private final com.wenqu.ai.executor.ExecutorRegistry executorRegistry;

    /**
     * 最近有会话的用户（沙盒热池预热用）：按最后会话时间倒序，去重。
     * 只读、失败返回空表；预热是优化，绝不拖累启动。
     */
    public List<String> recentUserIds(int limit) {
        if (limit <= 0) return List.of();
        try {
            List<Session> rows = sessionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Session>()
                            .select("user_id")
                            .gt("create_time", LocalDateTime.now().minusDays(14))
                            .groupBy("user_id")
                            .last("ORDER BY MAX(create_time) DESC LIMIT " + limit));
            return rows.stream().map(Session::getUserId)
                    .filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
        } catch (Exception e) {
            log.warn("读取最近会话用户失败（跳过沙盒预热）: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 创建新会话（MySQL + Redis）
     *
     * @param userId 归属用户（必须是真实登录用户；空值或 SYSTEM 直接 401）
     */
    public String createSession(String userId) {
        return createSession(userId, KIND_CHAT);
    }

    /**
     * 建一条平台内部临时会话（子智能体分支 / 工作流子智能体节点）：跑完即删，且<b>从建出来那一刻
     * 就不进会话列表</b>——归属仍是真实用户（权限与产物收养要靠它），所以只能靠种类标记区分。
     * 见 {@link #KIND_TEMP}。
     */
    public String createTempSession(String userId) {
        return createSession(userId, KIND_TEMP);
    }

    private String createSession(String userId, String kind) {
        return createSession(userId, kind, null);
    }

    private String createSession(String userId, String kind, String projectId) {
        // 旧口径：projectId 为空 ⇒ 再去解析 implicit 兜底容器
        return createSession(userId, kind, projectId, false);
    }

    /**
     * @param projectResolved true = projectId 已经是「解析后的最终归属」（null 表示显式不挂工作区），
     *                        不再兜底到 implicit 兜底容器；{@code createOrReuseEmptySession} 走这条
     */
    private String createSession(String userId, String kind, String projectId, boolean projectResolved) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        try {
            Session session = new Session();
            session.setId(sessionId);
            session.setUserId(requireUser(userId));
            session.setKind(kind);
            session.setMessageCount(0);
            // 只有用户会话挂工作区：temp 是平台内部派出的一轮（子智能体分支、工作流子智能体节点），
            // 挂进工作区会让「工作区里的对话」混进用户看不见的系统轮次。
            // 工作区解析失败/无真实用户/显式不挂工作区 → projectId 留 null，问答链路照常（可空是一等状态）。
            if (!KIND_TEMP.equals(kind)) {
                String resolved = projectResolved
                        ? projectId
                        : ((projectId != null && !projectId.isBlank())
                                ? projectId
                                : resolveTargetProject(session.getUserId(), null));
                if (resolved != null && !resolved.isBlank()) session.setProjectId(resolved);
            }
            sessionMapper.insert(session);
        } catch (Exception e) {
            log.warn("创建会话 MySQL 写入失败: {}", e.getMessage());
        }
        return sessionId;
    }

    /**
     * 解析本轮的目标工作区：显式传入 → 校验归属；未传 → 该用户的 implicit 兜底容器。
     *
     * @return 工作区 id；无真实用户身份/解析不出返回 null（问答照常，只是无归属）
     * @throws com.wenqu.ai.common.BizException 显式传了但工作区不存在或不属于本人
     */
    private String resolveTargetProject(String uid, String projectId) {
        // 显式「不挂工作区」：不再兜底到 implicit 兜底容器
        if (NO_PROJECT.equals(projectId)) return null;
        if (projectId != null && !projectId.isBlank()) {
            Project p = projectService.ownedOrNull(uid, projectId);
            if (p == null) {
                throw new com.wenqu.ai.common.BizException("工作区不存在或不属于你：" + projectId);
            }
            // 归档=收工：已归档的工作区不接新会话（老会话照常可看可聊，历史不冻结）
            if (Project.STATUS_ARCHIVED.equals(p.getStatus())) {
                throw new com.wenqu.ai.common.BizException("工作区已归档：恢复后才能在里面新建对话");
            }
            return p.getId();
        }
        Project implicit = projectService.ensureImplicit(uid);
        return implicit == null ? null : implicit.getId();
    }

    /**
     * 当前会话所属工作区的摘要（聊天页头部标识用）：名字 + 是否已归档。
     *
     * <p>用户有权在聊天的当场看见「我现在处于哪个工作区」，否则工作区归属就是一串看不见的后台字段。
     * <p><b>没挂工作区的会话也返回</b>（{@code projectId} 留空）：它仍有会话级工作区后端
     * （本机执行器目录，离线时云端沙盒）与权限档位，聊天页的档位选择器要读这两个字段。
     * 头部标识按 {@code projectId} 是否为空决定显示，不编工作区名。
     * <p>工作区已不存在 / 非本人会话仍返回 {@code null}（标识不显示）：
     * 不编名字——界面上不存在的名词会被复述出来（与 {@code ProjectService} 同一口径）。
     * 解析失败按无工作区处理，不抛：一个标识不该让聊天页开不了场。
     */
    public Map<String, Object> workspaceOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session s = sessionMapper.selectById(sessionId);
            if (s == null) return null;
            String uid = RequestUser.uid();
            // 归属校验：只给自己会话的标识
            boolean mine = Objects.equals(s.getUserId(), uid);
            if (!mine) return null;
            String pid = s.getProjectId();
            // 没挂工作区的会话不再整条返回 null：projectId 留空表示「没有工作区标识」，
            // 其余会话级字段（后端 / 档位）照常带出，聊天页的档位选择器据此可用。
            Project p = (pid == null || pid.isBlank()) ? null : projectService.byId(pid);
            if (p == null && pid != null && !pid.isBlank()) return null;   // 工作区已不存在：标识不显示
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("projectId", p == null ? "" : p.getId());
            // implicit 兜底容器没有名字：显示「会话」（与侧栏那一栏同名，不另造名词）
            m.put("name", p == null || p.getName() == null || p.getName().isBlank() ? "会话" : p.getName());
            m.put("implicit", p == null || Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus()));
            m.put("archived", p != null && Project.STATUS_ARCHIVED.equals(p.getStatus()));
            m.put("isLocal", p != null && projectService.isLocal(p));
            m.put("executorId", p == null || p.getExecutorId() == null ? "" : p.getExecutorId());
            // 目录清单：聊天页标题的悬浮提示与「本机文件」入口要用；不把绝对路径塞进页面正文
            java.util.List<Map<String, Object>> roots = new java.util.ArrayList<>();
            if (p != null) {
                for (com.wenqu.ai.model.ProjectRoot r : projectService.rootsOf(p)) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("alias", r.getAlias());
                    one.put("path", r.getPath());
                    one.put("primary", r.getPrimaryRoot() != null
                            && r.getPrimaryRoot() == com.wenqu.ai.model.ProjectRoot.PRIMARY);
                    roots.add(one);
                }
            }
            m.put("roots", roots);
            m.put("executorOnline", p != null && p.getExecutorId() != null && !p.getExecutorId().isBlank()
                    && executorRegistry.online(p.getExecutorId()));
            // 隐式会话的工作区后端（本机执行器目录 / 离线时云端沙盒）：界面按它决定
            // 「权限档位选择器要不要摆」「文件栏是不是本机目录」。
            m.put("wsBackend", s.getWsBackend() == null ? "" : s.getWsBackend());
            m.put("wsRoot", s.getWsRoot() == null ? "" : s.getWsRoot());
            m.put("wsExecutorId", s.getWsExecutorId() == null ? "" : s.getWsExecutorId());
            m.put("wsExecutorOnline", s.getWsExecutorId() != null && !s.getWsExecutorId().isBlank()
                    && executorRegistry.online(s.getWsExecutorId()));
            boolean pinnedLocal = SessionWorkspaceService.BACKEND_LOCAL.equals(s.getWsBackend());
            boolean pinnedOnline = pinnedLocal && s.getWsExecutorId() != null
                    && executorRegistry.online(s.getWsExecutorId());
            m.put("sessionLocal", pinnedLocal);
            // 本轮实际后端：钉住本机但执行器离线时文件在云端沙盒，文件栏文案要跟着变
            m.put("wsEffective", pinnedLocal ? (pinnedOnline ? "local" : "cloud")
                    : (SessionWorkspaceService.BACKEND_CLOUD.equals(s.getWsBackend()) ? "cloud" : ""));
            // 权限档位：显式工作区与隐式会话都认（隐式会话现在也有本机目录/云端沙盒）
            m.put("permissionPreset", s.getPermissionPreset() == null ? "" : s.getPermissionPreset());
            return m;
        } catch (Exception e) {
            log.warn("[PROJECT] 会话工作区标识解析失败（按无工作区处理）session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 把会话移动到另一个工作区，或摘掉归属（{@code projectId} 空/{@code __none__}）。
     *
     * <p><b>为什么必须有</b>：会话的工作区此前只在创建时定一次。用户在「会话」里聊开了才想起
     * 该归到某个工作区时，只能重开对话——历史带不走，工作区退化成"建会话前的选择题"。
     * <p><b>产物跟着一起走</b>：产物归属由会话反推而来，不跟着走会出现「上下文在新家、
     * 成果在旧家」的撕裂。
     * <p><b>如实边界（前端必须说）</b>：此前在旧工作区目录里产生的文件仍留在原处
     * （沙盒 workdir 按工作区分片，已落盘的文件不搬家），移动后新写的文件才进新工作区。
     * <p>归档区可以作为目标（整理动作不产生新执行）；来源是不是归档区无所谓。
     */
    public void moveSessionProject(String uid, String sessionId, String projectId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new com.wenqu.ai.common.BizException("缺少会话标识");
        }
        Session s = sessionMapper.selectById(sessionId);
        if (s == null || !Objects.equals(s.getUserId(), uid)) {
            throw new com.wenqu.ai.common.BizException("会话不存在或不属于你");
        }
        String target = projectService.resolveMoveTarget(uid, projectId);
        if (Objects.equals(target, s.getProjectId())) return;   // 幂等：原地移动直接成功
        // 目标不是真工作区（摘归属 / implicit 兜底容器）时，权限档位一起清掉：
        // 档位只对工作区有意义，留着会在一个没有工作区目录的会话上悄悄生效，而界面上已经没有改它的入口
        Project targetProject = target == null ? null : projectService.byId(target);
        boolean dropPreset = targetProject == null
                || Project.SELECTION_IMPLICIT.equals(targetProject.getSelectionStatus());
        var sessionUpdate = new LambdaUpdateWrapper<Session>()
                .eq(Session::getId, sessionId)
                .set(Session::getProjectId, target);
        if (dropPreset) sessionUpdate.set(Session::getPermissionPreset, null);
        // 置空必须走 wrapper 显式 set：updateById 的 NOT_NULL 策略会跳过 null 列（摘归属会静默失效）
        sessionMapper.update(null, sessionUpdate);
        artifactMapper.update(null, new LambdaUpdateWrapper<com.wenqu.ai.model.Artifact>()
                .eq(com.wenqu.ai.model.Artifact::getSessionId, sessionId)
                .set(com.wenqu.ai.model.Artifact::getProjectId, target));
        log.info("[PROJECT] 会话移动工作区 uid={} sid={} {} → {}（产物随行）", uid, sessionId, s.getProjectId(), target);
    }

    /**
     * 「新建对话」入口：先复用当前用户已存在的空会话（无消息、未绑定智能体、未置顶），没有才真正新建。
     * <p>
     * 空会话不再新增（前端侧栏本就隐藏空会话，堆积的空会话不出现在界面上，只会在游标分页里
     * 挤占真实会话的名额——首屏 20 条里大半是空会话时，侧栏首屏看起来就只剩几条）。
     * <b>注意「恒定为 1」只对新增这一侧成立</b>：规则上线前与各类探针留下的存量空行仍在表里，
     * 会被新对话逐条领走，所以领走时要顺手洗掉其上已失效的模型（见下方复用处）。
     * 复用条件与前端 useChatEngine 的本地复用口径一致：未绑定智能体的空会话才复用（已绑定的带着
     * 自己的绑定，复用会让「切换智能体」看起来没生效）。定时任务 / 工作流 / MCP 建会话走
     * createSession，不参与复用——它们的会话有各自语义，不该占用用户待用的空会话。
     * <p>
     * 并发：两次「新建对话」同时进来会各自查不到空会话、各建一个，故按用户加短锁；
     * 抢不到锁时稍等再查一次（持锁方已落库即可命中复用）。
     */
    public String createOrReuseEmptySession(String userId) {
        return createOrReuseEmptySession(userId, null);
    }

    /**
     * 新建对话（可指定工作区）。
     *
     * @param projectId 目标工作区；空/null = 兜底容器；{@code "__none__"} = 显式不挂工作区。
     *                  传了具体工作区就必须是本人的工作区（否则 BizException，不做静默回落——
     *                  把用户明确选的工作区悄悄换掉，比拒绝更难排查）
     */
    public String createOrReuseEmptySession(String userId, String projectId) {
        final String uid = requireUser(userId);
        // 目标工作区在加锁之前解析：解析失败要尽早暴露，不要让用户等一个锁
        final String targetProject = resolveTargetProject(uid, projectId);
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
            // 注意用 projectResolved=true：targetProject 为 null 表示用户显式要一条「不挂工作区」的
            // 会话，不能再走 createSession 的旧口径把它兜底到 implicit 兜底容器。
            if (empty == null) return createSession(uid, KIND_CHAT, targetProject, true);
            // 空会话没有任何内容，改归属无副作用：用户在工作区 B 点「新建对话」就该拿到 B 的空会话；
            // 从顶部「新建对话」进来（targetProject=null）时则要把它从旧工作区上摘下来——
            // 旧条件只处理非 null，会让空会话继续留在兜底容器。
            if (!Objects.equals(targetProject, empty.getProjectId())) {
                sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                        .eq(Session::getId, empty.getId())
                        .set(Session::getProjectId, targetProject));
            }
            // 领走之前洗一次：空行上存的模型是这条行上一辈子（或探针）留下的，这个新对话用户还没开始，
            // 留着就会让他第一问就收到一条「已清除你上次选的东西」。只洗不能用的——能用的那份是
            // 用户在开聊前主动选好的，必须原样带过去
            String stale = empty.getModel();
            if (stale != null && !stale.isBlank()
                    && !modelRegistryService.referenceUsability(stale, uid, null).usable()) {
                sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                        .eq(Session::getId, empty.getId())
                        .set(Session::getModel, null));
            }
            return empty.getId();
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

    /** 取当前用户可复用的空会话（无消息、未置顶；取最近更新的那条）。
     *  <p>P5 起不再看 agent_id：那一列已从"会话锁定的智能体"变成"最近一次作答的智能体"，
     *  一个还没发过消息的会话本来就没有作答方，用它当"不可复用"的判据是旧锁定语义的残留。
     *  <p>内部临时会话要排除：它从建出来到首条消息落库之间正是"无消息"的空档，被复用成用户的
     *  新对话就等于把一条分支会话交到人手上——分支跑完随手一删，用户这一轮就写进了已删会话。 */
    private Session findEmptySession(String uid) {
        LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
        q.eq(Session::getUserId, uid)
                .eq(Session::getMessageCount, 0)
                .ne(Session::getKind, KIND_TEMP)
                .and(w -> w.isNull(Session::getIsPinned).or().ne(Session::getIsPinned, 1))
                .orderByDesc(Session::getUpdateTime)
                .last("LIMIT 1");
        List<Session> rows = sessionMapper.selectList(q);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 归属校验：会话不存在抛 404；存在但不属于该用户则抛 403。登录用户严格隔离，不存在共享会话池。
     *
     * @return 校验通过的会话实体
     */
    public Session assertOwned(String sessionId, String userId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wenqu.ai.common.BizException(404, "会话不存在或已被删除");
        }
        String owner = session.getUserId() == null || session.getUserId().isBlank()
                ? RequestUser.SYSTEM : session.getUserId();
        String uid = requireUser(userId);
        if (!owner.equals(uid)) {
            throw new com.wenqu.ai.common.BizException(403, "无权访问该会话");
        }
        return session;
    }

    /** 按 id 取会话（**不做归属校验**，调用方负责）：会话只读分享页按 token 取标题用 */
    public Session sessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return sessionMapper.selectById(sessionId);
    }

    /** 会话归属必须是真实登录用户；空值或 SYSTEM 一律按未登录拒绝（fail-loud）。 */
    private String requireUser(String userId) {
        if (userId == null || userId.isBlank() || RequestUser.SYSTEM.equals(userId)) {
            throw new com.wenqu.ai.common.BizException(401, "请先登录");
        }
        return userId;
    }

    /**
     * 确保指定 ID 的会话存在且归属当前用户（chat/分享/定时任务传入尚未落库的 sessionId 时补建）
     *
     * @return 校验/补建后的会话 ID
     */
    public String ensureSession(String sessionId, String userId) {
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(requireUser(userId));
        session.setMessageCount(0);
        // 补建的用户会话同样挂到 implicit 兜底容器——否则「老客户端直接带 sessionId 进来」
        // 这条路上的会话会永远没有归属，而它恰恰是最常见的入口（前端新建对话后立刻发问）
        Project project = projectService.ensureImplicit(session.getUserId());
        if (project != null) session.setProjectId(project.getId());
        try {
            sessionMapper.insert(session);
        } catch (Exception e) {
            // 并发下同 ID 重复插入等异常：仅记录，聊天流程继续（消息追加不依赖会话记录存在）
            log.warn("补建会话记录失败 (session={}): {}", sessionId, e.getMessage());
        }
        return sessionId;
    }

    /**
     * 查询会话列表（游标分页版；仅当前用户；支持关键词搜索）。
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
        return listSessions(userId, keyword, cursor, size, null);
    }

    /**
     * 会话列表（带工作区过滤）。
     *
     * @param projectId 可选：只看某个工作区的会话；{@code "__unassigned__"} 只看真正没挂工作区的存量会话；
     *                  {@code "__nospace__"} 看「不属于任何真实工作区」（没挂工作区 + implicit 兜底容器，
     *                  侧栏顶部「会话」用）；{@code null}=不限（现状行为）。工作区不是权限边界，只是归属与过滤维度。
     */
    public SessionPage listSessions(String userId, String keyword, String cursor, int size, String projectId) {
        final String uid = requireUser(userId);
        final int pageSize = Math.max(1, Math.min(size, 100));
        final String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        // 「不属于任何真实工作区」：在真没挂工作区之外，并入该用户的 implicit 兜底容器。
        // 只查一次 id，三路查询共用；不懒创建（implicit 行不存在时本工作区下也不可能有会话）。
        final String noSpaceImplicitId = NO_SPACE_PROJECT.equals(projectId)
                ? projectService.implicitIdOf(uid) : null;

        // ---------- 分组计数：窄列查询（is_pinned/update_time/message_count）+ Java 分桶，一次查询覆盖全部桶 ----------
        LambdaQueryWrapper<Session> countQ = baseWrapper(uid, kw, projectId, noSpaceImplicitId);
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
            LambdaQueryWrapper<Session> pinnedQ = baseWrapper(uid, kw, projectId, noSpaceImplicitId);
            pinnedQ.eq(Session::getIsPinned, 1)
                    .orderByDesc(Session::getUpdateTime);
            sessionMapper.selectList(pinnedQ).forEach(s -> items.add(toInfo(s)));
        }
        LambdaQueryWrapper<Session> pageQ = baseWrapper(uid, kw, projectId, noSpaceImplicitId);
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
        fillLastMessage(items);

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

    /** 会话列表公共过滤条件：归属（严格本人）、种类（排除内部临时会话）、仅含消息的会话，
     *  供计数/置顶/分页三路查询复用（避免 OR 分组被重复拼装）。
     *  <p>「仅含消息」必须下推到 SQL：侧栏展示层隐藏空会话，若只靠前端过滤，空会话仍占游标分页的
     *  页容量——一旦积攒了 20+ 条空会话（如验证脚本直插、异常流程残留），最新的真实对话会被挤到
     *  第二页之后，侧栏整页"暂无会话"而组头计数正常（2026-10-14 实测）。空会话的复用由
     *  {@code createOrReuseEmptySession} 在服务端管理，不依赖列表返回空会话。 */
    private LambdaQueryWrapper<Session> baseWrapper(String uid, String kw,
                                                    String projectId, String noSpaceImplicitId) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Session::getUserId, uid);
        wrapper.ne(Session::getKind, KIND_TEMP);
        wrapper.gt(Session::getMessageCount, 0);
        // 工作区过滤（可选）：
        //   __nospace__      = 不属于任何真实工作区（没挂工作区 + implicit 兜底容器）
        //   __unassigned__   = 真正没挂工作区的存量会话（产物页共用这个口径）
        //   其余非空值        = 按工作区 id 精确匹配
        if (NO_SPACE_PROJECT.equals(projectId)) {
            final String implicitId = noSpaceImplicitId;
            wrapper.and(w -> {
                w.isNull(Session::getProjectId);
                if (implicitId != null && !implicitId.isBlank()) {
                    w.or().eq(Session::getProjectId, implicitId);
                }
            });
        } else if (ArtifactService.UNASSIGNED_PROJECT.equals(projectId)) {
            wrapper.isNull(Session::getProjectId);
        } else if (projectId != null && !projectId.isBlank()) {
            wrapper.eq(Session::getProjectId, projectId);
        }
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

    /**
     * 给本页会话补「最后一句」预览（工作区页的会话行与动态时间线用它说清「这条聊到了哪」）。
     *
     * <p>两步批量：先按 session_id 分组取每会话最大 sequence，再按 (session_id, sequence) 精确取
     * 那几条正文——整页固定两条 SQL，不逐行查（列表是首屏必走的路径，逐行就是 N+1）。
     * 只认**用户**最后一句：回答可能又长又是排版过的，一句提问才是「这条在做什么」的最短概括。
     * 失败按无预览处理：辅助信息，不该让整个列表打不开。
     */
    private void fillLastMessage(List<SessionInfo> items) {
        if (items == null || items.isEmpty()) return;
        try {
            List<String> ids = items.stream().map(SessionInfo::getId).filter(Objects::nonNull).toList();
            if (ids.isEmpty()) return;
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Message> seqQ =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            seqQ.select("session_id", "MAX(sequence) AS seq")
                    .eq("deleted", 0).eq("role", "user")
                    .in("session_id", ids).groupBy("session_id");
            Map<String, Object> lastSeq = new LinkedHashMap<>();
            for (Map<String, Object> row : messageMapper.selectMaps(seqQ)) {
                Object sid = row.get("session_id");
                Object seq = row.get("seq");
                if (sid != null && seq != null) lastSeq.put(String.valueOf(sid), seq);
            }
            if (lastSeq.isEmpty()) return;
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Message> contentQ =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            contentQ.select("session_id", "content").eq("deleted", 0).eq("role", "user");
            contentQ.and(w -> {
                for (Map.Entry<String, Object> e : lastSeq.entrySet()) {
                    w.or(w2 -> w2.eq("session_id", e.getKey()).eq("sequence", e.getValue()));
                }
            });
            Map<String, String> preview = new HashMap<>();
            for (Message m : messageMapper.selectList(contentQ)) {
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                preview.put(m.getSessionId(), excerptOf(m.getContent()));
            }
            for (SessionInfo info : items) info.setLastMessage(preview.get(info.getId()));
        } catch (Exception e) {
            log.warn("会话列表「最后一句」预览填充失败（按无预览处理）: {}", e.getMessage());
        }
    }

    /** 预览摘句：压成一行（换行与连续空白折成单空格）、上限 80 字 */
    private static String excerptOf(String raw) {
        String one = raw.replaceAll("\\s+", " ").trim();
        return one.length() <= 80 ? one : one.substring(0, 80) + "…";
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
        info.setProjectId(s.getProjectId());
        info.setModel(s.getModel() == null ? "" : s.getModel());
        info.setPermissionPreset(s.getPermissionPreset());
        // 这一列存的是某次点选留下的引用，而它随时可能变成不能用（供应商停用、模型被移除）：
        // 判不出来就只能等回答时撞上游报错，用户读到的是「网关坏了」而不是「你选的这个模型没了」。
        // 反过来，还没有消息的行（「新建对话」会复用空行）上的失效值是历史脏值，不是这个对话的选择——
        // 报出来会让用户以为自己对一个新对话做过什么决定
        if (s.getModel() != null && !s.getModel().isBlank()) {
            var u = modelRegistryService.referenceUsability(s.getModel(), null, null, false);
            if (u.usable()) {
                info.setModelIssue("");
            } else if (s.getMessageCount() == null || s.getMessageCount() <= 0) {
                info.setModel("");
                info.setModelIssue("");
            } else {
                info.setModelIssue(modelRegistryService.issueText(u.issue()));
            }
        }
        return info;
    }

    /**
     * 游客分享页的历史会话：按访客 uid + 智能体取最近若干条（只含有消息的）。
     * <p>
     * 不按 uid 一把列出：访客 uid 是浏览器级的（同一浏览器打开多个分享链接共用一个 uid），
     * 不带 agentId 过滤会把别个智能体的会话标题也暴露出来。
     * 单页取满即止、不做游标——一个访客在某个智能体下的会话数量由手动点击天然有界。
     * 内部临时会话同样排除：访客那一侧也会咨询其他助手，那条分支的提问不该出现在他的历史里。
     */
    public List<SessionInfo> listSessionsForAgent(String userId, String agentId, int limit) {
        if (agentId == null || agentId.isBlank()) return List.of();
        LambdaQueryWrapper<Session> q = new LambdaQueryWrapper<>();
        q.eq(Session::getUserId, requireUser(userId))
                .eq(Session::getAgentId, agentId)
                .ne(Session::getKind, KIND_TEMP)
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
     * 设置会话级权限档位（校验归属）。{@code code} 空或 {@code follow} = 清除覆盖，回到跟随智能体。
     *
     * <p>档位码的合法性在这里校：写进库再发现认不出，问答侧只能回落到更低的档位，
     * 用户看到的「已切到完全权限」就成了一句假话。
     *
     * <p><b>会话级档位对显式工作区与隐式会话都生效</b>：隐式会话也有工作区后端（本机执行器目录；
     * 离线时是云端沙盒），不再存在「没有目录所以没法执行」的会话。目录尚未落库时也允许先存——
     * 下一轮解析出目录后按这个档位执行，不出现「选了却报错」。
     * 清除（follow）永远不拦：给库里残值留一条出路。
     */
    public String setPermissionPreset(String userId, String sessionId, String code) {
        assertOwned(sessionId, userId);
        String v = code == null ? "" : code.trim();
        if (v.isEmpty() || "follow".equalsIgnoreCase(v) || "default".equalsIgnoreCase(v)) {
            sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .set(Session::getPermissionPreset, null));
            return null;
        }
        com.wenqu.ai.agent.permission.PermissionPreset p =
                com.wenqu.ai.agent.permission.PermissionPreset.fromCode(v);
        if (p == null) {
            throw new com.wenqu.ai.common.BizException(400, "认不出的权限档位：" + v);
        }
        Session update = new Session();
        update.setId(sessionId);
        update.setPermissionPreset(p.code());
        sessionMapper.updateById(update);
        log.info("[SESSION] 权限档位已切换 uid={} sid={} preset={}", userId, sessionId, p.code());
        return p.code();
    }

    /** 这条会话是不是挂在一个<b>真</b>工作区下：没挂 / 工作区已删 / implicit 兜底容器（侧栏「会话」）都不算。 */
    private boolean inRealWorkspace(Session s) {
        if (s == null || s.getProjectId() == null || s.getProjectId().isBlank()) return false;
        Project p = projectService.byId(s.getProjectId());
        return p != null && !Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus());
    }

    /**
     * 会话当前选的档位码（系统侧读取，不校验归属；null=没选过）。
     *
     * <p>显式工作区与隐式会话一律认库里的值：隐式会话也有工作区后端（本机执行器目录；
     * 离线时是云端沙盒），档位在这些会话上有真实的执行对象。
     */
    public String permissionPresetOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session s = sessionMapper.selectById(sessionId);
            return s == null ? null : s.getPermissionPreset();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 会话标题（系统侧读取，不校验归属）：工作区轮次守卫用它把「谁在跑」说成
     * 「会话『XX』的一轮对话」。查不到/没标题返回 {@code null}，由调用方退回泛称——
     * 不编名字（与 {@code workspaceOfSession} 同一口径）。
     */
    public String titleOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session s = sessionMapper.selectById(sessionId);
            String t = s == null ? null : s.getTitle();
            return t == null || t.isBlank() ? null : t;
        } catch (Exception e) {
            log.warn("[SESSION] 会话标题读取失败（按无标题处理）session={}: {}", sessionId, e.getMessage());
            return null;
        }
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
        final String uid = requireUser(userId);
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
     * 追加消息（MySQL 持久化 → Redis 缓存），返回消息 ID（用于反馈关联）。
     * <p>入参对象化：此前 14 个 telescoping 重载把 21 个位置参数一路传到底，新增一列要补一层重载，
     * 相邻同类型实参写反编译器也不会拦。这里只保留 {@link MessageWrite} 一个写口。
     */
    public String appendMessage(MessageWrite write) {
        if (write == null) return null;
        String sessionId = write.getSessionId();
        try {
            return appendToMysql(write);
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
            return appendToMysql(write);
        } catch (Exception e) {
            log.warn("MySQL 追加消息重试仍失败 (session={}): {}", sessionId, e.getMessage());
        }

        // Redis 缓存（MySQL 持续不可用也不影响前端体验；mysqlPending 标记使读侧能合并补写，防消息丢失）
        try {
            Map<String, Object> redisMsg = new HashMap<>();
            redisMsg.put("role", write.getRole());
            redisMsg.put("content", write.getContent());
            redisMsg.put("mysqlPending", true);
            if (notBlank(write.getTurnId())) {
                redisMsg.put("turnId", write.getTurnId()); // 本轮身份（降级缓存也带，补写 MySQL 后过程回放不断线）
            }
            // 降级消息自带全局唯一 msgId：补写 MySQL 时按 id 幂等（同一消息不重复插；
            // 且不再被「与历史消息同文」误判为重复——用户复读完全相同的问题也各自补写，不丢失）
            redisMsg.put("msgId", UUID.randomUUID().toString().replace("-", ""));
            if (notBlank(write.getThinking())) {
                redisMsg.put("thinking", write.getThinking());
            }
            if (write.getImages() != null && !write.getImages().isEmpty()) {
                redisMsg.put("images", write.getImages());
            }
            putJsonArray(redisMsg, "sources", write.getSources(), Map.class);
            putJson(redisMsg, "retrieved", write.getRetrieved());
            putJsonArray(redisMsg, "related", write.getRelated(), String.class);
            putJson(redisMsg, "artifacts", write.getArtifacts());
            putJson(redisMsg, "toolCalls", write.getToolCalls());
            putJsonArray(redisMsg, "attachments", write.getAttachments(), Map.class);
            putJson(redisMsg, "tokens", write.getTokens());
            putJsonArray(redisMsg, "timeline", write.getTimeline(), Map.class);
            if (notBlank(write.getProcessText())) {
                redisMsg.put("processText", write.getProcessText()); // 过程独白全文（降级缓存也带，读侧原样透出）
            }
            if (notBlank(write.getAgentId())) {
                redisMsg.put("agentId", write.getAgentId());   // 智能体归属（降级缓存同样带：补写 MySQL 后归属不丢）
                redisMsg.put("agentName", write.getAgentName() == null ? "" : write.getAgentName());
            }
            if (notBlank(write.getModel())) {
                redisMsg.put("model", write.getModel());       // 生效模型（与 tokens 同口径随降级缓存携带）
            }
            putJsonArray(redisMsg, "mentions", write.getMentions(), Map.class);
            putJsonArray(redisMsg, "historyRefs", write.getHistoryRefs(), Map.class);
            putJson(redisMsg, "plan", write.getPlan());
            putJson(redisMsg, "blocks", write.getBlocks()); // 显示块（降级缓存也带，补写 MySQL 后新前端不退化）
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void putJson(Map<String, Object> target, String key, String json) {
        if (!notBlank(json)) return;
        try {
            target.put(key, JSON.parse(json));
        } catch (Exception ignored) {
        }
    }

    private static void putJsonArray(Map<String, Object> target, String key, String json, Class<?> itemType) {
        if (!notBlank(json)) return;
        try {
            target.put(key, JSON.parseArray(json, itemType));
        } catch (Exception ignored) {
        }
    }

    /**
     * 事务内写 MySQL 一条消息：锁会话行（不存在则补建占位后重取锁）串行化同会话并发写 →
     * 序号取物理 max+1（含软删行，删除轮次后不复用，撤销按 seq-1 配对可靠）→ 插入 → 同步会话计数/首问标题。
     * 任一失败抛异常回滚，由调用方决定降级。
     */
    private String appendToMysql(MessageWrite w) {
        final String sessionId = w.getSessionId();
        final String role = w.getRole();
        final String content = w.getContent();
        // 列宽护栏：c_ai_message 的 TEXT 列容量 64KB（utf8mb4 按字节算，中文约 2.1 万字）。
        // 超长的直接后果不是「存一半」——是 MysqlDataTruncation 让**整条消息**（正文/工具卡/时间线/用量）
        // 一起落库失败（2026-10-22 线上实测：一轮异常长的思考把 thinking 列撑爆，助手消息整体没存进库）。
        // 落库侧截断兜底而不是只靠源头自律：思考与过程独白是回放性内容，截掉尾部只少看一点过程；
        // 消息本体丢了才是不可逆的。content 已升 MEDIUMTEXT（单条提问 6 万字那次的 ALTER），
        // 走自己的护栏（CONTENT_COLUMN_MAX_CHARS）——TEXT 的 2 万字旧护栏会把长回答（表单助手
        // 一轮 2 万字以上的 schema）拦腰截断，截断副本落库后，回放/读库侧拿到的都是残缺 JSON。
        final String contentCapped = capForColumn(content, CONTENT_COLUMN_MAX_CHARS);
        final String thinkingCapped = capForTextColumn(w.getThinking());
        final String processTextCapped = capForTextColumn(w.getProcessText());
        // blocks 自带全文，超长回答可能比 content 还大；超过两倍正文上限就放弃这一列，
        // 让消息本体与旧列照常落库（前端退回 timeline），而不是整行插入失败。
        String blocksValue = w.getBlocks();
        if (blocksValue != null && blocksValue.length() > CONTENT_COLUMN_MAX_CHARS * 2L) {
            log.warn("[BLOCKS] 显示块过大，跳过落库（正文与旧列照常）: session={} chars={}",
                    sessionId, blocksValue.length());
            blocksValue = null;
        }
        final String blocks = blocksValue;
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
            msg.setContent(contentCapped);
            msg.setThinking(thinkingCapped);
            msg.setImages(w.getImages() != null && !w.getImages().isEmpty() ? JSON.toJSONString(w.getImages()) : null);
            msg.setSources(w.getSources());
            msg.setRetrieved(w.getRetrieved());
            msg.setRelated(w.getRelated());
            msg.setArtifacts(w.getArtifacts());
            msg.setToolCalls(w.getToolCalls());
            msg.setAttachments(w.getAttachments());
            msg.setTokens(w.getTokens());
            msg.setModel(w.getModel());
            msg.setTimeline(w.getTimeline());
            msg.setProcessText(processTextCapped);
            msg.setBlocks(blocks);
            msg.setAgentId(w.getAgentId());
            msg.setAgentName(w.getAgentName());
            msg.setMentions(w.getMentions());         // @ 引用（轮级注入 → 消息级常驻标注，刷新/历史回显保留）
            msg.setHistoryRefs(w.getHistoryRefs());   // # 历史引用（同上）
            msg.setPlan(w.getPlan());                 // 计划批准卡（刷新/历史按轮重建执行计划卡）
            // 本轮身份：与 c_ai_session_event.turn_id 同源，逐气泡「过程」按它取本行那一轮的账本。
            // 空串归一成 NULL——列语义是「没有关联键」，不留一个既查不到东西又占位的空串
            msg.setTurnId(notBlank(w.getTurnId()) ? w.getTurnId() : null);
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
     * TEXT 列的字符数护栏（64KB 字节容量；utf8mb4 中文最多 3 字节/字，取 2 万字留足余量）。
     * 超长截尾并带省略标注——只丢回放性内容，不让整条消息因 MysqlDataTruncation 落库失败。
     */
    private static final int TEXT_COLUMN_MAX_CHARS = 20_000;

    /**
     * content 列的字符数护栏。content 已升 MEDIUMTEXT（16MB 字节），TEXT 时代的 2 万字护栏
     * 会让长回答（表单设计助手一轮 2 万字以上的整份 schema）落库即残缺；这里只留一道
     * 兜底（100 万字 ≈ 最坏 4MB 字节，远在列容量内，也是现实回答上限的数倍），挡失控输出。
     */
    private static final int CONTENT_COLUMN_MAX_CHARS = 1_000_000;

    private static String capForTextColumn(String s) {
        return capForColumn(s, TEXT_COLUMN_MAX_CHARS);
    }

    private static String capForColumn(String s, int limit) {
        if (s == null || s.length() <= limit) return s;
        return s.substring(0, limit) + "\n…（超过单列容量，已截断）";
    }

    /**
     * 写入会话当前任务清单（整表覆盖，只留最新一份）。
     * <p>清单表达"现在还剩几步"，不是历史；历次写入由会话事件账本留痕。
     */
    public void saveTodos(String sessionId, String todosJson) {
        try {
            Session s = new Session();
            s.setId(sessionId);
            s.setTodos(todosJson);
            sessionMapper.updateById(s);
        } catch (Exception e) {
            log.warn("[TODO] 任务清单落库失败: session={} {}", sessionId, e.getMessage());
        }
    }

    /** 读本会话当前任务清单（JSON 字符串；无清单返回 null）。归属校验由调用方按 uid 做 */
    public String todosOf(String sessionId) {
        try {
            Session s = sessionMapper.selectById(sessionId);
            return s == null ? null : s.getTodos();
        } catch (Exception e) {
            log.warn("[TODO] 任务清单读取失败: session={} {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 用户叫停且本轮什么都没产出时，把终止原因记在**那一问**上，前端在问题下方渲染一行安静的说明。
     * <p>为什么不落一条「已停止」的空回答气泡：仓库既有口径是空的回答留在对话里比不留更碍眼
     * （分享页的停止处理同款理由）；而「这一问没有回答」本就是那一问的事实，记在它身上最贴合。
     * <p>本轮已产出过内容（半程正文、只有工具卡、只有思考）时不标——那些已有 assistant 消息带
     * 「⏹ 已停止本轮」可看，再在问上标一次是重复信息。
     *
     * @param reason {@code stopped}=用户主动叫停；{@code interrupted}=断线超过宽限、这一轮被系统按中断收束。
     *               两者文案必须不同：后者不是用户做的事，写成「已按你的要求停止」是把系统的决定算在人头上
     */
    public void markTurnStopped(String sessionId, String reason) {
        try {
            Message last = messageMapper.selectLastUserMessage(sessionId);
            if (last == null) return;
            if (messageMapper.countAssistantAfter(sessionId, last.getSequence()) > 0) return;
            messageMapper.updateEndReason(last.getId(), reason == null || reason.isBlank() ? "stopped" : reason);
        } catch (Exception e) {
            log.warn("[STOP] 标记本轮终止原因失败: session={} {}", sessionId, e.getMessage());
        }
    }

    
    /**
     * 记下本轮实际作答的智能体（P5：这一列从"会话锁定值"改为"最近作答方"）。
     * <p>为什么还留着写它：分享页要按智能体列访客会话（{@link #listSessionsForAgent}）、
     * 访客闲置会话要按智能体回收（{@link #deleteSessionsOfVisitor}）——对免登录链路来说
     * 作答方恒等于被发布的那个智能体，语义没变；变的是登录对话页不再被它锁住。
     * <p>best-effort：写失败只影响列表回显，不影响这一轮回答。
     */
    public void recordTurnAgent(String sessionId, String agentId, String agentName) {
        if (sessionId == null || sessionId.isBlank() || agentId == null || agentId.isBlank()) return;
        try {
            sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .set(Session::getAgentId, agentId)
                    .set(Session::getAgentName, agentName == null ? "" : agentName));
        } catch (Exception e) {
            log.warn("记录本轮作答智能体失败 (session={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 记下本轮实际生效的模型引用（轮首解析出可用模型后顺手落一份，与 {@link #recordTurnAgent} 同风格）。
     * <p>为什么要写：会话行上的 model 原本只在聊天页<b>手动切换</b>时写入，靠个人默认作答的会话永远
     * 没有这份记录——隔天打开旧会话，右下角模型选择器回显的是"现在的"个人默认，而不是这个会话
     * 上一轮真正用的那个。每轮把生效值落一份后，回显与下一轮解析（会话固定优先于个人默认）都钉在
     * 会话实际用的模型上，个人默认的改动只影响新会话。
     * <p>best-effort：写失败只影响回显，不影响这一轮回答。手动切换仍走 {@link #updateModel}
     * （带归属校验与 fail-loud）；这里记录的是本轮解析链的结论，不再重复校验。
     */
    public void recordTurnModel(String sessionId, String modelRef) {
        if (sessionId == null || sessionId.isBlank() || modelRef == null || modelRef.isBlank()) return;
        try {
            sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .set(Session::getModel, modelRef));
        } catch (Exception e) {
            log.warn("记录本轮生效模型失败 (session={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 会话级模型覆盖（聊天页切换模型即存，刷新/换端后选择不丢）。
     * model 空白 = 清除覆盖回到跟随个人默认；非空去空白、限长 190（与 c_ai_message.model 列同宽）。
     * 引用值的有效性（可解析/可用）由控制器先行 fail-loud 校验，这里只管归属与落库。
     */
    public void updateModel(String userId, String sessionId, String model) {
        assertOwned(sessionId, userId);
        String m = model == null ? "" : model.trim();
        if (m.length() > 190) throw new com.wenqu.ai.common.BizException("模型引用过长");
        // 清除覆盖要显式 SET null：updateById 默认忽略 null 字段（置空清不掉，故显式 SET）
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getId, sessionId)
                .set(Session::getModel, m.isEmpty() ? null : m));
    }


    /** 本人会话里仍钉着该供应商的模型覆盖条数（供应商管理界面「谁还在用」的那一项） */
    public int countModelOverridesOf(String userId, String providerId) {
        if (providerId == null || providerId.isBlank()) return 0;
        return Math.toIntExact(sessionMapper.selectCount(new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, requireUser(userId))
                .likeRight(Session::getModel, providerId.trim() + "/")));
    }

    /**
     * 清除会话级模型覆盖中指向某个供应商的那些条（只动本人的会话——这一列只归本人改）。
     * <p>为什么需要：停用供应商或把模型从模型列表里移除，都不会让历史会话里钉着的那串引用自己消失，
     * 于是每一轮都要拿坏引用去打上游（真机 sbx-3：停用后连续几轮 401）。问答侧会在撞上时清掉当前这一条，
     * 这里给的是「一次清干净」的入口，配合供应商管理界面的引用清单用。
     *
     * @return 清掉的条数
     */
    public int clearModelOverridesOf(String userId, String providerId) {
        int count = countModelOverridesOf(userId, providerId);
        if (count == 0) return 0;
        sessionMapper.update(null, new LambdaUpdateWrapper<Session>()
                .eq(Session::getUserId, requireUser(userId))
                .likeRight(Session::getModel, providerId.trim() + "/")
                .set(Session::getModel, null));
        return count;
    }

    /**
     * 读取会话已存的模型覆盖（聊天轮请求未显式携带时的回落依据）。无覆盖/会话不存在/查询失败返回空串。
     */
    public String sessionModelOf(String sessionId) {
        return sessionPinOf(sessionId).model();
    }

    /**
     * @param model  已存的模型覆盖（空串=没有）
     * @param empty  这条会话还没有任何消息
     */
    public record SessionPin(String model, boolean empty) {
    }

    /**
     * 会话覆盖 + 「这条会话还是不是空的」。
     * <p>空会话这一维是给失效判定用的：「新建对话」会复用一条还没消息的行（前端 createNewSession 的
     * emptySid 分支），这种行上可能带着它上一辈子留下的、如今已经停用的模型。用户在这个新对话里
     * 什么都没选过，所以既不该在胶囊上报红，也不该在回答前被告知「清除了你上次选的东西」。
     * 可选的模型不会走到这一步——能选的一定可用，空会话上存着不可用的值只可能是历史脏值。
     */
    public SessionPin sessionPinOf(String sessionId) {
        try {
            Session s = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                    .eq(Session::getId, sessionId)
                    .select(Session::getModel, Session::getMessageCount)
                    .last("LIMIT 1"));
            if (s == null) return new SessionPin("", true);
            return new SessionPin(s.getModel() == null ? "" : s.getModel(),
                    s.getMessageCount() == null || s.getMessageCount() <= 0);
        } catch (Exception e) {
            log.warn("读取会话模型覆盖失败 (session={}): {}", sessionId, e.getMessage());
            return new SessionPin("", true);
        }
    }

    /**
     * 清除 Redis 缓存（保留 MySQL 数据）
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    /**
     * 清空当前用户的会话（MySQL 软删 + Redis 清理）
     */
    public void clearAll(String userId) {
        String uid = requireUser(userId);
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
     * 每轮文件改动补写到回答消息行（appendMessage 不为此扩参，与 setMessageVariant 同一理由；
     * 轮末对比要在收尾处等执行器，落库时点在回答落库之后）。
     * <p>只存相对路径与行数：文件内容留在执行器本机的快照/捕获里，点开差异时再让执行器现算。
     */
    public void setMessageChanges(String messageId, String changesJson) {
        if (messageId == null || messageId.isBlank() || changesJson == null || changesJson.isBlank()) return;
        try {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .set(Message::getChanges, changesJson));
        } catch (Exception e) {
            // 改动卡写不进去只是这一轮「回头看不见」，回答本体不受影响；日志留痕即可
            log.warn("[CHANGES] 每轮文件改动写入失败 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    /**
     * 本轮降级事件补写（回答落库后另发一条 UPDATE，只在**真有降级**时调用——正常轮次不产生这次往返）。
     * <p>为什么不塞进 appendMessage 那串 21 个位置参数：那条签名已是 P6 的搬迁对象，为一个偶发字段
     * 再加一位会让调用点更难对齐；且降级列表在落库时点之后还会增长（工具链路与换路重试都可能在之后登记）。
     */
    /**
     * 本轮降级事件补写到回答消息行（没降级就不写）。
     * <p>补的是「只在流里活一次」那半只钥匙：降级条此前只随 done 事件下发，刷新/切会话后整块消失——
     * 用户当时看见「本轮没咨询成其他助手」，回头看不见就成了没发生过，与设置页不许有假开关是同一条底线。
     * <p>P6 刀 4 从问答侧的那层私有包装收口到这里：主链路与工作流那条路都要补，
     * 留两份就是两份「什么时候不补」的判据。
     */
    public void persistDegradations(String messageId, java.util.List<java.util.Map<String, String>> degradations) {
        if (messageId == null || messageId.isBlank() || degradations == null || degradations.isEmpty()) return;
        saveMessageDegradations(messageId, com.alibaba.fastjson2.JSON.toJSONString(degradations));
    }

    public void saveMessageDegradations(String messageId, String degradationsJson) {
        if (messageId == null || messageId.isBlank()
                || degradationsJson == null || degradationsJson.isBlank()
                || "[]".equals(degradationsJson)) {
            return;
        }
        try {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .set(Message::getDegradations, degradationsJson));
        } catch (Exception e) {
            // 写不进去只影响「刷新后还能不能看到这轮的降级条」，不阻断问答收尾，留痕即可
            log.warn("[DEGRADATION] 降级事件落库失败 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    /**
     * 本轮警示条补写到回答消息行（没警示就不写）。
     * <p>与降级条同一个错法：warn 帧只随流下发一次，「沙盒写入未执行」「该操作被权限档位拒绝」这类
     * 当场看得见的异常刷新后整块消失，用户回头读到的是一轮看起来普普通通的回答。
     * <p>只存最后一条：实时那侧本来就是一颗气泡一个警示位（后发的盖掉前发的），落库这份与它同形。
     */
    public void saveMessageWarn(String messageId, String warn) {
        if (messageId == null || messageId.isBlank() || warn == null || warn.isBlank()) return;
        try {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .set(Message::getWarnMsg, warn));
        } catch (Exception e) {
            log.warn("[WARN] 警示条落库失败 (messageId={}): {}", messageId, e.getMessage());
        }
    }

    /** 失败原因落库的原文上限：这一句是给用户看的，不是异常栈（工作流那条会带 run.error 全文） */
    private static final int FAIL_MSG_MAX = 2000;

    /**
     * 本轮失败原因补写：挂在<b>本轮最后一行</b>上——跑出了半截回答就挂回答，什么都没产出就挂在那一问上。
     * <p>补的是「只随流下发一次」那半只钥匙：error 帧当场看得见，刷新一次连「这一轮断过」这件事
     * 都读不出来，用户面对的是一轮凭空消失的回答（与 {@code warn_msg}、{@code degradations} 同一个错法）。
     * <p>时机由调用方保证：必须在收尾时调，那时本轮的 assistant 行该落的已经落了——早一步判会把
     * 失败挂到提问上，界面上就成了「错误卡在问下面、半截回答在它上面」的错位。
     * <p>写不进去只影响「刷新后还能不能看到这轮为什么失败」，不阻断问答收尾，留痕即可。
     */
    public void saveTurnFailure(String sessionId, String turnId, String message) {
        if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) return;
        String text = message.length() > FAIL_MSG_MAX ? message.substring(0, FAIL_MSG_MAX) + "…" : message;
        try {
            Message row = turnId == null || turnId.isBlank()
                    ? null : messageMapper.selectLastOfTurn(sessionId, turnId);
            // 本轮一行都没有（提问在更早的提交里没落库）：退回最后一问，别把失败原因丢掉
            if (row == null) row = messageMapper.selectLastUserMessage(sessionId);
            if (row == null) {
                log.warn("[FAIL] 本轮没有可挂失败原因的消息行: session={} turn={}", sessionId, turnId);
                return;
            }
            messageMapper.updateFailMsg(row.getId(), text);
        } catch (Exception e) {
            log.warn("[FAIL] 失败原因落库失败 (session={} turn={}): {}", sessionId, turnId, e.getMessage());
        }
    }

    /**
     * 把一条刚落库的用户提问标记为「目标指令回声」（值＝目标 id）。
     * <p>与 {@code saveMessageWarn} 同一个形状：先按常规写口把正文落进去，再补这一列，
     * 免得为一条标注去加长参数列（十个入参的 appendMessage 已经是第七个调用点在读）。
     * <p>失败只告警：目标本身已经成立，回声只是对话里的一行留痕。
     */
    public void markGoalCommand(String messageId, String goalId) {
        if (messageId == null || messageId.isBlank() || goalId == null || goalId.isBlank()) return;
        try {
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .set(Message::getGoalCommand, goalId));
        } catch (Exception e) {
            log.warn("[GOAL] 目标指令回声标记失败 (messageId={}): {}", messageId, e.getMessage());
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
        // 本轮身份：前端据此挂逐气泡「过程」入口（按轮取事件账本）；存量为 NULL=这一轮没有过程记录
        if (m.getTurnId() != null && !m.getTurnId().isBlank()) {
            map.put("turnId", m.getTurnId());
        }
        // 本轮终止原因（记在用户提问上）：前端据此在问题下方显示「本轮已停止 · 未产生回答」
        if (m.getEndReason() != null && !m.getEndReason().isBlank()) {
            map.put("endReason", m.getEndReason());
        }
        // 本轮失败原因（挂在回答上，零产出轮挂在那一问上）：前端据此重建错误卡，刷新后仍看得见为什么断
        if (m.getFailMsg() != null && !m.getFailMsg().isBlank()) {
            map.put("failMsg", m.getFailMsg());
        }
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
        if (m.getDegradations() != null && !m.getDegradations().isBlank()) {
            try {
                // 本轮降级事件（历史回显：刷新后降级条仍在，与当轮流式观感一致）
                map.put("degradations", JSON.parseArray(m.getDegradations(), Map.class));
            } catch (Exception e) {
                // degradations 解析失败忽略
            }
        }
        if (m.getArtifacts() != null && !m.getArtifacts().isBlank()) {
            try {
                map.put("artifacts", JSON.parseArray(m.getArtifacts(), Map.class)); // 产物卡片（前端按需签名下载）
            } catch (Exception e) {
                // artifacts 解析失败忽略
            }
        }
        // 本轮警示条（沙盒基建异常/权限拒绝这类当场看得见的异常）：只随流下发时刷新即消失
        if (m.getWarnMsg() != null && !m.getWarnMsg().isBlank()) {
            map.put("warnMsg", m.getWarnMsg());
        }
        if (m.getToolCalls() != null && !m.getToolCalls().isBlank()) {
            try {
                map.put("toolCalls", JSON.parseArray(m.getToolCalls(), Map.class)); // 工具调用过程（历史回显）
            } catch (Exception e) {
                // toolCalls 解析失败忽略
            }
        }
        // 每轮文件改动（历史回显：改动卡刷新后仍在；只含相对路径与行数，差异按需再取）
        if (m.getChanges() != null && !m.getChanges().isBlank()) {
            try {
                map.put("changes", JSON.parse(m.getChanges()));
            } catch (Exception e) {
                // changes 解析失败忽略
            }
        }
        if (m.getPlan() != null && !m.getPlan().isBlank()) {
            try {
                map.put("plan", JSON.parse(m.getPlan())); // 计划批准卡（历史回显：每轮计划各留一张，前端重建气泡计划卡）
            } catch (Exception e) {
                // plan 解析失败忽略
            }
        }
        // 目标指令回声（记在用户提问上）：前端据此挂命令标注并撤掉普通消息动作；NULL=普通提问
        if (m.getGoalCommand() != null && !m.getGoalCommand().isBlank()) {
            map.put("goalCommand", m.getGoalCommand());
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
        if (m.getBlocks() != null && !m.getBlocks().isBlank()) {
            try {
                map.put("blocks", JSON.parse(m.getBlocks())); // 显示块投影（新前端优先于 timeline）
            } catch (Exception e) {
                // blocks 解析失败忽略，前端按 timeline/processText 兜底
            }
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
                    msg.setContent(capForColumn(content, CONTENT_COLUMN_MAX_CHARS));
                    msg.setImages(imagesJson);
                    Object thinkingObj = m.get("thinking");
                    if (thinkingObj != null) {
                        // 降级缓存里的 thinking 是未截断原文：直接落 TEXT 列会重演 2026-10-22 的整行落库失败
                        msg.setThinking(capForTextColumn(String.valueOf(thinkingObj)));
                    }
                    Object sourcesObj = m.get("sources");
                    if (sourcesObj != null) {
                        msg.setSources(JSON.toJSONString(sourcesObj));
                    }
                    Object relatedObj = m.get("related");
                    if (relatedObj != null) {
                        msg.setRelated(JSON.toJSONString(relatedObj));
                    }
                    Object artifactsObj = m.get("artifacts");
                    if (artifactsObj != null) {
                        msg.setArtifacts(JSON.toJSONString(artifactsObj));
                    }
                    Object toolCallsObj = m.get("toolCalls");
                    if (toolCallsObj != null) {
                        msg.setToolCalls(JSON.toJSONString(toolCallsObj));
                    }
                    Object attachmentsObj = m.get("attachments");
                    if (attachmentsObj != null) {
                        msg.setAttachments(JSON.toJSONString(attachmentsObj));
                    }
                    Object tokensObj = m.get("tokens");
                    if (tokensObj != null) {
                        msg.setTokens(JSON.toJSONString(tokensObj));
                    }
                    Object timelineObj = m.get("timeline");
                    if (timelineObj != null) {
                        msg.setTimeline(JSON.toJSONString(timelineObj));
                    }
                    Object processTextObj = m.get("processText");
                    if (processTextObj != null) {
                        msg.setProcessText(capForTextColumn(String.valueOf(processTextObj)));
                    }
                    Object modelObj = m.get("model");
                    if (modelObj != null) {
                        msg.setModel(String.valueOf(modelObj));
                    }
                    Object agentIdObj = m.get("agentId");
                    if (agentIdObj != null) {
                        msg.setAgentId(String.valueOf(agentIdObj));
                        msg.setAgentName(m.get("agentName") == null ? "" : String.valueOf(m.get("agentName")));
                    }
                    Object blocksObj = m.get("blocks");
                    if (blocksObj != null) {
                        msg.setBlocks(JSON.toJSONString(blocksObj));
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
                    Object turnIdObj = m.get("turnId");
                    if (turnIdObj != null) {
                        String tid = String.valueOf(turnIdObj);
                        msg.setTurnId(tid.isBlank() ? null : tid); // 本轮身份（Redis 降级消息补写 MySQL 后过程回放仍定位到轮）
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
     * 也就是说访客会话被回收后还有 {@code cleanup.recordRetentionDays} 天可以被捞回来。
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
