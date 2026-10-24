package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.ProjectMapper;
import com.wenqu.ai.mapper.ProjectNoteLogMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectNoteLog;
import com.wenqu.ai.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作区容器（{@code c_ai_project}）的服务层。
 *
 * <p>本类只做三件事，刻意保持薄——工作区不是权限模型，也不是编排单元：
 * <ol>
 *   <li>{@link #ensureImplicit(String)}：给用户拿到（必要时懒创建）那个唯一的 implicit 兜底容器；</li>
 *   <li>{@link #projectIdOfSession(String)}：由会话反推工作区——沙盒作用域与产物归属共用这一个入口，
 *       避免两处各写一套推导而漂移；</li>
 *   <li>{@link #listOf(String)}：列出该用户的工作区（implicit 在前）。</li>
 * </ol>
 *
 * <p><b>为什么用侧效"懒创建"而不是建用户时种一行</b>：种在 {@code UserService} 里会漏掉
 * 存量用户（几十个 historical 账号）与外部 IdP 首次登录（{@code oidc.*} 独立建号路径）；
 * 懒创建只要求「用得到的时候建」，两条路都覆盖，且不给从不用一问一答的用户建空行。
 * 并发安全靠 {@code uk_uid_idem(uid, idempotency_key)} 唯一索引：抢插失败的线程重查即可。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final SessionMapper sessionMapper;
    private final ArtifactMapper artifactMapper;
    /** 约定留痕：update() 改约定时把被替换的旧版抄一行进来（leaf mapper，直接注入无环） */
    private final ProjectNoteLogMapper noteLogMapper;
    /**
     * 校验「工作区默认助手」确实是本人可读的助手。
     * <p><b>必须惰性取</b>：直接注入 AgentService 会形成
     * {@code ProjectService → AgentService → ToolRegistry → ArtifactToolProvider → ArtifactService → ProjectService}
     * 的构造器环（Spring Boot 3 默认禁止循环依赖，启动直接失败，2026-10-17 真实上下文测试当场抓到）。
     * 校验是低频动作，用到时再取即可。
     */
    private final org.springframework.beans.factory.ObjectProvider<AgentService> agentProvider;
    /**
     * 归档联动停用/恢复定时任务。
     * <p>同样必须惰性取：{@code ScheduledJobService} 的构造器依赖本类（校验任务的工作区归属），
     * 直接注入会构成构造器环（Spring Boot 3 默认禁止，启动直接失败）。归档是低频动作，用到时再取即可。
     */
    private final org.springframework.beans.factory.ObjectProvider<ScheduledJobService> scheduledJobProvider;
    /**
     * 删工作区时按用户选择连工作目录一起删。
     * <p>同样惰性取：{@code ProjectFileService} 的构造器依赖本类（{@code baseDir} 里的归属校验）。
     */
    private final org.springframework.beans.factory.ObjectProvider<ProjectFileService> projectFileProvider;
    /**
     * 共用工作目录的全局总闸（{@code sandbox.projectWorkdirEnabled}）。
     * <p>直接注入安全：ConfigService 只依赖配置侧的 mapper/redis/schema，不反向依赖本类。
     */
    private final ConfigService configService;

    /**
     * 校验「工作区绑定知识库」确实存在且对本人可见。
     * <p><b>必须惰性取</b>：与 AgentService 同理，直接注入会构成构造器环，绑定是低频动作。
     */
    private final org.springframework.beans.factory.ObjectProvider<KnowledgeBaseService> kbProvider;

    public ProjectService(ProjectMapper projectMapper, SessionMapper sessionMapper,
                          ArtifactMapper artifactMapper, ProjectNoteLogMapper noteLogMapper,
                          org.springframework.beans.factory.ObjectProvider<AgentService> agentProvider,
                          org.springframework.beans.factory.ObjectProvider<ScheduledJobService> scheduledJobProvider,
                          org.springframework.beans.factory.ObjectProvider<ProjectFileService> projectFileProvider,
                          ConfigService configService,
                          org.springframework.beans.factory.ObjectProvider<KnowledgeBaseService> kbProvider) {
        this.projectMapper = projectMapper;
        this.sessionMapper = sessionMapper;
        this.artifactMapper = artifactMapper;
        this.noteLogMapper = noteLogMapper;
        this.agentProvider = agentProvider;
        this.scheduledJobProvider = scheduledJobProvider;
        this.projectFileProvider = projectFileProvider;
        this.configService = configService;
        this.kbProvider = kbProvider;
    }

    /**
     * 该用户的 implicit 兜底容器；不存在则创建。
     *
     * @param uid 用户 uid；空/匿名返回 null（不建工作区，调用方按「无工作区」继续）
     * @return 兜底容器；uid 不可用时返回 null
     */
    public Project ensureImplicit(String uid) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid)) {
            // 匿名/游客不建工作区：他们没有一个可持续的身份，建了也无从归属（分享页访客会话同理）
            return null;
        }
        Project existing = findImplicit(uid);
        if (existing != null) return existing;

        Project p = new Project();
        // 必须先自己生成 id 再拼 workdir：@TableId(ASSIGN_UUID) 是**insert 时**才回填 id 的，
        // 依赖"insert 后就有 id"会把路径写成 projects/null，于是**所有用户的兜底容器落到同一个目录**
        // （2026-10-17 真实上下文集成测试当场抓到，mock 单测测不出这种时序）。
        // 自己先赋值后，MyBatis-Plus 见 id 非空就不再生成。
        p.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        p.setUid(uid);
        p.setName(null); // implicit 不给名字：它不是用户建的工作区，界面上只体现为侧栏「会话」栏
        p.setSelectionStatus(Project.SELECTION_IMPLICIT);
        p.setDirectoryMode(Project.MODE_MANAGED);
        p.setStatus(Project.STATUS_ACTIVE);
        // 兜底容器不是工作区：「会话」栏那批对话保持各自一份目录（引入工作区前的行为），
        // 共用目录只对用户显式建的场子生效——随口一问的两次聊天不该互相看得见对方的中间文件
        p.setSharedWorkdir(0);
        p.setDeleted(0);
        // 显式给时间：insert 不会回读 DB 默认值，不设的话创建响应里 createTime 是 null（列表里又有值，前后不一致）
        p.setCreateTime(java.time.LocalDateTime.now());
        p.setIdempotencyKey(Project.IMPLICIT_IDEMPOTENCY_KEY);
        p.setWorkdirPath(p.resolveWorkdirPath());
        try {
            projectMapper.insert(p);
            log.info("[PROJECT] 懒创建 implicit 兜底容器 uid={} id={} workdir={}", uid, p.getId(), p.getWorkdirPath());
            return p;
        } catch (DuplicateKeyException e) {
            // 并发抢插：另一个线程已经建好了，回到"查得到"那条路
            log.debug("[PROJECT] implicit 容器并发创建，改用已存在那一行 uid={}", uid);
        } catch (Exception e) {
            // fail-loud：建不出来就让上层看到，不要假装有工作区（宁可无工作区继续问答）
            log.warn("[FAIL-LOUD] implicit 兜底容器创建失败 uid={}: {}", uid, e.getMessage());
            return null;
        }
        return findImplicit(uid);
    }

    /**
     * 只读：该用户已有的 implicit 兜底容器 id；没有则返回 {@code null}。
     *
     * <p>与 {@link #ensureImplicit(String)} 的区别是<b>不懒创建</b>：会话列表的
     * 「不属于任何真实工作区」过滤会反复调用它，为了一个过滤条件去插一行工作区是副作用。
     * 没有 implicit 行时，用户也不可能有挂在该工作区下的会话（会话落工作区晚于工作区创建），
     * 返回 null 让调用方只按 {@code project_id IS NULL} 过滤即可。
     */
    public String implicitIdOf(String uid) {
        if (uid == null || uid.isBlank()) return null;
        Project p = findImplicit(uid);
        return p == null ? null : p.getId();
    }

    private Project findImplicit(String uid) {
        return projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUid, uid)
                .eq(Project::getSelectionStatus, Project.SELECTION_IMPLICIT)
                .last("LIMIT 1"));
    }

    /**
     * 由会话反推所属工作区。
     * <p>沙盒作用域（{@code SandboxService}）与产物归属（{@code ArtifactService}）共用此入口，
     * 保证「同一工作区的东西落在同一处」这条口径只有一份实现。
     *
     * @return 工作区 id；会话不存在/没挂工作区/查询失败一律 null（调用方按无工作区处理，不抛）
     */
    public String projectIdOfSession(String sessionId) {
        Project p = projectOfSession(sessionId);
        return p == null ? null : p.getId();
    }

    /**
     * 由会话反推归属用户 uid。
     * <p>会话沙盒目录的预览地址（{@code ProjectFileService#openView}）要用：那条路是浏览器顶层导航，
     * 带不上登录头，凭据只有地址自己的签名——与工作区那一份同一做法（token 里只放 id，uid 由库里那一行给）。
     *
     * @return 归属 uid；会话不存在或查询失败一律 null
     */
    public String uidOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session s = sessionMapper.selectById(sessionId);
            return s == null ? null : s.getUserId();
        } catch (Exception e) {
            log.warn("[PROJECT] 会话归属用户解析失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 由会话反推所属工作区行（沙盒要同时拿 id 与 workdir_path，故除 {@link #projectIdOfSession} 外再给一条）。
     *
     * @return 工作区；会话不存在/没挂工作区/工作区已删/查询失败一律 null
     */
    public Project projectOfSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            Session s = sessionMapper.selectById(sessionId);
            String projectId = s == null ? null : s.getProjectId();
            if (projectId == null || projectId.isBlank()) return null;
            return projectMapper.selectById(projectId);
        } catch (Exception e) {
            log.warn("[PROJECT] 会话归属解析失败（按无工作区处理）session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 服务端内部查询（无用户上下文，如调度线程/启动维护）：按 id 取工作区行；不存在返回 {@code null}。
     * <p>与 {@link #ownedOrNull} 的区别是不校验归属——调用方都是系统侧（如定时任务扫描判断
     * 工作区是否已归档），没有登录身份可校验。
     */
    public Project byId(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        return projectMapper.selectById(projectId);
    }

    /**
     * 解析「移动归属」的目标工作区：{@code null}/空/{@code __none__} = 摘掉归属（退回「会话」那一档），
     * 否则必须是本人的工作区（fail-loud）。
     *
     * <p>与 {@link SessionService#resolveTargetProject} 的关键区别：那个拒绝**归档区**（那是"新建"的
     * 规矩，归档=不接新活）；移动是整理动作，允许把东西挪进归档区——归拢历史不该被"收工"挡住。
     * <p>{@code __none__} 常量直接复用 {@link SessionService#NO_PROJECT}（同包同值，避免两处各写一份漂移）。
     */
    public String resolveMoveTarget(String uid, String projectId) {
        if (projectId == null || projectId.isBlank() || SessionService.NO_PROJECT.equals(projectId)) return null;
        Project p = ownedOrNull(uid, projectId);
        if (p == null) {
            throw new IllegalArgumentException("工作区不存在或不属于你：" + projectId);
        }
        return p.getId();
    }

    /**
     * 该用户可见的工作区列表：implicit 兜底容器在前，其余按创建时间倒序。
     * <p>不返回已逻辑删除的行（MyBatis-Plus {@code @TableLogic} 自动过滤）。
     */
    public List<Project> listOf(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        List<Project> all = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUid, uid)
                .orderByAsc(Project::getSelectionStatus)   // implicit < selectable（字典序 i < s，恰好是想要的顺序）
                .orderByDesc(Project::getCreateTime));
        return all == null ? List.of() : all;
    }

    /**
     * 用户显式新建一个工作区（{@code selection_status=selectable}）。
     *
     * @param name          工作区名；空白拒绝（用户自建工作区必须有名字，否则和 implicit 无法区分）
     * @param fromProjectId 可选模板：从「我的」另一个工作区复制<em>设置</em>（约定/绑定资料域/
     *                      共用目录/记忆隔离）——写一套好约定的经验该能复用；会话、文件、事项、
     *                      运行记录一概不带（那是那个场子的历史，不是配置）
     * @return 新建的工作区
     * @throws IllegalArgumentException 名字非法或模板工作区不存在/不属于该用户
     */
    public Project create(String uid, String name, String fromProjectId) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid)) {
            throw new IllegalArgumentException("请先登录后再新建工作区");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("工作区名不能为空");
        }
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("工作区名过长（最多 " + MAX_NAME_LEN + " 字）");
        }
        Project tpl = null;
        if (fromProjectId != null && !fromProjectId.isBlank()) {
            tpl = ownedProject(uid, fromProjectId);
        }
        Project p = new Project();
        // 同 ensureImplicit：id 必须先自己生成，workdir 要用它分片
        p.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        p.setUid(uid);
        p.setName(trimmed);
        p.setSelectionStatus(Project.SELECTION_SELECTABLE);
        p.setDirectoryMode(Project.MODE_MANAGED);
        p.setStatus(Project.STATUS_ACTIVE);
        // 新建工作区默认共用目录：工作区的核心承诺就是「传进来的材料这个场子里的对话都看得见」，
        // 默认关等于每个新场子都要先撞一次「模型说查无此文件」。不想要的场子在设置里一键关回会话级
        p.setSharedWorkdir(1);
        if (tpl != null) {
            // 模板复制只抄「规矩」不抄「历史」：约定/资料域/两个开关；目录模式不抄（linked 位未实现）
            p.setInstructions(tpl.getInstructions());
            p.setKbIds(tpl.getKbIds());
            p.setSharedWorkdir(tpl.getSharedWorkdir() == null ? 1 : tpl.getSharedWorkdir());
            p.setMemoryIsolated(tpl.getMemoryIsolated() == null ? 0 : tpl.getMemoryIsolated());
        }
        p.setDeleted(0);
        p.setCreateTime(java.time.LocalDateTime.now());
        p.setWorkdirPath(p.resolveWorkdirPath());
        projectMapper.insert(p);
        log.info("[PROJECT] 新建工作区 uid={} id={} name={} fromTemplate={}",
                uid, p.getId(), trimmed, tpl != null ? tpl.getId() : "-");
        return p;
    }

    /** 保持旧签名（不带模板） */
    public Project create(String uid, String name) {
        return create(uid, name, null);
    }

    /**
     * 删除一个工作区。
     *
     * <p><b>只摘归属，不删东西</b>——这是本卡最重要的一条语义：
     * <ul>
     *   <li>其下<b>会话</b>：{@code project_id} 置 NULL（对话史不销毁），工作区外仍然能看到；</li>
     *   <li>其下<b>产物</b>：{@code project_id} 置 NULL（"会话可以删，但产出的成果仍归人生"
     *       ——{@code Artifact} 类注释的原话，级联删除会直接违反它）；</li>
     *   <li>工作区行本身：逻辑删除（{@code deleted=1}）。</li>
     * </ul>
     *
     * <p><b>工作目录（{@code deleteFiles=true}）</b>：默认不删——删了行就再也找不到那个目录，
     * 它会作为孤儿永远躺在盘上。所以给用户一个「连文件一起删」的显式选项，且**先删文件、
     * 失败即整单失败**（行保留可重试），不搞"行删了文件没删掉"的不可逆组合。
     *
     * <p><b>implicit 兜底容器不可删</b>：它是每个人的兜底归属，删了会让新会话无处可挂。
     *
     * @param deleteFiles true = 连同工作区目录一起删除（不可恢复）；false = 文件留在盘上
     * @throws IllegalArgumentException 工作区不存在/不属于该用户/implicit 容器
     * @throws IllegalStateException      选择连文件一起删但删除失败（工作区行不动，可重试）
     */
    public void delete(String uid, String projectId, boolean deleteFiles) {
        Project p = ownedProject(uid, projectId);
        if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
            throw new IllegalArgumentException("「会话」栏不是一个工作区，不能删除");
        }
        if (deleteFiles) {
            ProjectFileService files = projectFileProvider.getIfAvailable();
            if (files == null) {
                throw new IllegalStateException("文件服务暂不可用，暂时无法连文件一起删除，请稍后重试");
            }
            // 先删文件：失败即抛，下面两行摘归属与删行都不会执行（行留着，目录就还找得到）
            files.deleteWorkdir(uid, projectId);
        }
        // 先摘归属（两条 update 都是集合操作，幂等；即便随后工作区行删除失败也不会留下悬空引用）
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Session>()
                .eq(Session::getProjectId, p.getId())
                .set(Session::getProjectId, null));
        artifactMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Artifact>()
                .eq(Artifact::getProjectId, p.getId())
                .set(Artifact::getProjectId, null));
        // 逻辑删除工作区行（@TableLogic 自动写 deleted=1，select 自动过滤）
        projectMapper.deleteById(p.getId());
        log.info("[PROJECT] 删除工作区（会话/产物已摘归属，未删除{}）uid={} id={} name={}",
                deleteFiles ? "，工作目录已删" : "", uid, p.getId(), p.getName());
    }

    /** 保持原签名（不删文件）；新调用方显式传 {@code deleteFiles} */
    public void delete(String uid, String projectId) {
        delete(uid, projectId, false);
    }

    /**
     * 取"我的"工作区行；不存在/他人/已删返回 {@code null}（调用方自己决定是拒绝还是回落）。
     * <p>与 {@link #ownedProject} 的区别只在错误姿态：这里是查询式调用，抛异常会让
     * 「新建对话时带了个过期工作区 id」变成整轮失败。
     */
    public Project ownedOrNull(String uid, String projectId) {
        if (uid == null || uid.isBlank() || projectId == null || projectId.isBlank()) return null;
        Project p = projectMapper.selectById(projectId);
        return (p != null && uid.equals(p.getUid())) ? p : null;
    }

    /** 取"我的"工作区行；不存在/他人/已删一律抛可读错误（fail-loud，不静默当无事） */
    private Project ownedProject(String uid, String projectId) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("请先登录");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("缺少工作区标识");
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new IllegalArgumentException("工作区不存在或已删除");
        }
        if (!uid.equals(p.getUid())) {
            throw new IllegalArgumentException("无权操作该工作区");
        }
        return p;
    }

    /** 取工作区详情（校验归属；不存在/他人/已删一律抛可读错误） */
    public Project detail(String uid, String projectId) {
        return ownedProject(uid, projectId);
    }

    /**
     * 更新工作区设置：名称 / 工作区约定 / 默认助手 / 绑定知识库 / 共用工作目录 / 记忆隔离。
     *
     * <p>各参数都是「传才改」（null = 不动）；空串/空列表表示清空。默认助手与绑定的库
     * 都必须本人可用，否则抛 IllegalArgumentException——静默忽略会让用户以为绑定生效了。
     * <p>约定被替换（值真的变了）时把<b>旧版</b>抄进留痕表：聊天页只报「约定生效」不报内容，
     * 「当时生效的是哪版」必须可查（恢复=把旧值再存一版，不另做回滚通道）。
     */
    public Project update(String uid, String projectId, String name, String instructions,
                          String defaultAgentId, List<String> kbIds, Boolean sharedWorkdir,
                          Boolean memoryIsolated) {
        Project p = ownedProject(uid, projectId);
        boolean changed = false;
        if (name != null) {
            String n = name.trim();
            if (!Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus()) && n.isEmpty()) {
                throw new IllegalArgumentException("工作区名不能为空");
            }
            if (n.length() > MAX_NAME_LEN) {
                throw new IllegalArgumentException("工作区名过长（最多 " + MAX_NAME_LEN + " 字）");
            }
            p.setName(n.isEmpty() ? null : n);
            changed = true;
        }
        if (instructions != null) {
            String ins = instructions.trim();
            if (ins.length() > MAX_INSTRUCTIONS_LEN) {
                throw new IllegalArgumentException("工作区约定过长（最多 " + MAX_INSTRUCTIONS_LEN + " 字）");
            }
            String old = p.getInstructions();
            String next = ins.isEmpty() ? null : ins;
            if (!java.util.Objects.equals(old, next)) {
                // 只在值真的变了时留痕：空保存（同值重存）不制造一排无意义的历史行
                ProjectNoteLog logRow = new ProjectNoteLog();
                logRow.setProjectId(p.getId());
                logRow.setContent(old);
                logRow.setCreateTime(java.time.LocalDateTime.now());
                noteLogMapper.insert(logRow);
            }
            p.setInstructions(next);
            changed = true;
        }
        if (defaultAgentId != null) {
            String id = defaultAgentId.trim();
            if (id.isEmpty()) {
                p.setDefaultAgentId(null);
            } else {
                // fail-loud：绑一个读不到的助手，用户只会在下一次对话里遇到"怎么还是别人答"
                AgentService agentService = agentProvider.getIfAvailable();
                if (agentService == null || agentService.get(id) == null) {
                    throw new IllegalArgumentException("默认助手不可用（不存在或你无访问权限）");
                }
                p.setDefaultAgentId(id);
            }
            changed = true;
        }
        if (kbIds != null) {
            KnowledgeBaseService kbService = kbProvider.getIfAvailable();
            if (kbService == null) {
                throw new IllegalStateException("知识库组件暂不可用，暂时无法保存绑定，请稍后重试");
            }
            java.util.LinkedHashSet<String> clean = new java.util.LinkedHashSet<>();
            for (String raw : kbIds) {
                String id = raw == null ? "" : raw.trim();
                if (id.isEmpty()) continue;
                // fail-loud：绑一个不存在/别人的库，用户只会在下一次对话里发现"检索还是不搜它"
                com.wenqu.ai.model.KnowledgeBase kb = kbService.get(id);
                boolean usable = kb != null
                        && (uid.equals(kb.getCreatedBy()) || Integer.valueOf(1).equals(kb.getBuiltin()));
                if (!usable) {
                    throw new IllegalArgumentException("绑定的知识库不可用（不存在或你没有访问权限）");
                }
                clean.add(id);
            }
            p.setKbIds(clean.isEmpty() ? null : String.join(",", clean));
            changed = true;
        }
        if (sharedWorkdir != null) {
            if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
                throw new IllegalArgumentException("「会话」栏不是一个工作区，没有共用目录可开");
            }
            p.setSharedWorkdir(Boolean.TRUE.equals(sharedWorkdir) ? 1 : 0);
            changed = true;
        }
        if (memoryIsolated != null) {
            if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
                throw new IllegalArgumentException("「会话」栏不是一个工作区，没有记忆隔离可开");
            }
            p.setMemoryIsolated(Boolean.TRUE.equals(memoryIsolated) ? 1 : 0);
            changed = true;
        }
        if (changed) {
            p.setUpdateTime(java.time.LocalDateTime.now());
            // 走显式列集而不是 updateById：后者的字段策略会跳过 null，「清空约定 / 取消默认助手」
            // 会返回成功、提示也对，但库里一个字没动，下一次读就把旧值顶回来。
            projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Project>()
                    .eq(Project::getId, p.getId())
                    .set(Project::getName, p.getName())
                    .set(Project::getInstructions, p.getInstructions())
                    .set(Project::getDefaultAgentId, p.getDefaultAgentId())
                    .set(Project::getKbIds, p.getKbIds())
                    .set(Project::getSharedWorkdir, p.getSharedWorkdir())
                    .set(Project::getMemoryIsolated, p.getMemoryIsolated())
                    .set(Project::getUpdateTime, p.getUpdateTime()));
            log.info("[PROJECT] 更新工作区设置 uid={} id={} name={} hasInstructions={} defaultAgent={} kbIds={} sharedWorkdir={} memoryIsolated={}",
                    uid, p.getId(), p.getName(), p.getInstructions() != null, p.getDefaultAgentId(),
                    p.getKbIds(), p.getSharedWorkdir(), p.getMemoryIsolated());
        }
        return p;
    }

    /**
     * 归档 / 恢复工作区。
     * <p>归档不是删除：会话与产物一条不动，只把这个容器从"正在推进"降级为"已收工"。
     * implicit 兜底容器不允许归档（它是侧栏「会话」栏那批无工作区会话的归属根）。
     * <p><b>归档即停活</b>：归属该工作区的已启用定时任务会被自动停用（打快照标记），
     * 恢复时只还原被归档停掉的那一批——用户自己停用的任务不会被擅自放跑。
     * 没有这条，「归档」只是折起侧栏，任务周一早上照跑照烧额度，与"收工"的字面意思相反。
     */
    public Project setStatus(String uid, String projectId, String status) {
        Project p = ownedProject(uid, projectId);
        if (!Project.STATUS_ACTIVE.equals(status) && !Project.STATUS_ARCHIVED.equals(status)) {
            throw new IllegalArgumentException("状态只能是 active 或 archived");
        }
        if (Project.STATUS_ARCHIVED.equals(status) && Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
            throw new IllegalArgumentException("「会话」栏不是一个工作区，不能归档");
        }
        if (Project.STATUS_ARCHIVED.equals(status)) {
            ScheduledJobService jobs = scheduledJobProvider.getIfAvailable();
            if (jobs == null) {
                // fail-loud：停不掉任务却把工作区归档了，等于悄悄违背"收工"语义
                throw new IllegalStateException("定时任务组件暂不可用，暂时无法归档工作区，请稍后重试");
            }
            jobs.pauseByArchive(p.getId());
        } else if (Project.STATUS_ARCHIVED.equals(p.getStatus())) {
            // 仅「已归档 → 恢复」时还原；本来就是 active 的重复置位不动作
            ScheduledJobService jobs = scheduledJobProvider.getIfAvailable();
            if (jobs != null) jobs.resumeByArchive(p.getId());
        }
        p.setStatus(status);
        p.setUpdateTime(java.time.LocalDateTime.now());
        projectMapper.updateById(p);
        log.info("[PROJECT] 工作区 {} 状态改为 {}", p.getId(), status);
        return p;
    }

    /** 工作区约定的长度上限（进 system prompt，太长会挤掉真实上下文） */
    public static final int MAX_INSTRUCTIONS_LEN = 2000;

    /**
     * 该会话所属工作区的默认助手 id；无工作区/未配置返回 null（调用方回落到平台默认智能体）。
     */
    public String defaultAgentIdOfSession(String sessionId) {
        Project p = projectOfSession(sessionId);
        return (p == null || p.getDefaultAgentId() == null || p.getDefaultAgentId().isBlank())
                ? null : p.getDefaultAgentId();
    }

    /**
     * 该会话所属工作区的<b>默认检索域</b>：工作区绑定的知识库 id 清单（保序）。
     *
     * <p>只在本轮没有任何显式域时被消费（助手未绑库且未 @ 库，见 {@code WenquAgentxTools#newTurn}）
     * ——它是「没说就搜这里」的默认值，不是权限墙；@ 提他与助手绑定都会盖过它。
     *
     * @return 绑定库 id；无工作区/未绑定返回空清单（调用方按「没有默认域」继续，行为不变）
     */
    public List<String> kbIdsOfSession(String sessionId) {
        Project p = projectOfSession(sessionId);
        if (p == null || p.getKbIds() == null || p.getKbIds().isBlank()) return List.of();
        // 保序切分（不用 KnowledgeBaseService.splitIds：它返回 HashSet，绑定顺序=检索优先级要保住）
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : p.getKbIds().split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 该工作区是否与沙盒<b>实际共用</b>工作目录 = 本区开关（{@code sharedWorkdir}）
     * ＋ 全局总闸（{@code sandbox.projectWorkdirEnabled}，应急时一键全退回会话级）。
     *
     * <p>全仓唯一的生效判据：沙盒作用域（{@code SandboxService}）、工作区页的「模型看得见吗」提示
     * （{@code ProjectFileService}）与工作区轮次互斥（{@code ChatService}）都读这里，
     * 三处各写一份迟早漂移成「提示说看得见、沙盒却挂的会话目录」。
     */
    public boolean workdirShared(Project p) {
        if (p == null || p.getSharedWorkdir() == null || p.getSharedWorkdir() != 1) return false;
        return configService.getBoolean("sandbox.projectWorkdirEnabled");
    }

    /**
     * 该会话所属工作区是否开了「记忆隔离」（干净房间：不注入本人个人记忆、也不写入）。
     * <p>消费方只有引擎的记忆闸（{@code AgentscopeChatEngine} 装配期）——按游客档全关
     * （hooks 与 memory_* 工具同关）：只关 hooks 仍会注入 MEMORY.md，那就不是隔离。
     * 会话不挂工作区一律 false（「会话」栏照常共用个人记忆）。
     */
    public boolean memoryIsolatedOfSession(String sessionId) {
        Project p = projectOfSession(sessionId);
        return p != null && p.getMemoryIsolated() != null && p.getMemoryIsolated() == 1;
    }

    /**
     * 约定留痕（被替换的旧版），按被替换时刻倒序，最多 20 条。
     * content 为 null 表示当时约定为空（首次写入前的状态）。
     */
    public List<ProjectNoteLog> instructionHistory(String uid, String projectId) {
        detail(uid, projectId);   // 归属校验（不存在/他人/已删一律抛）
        return noteLogMapper.selectList(new LambdaQueryWrapper<ProjectNoteLog>()
                .eq(ProjectNoteLog::getProjectId, projectId)
                .orderByDesc(ProjectNoteLog::getCreateTime)
                .last("LIMIT 20"));
    }

    /**
     * 该会话所属工作区的**背景段**（注入 system prompt）。
     *
     * <p>只在工作区有约定时返回内容：工作区名单独注入没有价值，反而每轮多花 token。
     * 约定是用户在工作区页写的「这个工作区的规矩」，它对工作区内所有会话生效——这正是工作区与分类的区别。
     *
     * @return 可直接拼接的文本段；无工作区/无约定返回 null
     */
    public String projectContextOfSession(String sessionId) {
        Project p = projectOfSession(sessionId);
        if (p == null) return null;
        String ins = p.getInstructions();
        if (ins == null || ins.isBlank()) return null;
        // implicit 兜底容器没有名字：这里不给它编一个（界面上不存在的名词会被模型复述出来）
        String name = (p.getName() == null || p.getName().isBlank()) ? "" : p.getName();
        // 只给事实与约束，不加"你是…"这类人设话术（人设归智能体，别在这里抢）
        return "\n\n【当前工作区】" + name
                + "\n以下是本工作区的约定，对本次对话全程有效，回答时应当遵守：\n" + ins;
    }

    private static final int MAX_NAME_LEN = 60;
}
