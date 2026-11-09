package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.ArtifactMapper;
import com.wenqu.ai.mapper.ProjectMapper;
import com.wenqu.ai.mapper.ProjectRootMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

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
    /** 工作区源文件夹（本机目录）：工作区 = 一台本机上的一个或多个真实目录。 */
    private final ProjectRootMapper projectRootMapper;
    /** 工作区事项清单：删工作区时随工作区清理（没有独立入口，留孤儿行只会占库） */
    private final com.wenqu.ai.mapper.ProjectItemMapper projectItemMapper;
    /**
     * 归档联动停用/恢复定时任务。
     * <p>必须惰性取：{@code ScheduledJobService} 的构造器依赖本类（校验任务的工作区归属），
     * 直接注入会构成构造器环（Spring Boot 3 默认禁止，启动直接失败）。归档是低频动作，用到时再取即可。
     */
    private final org.springframework.beans.factory.ObjectProvider<ScheduledJobService> scheduledJobProvider;

    public ProjectService(ProjectMapper projectMapper, SessionMapper sessionMapper,
                          ArtifactMapper artifactMapper,
                          ProjectRootMapper projectRootMapper,
                          com.wenqu.ai.mapper.ProjectItemMapper projectItemMapper,
                          org.springframework.beans.factory.ObjectProvider<ScheduledJobService> scheduledJobProvider) {
        this.projectMapper = projectMapper;
        this.sessionMapper = sessionMapper;
        this.artifactMapper = artifactMapper;
        this.projectRootMapper = projectRootMapper;
        this.projectItemMapper = projectItemMapper;
        this.scheduledJobProvider = scheduledJobProvider;
    }

    /**
     * 该用户的 implicit 兜底容器；不存在则创建。
     *
     * @param uid 用户 uid；空/SYSTEM 返回 null（不建工作区，调用方按「无工作区」继续）
     * @return 兜底容器；uid 不可用时返回 null
     */
    public Project ensureImplicit(String uid) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(uid)) {
            // SYSTEM/游客不建工作区：他们没有一个可持续的身份，建了也无从归属（分享页访客会话同理）
            return null;
        }
        Project existing = findImplicit(uid);
        if (existing != null) return existing;

        Project p = new Project();
        // 必须先自己生成 id 再拼 workdir：@TableId(ASSIGN_UUID) 是**insert 时**才回填 id 的，
        // 依赖"insert 后就有 id"会把路径写成 projects/null，于是**所有用户的兜底容器落到同一个目录**。
        // 自己先赋值后，MyBatis-Plus 见 id 非空就不再生成。
        p.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        p.setUid(uid);
        p.setName(null); // implicit 不给名字：它不是用户建的工作区，界面上只体现为侧栏「会话」栏
        p.setSelectionStatus(Project.SELECTION_IMPLICIT);
        p.setStatus(Project.STATUS_ACTIVE);
        p.setDeleted(0);
        // 显式给时间：insert 不会回读 DB 默认值，不设的话创建响应里 createTime 是 null（列表里又有值，前后不一致）
        p.setCreateTime(java.time.LocalDateTime.now());
        p.setIdempotencyKey(Project.IMPLICIT_IDEMPOTENCY_KEY);
        try {
            projectMapper.insert(p);
            log.info("[PROJECT] 懒创建 implicit 兜底容器 uid={} id={}", uid, p.getId());
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
     * 由会话反推所属工作区行（本机执行器的源文件夹、权限与钩子都要这一行，故除 {@link #projectIdOfSession} 外再给一条）。
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
     * 用户显式新建一个本机工作区。
     *
     * <p><b>工作区 = 用户电脑上的一个或多个真实目录</b>：{@code roots} 至少一项，第一项为主目录
     * （框架 cwd、相对路径与命令执行的基准），其余为附加根；它们必须都在同一台执行器上。
     * 服务器只记「哪台机器、哪些绝对路径、显示成什么名」，不保存用户文件的副本。
     *
     * @param executorId    本机执行器 id（c_ai_executor.id）；必须属于该用户
     * @param roots         源文件夹（绝对路径 + 可选别名）；第一项为主目录
     */
    @Transactional
    public Project create(String uid, String name, String executorId, List<RootSpec> roots) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(uid)) {
            throw new IllegalArgumentException("请先登录后再新建工作区");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("工作区名不能为空");
        }
        if (trimmed.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("工作区名过长（最多 " + MAX_NAME_LEN + " 字）");
        }
        if (executorId == null || executorId.isBlank()) {
            throw new IllegalArgumentException("工作区必须绑定一台本机执行器：先在你这台电脑上启动并授权 wenqu-agent");
        }
        List<RootSpec> normalized = normalizeRoots(roots);
        Project p = new Project();
        p.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        p.setUid(uid);
        p.setName(trimmed);
        p.setSelectionStatus(Project.SELECTION_SELECTABLE);
        p.setExecutorId(executorId.trim());
        p.setStatus(Project.STATUS_ACTIVE);
        p.setDeleted(0);
        p.setCreateTime(java.time.LocalDateTime.now());
        projectMapper.insert(p);
        insertRoots(uid, p.getId(), normalized);
        log.info("[PROJECT] 新建本机工作区 uid={} id={} name={} executor={} roots={}",
                uid, p.getId(), trimmed, executorId, normalized.size());
        return p;
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
     * <p><b>本机文件不删</b>：目录在用户电脑上，问渠只摘服务器上的归属；删除工作区绝不等于删硬盘。
     *
     * <p><b>implicit 兜底容器不可删</b>：它是每个人的兜底归属，删了会让新会话无处可挂。
     *
     * @throws IllegalArgumentException 工作区不存在/不属于该用户/implicit 容器
     */
    public void delete(String uid, String projectId) {
        Project p = ownedProject(uid, projectId);
        if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
            throw new IllegalArgumentException("「会话」栏不是一个工作区，不能删除");
        }
        // 先摘归属（两条 update 都是集合操作，幂等；即便随后工作区行删除失败也不会留下悬空引用）。
        // 会话级权限档位一并清掉：它只对工作区有意义，跟着会话留在「会话」栏里就成了没有入口的隐性约束
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Session>()
                .eq(Session::getProjectId, p.getId())
                .set(Session::getProjectId, null)
                .set(Session::getPermissionPreset, null));
        artifactMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Artifact>()
                .eq(Artifact::getProjectId, p.getId())
                .set(Artifact::getProjectId, null));
        // 目录绑定与事项清单随工作区一起清理（逻辑删工作区行；绝对路径本身不动）
        projectRootMapper.delete(new LambdaQueryWrapper<ProjectRoot>()
                .eq(ProjectRoot::getProjectId, p.getId()));
        projectItemMapper.delete(new LambdaQueryWrapper<com.wenqu.ai.model.ProjectItem>()
                .eq(com.wenqu.ai.model.ProjectItem::getProjectId, p.getId())
                .eq(com.wenqu.ai.model.ProjectItem::getUid, uid));
        // 逻辑删除工作区行（@TableLogic 自动写 deleted=1，select 自动过滤）
        projectMapper.deleteById(p.getId());
        log.info("[PROJECT] 删除工作区（会话/产物已摘归属，本机文件未动）uid={} id={} name={}",
                uid, p.getId(), p.getName());
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
     * 把工作区绑到一台本机执行器上的一个或多个目录（替换式：传进来的这一批就是全部）。
     *
     * <p>工作区由 {@code roots} 描述：至少一项，第一项为主目录。
     * 替换只动「服务器记的路径映射」，用户硬盘上的文件一个都不动。
     */
    @Transactional
    public Project bindRoots(String uid, String projectId, String executorId, List<RootSpec> roots) {
        Project p = ownedProject(uid, projectId);
        if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
            throw new IllegalArgumentException("「会话」栏不是一个工作区，不能绑定目录");
        }
        if (executorId == null || executorId.isBlank()) {
            throw new IllegalArgumentException("工作区必须绑定一台本机执行器：先在你这台电脑上启动并授权 wenqu-agent");
        }
        List<RootSpec> normalized = normalizeRoots(roots);
        projectRootMapper.delete(new LambdaQueryWrapper<ProjectRoot>()
                .eq(ProjectRoot::getProjectId, p.getId()));
        insertRoots(uid, p.getId(), normalized);
        p.setExecutorId(executorId.trim());
        projectMapper.updateById(p);
        log.info("[PROJECT] 工作区目录已绑定 uid={} project={} executor={} roots={}",
                uid, p.getId(), executorId, normalized.size());
        return p;
    }


    /**
     * 工作区的源文件夹（按展示顺序，第一项即主目录）。没有绑定目录返回空表——
     * 界面据此显示「未绑定目录」，不做任何猜路径的兜底。
     */
    public List<ProjectRoot> rootsOf(Project p) {
        if (p == null || p.getId() == null) return List.of();
        List<ProjectRoot> rows = projectRootMapper.selectList(new LambdaQueryWrapper<ProjectRoot>()
                .eq(ProjectRoot::getProjectId, p.getId())
                .orderByAsc(ProjectRoot::getSortNo));
        return rows == null ? List.of() : rows;
    }

    public List<ProjectRoot> rootsOf(String projectId) {
        return rootsOf(byId(projectId));
    }

    /** 主目录（相对路径与命令执行的根）。没有目录绑定时返回 null。 */
    public ProjectRoot primaryRootOf(Project p) {
        List<ProjectRoot> roots = rootsOf(p);
        for (ProjectRoot r : roots) {
            if (r.getPrimaryRoot() != null && r.getPrimaryRoot() == ProjectRoot.PRIMARY) return r;
        }
        return roots.isEmpty() ? null : roots.get(0);
    }

    /** 这个工作区是不是「本机目录」形态：绑了执行器且有主目录。 */
    public boolean isLocal(Project p) {
        return p != null && p.getExecutorId() != null && !p.getExecutorId().isBlank();
    }

    /** 新建/重绑时的入参：绝对路径 + 可选别名（空=取目录名）。 */
    public record RootSpec(String path, String alias) {
    }

    /**
     * 规范化目录清单：至少一项、最多 8 项；路径必须绝对；别名缺失取目录名并自动去重；
     * 同一路径不能绑两次（用户的同一个文件夹在一个工作区里出现两遍只会让移动/删除指代不清）。
     */
    private static List<RootSpec> normalizeRoots(List<RootSpec> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("请先选择至少一个本机文件夹");
        }
        if (roots.size() > 8) {
            throw new IllegalArgumentException("一个工作区最多绑定 8 个文件夹");
        }
        List<RootSpec> out = new ArrayList<>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        LinkedHashSet<String> seenAliases = new LinkedHashSet<>();
        for (RootSpec raw : roots) {
            String path = raw == null || raw.path() == null ? "" : raw.path().trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("文件夹路径不能为空");
            }
            if (!isAbsolutePath(path)) {
                throw new IllegalArgumentException("文件夹必须是那台电脑上的绝对路径：" + path);
            }
            if (!seenPaths.add(path)) {
                throw new IllegalArgumentException("同一个文件夹不能重复添加：" + path);
            }
            String alias = raw.alias() == null ? "" : raw.alias().trim();
            if (alias.isEmpty()) alias = baseName(path);
            if (alias.length() > 60) alias = alias.substring(0, 60);
            if (alias.isEmpty() || ".".equals(alias) || "..".equals(alias)
                    || alias.indexOf('/') >= 0 || alias.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("文件夹名称不合法：" + alias);
            }
            String unique = alias;
            int n = 2;
            while (!seenAliases.add(unique.toLowerCase(Locale.ROOT))) {
                unique = alias + "-" + n++;
            }
            out.add(new RootSpec(path.replaceAll("[/\\\\]+$", ""), unique));
        }
        return out;
    }

    private static boolean isAbsolutePath(String path) {
        if (path.startsWith("/")) return true;
        return path.length() >= 3 && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':' && (path.charAt(2) == '/' || path.charAt(2) == '\\');
    }

    private static String baseName(String path) {
        String p = path == null ? "" : path.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        return name.isBlank() ? "文件夹" : name;
    }

    private void insertRoots(String uid, String projectId, List<RootSpec> roots) {
        for (int i = 0; i < roots.size(); i++) {
            RootSpec r = roots.get(i);
            ProjectRoot row = new ProjectRoot();
            row.setUid(uid);
            row.setProjectId(projectId);
            row.setAlias(r.alias());
            row.setPath(r.path());
            row.setPrimaryRoot(i == 0 ? ProjectRoot.PRIMARY : ProjectRoot.ADDITIONAL);
            row.setSortNo(i);
            row.setCreateTime(java.time.LocalDateTime.now());
            projectRootMapper.insert(row);
        }
    }

    /**
     * 更新工作区设置：名称 / 记忆隔离 / 外观。各参数都是「传才改」（null = 不动）。
     */
    public Project update(String uid, String projectId, String name, Boolean memoryIsolated,
                          String icon, String color) {
        Project p = ownedProject(uid, projectId);
        boolean changed = false;
        if (icon != null) {
            p.setIcon(icon.isBlank() ? null : icon.trim());
            changed = true;
        }
        if (color != null) {
            p.setColor(color.isBlank() ? null : color.trim());
            changed = true;
        }
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
        if (memoryIsolated != null) {
            if (Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())) {
                throw new IllegalArgumentException("「会话」栏不是一个工作区，没有记忆隔离可开");
            }
            p.setMemoryIsolated(Boolean.TRUE.equals(memoryIsolated) ? 1 : 0);
            changed = true;
        }
        if (changed) {
            p.setUpdateTime(java.time.LocalDateTime.now());
            projectMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Project>()
                    .eq(Project::getId, p.getId())
                    .set(Project::getName, p.getName())
                    .set(Project::getMemoryIsolated, p.getMemoryIsolated())
                    // 外观两列也必须进列集：漏了它们，接口照样返回「已保存」和带 icon/color 的 DTO，
                    // 库里却一个字没动，刷新/重进后图标颜色又回到默认——比直接报错更难查
                    .set(Project::getIcon, p.getIcon())
                    .set(Project::getColor, p.getColor())
                    .set(Project::getUpdateTime, p.getUpdateTime()));
            log.info("[PROJECT] 更新工作区设置 uid={} id={} name={} memoryIsolated={} icon={} color={}",
                    uid, p.getId(), p.getName(), p.getMemoryIsolated(), p.getIcon(), p.getColor());
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

    private static final int MAX_NAME_LEN = 60;
}
