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
 * 问渠的技能仓库（全框架化 P3）——把「classpath 内置技能 + 用户自己的技能」两路合并交给框架。
 *
 * <h3>为什么目录层不用改</h3>
 * 框架 {@code FileSystemSkillRepository} 源码注释要求的布局是
 * {@code baseDir/{skill-name}/SKILL.md}，而问渠的技能目录本来就是这个形状：
 * <ul>
 *   <li>用户技能：{@code skill-projections/{uid}/{技能名}/SKILL.md}</li>
 *   <li>内置技能：{@code user-data/skills-builtin/{技能名}/SKILL.md}
 *       （随 jar 里的 {@code classpath:skills/} 分发，启动时由 {@link BuiltinSkillSeeder} 落盘）</li>
 * </ul>
 * 故本类不复制、不迁移任何技能文件，只做<b>选择与合并</b>。
 *
 * <p><b>{@code skill-projections/{uid}/} 是冻结的历史用户级技能库</b>（全框架化 P3 之后没有写入方）：
 * 写走框架 {@code skill_manage}、落沙箱工作区；读侧继续解析它是为了不丢存量资产。它<b>不再有生产方</b>
 * ——别再往里写新东西，写进去的内容只有本类读得到，模型侧看不到第二份。
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
 * <p>那一路技能本类<b>也读得到</b>（{@link #catalog} 的第三路），但只在调用方点名会话、
 * 且容器已在场时读——清单要能审到模型自己沉淀的技能，又不能为看一眼清单就把容器拉起来。
 * 问答注入路径（{@link #getAllSkills}）不带这一路：框架装配 agent 时自己会挂上那份可写仓库，
 * 本类再读一遍等于每轮多打一趟沙盒。
 *
 * <h3>合并口径</h3>
 * 优先级递增：内置 → 本人投影 → 沙盒工作区。同名以<b>最后那一路</b>为准，
 * 与框架 {@code composeSkillRepositories} 的 Layer 顺序一致（模型看到的也是这个优先级）。
 */
@Service
public class WenquSkillRepository implements RuntimeContextSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(WenquSkillRepository.class);

    /** 内置技能根：由 {@link BuiltinSkillSeeder} 从 classpath 落盘到 {@code user-data/skills-builtin}。 */
    private final Path builtinRoot;
    /** 用户技能投影根：{@code skill-projections/{uid}/}。 */
    private final Path userProjectsRoot;
    /** 平台级停用清单（{@code skill.disabled} 全局层）。 */
    private final com.wenqu.ai.service.ConfigService configService;
    /** 个人停用清单（同键的个人层，按 uid 显式读，不依赖线程态）。 */
    private final com.wenqu.ai.service.UserConfigService userConfigs;
    /** 沙盒工作区那一路的读通道。 */
    private final com.wenqu.ai.service.SandboxService sandboxService;

    public WenquSkillRepository(
            @Value("${wenqu.skills.builtin-dir:}") String builtinDir,
            @Value("${wenqu.skills.projects-dir:skill-projections}") String projectsDir,
            com.wenqu.ai.service.ConfigService configService,
            com.wenqu.ai.service.UserConfigService userConfigs,
            com.wenqu.ai.service.SandboxService sandboxService) {
        this.builtinRoot = resolveBuiltinRoot(builtinDir);
        this.userProjectsRoot = (projectsDir == null || projectsDir.isBlank())
                ? Paths.get("skill-projections")
                : Paths.get(projectsDir);
        this.configService = configService;
        this.userConfigs = userConfigs;
        this.sandboxService = sandboxService;
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

    /** 本轮用户可见的技能目录（用户投影目录；不存在就是空）。 */
    private Path userDirOf(RuntimeContext ctx) {
        String uid = uidOf(ctx);
        if (uid == null || userProjectsRoot == null) return null;
        Path dir = userProjectsRoot.resolve(uid);
        return Files.isDirectory(dir) ? dir : null;
    }

    // ==================== RuntimeContextSkillRepository ====================

    /**
     * 清单条目：技能 + 来源 + 是否停用 + 停用是否来自平台层。
     *
     * @param global 平台层也停用了这一条——个人改不动它，界面该收起「恢复使用」这个出口
     */
    public record SkillEntry(AgentSkill skill, String source, boolean disabled, boolean global) {
    }

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_USER = "user";
    public static final String SOURCE_WORKSPACE = "workspace";

    /** 停用清单的配置键（平台层 + 个人层同名）。 */
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
     * 提供「恢复使用」），以及可点名会话。
     *
     * @param sessionId 点名会话时额外并上「沙盒工作区」那一路（助手在对话里沉淀下来的技能）。
     *                  传空、或该会话沙盒不在场时只给内置 + 本人投影——<b>看一眼清单不许把容器拉起来</b>
     *                  （与沙盒文件浏览端点同一条判据）。
     */
    public List<SkillEntry> catalog(RuntimeContext ctx, String sessionId) {
        Map<String, SkillEntry> byName = new LinkedHashMap<>();
        // 停用清单 = 平台层 ∪ 本人层。个人值按 uid 显式查：清单端点跑在 Tomcat 线程上，
        // 问答流水线那份个人覆盖的 ThreadLocal 在这里不存在，靠 ConfigService.get 的覆盖语义会静默读空
        Set<String> global = nameSet(configService.get(DISABLED_KEY));
        Set<String> disabled = new LinkedHashSet<>(global);
        addNames(disabled, userConfigs.personalValue(uidOf(ctx), DISABLED_KEY));
        for (AgentSkill s : readAll(builtinRoot)) {
            byName.put(s.getName(), entry(s, SOURCE_BUILTIN, disabled, global));
        }
        Path userDir = userDirOf(ctx);
        if (userDir != null) {
            for (AgentSkill s : readAll(userDir)) {
                byName.put(s.getName(), entry(s, SOURCE_USER, disabled, global));
            }
        }
        // 最后并入：同名以工作区版为准（与框架 composeSkillRepositories 的 Layer 顺序一致，
        // 模型看到的也是这个优先级）
        for (AgentSkill s : readWorkspace(ctx, sessionId)) {
            byName.put(s.getName(), entry(s, SOURCE_WORKSPACE, Set.of(), Set.of()));
        }
        return new ArrayList<>(byName.values());
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

    private static SkillEntry entry(AgentSkill s, String source, Set<String> disabled, Set<String> global) {
        String name = s.getName() == null ? "" : s.getName().trim().toLowerCase();
        return new SkillEntry(s, source,
                !name.isEmpty() && disabled.contains(name),
                !name.isEmpty() && global.contains(name));
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
                "wenqu", "wenqu-skills", true);
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