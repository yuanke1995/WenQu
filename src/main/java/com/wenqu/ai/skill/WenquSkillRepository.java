package com.wenqu.ai.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.repository.RuntimeContextSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 问渠的技能仓库（全框架化 P3 + 用户自装技能）——四路合并交给框架。
 *
 * <h3>四层来源（优先级递增）</h3>
 * <ol>
 *   <li><b>内置技能</b>：{@code user-data/skills-builtin/{技能名}/SKILL.md}
 *       （随 jar 里的 {@code classpath:skills/} 分发，启动时由 {@link BuiltinSkillSeeder} 落盘）；</li>
 *   <li><b>历史投影</b>：{@code skill-projections/{uid}/{技能名}/SKILL.md}（冻结的存量资产，只读）；</li>
 *   <li><b>沙盒工作区</b>：模型在对话里用 {@code skill_manage} 沉淀的技能，随会话/工作区走；</li>
 *   <li><b>用户自装</b>：{@code user-data/skills-users/{uid}/{技能名}/SKILL.md}（{@link UserSkillStore}，
 *       跨会话、不依赖沙箱，用户明确安装的版本优先级最高）。</li>
 * </ol>
 * 框架 {@code FileSystemSkillRepository} 要求的布局正是 {@code baseDir/{skill-name}/SKILL.md}，
 * 故本类不复制、不迁移任何技能文件，只做<b>选择与合并</b>。
 *
 * <p><b>{@code skill-projections/{uid}/} 是冻结的历史用户级技能库</b>（全框架化 P3 之后没有写入方）：
 * 写走用户自装目录或框架 {@code skill_manage}（沙箱工作区）；读侧继续解析它是为了不丢存量资产。
 *
 * <h3>为什么实现 RuntimeContextSkillRepository</h3>
 * 每轮的 {@code uid} 不同，能看到的技能也不同。框架为此提供了
 * {@code getAllSkills(RuntimeContext)}（源码注释：*"A skill repository whose visible contents
 * depend on the current request context"*）——用它按当轮用户解析可见技能，
 * 而不是把某个用户的技能目录写死在 builder 上。
 *
 * <h3>与框架 skill_manage 工具的关系（重要）</h3>
 * 框架的 {@code SkillManageTool} 走的是它自己的 {@code WorkspaceSkillRepository}
 * （{@code HarnessAgent:2968-2999}：从 {@code skillRepositories} 里找不可写的
 * {@code WorkspaceSkillRepository} 并替换成指向 {@code mainDir()} 的可写实例）。
 * <b>写不走本类</b>：写落在<b>沙箱工作区</b>（{@code /home/gem/user-data/skills/}，经 provisioner bind 到
 * {@code shared/{uid}/workspace} 或 {@code sessions/{thread_id}}，天然按用户/工作区隔离），
 * 因此 {@link #save}/{@link #delete} 显式抛 {@link UnsupportedOperationException}：
 * 硬接只会造出「标记可写但写不进去」的假象。
 * <p>那一路技能本类<b>也读得到</b>（{@link #catalog} 的沙盒工作区分支），但只在调用方点名会话、
 * 且容器已在场时读——清单要能审到模型自己沉淀的技能，又不能为看一眼清单就把容器拉起来。
 * 问答注入路径（{@link #getAllSkills}）不带这一路：框架装配 agent 时自己会挂上那份可写仓库，
 * 本类再读一遍等于每轮多打一趟沙盒。
 *
 * <h3>合并口径</h3>
 * 优先级递增（应用侧）：内置 → 历史投影 → 沙盒工作区 → 用户自装。同名以<b>最后那一路</b>为准：
 * 用户明确安装的版本压过会话里临时沉淀的同名技能。
 * <p><b>注意</b>：框架装配时还会把 {@code skill_manage} 的可写工作区仓库追加到列表末尾，
 * 所以<b>模型侧</b>同一会话内同名时仍以工作区那份为准；管理页与 {@code /available} 展示的是
 * 用户自装那份。两者口径差异见 {@code docs/skills.md}「已知限制」。
 */
@Service
public class WenquSkillRepository implements RuntimeContextSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(WenquSkillRepository.class);

    /** 内置技能根：由 {@link BuiltinSkillSeeder} 从 classpath 落盘到 {@code user-data/skills-builtin}。 */
    private final Path builtinRoot;
    /** 历史用户技能投影根：{@code skill-projections/{uid}/}（冻结存量，只读）。 */
    private final Path legacyProjectsRoot;
    /** 用户自装技能仓库（uid 级宿主目录，可读写）。 */
    private final UserSkillStore userSkillStore;
    /** 平台级停用清单（{@code skill.disabled}，管理员维护）。 */
    private final com.wenqu.ai.service.ConfigService configService;
    /** 沙盒工作区那一路的读通道。 */
    private final com.wenqu.ai.service.SandboxService sandboxService;

    public WenquSkillRepository(
            @Value("${wenqu.skills.builtin-dir:}") String builtinDir,
            @Value("${wenqu.skills.projects-dir:skill-projections}") String projectsDir,
            com.wenqu.ai.service.ConfigService configService,
            com.wenqu.ai.service.SandboxService sandboxService,
            UserSkillStore userSkillStore) {
        this.builtinRoot = resolveBuiltinRoot(builtinDir);
        this.legacyProjectsRoot = (projectsDir == null || projectsDir.isBlank())
                ? Paths.get("skill-projections")
                : Paths.get(projectsDir);
        this.configService = configService;
        this.sandboxService = sandboxService;
        this.userSkillStore = userSkillStore;
    }

    /**
     * 内置技能根：显式配置优先，否则取 {@code user-data/skills-builtin}——那份由
     * {@link BuiltinSkillSeeder} 从 classpath 落盘。
     * <p>这里曾经直接拼 {@code user.dir/src/main/resources/skills}：源码树路径在容器里不存在
     * （运行阶段只有 {@code app.jar}），内置技能会静默变成空列表。
     */
    public static Path resolveBuiltinRoot(String configuredDir) {
        return (configuredDir == null || configuredDir.isBlank())
                ? Paths.get(System.getProperty("user.dir"), "user-data", "skills-builtin")
                : Paths.get(configuredDir);
    }

    /** 当前轮的 uid；仓库是无状态的，逐轮解析。 */
    private static String uidOf(RuntimeContext ctx) {
        if (ctx == null) return null;
        String uid = ctx.getUserId();
        return uid == null || uid.isBlank() ? null : uid;
    }

    /** 本轮用户可见的历史投影目录（只读；不存在就是空）。 */
    private Path legacyDirOf(RuntimeContext ctx) {
        String uid = uidOf(ctx);
        if (uid == null || legacyProjectsRoot == null) return null;
        Path dir = legacyProjectsRoot.resolve(uid).normalize();
        if (!dir.startsWith(legacyProjectsRoot.normalize())) return null;
        return Files.isDirectory(dir) ? dir : null;
    }

    // ==================== RuntimeContextSkillRepository ====================

    /**
     * 清单条目：技能 + 来源 + 是否停用（以及停用来自哪一层）。
     * <p>停用有两层：平台级 {@code skill.disabled}（管理员）与个人级 {@code .disabled}
     * （{@link UserSkillStore}，成员自己停）。界面要能分别说明「谁停的」，所以拆成两个标记。
     */
    public record SkillEntry(AgentSkill skill, String source, boolean disabled,
                             boolean platformDisabled, boolean userDisabled) {
    }

    public static final String SOURCE_BUILTIN = "builtin";
    /** 用户自装（uid 级宿主目录）。 */
    public static final String SOURCE_USER = "user";
    /** 历史投影（{@code skill-projections/{uid}}，冻结存量）。 */
    public static final String SOURCE_LEGACY = "legacy";
    public static final String SOURCE_WORKSPACE = "workspace";

    /** 停用清单的配置键（只有平台层）。 */
    public static final String DISABLED_KEY = "skill.disabled";

    /** 沙盒工作区技能根（相对沙盒用户数据根）——与框架 {@code skill_manage} 的落点同取一个常量。 */
    private static final String WORKSPACE_SKILLS_DIR =
            io.agentscope.harness.agent.tool.SkillManageConfig.DEFAULT_MAIN_DIR;

    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        List<AgentSkill> out = new ArrayList<>();
        for (SkillEntry e : catalog(context, null)) {
            if (!e.disabled()) out.add(e.skill());
        }
        return out;
    }

    /**
     * 本轮可见技能的全景清单：有哪些、各自管什么、哪条已被停用、每条从哪来。
     *
     * <p>与 {@link #getAllSkills} 的差别只有两点：带来源与停用标记（停用的也返回，否则界面没法
     * 展示它被停这件事），以及可点名会话。
     *
     * @param sessionId 点名会话时额外并上「沙盒工作区」那一路（助手在对话里沉淀下来的技能）。
     *                  传空、或该会话沙盒不在场时只给内置 + 历史投影 + 用户自装——<b>看一眼清单不许把容器拉起来</b>
     *                  （与沙盒文件浏览端点同一条判据）。
     */
    public List<SkillEntry> catalog(RuntimeContext ctx, String sessionId) {
        Map<String, SkillEntry> byName = new LinkedHashMap<>();
        Set<String> platformDisabled = nameSet(configService.get(DISABLED_KEY));
        String uid = uidOf(ctx);
        Set<String> userDisabled = (uid == null || userSkillStore == null)
                ? Set.of() : userSkillStore.disabledNames(uid);

        for (AgentSkill s : readAll(builtinRoot)) {
            byName.put(s.getName(), entry(s, SOURCE_BUILTIN, platformDisabled, userDisabled));
        }
        Path legacyDir = legacyDirOf(ctx);
        if (legacyDir != null) {
            for (AgentSkill s : readAll(legacyDir)) {
                byName.put(s.getName(), entry(s, SOURCE_LEGACY, platformDisabled, userDisabled));
            }
        }
        // 沙盒工作区：随会话走，不参与个人停用（换会话本就不在）
        for (AgentSkill s : readWorkspace(ctx, sessionId)) {
            byName.put(s.getName(), entry(s, SOURCE_WORKSPACE, Set.of(), Set.of()));
        }
        // 用户自装最后并入 = 优先级最高：明确安装的版本压过会话里临时沉淀的同名技能
        if (uid != null && userSkillStore != null) {
            for (AgentSkill s : readAll(userSkillStore.userDir(uid))) {
                byName.put(s.getName(), entry(s, SOURCE_USER, platformDisabled, userDisabled));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** 内置技能名（小写）：用户自装 / URL 安装不得占用，避免「装个包把内置技能悄悄顶掉」。 */
    public Set<String> builtinNames() {
        Set<String> names = new LinkedHashSet<>();
        for (AgentSkill s : readAll(builtinRoot)) {
            if (s.getName() != null && !s.getName().isBlank()) {
                names.add(s.getName().trim().toLowerCase());
            }
        }
        return names;
    }

    /**
     * 按名解析一条技能（<b>含已停用的</b>）：「本轮点名带正文」与「查看正文」共用这一处，
     * 保证界面上读到的和模型真吃到的字节是同一份。
     * <p>点名是明确意图，优先于停用清单——所以这里不过滤。
     */
    public SkillEntry resolve(RuntimeContext ctx, String sessionId, String name) {
        if (name == null || name.isBlank()) return null;
        for (SkillEntry e : catalog(ctx, sessionId)) {
            if (name.equals(e.skill().getName())) return e;
        }
        return null;
    }

    private static SkillEntry entry(AgentSkill s, String source,
                                    Set<String> platformDisabled, Set<String> userDisabled) {
        String name = s.getName() == null ? "" : s.getName().trim().toLowerCase();
        boolean platform = !name.isEmpty() && platformDisabled.contains(name);
        boolean user = !name.isEmpty() && userDisabled.contains(name);
        return new SkillEntry(s, source, platform || user, platform, user);
    }

    private static Set<String> nameSet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        addNames(out, raw);
        return out;
    }

    /** 停用清单里的技能名：一行一条，逗号/分号也认；比对忽略大小写与首尾空白。 */
    private static void addNames(Set<String> into, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String line : raw.split("[\\r\\n,，;；]+")) {
            String v = line.trim().toLowerCase();
            if (!v.isEmpty()) into.add(v);
        }
    }

    /**
     * 沙盒工作区那一路：经框架同一份仓库实现读（glob {@code SKILL.md} + 逐个解析 frontmatter），
     * 与模型侧共用解析口径，避免「界面列得出、模型读不到」这类两套实现的偏差。
     */
    private List<AgentSkill> readWorkspace(RuntimeContext ctx, String sessionId) {
        String uid = uidOf(ctx);
        if (uid == null || sessionId == null || sessionId.isBlank()) return List.of();
        try {
            if (!sandboxService.available(sessionId, uid)) return List.of();
            io.agentscope.harness.agent.filesystem.AbstractFilesystem fs =
                    sandboxService.frameworkFilesystem(sessionId, uid, false);
            if (fs == null) return List.of();
            List<AgentSkill> skills = new io.agentscope.harness.agent.skill.WorkspaceSkillRepository(
                    fs, WORKSPACE_SKILLS_DIR, SOURCE_WORKSPACE, false).getAllSkills(ctx);
            return skills == null ? List.of() : skills;
        } catch (RuntimeException e) {
            // 沙盒读失败不该让清单整页报错：那一路按空处理，内置与本人那两路照旧给
            log.warn("[SKILL] 读取沙盒工作区技能失败 session={}: {}", sessionId, e.toString());
            return List.of();
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        if (name == null || name.isBlank()) return null;
        SkillEntry e = resolve(RuntimeContext.empty(), null, name);
        return e == null ? null : e.skill();
    }

    @Override
    public AgentSkill getSkill(String name, RuntimeContext context) {
        if (name == null || name.isBlank()) return null;
        SkillEntry e = resolve(context, null, name);
        return e == null ? null : e.skill();
    }

    // ==================== AgentSkillRepository ====================

    /**
     * 无上下文的可见技能（父接口的抽象方法）。
     *
     * <p>退回「只看内置」：没有 uid 就没有用户目录归属，此时把<b>任何</b>用户的技能
     * 当成公共技能暴露出去是越权。故此处只给内置那一份。
     */
@Override
    public List<AgentSkill> getAllSkills() {
        return getAllSkills(RuntimeContext.empty());
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> names = new ArrayList<>();
        for (AgentSkill s : getAllSkills()) {
            names.add(s.getName());
        }
        return names;
    }

    @Override
    public boolean skillExists(String name) {
        return getSkill(name) != null;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        // 迁移后用户侧增删改由框架 skill_manage 工具走文件系统；
        // 这里不接数据库，也不擅自决定写哪个目录（那属于 runtime 分支）。
        throw new UnsupportedOperationException(
                "wenqu skill repository is read-only in this build; "
                        + "skill management goes through the framework skill tools");
    }

    @Override
    public boolean delete(String name) {
        throw new UnsupportedOperationException(
                "wenqu skill repository is read-only in this build; "
                        + "skill management goes through the framework skill tools");
    }

    @Override
    public io.agentscope.core.skill.repository.AgentSkillRepositoryInfo getRepositoryInfo() {
        return new io.agentscope.core.skill.repository.AgentSkillRepositoryInfo(
                "wenqu", "wenqu-skills", false);
    }

    @Override
    public String getSource() {
        return "wenqu";
    }

    @Override
    public void setWriteable(boolean writeable) {
        // 只读语义由 save/delete 的 UnsupportedOperation 表达；
        // 这里的开关刻意不改变行为，避免出现「标记可写但写不了」的假象。
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    // ==================== 内部 ====================

    /** 读一个目录下的全部技能（框架仓库负责解析 frontmatter）。 */
    private List<AgentSkill> readAll(Path baseDir) {
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return List.of();
        }
        try {
            FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir, false, baseDir.toString());
            List<AgentSkill> skills = repo.getAllSkills();
            return skills == null ? List.of() : skills;
        } catch (RuntimeException e) {
            // 目录损坏不该让整轮问答失败：记日志并按「该来源无技能」继续。
            log.warn("[SKILL] 读取技能目录失败 dir={}: {}", baseDir, e.toString());
            return List.of();
        }
    }
}