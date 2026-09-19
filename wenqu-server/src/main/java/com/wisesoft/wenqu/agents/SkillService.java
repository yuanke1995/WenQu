package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.config.RuntimePaths;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Skill;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.permissions.ResourcePermissions;
import com.wisesoft.wenqu.permissions.ShareableResource;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.RepoValues;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Skill 服务。
 *
 * <p>由参考实现的 agents/skills/service.py（1827 行）逐符号翻译：常量（slug 正则、
 * 文本扩展名白名单、内置操作者/管理员角色、默认与内置共享配置、草稿 TTL、个人来源类型、
 * 存储锁）逐字一致；函数名、参数、抛错文案、字典键序、返回结构逐条对位。
 *
 * <p>必要替换（必须保留，已标注）：
 * <ul>
 *   <li><b>建议锁前缀取本系统标识</b>：参考实现 {@code _USER_SKILL_PROJECTION_LOCK_SCOPE}
 *       为 {@code "<参考实现前缀>:skills:user-projection:v1:"}，本工程改为
 *       {@code "wenqu:skills:user-projection:v1:"}（缓存/锁键前缀属约定允许的必要替换）。
 *   <li><b>MySQL 方言</b>：参考实现的 PostgreSQL 事务级建议锁
 *       （{@code pg_advisory_xact_lock(hashtext(...))}）在 MySQL 无对应能力，改为在
 *       <b>同一条连接</b>上执行 {@code SELECT GET_LOCK(?, ?)} / {@code SELECT RELEASE_LOCK(?)}
 *       （见 {@link #withAdvisoryLock} / {@link #withAdvisoryLocks}）。语义差异需注意：
 *       PG 锁随事务结束释放，故参考实现里「先加锁 → 读数据 → 落盘」天然同事务；
 *       MySQL 锁是<b>会话级</b>且不会自动释放，因此凡是参考实现把多步放在同一事务里的地方，
 *       本工程必须把多步一并包进锁回调（如 {@code refreshUserSkillProjectionAsync}），
 *       多个 uid 的场景用一次性多锁等价复现「一个事务持有多把锁」。参考实现里以
 *       {@code dialect.name == "postgresql"} 为前置条件的调用，在本工程（MySQL）下等价于
 *       始终需要互斥，故按 MySQL 语义实现。
 *   <li><b>品牌前缀</b>：内置 skill 描述与 SKILL.md 中的参考实现产品名替换为「问渠」
 *       （仅用户可见文案，不改 slug / 目录名 / 依赖名）。
 * </ul>
 *
 * <p>能力差异（显式标注，非遗漏）：
 * <ul>
 *   <li>{@code AsyncSession db} 形参：本工程仓储为 Spring bean（Mapper 调用即事务），
 *       方法与参考实现同名但无 db 形参；需要多语句原子性的函数由 {@code @Transactional} 承担，
 *       批量删除用 REQUIRES_NEW 子事务对应参考实现的「单技能独立子事务与回滚」。
 *   <li>{@code asyncio.to_thread} → 调用方线程直接执行（既有约定）。
 *   <li>{@code asyncio.gather} → 顺序执行（Java 无协程，语义等价）。
 *   <li>{@code fcntl.flock}（进程间文件锁）→ {@link FileChannel#lock()} 独占锁。
 *   <li>{@code threading.Lock} → {@link ReentrantLock}。
 *   <li>{@code Skill.to_dict()}（模型方法）→ {@link #skillToDict}（本工程实体为纯 MyBatis 记录）。
 *   <li>内置 skill 的 {@code source_dir} 原指参考实现的包内目录；本工程复制到
 *       {@code src/main/resources/skills/}，运行时按 classpath 解析为真实目录路径
 *       （见 {@link #builtinSkillsRoot}）。
 *   <li>{@code yaml.safe_load} / {@code yaml.safe_dump(sort_keys=False, allow_unicode=True)}
 *       → snakeyaml 的 SafeConstructor / 保序 Representer（见 {@link #newYamlLoader} /
 *       {@link #dumpYamlPreservingOrder}）。
 * </ul>
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    // ---------------------------------------------------------------- 常量（逐字对齐）

    /** {@code ^[a-z0-9]+(-[a-z0-9]+)*$}（参考实现的 SKILL_SLUG_PATTERN，SKILL_NAME_PATTERN 同源）。 */
    private static final Pattern SKILL_SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final Pattern SKILL_SLUG_PATTERN_SAME = SKILL_SLUG_PATTERN;

    /** 可读写的文本文件扩展名白名单（参考实现 TEXT_FILE_EXTENSIONS 逐项照搬）。 */
    public static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
            ".md", ".txt", ".py", ".js", ".ts", ".json", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf", ".xml",
            ".html", ".css", ".sql", ".sh", ".bat", ".ps1", ".env", ".csv", ".tsv", ".rst", ".ipynb", ".vue", ".jsx",
            ".tsx");

    public static final String BUILTIN_SKILL_OPERATOR = "builtin-system";
    public static final Set<String> ADMIN_ROLES = Set.of("admin", "superadmin");
    public static final int SKILL_DRAFT_TTL_SECONDS = 60 * 60;
    public static final String PERSONAL_SKILL_SOURCE_TYPE = "personal";

    /** 参考实现 {@code _USER_SKILL_PROJECTION_LOCK_SCOPE}；前缀为本系统标识（必要替换）。 */
    private static final String USER_SKILL_PROJECTION_LOCK_SCOPE = "wenqu:skills:user-projection:v1:";

    /** 参考实现 {@code SKILL_STORAGE_LOCK = 0x5958534B}（数值逐字保留）。 */
    public static final long SKILL_STORAGE_LOCK = 0x5958534BL;

    /** 单条建议锁的等待上限（秒），MySQL {@code GET_LOCK} 需要；参考实现的建议锁为无限等待。 */
    private static final int ADVISORY_LOCK_TIMEOUT_SECONDS = 60;

    // ---------------------------------------------------------------- 依赖

    private final SkillRepository skillRepository;
    private final UserRepository userRepository;
    private final McpService mcpService;
    private final SkillRemoteInstall skillRemoteInstall;
    private final AgentRepository agentRepository;
    private final ConversationRepository conversationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTransaction;

    private final Map<String, ReentrantLock> userSkillsLocks = new ConcurrentHashMap<>();

    public SkillService(
            SkillRepository skillRepository,
            UserRepository userRepository,
            McpService mcpService,
            SkillRemoteInstall skillRemoteInstall,
            AgentRepository agentRepository,
            ConversationRepository conversationRepository,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.skillRepository = skillRepository;
        this.userRepository = userRepository;
        this.mcpService = mcpService;
        this.skillRemoteInstall = skillRemoteInstall;
        this.agentRepository = agentRepository;
        this.conversationRepository = conversationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- 共享配置常量

    /** {@code DEFAULT_SKILL_SHARE_CONFIG}。 */
    public static JSONObject defaultSkillShareConfig() {
        JSONObject scope = new JSONObject();
        scope.put("access_level", "user");
        scope.put("department_ids", new ArrayList<>());
        scope.put("user_uids", new ArrayList<>());
        return scope;
    }

    /** {@code BUILTIN_SKILL_SHARE_CONFIG}。 */
    public static JSONObject builtinSkillShareConfig() {
        JSONObject scope = new JSONObject();
        scope.put("access_level", "global");
        scope.put("department_ids", new ArrayList<>());
        scope.put("user_uids", new ArrayList<>());
        return scope;
    }

    // ---------------------------------------------------------------- 锁（_get_user_skills_lock / _user_skills_file_lock）

    private ReentrantLock getUserSkillsLock(String uid) {
        return userSkillsLocks.computeIfAbsent(uid == null ? "" : uid, key -> new ReentrantLock());
    }

    /** 在共享投影卷上串行化同一用户的目录替换（参考实现 {@code _user_skills_file_lock}）。 */
    private void withUserSkillsFileLock(String uid, Runnable action) {
        Path lockDir = RuntimePaths.getSkillProjectionDir().resolve(".locks");
        try {
            Files.createDirectories(lockDir);
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建 Skill 投影锁目录: " + lockDir, exc);
        }
        Path lockPath = lockDir.resolve(WorkspacePaths.workspaceUidDirname(uid) + ".lock");
        try (FileChannel channel = FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                FileLock ignored = channel.lock()) {
            action.run();
        } catch (IOException exc) {
            throw new IllegalStateException("Skill 投影文件锁失败: " + lockPath, exc);
        }
    }

    /** 在给定作用域上持有 MySQL 建议锁后执行（参考实现 {@code pg_advisory_xact_lock} 的等价物）。 */
    private void withAdvisoryLock(String scope, Runnable action) {
        withAdvisoryLocks(List.of(scope), action);
    }

    /**
     * 一次性持有多个建议锁后执行（对应参考实现「同一事务内为多个 uid 加锁」的写法）。
     *
     * <p>MySQL 的 {@code GET_LOCK} 是<b>会话级</b>锁，同一连接可重复加不同名字的锁，
     * 因此把全部 scope 在同一个连接上加齐、跑完动作再逐个 {@code RELEASE_LOCK}，
     * 即可复现参考实现中「一个事务持有多把锁」的效果。
     */
    private void withAdvisoryLocks(List<String> scopes, Runnable action) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            List<String> acquired = new ArrayList<>();
            try {
                for (String scope : scopes) {
                    try (java.sql.PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                        acquire.setString(1, scope);
                        acquire.setInt(2, ADVISORY_LOCK_TIMEOUT_SECONDS);
                        try (java.sql.ResultSet rs = acquire.executeQuery()) {
                            if (!rs.next() || rs.getObject(1) == null) {
                                throw new IllegalStateException("获取 Skill 建议锁超时: " + scope);
                            }
                        }
                    }
                    acquired.add(scope);
                }
                action.run();
            } finally {
                for (String scope : acquired) {
                    try (java.sql.PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        release.setString(1, scope);
                        release.executeQuery().close();
                    }
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- 纯函数层

    /** {@code normalize_string_list}：剥空白、丢非字符串、保序去重。 */
    public static List<String> normalizeStringList(List<?> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                continue;
            }
            String item = text.strip();
            if (item.isEmpty() || seen.contains(item)) {
                continue;
            }
            seen.add(item);
            normalized.add(item);
        }
        return normalized;
    }

    /** 从 JSON 文本列解码字符串列表（JSON 列为 TEXT，需显式反序列化）。 */
    public static List<String> normalizeStringListFromJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Object parsed = JSON.parse(jsonText);
            if (parsed instanceof List<?> list) {
                return normalizeStringList(list);
            }
        } catch (RuntimeException exc) {
            log.warn("Skill 依赖列解析失败，按空列表处理: " + exc.getMessage());
        }
        return new ArrayList<>();
    }

    public static boolean isValidSkillSlug(String slug) {
        if (slug == null) {
            return false;
        }
        return SKILL_SLUG_PATTERN_SAME.matcher(slug.strip()).find();
    }

    /** {@code is_builtin_skill}（参考实现兼容 dict 与 ORM 对象，Java 仅实体）。 */
    public static boolean isBuiltinSkill(Skill item) {
        return item != null && "builtin".equals(item.getSourceType());
    }

    /** {@code get_allowed_skill_access_levels}。 */
    public static List<String> getAllowedSkillAccessLevels(User user) {
        if (ADMIN_ROLES.contains(user.getRole())) {
            return List.of("global", "department", "user");
        }
        return List.of("user");
    }

    /** {@code normalize_skill_share_config}。 */
    public static JSONObject normalizeSkillShareConfig(
            JSONObject shareConfig, String operatorUid, String sourceType, Set<String> allowedAccessLevels) {
        String effectiveSourceType = sourceType == null ? "upload" : sourceType;
        if ("builtin".equals(effectiveSourceType)) {
            JSONObject config = new JSONObject();
            config.put("version", 2);
            config.put("read_scope", builtinSkillShareConfig());
            config.put("manage_scope", null);
            return config;
        }

        JSONObject defaultScope = new JSONObject();
        defaultScope.put("access_level", "user");
        defaultScope.put("department_ids", new ArrayList<>());
        defaultScope.put("user_uids", operatorUid == null ? new ArrayList<>() : List.of(operatorUid));

        JSONObject effective = shareConfig;
        if (effective == null) {
            effective = new JSONObject();
            effective.put("version", 2);
            effective.put("read_scope", defaultScope);
            effective.put("manage_scope", null);
        }
        return ResourcePermissions.normalizePermissionConfig(
                effective, allowedAccessLevels, "当前用户无权使用该 Skill 共享范围", true);
    }

    // ---------------------------------------------------------------- 权限判定

    /** {@code user_can_access_skill}。 */
    public static boolean userCanAccessSkill(User user, Skill skill, boolean requireEnabled) {
        if (requireEnabled && !Boolean.TRUE.equals(skill.getEnabled())) {
            return false;
        }
        return resolveSkillPermission(user, skill) != ResourcePermission.NONE;
    }

    /** {@code user_can_manage_skill}。 */
    public static boolean userCanManageSkill(User user, Skill skill) {
        if (isBuiltinSkill(skill)) {
            return ADMIN_ROLES.contains(user.getRole());
        }
        return resolveSkillPermission(user, skill) == ResourcePermission.MANAGE;
    }

    /**
     * {@code user_can_access_skill}（{@link ResolvedSkill} 版本）。
     *
     * <p>能力差异（已标注）：参考实现同一个函数靠鸭子类型同时接受 ORM {@code Skill} 与
     * {@link ResolvedSkill}，Java 静态类型下拆成重载；{@code require_enabled} 的取值口径
     * 与参考实现一致——读资源自身的 enabled（{@code item.enabled}）。
     */
    public static boolean userCanAccessSkill(User user, ResolvedSkill item, boolean requireEnabled) {
        if (requireEnabled && !item.enabled()) {
            return false;
        }
        return ResourcePermissions.resolveSkillPermission(PermissionSubject.of(user), item)
                != ResourcePermission.NONE;
    }

    /** {@code user_can_manage_skill}（{@link ResolvedSkill} 版本）。 */
    public static boolean userCanManageSkill(User user, ResolvedSkill item) {
        if (isBuiltinSkill(item)) {
            return ADMIN_ROLES.contains(user.getRole());
        }
        return ResourcePermissions.resolveSkillPermission(PermissionSubject.of(user), item)
                == ResourcePermission.MANAGE;
    }

    /** {@code is_builtin_skill}（{@link ResolvedSkill} 版本）。 */
    public static boolean isBuiltinSkill(ResolvedSkill item) {
        return item != null && "builtin".equals(item.sourceType());
    }

    private static ResourcePermission resolveSkillPermission(User user, Skill skill) {
        return ResourcePermissions.resolveSkillPermission(PermissionSubject.of(user), ShareableResource.of(skill));
    }

    /** {@code can_skill_depend_on}：依赖范围必须被父项范围完整覆盖。 */
    public static boolean canSkillDependOn(Skill parent, Skill dependency) {
        if (!Boolean.TRUE.equals(dependency.getEnabled())) {
            return false;
        }
        if (isBuiltinSkill(dependency)) {
            return true;
        }

        JSONObject depConfig = ResourcePermissions.normalizePermissionConfig(
                ShareableResource.of(dependency).shareConfig());
        JSONObject parentConfig = ResourcePermissions.normalizePermissionConfig(
                ShareableResource.of(parent).shareConfig());
        List<JSONObject> dependencyScopes = new ArrayList<>();
        List<JSONObject> parentScopes = new ArrayList<>();
        for (JSONObject scope : new JSONObject[] {
            depConfig.getJSONObject("read_scope"), depConfig.getJSONObject("manage_scope")
        }) {
            if (isTruthy(scope)) {
                dependencyScopes.add(scope);
            }
        }
        for (JSONObject scope : new JSONObject[] {
            parentConfig.getJSONObject("read_scope"), parentConfig.getJSONObject("manage_scope")
        }) {
            if (isTruthy(scope)) {
                parentScopes.add(scope);
            }
        }
        if (dependencyScopes.isEmpty()) {
            dependencyScopes.add(ownerScopeOf(dependency.getCreatedBy()));
        }
        if (parentScopes.isEmpty()) {
            parentScopes.add(ownerScopeOf(parent.getCreatedBy()));
        }
        for (JSONObject parentScope : parentScopes) {
            boolean covered = false;
            for (JSONObject dependencyScope : dependencyScopes) {
                if (scopeContains(dependencyScope, parentScope)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject ownerScopeOf(String createdBy) {
        JSONObject scope = new JSONObject();
        scope.put("access_level", "user");
        scope.put("department_ids", new ArrayList<>());
        scope.put("user_uids", List.of(createdBy == null ? "" : createdBy));
        return scope;
    }

    /** Python {@code bool(x)}：None / 空 dict / 空 list 均为假。 */
    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return true;
    }

    /** {@code _scope_contains}：判断一个共享范围是否完整覆盖另一个范围。 */
    private static boolean scopeContains(JSONObject container, JSONObject target) {
        String containerLevel = container.getString("access_level");
        String targetLevel = target.getString("access_level");
        if ("global".equals(containerLevel)) {
            return true;
        }
        if ("global".equals(targetLevel) || !java.util.Objects.equals(containerLevel, targetLevel)) {
            return false;
        }
        if ("department".equals(targetLevel)) {
            return intSet(container.get("department_ids")).containsAll(intSet(target.get("department_ids")));
        }
        if ("user".equals(targetLevel)) {
            return strSet(container.get("user_uids")).containsAll(strSet(target.get("user_uids")));
        }
        return false;
    }

    private static Set<Integer> intSet(Object value) {
        Set<Integer> result = new LinkedHashSet<>();
        if (value instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Number number) {
                    result.add(number.intValue());
                } else if (item != null) {
                    result.add(Integer.parseInt(String.valueOf(item).strip()));
                }
            }
        }
        return result;
    }

    private static Set<String> strSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /** {@code _ensure_non_builtin}。 */
    private static void ensureNonBuiltin(Skill item) {
        if (isBuiltinSkill(item)) {
            throw new IllegalArgumentException("内置 skill 不允许执行该操作");
        }
    }

    // ---------------------------------------------------------------- 目录根

    /** {@code get_skills_root_dir}：共享与内置 Skill 的持久源目录。 */
    public static Path getSkillsRootDir() {
        Path root = RuntimePaths.getSkillDataDir().resolve("shared");
        try {
            Files.createDirectories(root);
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建 Skill 共享根目录: " + root, exc);
        }
        return root;
    }

    /** {@code get_skill_drafts_root_dir}：可丢弃的 Skill 安装草稿目录。 */
    public static Path getSkillDraftsRootDir() {
        Path root = RuntimePaths.getRuntimeDir().resolve("skill_import_drafts");
        try {
            Files.createDirectories(root);
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建 Skill 草稿根目录: " + root, exc);
        }
        return root;
    }

    /** {@code get_user_skills_root_dir}：用户获授权的共享 Skill 只读投影根目录。 */
    public static Path getUserSkillsRootDir(String uid) {
        Path root = RuntimePaths.getSkillProjectionDir().resolve(WorkspacePaths.workspaceUidDirname(uid));
        try {
            Files.createDirectories(root);
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建用户 Skill 投影目录: " + root, exc);
        }
        return root;
    }

    /** {@code get_personal_skills_root_dir}：UserWorkspace 内认证用户唯一的个人 Skill 目录。 */
    public static Path getPersonalSkillsRootDir(String uid) {
        return WorkspacePaths.userWorkspaceDir(uid).resolve("agents").resolve("skills");
    }

    /** {@code _personal_skills_root}：已创建且位于当前用户工作区内的个人 Skill 根。 */
    private static Path personalSkillsRoot(String uid) {
        WorkspacePaths.ensureUserWorkspace(uid);
        Path workspaceRoot = WorkspacePaths.userWorkspaceDir(uid).toAbsolutePath().normalize();
        Path root = getPersonalSkillsRootDir(uid);
        try {
            Files.createDirectories(root);
        } catch (IOException exc) {
            throw new IllegalStateException("无法创建个人 Skill 根目录: " + root, exc);
        }
        return SafeFiles.ensureWithinRoot(root.toAbsolutePath().normalize(), workspaceRoot, "个人 Skill 路径越界");
    }

    // ---------------------------------------------------------------- 草稿加载

    private static final Pattern DRAFT_ID_PATTERN = Pattern.compile("[0-9a-fA-F-]{32,36}");

    /** {@code _load_skill_draft}。 */
    private DraftLoad loadSkillDraft(String draftId) {
        String candidate = draftId == null ? "" : draftId;
        if (!DRAFT_ID_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException("无效的安装草稿");
        }
        Path draftsRoot = getSkillDraftsRootDir().toAbsolutePath().normalize();
        Path draftDir = draftsRoot.resolve(candidate).toAbsolutePath().normalize();
        if (!draftDir.startsWith(draftsRoot)) {
            throw new IllegalArgumentException("无效的安装草稿");
        }
        Path metadataPath = draftDir.resolve("metadata.json");
        if (!Files.exists(metadataPath)) {
            throw new IllegalArgumentException("安装草稿不存在或已过期");
        }
        JSONObject data;
        try {
            data = JSON.parseObject(Files.readString(metadataPath, StandardCharsets.UTF_8));
        } catch (IOException exc) {
            throw new IllegalArgumentException("安装草稿不存在或已过期");
        }
        if (data == null) {
            data = new JSONObject();
        }
        long expiresAt = data.getLongValue("expires_at", 0L);
        if (expiresAt < System.currentTimeMillis() / 1000.0) {
            deleteTreeQuietly(draftDir);
            throw new IllegalArgumentException("安装草稿已过期");
        }
        return new DraftLoad(draftDir, data);
    }

    /** {@code _load_skill_draft} 的返回二元组。 */
    private record DraftLoad(Path draftDir, JSONObject data) {}

    /** {@code _load_and_select_draft_items}。 */
    private DraftSelection loadAndSelectDraftItems(String draftId, List<String> slugs, User operator) {
        DraftLoad loaded = loadSkillDraft(draftId);
        JSONObject data = loaded.data();
        String createdBy = data.getString("created_by");
        if (!java.util.Objects.equals(createdBy, operator.getUid()) && !ADMIN_ROLES.contains(operator.getRole())) {
            throw new IllegalArgumentException("无权确认该安装草稿");
        }
        String sourceType = data.getString("source_type");
        if (!"upload".equals(sourceType) && !"remote".equals(sourceType)) {
            throw new IllegalArgumentException("无效的安装草稿来源");
        }

        List<JSONObject> draftItems = objectList(data.get("items"));
        if (slugs != null) {
            Set<String> selectedSlugs = new LinkedHashSet<>(slugs);
            if (selectedSlugs.isEmpty()) {
                throw new IllegalArgumentException("至少选择一个 Skill");
            }
            Set<String> availableSlugs = new LinkedHashSet<>();
            for (JSONObject item : draftItems) {
                availableSlugs.add(item.getString("slug") == null ? "" : item.getString("slug").strip());
            }
            for (String slug : selectedSlugs) {
                if (!availableSlugs.contains(slug)) {
                    throw new IllegalArgumentException("确认安装包含草稿外的 Skill");
                }
            }
            List<JSONObject> filtered = new ArrayList<>();
            for (JSONObject item : draftItems) {
                String slug = item.getString("slug") == null ? "" : item.getString("slug").strip();
                if (selectedSlugs.contains(slug)) {
                    filtered.add(item);
                }
            }
            draftItems = filtered;
        }
        return new DraftSelection(loaded.draftDir(), data, draftItems);
    }

    private record DraftSelection(Path draftDir, JSONObject data, List<JSONObject> draftItems) {}

    private static List<JSONObject> objectList(Object value) {
        List<JSONObject> result = new ArrayList<>();
        if (value instanceof java.util.Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof JSONObject object) {
                    result.add(object);
                } else if (item instanceof Map<?, ?> map) {
                    JSONObject converted = new JSONObject();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        converted.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    result.add(converted);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 投影同步

    /** {@code sync_user_accessible_skills_async}（to_thread 折叠为同步调用）。 */
    public Path syncUserAccessibleSkillsAsync(String uid, Map<String, String> sourceDirs) {
        return syncUserAccessibleSkills(uid, sourceDirs);
    }

    /** {@code refresh_user_skill_projection_async}：按数据库最新授权快照重建投影。 */
    public Map<String, String> refreshUserSkillProjectionAsync(String uid) {
        String normalizedUid = uid == null ? "" : uid.strip();
        if (normalizedUid.isEmpty()) {
            throw new IllegalArgumentException("uid is required to refresh the user Skill projection");
        }

        final Map<String, String> sourceDirs = new LinkedHashMap<>();
        // 参考实现的建议锁是「事务级」（pg_advisory_xact_lock），因此从读授权快照到
        // sync_user_accessible_skills_async 全程都处于同一事务（同一把锁）之下；
        // MySQL 的 GET_LOCK 是会话级，必须把这三步一并包进回调，否则锁在读取前就被释放。
        withAdvisoryLock(USER_SKILL_PROJECTION_LOCK_SCOPE + normalizedUid, () -> {
            User user = userRepository.getByUid(normalizedUid);
            if (user == null || (user.getIsDeleted() != null && user.getIsDeleted() != 0)) {
                // 用户已删除/不存在 → 空来源集合（fail-closed，清掉整个投影）
                sourceDirs.clear();
            } else {
                for (Skill item : listAccessibleSharedSkills(user, true)) {
                    if (item.getSlug() != null) {
                        sourceDirs.put(item.getSlug(), resolveSkillDir(item).toString());
                    }
                }
            }
            syncUserAccessibleSkillsAsync(normalizedUid, sourceDirs);
        });
        return sourceDirs;
    }

    /** {@code _remove_skill_from_user_projection}。 */
    private void removeSkillFromUserProjection(String uid, String slug) {
        if (!isValidSkillSlug(slug)) {
            throw new IllegalArgumentException("无效 skill slug");
        }
        ReentrantLock lock = getUserSkillsLock(uid);
        lock.lock();
        try {
            withUserSkillsFileLock(uid, () -> removeSkillProjectionEntry(getUserSkillsRootDir(uid).resolve(slug)));
        } finally {
            lock.unlock();
        }
    }

    /** {@code apply_skill_projection_policy_change}：提交授权变更并同步所有已存在的 uid 投影。 */
    public void applySkillProjectionPolicyChange(String slug) {
        List<String> uids = new ArrayList<>();
        List<User> users = userRepository.listActiveOrderedById();
        Path projectionRoot = RuntimePaths.getSkillProjectionDir();
        for (User user : users) {
            String uid = user.getUid() == null ? null : String.valueOf(user.getUid());
            if (uid == null) {
                continue;
            }
            if (Files.isDirectory(projectionRoot.resolve(WorkspacePaths.workspaceUidDirname(uid)))) {
                uids.add(uid);
            }
        }

        // 参考实现在同一事务里一次性拿到全部 uid 的建议锁（移除阶段 fail-closed 地串行化），
        // 提交后再逐个 refresh（refresh 自己会重新加锁）。MySQL 会话级锁用一次性多锁实现。
        List<String> scopes = new ArrayList<>();
        for (String uid : uids) {
            scopes.add(USER_SKILL_PROJECTION_LOCK_SCOPE + uid);
        }
        withAdvisoryLocks(scopes, () -> {
            for (String uid : uids) {
                removeSkillFromUserProjection(uid, slug);
            }
        });
        for (String uid : uids) {
            refreshUserSkillProjectionAsync(uid);
        }
    }

    /** {@code sync_user_accessible_skills}：把用户可访问的共享 Skill 同步到只读投影目录。 */
    public Path syncUserAccessibleSkills(String uid, Map<String, String> sourceDirs) {
        Path userSkillsRoot = getUserSkillsRootDir(uid);
        Map<String, Path> normalizedSources = new LinkedHashMap<>();
        if (sourceDirs != null) {
            for (Map.Entry<String, String> entry : sourceDirs.entrySet()) {
                String slug = entry.getKey();
                String path = entry.getValue();
                if (isValidSkillSlug(slug) && path != null) {
                    normalizedSources.put(slug, Path.of(path).toAbsolutePath().normalize());
                }
            }
        }
        Set<String> accessibleSlugs = new LinkedHashSet<>(normalizedSources.keySet());

        ReentrantLock lock = getUserSkillsLock(uid);
        lock.lock();
        try {
            withUserSkillsFileLock(uid, () -> {
                for (Path entry : listChildren(userSkillsRoot)) {
                    if (accessibleSlugs.contains(entry.getFileName().toString())) {
                        continue;
                    }
                    removeSkillProjectionEntry(entry);
                }

                for (Map.Entry<String, Path> entry : normalizedSources.entrySet()) {
                    String slug = entry.getKey();
                    Path sourceDir = entry.getValue();
                    Path targetDir = userSkillsRoot.resolve(slug);
                    Path tempTarget = userSkillsRoot.resolve("." + slug + ".tmp-" + shortUuid());
                    try {
                        if (skillDirsEqual(sourceDir, targetDir)) {
                            continue;
                        }
                        copySkillTreeNoSymlinks(sourceDir, tempTarget);
                        removeSkillProjectionEntry(targetDir);
                        moveDirectory(tempTarget, targetDir);
                    } catch (java.nio.file.NoSuchFileException exc) {
                        log.warn("跳过不存在的 Skill 来源: slug=" + slug);
                        removeSkillProjectionEntry(targetDir);
                    } catch (IOException | RuntimeException exc) {
                        removeSkillProjectionEntry(targetDir);
                        throw new IllegalStateException(exc.getMessage(), exc);
                    } finally {
                        if (Files.exists(tempTarget, LinkOption.NOFOLLOW_LINKS)) {
                            deleteTreeQuietly(tempTarget);
                        }
                    }
                }
            });
        } finally {
            lock.unlock();
        }
        return userSkillsRoot;
    }

    /** {@code _remove_skill_projection_entry}：删除投影条目，不跟随符号链接。 */
    private static void removeSkillProjectionEntry(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                deleteTreeQuietly(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException exc) {
            log.warn("删除投影条目失败: " + path + " (" + exc.getMessage() + ")");
        }
    }

    // ---------------------------------------------------------------- 内置 skill 规格

    /** 内置 skill 规格（对应参考实现 buildin/__init__.py 的 BuiltinSkillSpec）。 */
    public record BuiltinSkillSpec(
            String slug,
            Path sourceDir,
            String description,
            String version,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies) {}

    /** 内置 skill 资源根（本工程由 classpath resources/skills 提供，见类注释）。 */
    static Path builtinSkillsRoot() {
        try {
            java.net.URL resource = SkillService.class.getClassLoader().getResource("skills");
            if (resource == null) {
                throw new IllegalStateException("内置 skill 资源目录缺失: skills");
            }
            return Path.of(resource.toURI()).toAbsolutePath().normalize();
        } catch (java.net.URISyntaxException exc) {
            throw new IllegalStateException("无法解析内置 skill 资源目录", exc);
        }
    }

    /** 内置 skill 清单（slug / 目录 / 描述 / 版本 / 三类依赖逐字对齐参考实现）。 */
    public static List<BuiltinSkillSpec> getBuiltinSkillSpecs() {
        Path root = builtinSkillsRoot();
        List<BuiltinSkillSpec> specs = new ArrayList<>();
        specs.add(new BuiltinSkillSpec(
                "image-gen",
                root.resolve("image-gen"),
                "在 Agent 沙盒中生成图片并保存到 outputs，默认支持 Qwen-Image，也可接入其它图片生成接口。",
                "2026.06.02",
                List.of("present_artifacts"),
                List.of(),
                List.of()));
        specs.add(new BuiltinSkillSpec(
                "html-preview",
                root.resolve("html-preview"),
                "使用 Markdown `html:preview` 围栏输出轻量静态 HTML/CSS 可视化，"
                        + "适合数值对比、流程、时间线、层级关系和关键指标。",
                "2026.07.23",
                List.of(),
                List.of(),
                List.of()));
        specs.add(new BuiltinSkillSpec(
                "deep-research",
                root.resolve("deep-research"),
                "深度研究编排方法论：澄清范围、拆解规划、并行调度子智能体调研、对抗式核验、综合成带引用的结构化报告。",
                "2026.07.29",
                List.of("web_search"),
                List.of(),
                List.of("html-preview")));
        specs.add(new BuiltinSkillSpec(
                "knowledge-base",
                root.resolve("knowledge-base"),
                "使用问渠知识库进行检索、打开文档、文档内定位和查看思维导图。",
                "2026.06.24",
                List.of(
                        "list_kbs",
                        "query_kb",
                        "find_kb_document",
                        "open_kb_document",
                        "get_mindmap",
                        "search_file",
                        "download_kb_file"),
                List.of(),
                List.of()));
        specs.add(new BuiltinSkillSpec(
                "mysql-reporter",
                root.resolve("mysql-reporter"),
                "基于 MySQL 数据库生成查询报表和可视化图表，适合分析业务指标、统计趋势，并用 Charts MCP 展示结果。",
                "2026.06.05",
                List.of(),
                List.of("mcp-server-chart"),
                List.of()));
        return specs;
    }

    /** {@code _build_builtin_skill_dir_path}：{@code (Path("shared") / slug).as_posix()}。 */
    private static String buildBuiltinSkillDirPath(String slug) {
        return PosixPathLite.parse("shared").join(List.of(slug)).asPosix();
    }

    // ---------------------------------------------------------------- 目录工具

    /** {@code _dir_contains_symlink}：目录内是否包含任意符号链接子路径。 */
    static boolean dirContainsSymlink(Path path) {
        final boolean[] found = {false};
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.forEach(child -> {
                if (!child.equals(path) && Files.isSymbolicLink(child)) {
                    found[0] = true;
                }
            });
        } catch (IOException exc) {
            throw new IllegalStateException("扫描 Skill 目录失败: " + path, exc);
        }
        return found[0];
    }

    /** {@code copy_skill_tree_no_symlinks}：复制不含符号链接的 Skill 目录到 staging。 */
    static void copySkillTreeNoSymlinks(Path sourceDir, Path targetDir) throws IOException {
        Path source = sourceDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(source.toString());
        }
        if (dirContainsSymlink(source)) {
            throw new IllegalArgumentException("Skill 来源只允许普通文件和目录: " + source);
        }
        try {
            copyRecursively(source, targetDir);
        } catch (RuntimeException | IOException exc) {
            deleteTreeQuietly(targetDir);
            throw exc;
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        List<Path> children = listChildren(source);
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path child : children) {
            Path childTarget = target.resolve(child.getFileName().toString());
            if (Files.isSymbolicLink(child)) {
                throw new IllegalArgumentException("Skill 来源只允许普通文件和目录: " + child);
            }
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                copyRecursively(child, childTarget);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(child, childTarget, StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new IllegalArgumentException("Skill 来源只允许普通文件和目录: " + child);
            }
        }
    }

    /** {@code _copy_skill_snapshot}：复制并解析 Skill staging，可校验来源或重写最终 slug。 */
    private static JSONObject copySkillSnapshot(
            Path sourceDir, Path targetDir, String expectedSlug, String finalSlug) throws IOException {
        copySkillTreeNoSymlinks(sourceDir, targetDir);
        JSONObject parsed = parseSkillDirMetadata(targetDir);
        if (expectedSlug != null && !expectedSlug.equals(parsed.getString("slug"))) {
            throw new IllegalArgumentException("Skill slug 在复制过程中发生变化");
        }
        if (finalSlug != null && !finalSlug.equals(parsed.getString("slug"))) {
            Path skillMd = targetDir.resolve("SKILL.md");
            String rewritten = rewriteFrontmatterSlug(Files.readString(skillMd, StandardCharsets.UTF_8), finalSlug);
            Files.writeString(skillMd, rewritten, StandardCharsets.UTF_8);
        }
        return parsed;
    }

    /** {@code skill_dirs_equal}：按 no-follow 字节与执行位比较来源和投影。 */
    static boolean skillDirsEqual(Path dir1, Path dir2) {
        byte[] sourceHash = computeProjectionHash(dir1);
        try {
            return java.util.Arrays.equals(sourceHash, computeProjectionHash(dir2));
        } catch (RuntimeException exc) {
            // 缺失或被替换为链接的投影必须重建，不能沿用相同字节的链接。
            return false;
        }
    }

    /** {@code _compute_projection_hash}：no-follow 读取投影比较摘要，拒绝链接和特殊文件。 */
    private static byte[] computeProjectionHash(Path path) {
        MessageDigest hasher = newSha256();
        Path absolute = path.toAbsolutePath().normalize();
        visitProjection(absolute, hasher);
        return hasher.digest();
    }

    private static void visitProjection(Path directory, MessageDigest hasher) {
        List<Path> children = listChildren(directory);
        List<String> names = new ArrayList<>();
        for (Path child : children) {
            names.add(child.getFileName().toString());
        }
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            hasher.update(name.getBytes(StandardCharsets.UTF_8));
            hasher.update((byte) 0);
            Path child = directory.resolve(name);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            if (attrs.isDirectory()) {
                hasher.update("directory".getBytes(StandardCharsets.UTF_8));
                hasher.update((byte) 0);
                visitProjection(child, hasher);
                hasher.update("end-directory".getBytes(StandardCharsets.UTF_8));
                hasher.update((byte) 0);
                continue;
            }
            // open_regular_file_fd：符号链接与非普通文件在此抛错
            SafeFiles.OpenedRegularFile opened;
            try {
                opened = SafeFiles.openRegularFile(directory, List.of(name), false);
            } catch (IOException exc) {
                throw new IllegalStateException("Skill 投影包含非法条目: " + child, exc);
            }
            hasher.update("file".getBytes(StandardCharsets.UTF_8));
            hasher.update((byte) 0);
            hasher.update((byte) (execBits(opened.file()) & 73));
            MessageDigest contentHash = newSha256();
            try {
                try (InputStream input = opened.openRead()) {
                    byte[] buffer = new byte[1024 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        contentHash.update(buffer, 0, read);
                    }
                }
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            hasher.update(contentHash.digest());
        }
    }

    /** {@code _compute_dir_hash}：按相对路径排序后逐条目累积（含执行位）。 */
    static String computeDirHash(Path sourceDir) {
        MessageDigest hasher = newSha256();
        List<Path> entries = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(path -> !path.equals(sourceDir)).forEach(entries::add);
        } catch (IOException exc) {
            throw new IllegalStateException("计算 Skill 目录哈希失败: " + sourceDir, exc);
        }
        entries.sort(Comparator.comparing(path -> sourceDir.relativize(path).toString().replace('\\', '/')));
        for (Path entry : entries) {
            String relativePath = sourceDir.relativize(entry).toString().replace('\\', '/');
            hasher.update(relativePath.getBytes(StandardCharsets.UTF_8));
            hasher.update((byte) 0);
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                hasher.update("directory".getBytes(StandardCharsets.UTF_8));
                hasher.update((byte) 0);
                continue;
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                hasher.update("other".getBytes(StandardCharsets.UTF_8));
                hasher.update((byte) 0);
                continue;
            }
            hasher.update("file".getBytes(StandardCharsets.UTF_8));
            hasher.update((byte) 0);
            hasher.update((byte) (execBits(entry) & 73));
            try (InputStream input = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    hasher.update(buffer, 0, read);
                }
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            hasher.update((byte) 0);
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : hasher.digest()) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /**
     * 执行位摘要（对应 {@code stat.S_IMODE(mode) & 0o111}）。
     *
     * <p>与 POSIX 一致：属主执行位取 0o100=64、属组取 0o010=8、其他取 0o001=1
     * （位序不可改动，否则与参考实现的投影比较摘要不一致）。
     */
    private static int execBits(Path path) {
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            int bits = 0;
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                bits |= 64; // 0o100
            }
            if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) {
                bits |= 8; // 0o010
            }
            if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                bits |= 1; // 0o001
            }
            return bits;
        } catch (IOException | UnsupportedOperationException exc) {
            // 非 POSIX 文件系统退化：仅能判断当前用户可执行位（能力差异，已标注）。
            return Files.isExecutable(path) ? 64 : 0;
        }
    }

    /** {@code _replace_skill_target}：先复制到临时目录，可选校验后再原子替换。 */
    private static void replaceSkillTarget(Path targetDir, Path sourceDir, java.util.function.Consumer<Path> validate) {
        Path tempTarget = targetDir.resolveSibling("." + targetDir.getFileName() + ".tmp-" + shortUuid());
        Path trashDir = null;
        if (Files.exists(tempTarget, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeQuietly(tempTarget);
        }
        try {
            copySkillTreeNoSymlinks(sourceDir, tempTarget);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        try {
            if (validate != null) {
                validate.accept(tempTarget);
            }
            if (Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS)) {
                trashDir = targetDir.resolveSibling("." + targetDir.getFileName() + ".bak-" + shortUuid());
                moveDirectory(targetDir, trashDir);
            }
            moveDirectory(tempTarget, targetDir);
        } catch (RuntimeException exc) {
            deleteTreeQuietly(tempTarget);
            if (trashDir != null && Files.exists(trashDir, LinkOption.NOFOLLOW_LINKS) && !Files.exists(targetDir)) {
                moveDirectory(trashDir, targetDir);
            }
            throw exc;
        }
        if (trashDir != null && Files.exists(trashDir, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeQuietly(trashDir);
        }
    }

    // ---------------------------------------------------------------- 可见性查询

    /** {@code list_accessible_skills}：当前用户最终生效的共享与个人 Skill。 */
    public List<ResolvedSkill> listAccessibleSkills(User user, boolean requireEnabled) {
        List<Skill> sharedItems = listAccessibleSharedSkills(user, requireEnabled);
        List<ResolvedSkill> personalItems = listPersonalSkills(String.valueOf(user.getUid()));
        Map<String, ResolvedSkill> personalBySlug = new LinkedHashMap<>();
        for (ResolvedSkill item : personalItems) {
            personalBySlug.put(item.slug(), item);
        }

        Map<String, ResolvedSkill> effective = new LinkedHashMap<>();
        for (Skill item : sharedItems) {
            effective.put(
                    item.getSlug(),
                    resolvedSharedSkill(item, personalBySlug.containsKey(item.getSlug()), false));
        }
        for (Map.Entry<String, ResolvedSkill> entry : personalBySlug.entrySet()) {
            effective.put(entry.getKey(), entry.getValue().withOverridesShared(effective.containsKey(entry.getKey())));
        }
        return new ArrayList<>(effective.values());
    }

    /** {@code list_skill_cards_for_user}：管理页所需的共享与个人 Skill 卡片。 */
    public List<ResolvedSkill> listSkillCardsForUser(User user) {
        List<Skill> sharedItems = listVisibleSkillsForManagement(user);
        List<ResolvedSkill> personalItems = listPersonalSkills(String.valueOf(user.getUid()));
        Set<String> personalSlugs = new LinkedHashSet<>();
        for (ResolvedSkill item : personalItems) {
            personalSlugs.add(item.slug());
        }
        Set<String> sharedSlugs = new LinkedHashSet<>();
        for (Skill item : sharedItems) {
            sharedSlugs.add(item.getSlug());
        }

        List<ResolvedSkill> results = new ArrayList<>();
        for (ResolvedSkill item : personalItems) {
            results.add(item.withOverridesShared(sharedSlugs.contains(item.slug())));
        }
        for (Skill item : sharedItems) {
            results.add(resolvedSharedSkill(item, personalSlugs.contains(item.getSlug()), false));
        }
        return results;
    }

    /** {@code list_visible_skills_for_management}。 */
    public List<Skill> listVisibleSkillsForManagement(User user) {
        List<Skill> visible = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Skill item : skillRepository.listAll()) {
            if (seen.contains(item.getSlug())) {
                continue;
            }
            if (userCanManageSkill(user, item)
                    || (Boolean.TRUE.equals(item.getEnabled()) && userCanAccessSkill(user, item, true))) {
                visible.add(item);
                seen.add(item.getSlug());
            }
        }
        return visible;
    }

    /** {@code list_skills}。 */
    public List<Skill> listSkills() {
        return skillRepository.listAll();
    }

    /** {@code list_skill_slugs}。 */
    public List<String> listSkillSlugs(User user) {
        if (user != null) {
            return listSharedSkillSlugs(user);
        }
        List<String> result = new ArrayList<>();
        for (Skill item : skillRepository.listEnabled()) {
            if (item.getSlug() != null) {
                result.add(item.getSlug());
            }
        }
        return result;
    }

    /** {@code get_skill_dependency_options}。 */
    public Map<String, Object> getSkillDependencyOptions(User user, String slug) {
        List<String> skillSlugs = listSkillSlugs(user);
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (Map<String, Object> tool : ToolkitsService.getToolMetadata(null)) {
            String toolSlug = String.valueOf(tool.get("slug"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slug", toolSlug);
            Object name = tool.get("name");
            entry.put("name", name == null ? toolSlug : name);
            toolList.add(entry);
        }
        List<String> mcpNames = mcpService.getEnabledMcpServerSlugs();

        if (slug != null && !slug.isEmpty()) {
            List<String> filtered = new ArrayList<>();
            for (String item : skillSlugs) {
                if (!item.equals(slug)) {
                    filtered.add(item);
                }
            }
            skillSlugs = filtered;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolList);
        result.put("mcps", mcpNames);
        result.put("skills", skillSlugs);
        return result;
    }

    /** {@code _list_accessible_shared_skills}：按现有共享范围返回用户可访问的数据库 Skill。 */
    public List<Skill> listAccessibleSharedSkills(User user, boolean requireEnabled) {
        List<Skill> items = requireEnabled ? skillRepository.listEnabled() : skillRepository.listAll();
        List<Skill> result = new ArrayList<>();
        for (Skill item : items) {
            if (userCanAccessSkill(user, item, requireEnabled)) {
                result.add(item);
            }
        }
        return result;
    }

    /** {@code _list_shared_skill_slugs}：依赖配置可引用的共享 Skill slug。 */
    private List<String> listSharedSkillSlugs(User user) {
        List<String> result = new ArrayList<>();
        for (Skill item : listAccessibleSharedSkills(user, true)) {
            if (item.getSlug() != null) {
                result.add(item.getSlug());
            }
        }
        return result;
    }

    /** {@code _get_all_tool_names}。 */
    private static List<String> getAllToolNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : ToolkitsService.getToolMetadata(null)) {
            names.add(String.valueOf(tool.get("slug")));
        }
        return names;
    }

    // ---------------------------------------------------------------- 依赖校验与更新

    /** {@code _validate_dependencies} 的返回三元组。 */
    private record ValidatedDependencies(List<String> tools, List<String> mcps, List<String> skills) {}

    /** {@code _validate_dependencies}。 */
    private ValidatedDependencies validateDependencies(
            Skill parent,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies,
            Map<String, Skill> availableSkills) {
        List<String> tools = normalizeStringList(toolDependencies);
        List<String> mcps = normalizeStringList(mcpDependencies);
        List<String> skills = normalizeStringList(skillDependencies);

        Set<String> availableTools = new LinkedHashSet<>(getAllToolNames());
        List<String> invalidTools = new ArrayList<>();
        for (String name : tools) {
            if (!availableTools.contains(name)) {
                invalidTools.add(name);
            }
        }
        if (!invalidTools.isEmpty()) {
            throw new IllegalArgumentException("存在无效工具依赖: " + String.join(", ", invalidTools));
        }

        Set<String> availableMcps = new LinkedHashSet<>(mcpService.getEnabledMcpServerSlugs());
        List<String> invalidMcps = new ArrayList<>();
        for (String name : mcps) {
            if (!availableMcps.contains(name)) {
                invalidMcps.add(name);
            }
        }
        if (!invalidMcps.isEmpty()) {
            throw new IllegalArgumentException("存在无效 MCP 依赖: " + String.join(", ", invalidMcps));
        }

        List<String> invalidSkills = new ArrayList<>();
        for (String name : skills) {
            if (!availableSkills.containsKey(name)) {
                invalidSkills.add(name);
            }
        }
        if (!invalidSkills.isEmpty()) {
            throw new IllegalArgumentException("存在无效 skill 依赖: " + String.join(", ", invalidSkills));
        }

        if (skills.contains(parent.getSlug())) {
            throw new IllegalArgumentException("skill_dependencies 不允许包含自身");
        }

        List<String> forbidden = new ArrayList<>();
        for (String name : skills) {
            if (!canSkillDependOn(parent, availableSkills.get(name))) {
                forbidden.add(name);
            }
        }
        if (!forbidden.isEmpty()) {
            throw new IllegalArgumentException("存在权限范围不匹配的 skill 依赖: " + String.join(", ", forbidden));
        }

        return new ValidatedDependencies(tools, mcps, skills);
    }

    /** {@code update_skill_dependencies}。 */
    @Transactional
    public Skill updateSkillDependencies(
            String slug,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies,
            User operator) {
        Skill item = getManageableSkillOrRaise(operator, slug);
        ensureNonBuiltin(item);
        Map<String, Skill> availableSkills = new LinkedHashMap<>();
        for (Skill skill : listAccessibleSharedSkills(operator, true)) {
            availableSkills.put(skill.getSlug(), skill);
        }
        ValidatedDependencies validated = validateDependencies(
                item, toolDependencies, mcpDependencies, skillDependencies, availableSkills);
        return skillRepository.updateDependencies(
                item, validated.tools(), validated.mcps(), validated.skills(), operator.getUid());
    }

    // ---------------------------------------------------------------- SKILL.md 解析

    /** {@code _validate_skill_slug_value}。 */
    private static String validateSkillSlugValue(String slug, String fieldName) {
        String value = slug == null ? "" : slug.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md frontmatter 缺少 " + fieldName);
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("SKILL.md frontmatter." + fieldName + " 长度不能超过 128");
        }
        if (!SKILL_SLUG_PATTERN.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "SKILL.md frontmatter." + fieldName + " 必须是小写字母/数字/短横线，且不能连续短横线");
        }
        return value;
    }

    /** {@code _validate_skill_display_name}。 */
    private static String validateSkillDisplayName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md frontmatter 缺少 name");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("SKILL.md frontmatter.name 长度不能超过 128");
        }
        return value;
    }

    /** {@code _split_frontmatter} 的返回二元组。 */
    private record Frontmatter(String raw, String body) {}

    /** {@code _split_frontmatter}。 */
    private static Frontmatter splitFrontmatter(String content) {
        if (!content.startsWith("---")) {
            throw new IllegalArgumentException("SKILL.md 缺少有效 frontmatter（--- ... ---）");
        }
        List<String> lines = splitLinesKeepEnds(content);
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            throw new IllegalArgumentException("SKILL.md 缺少有效 frontmatter（--- ... ---）");
        }

        StringBuilder frontmatter = new StringBuilder();
        int bodyStart = -1;
        int index = 1;
        for (; index < lines.size(); index++) {
            String line = lines.get(index);
            if ("---".equals(line.strip())) {
                bodyStart = index + 1;
                break;
            }
            frontmatter.append(line);
        }
        if (bodyStart < 0) {
            throw new IllegalArgumentException("SKILL.md 缺少有效 frontmatter（--- ... ---）");
        }

        StringBuilder body = new StringBuilder();
        for (int i = bodyStart; i < lines.size(); i++) {
            body.append(lines.get(i));
        }
        return new Frontmatter(frontmatter.toString(), body.toString());
    }

    /** Python {@code str.splitlines(keepends=True)} 语义。 */
    private static List<String> splitLinesKeepEnds(String content) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            current.append(ch);
            if (ch == '\n') {
                lines.add(current.toString());
                current.setLength(0);
            } else if (ch == '\r') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    continue;
                }
                lines.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** {@code _parse_skill_markdown} 的返回四元组。 */
    private record ParsedSkillMarkdown(String slug, String name, String description, Map<String, Object> data) {}

    /** {@code _parse_skill_markdown}。 */
    private static ParsedSkillMarkdown parseSkillMarkdown(String content) {
        Frontmatter frontmatter = splitFrontmatter(content);
        Object loaded;
        try {
            loaded = newYamlLoader().load(frontmatter.raw());
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("SKILL.md frontmatter YAML 解析失败: " + exc.getMessage(), exc);
        }
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("SKILL.md frontmatter 必须是对象");
        }
        Map<String, Object> data = toStringKeyedMap(rawMap);

        String name = validateSkillDisplayName(asString(data.get("name")));
        String rawSlug = asString(data.get("slug")).strip();
        String slug = rawSlug.isEmpty()
                ? validateSkillSlugValue(name, "name")
                : validateSkillSlugValue(rawSlug, "slug");
        String description = asString(data.get("description")).strip();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md frontmatter 缺少 description");
        }
        return new ParsedSkillMarkdown(slug, name, description, data);
    }

    /** {@code _rewrite_frontmatter_slug}。 */
    private static String rewriteFrontmatterSlug(String content, String newSlug) {
        Frontmatter frontmatter = splitFrontmatter(content);
        Object loaded = newYamlLoader().load(frontmatter.raw());
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("SKILL.md frontmatter 必须是对象");
        }
        Map<String, Object> data = toStringKeyedMap(rawMap);
        Object existing = data.get("slug");
        if (existing != null && !asString(existing).isEmpty()) {
            data.put("slug", newSlug);
        } else {
            data.put("name", newSlug);
        }
        String dumped = dumpYamlPreservingOrder(data).strip();
        return "---\n" + dumped + "\n---\n" + frontmatter.body();
    }

    /** {@code yaml.safe_load} 等价物（SafeConstructor + 标准解析）。 */
    private static Yaml newYamlLoader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * {@code yaml.safe_dump(data, sort_keys=False, allow_unicode=True)} 等价物：
     * 保留键的插入顺序（LinkedHashMap 语义）、不折行、允许非 ASCII 直出。
     */
    private static String dumpYamlPreservingOrder(Map<String, Object> data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        options.setSplitLines(false);
        options.setIndent(2);
        Representer representer = new Representer(options);
        return new Yaml(representer, options).dump(data);
    }

    private static Map<String, Object> toStringKeyedMap(Map<?, ?> raw) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            data.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return data;
    }

    private static String asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        return String.valueOf(value);
    }

    // ---------------------------------------------------------------- ZIP 校验

    /** {@code _validate_zip_paths}。 */
    private static void validateZipPaths(ZipFile zipFile) {
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            PosixPathLite pure = PosixPathLite.parse(name);
            if (pure.isAbsolute()) {
                throw new IllegalArgumentException("ZIP 包含不安全绝对路径: " + name);
            }
            if (pure.partsContain("..")) {
                throw new IllegalArgumentException("ZIP 包含路径穿越片段: " + name);
            }
        }
    }

    /** {@code _generate_available_slug}。 */
    private String generateAvailableSlug(String baseSlug) {
        Path root = getSkillsRootDir();
        if (!skillRepository.existsSlug(baseSlug) && !Files.exists(root.resolve(baseSlug))) {
            return baseSlug;
        }
        int index = 2;
        while (true) {
            String candidate = baseSlug + "-v" + index;
            if (!skillRepository.existsSlug(candidate) && !Files.exists(root.resolve(candidate))) {
                return candidate;
            }
            index++;
        }
    }

    /** {@code parse_skill_dir_metadata}。 */
    public static JSONObject parseSkillDirMetadata(Path sourceSkillDir) throws IOException {
        Path skillMdPath = sourceSkillDir.resolve("SKILL.md");
        if (!Files.exists(skillMdPath) || !Files.isRegularFile(skillMdPath)) {
            throw new IllegalArgumentException("技能目录缺少根级 SKILL.md");
        }
        String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
        ParsedSkillMarkdown parsed = parseSkillMarkdown(content);
        JSONObject metadata = new JSONObject();
        metadata.put("slug", parsed.slug());
        metadata.put("name", parsed.name());
        metadata.put("description", parsed.description());
        metadata.put("tool_dependencies", normalizeStringList(asList(parsed.data().get("tool_dependencies"))));
        metadata.put("mcp_dependencies", normalizeStringList(asList(parsed.data().get("mcp_dependencies"))));
        metadata.put("skill_dependencies", normalizeStringList(asList(parsed.data().get("skill_dependencies"))));
        return metadata;
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return null;
    }

    // ---------------------------------------------------------------- 个人 Skill

    /** {@code _resolve_personal_skill_dir}：安全解析固定根下的个人 Skill 目录。 */
    private static Path resolvePersonalSkillDir(Path root, String slug) {
        if (!isValidSkillSlug(slug)) {
            throw new IllegalArgumentException("无效 skill slug");
        }
        Path target = root.resolve(slug);
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("个人 Skill 路径非法");
        }
        return target;
    }

    /** {@code list_personal_skills}：直接扫描个人 Skill 持久目录。 */
    public List<ResolvedSkill> listPersonalSkills(String uid) {
        return scanPersonalSkills(uid);
    }

    /** {@code install_personal_skill_dir}。 */
    public ResolvedSkill installPersonalSkillDir(String uid, Path sourceDir, String expectedSlug) {
        return installPersonalSkillDirSync(uid, sourceDir, expectedSlug);
    }

    /** {@code read_personal_skill_file}。 */
    public Map<String, Object> readPersonalSkillFile(String uid, String slug, String relativePath) throws IOException {
        Path skillDir = resolvePersonalSkillDir(personalSkillsRoot(uid), slug);
        ResolvedRelative resolved = resolveRelativePath(skillDir, relativePath, false);
        Path target = resolved.target();
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (!isTextPath(target)) {
            throw new IllegalArgumentException("仅支持读取文本文件");
        }
        String content;
        try {
            content = readUtf8Strict(target);
        } catch (java.nio.charset.CharacterCodingException exc) {
            throw new IllegalArgumentException("文件编码不支持（仅支持 UTF-8）");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", resolved.normalizedPath());
        result.put("content", content);
        return result;
    }

    /** {@code delete_personal_skill}。 */
    public void deletePersonalSkill(String uid, String slug) {
        Path skillDir = resolvePersonalSkillDir(personalSkillsRoot(uid), slug);
        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("个人 Skill 不存在");
        }
        deleteTreeQuietly(skillDir);
    }

    /**
     * {@code enable_personal_skills_for_agent_config}：为显式 Skill 白名单追加个人 Skill；
     * 全部模式（{@code context.skills} 未配置）无需写入，直接返回 true。
     *
     * <p>参考实现在 agents/toolkits/buildin/install_skill.py 的安装流程里被调用，
     * 本工程按同一语义落在此处（会话归属、智能体归属两处鉴权逐条照搬）。
     */
    @Transactional
    public boolean enablePersonalSkillsForAgentConfig(String threadId, String uid, List<String> skillSlugs) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation == null || !String.valueOf(conversation.getUid()).equals(String.valueOf(uid))) {
            return false;
        }
        Agent agent = agentRepository.getBySlug(conversation.getAgentId());
        if (agent == null || !String.valueOf(uid).equals(String.valueOf(agent.getCreatedBy()))) {
            return false;
        }

        JSONObject agentConfig = RepoValues.parseObject(agent.getConfigJson());
        if (agentConfig == null) {
            agentConfig = new JSONObject();
        }
        JSONObject context = agentConfig.getJSONObject("context");
        if (context == null) {
            context = new JSONObject();
        }
        Object configuredSkills = context.get("skills");
        if (configuredSkills == null) {
            return true;
        }

        List<String> selectedSkills =
                normalizeStringList(configuredSkills instanceof List<?> list ? list : List.of());
        List<String> merged = new ArrayList<>(selectedSkills);
        merged.addAll(skillSlugs == null ? List.of() : skillSlugs);
        List<String> updatedSkills = normalizeStringList(merged);
        if (updatedSkills.equals(selectedSkills)) {
            return true;
        }

        JSONObject contextPatch = new JSONObject();
        contextPatch.put("skills", updatedSkills);
        JSONObject configPatch = new JSONObject();
        configPatch.put("context", contextPatch);

        Map<String, Collection<String>> resourceAccess = new LinkedHashMap<>();
        resourceAccess.put("skills", new LinkedHashSet<>(skillSlugs == null ? List.of() : skillSlugs));

        agentRepository.update(
                agent,
                null,
                null,
                null,
                null,
                configPatch,
                resourceAccess,
                null,
                null,
                String.valueOf(uid),
                null);
        return true;
    }

    /** {@code _resolved_shared_skill}：把数据库 Skill 适配为统一的有效 Skill 描述。 */
    private static ResolvedSkill resolvedSharedSkill(Skill item, boolean shadowedByPersonal, boolean overridesShared) {
        String sourceScope = isBuiltinSkill(item) ? "builtin" : "shared";
        return new ResolvedSkill(
                item.getId(),
                item.getSlug(),
                item.getName(),
                item.getDescription(),
                item.getSourceType(),
                sourceScope,
                resolveSkillDir(item),
                Boolean.TRUE.equals(item.getEnabled()),
                item.getCreatedBy(),
                ResourcePermissions.normalizePermissionConfig(ShareableResource.of(item).shareConfig()),
                normalizeStringListFromJson(item.getToolDependencies()),
                normalizeStringListFromJson(item.getMcpDependencies()),
                normalizeStringListFromJson(item.getSkillDependencies()),
                item.getVersion(),
                item.getContentHash(),
                overridesShared,
                shadowedByPersonal);
    }

    /** {@code _resolved_personal_skill}：个人目录元数据适配为不含共享语义的有效 Skill 描述。 */
    private static ResolvedSkill resolvedPersonalSkill(String uid, Path root, JSONObject metadata) {
        String slug = metadata.getString("slug");
        if (!isValidSkillSlug(slug)) {
            throw new IllegalArgumentException("个人 Skill 包含非法 slug");
        }
        return new ResolvedSkill(
                "personal:" + slug,
                slug,
                metadata.getString("name"),
                metadata.getString("description"),
                PERSONAL_SKILL_SOURCE_TYPE,
                PERSONAL_SKILL_SOURCE_TYPE,
                root.resolve(slug),
                true,
                uid,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                false,
                false);
    }

    /** {@code _scan_personal_skills}：扫描并校验个人 Skill 的直接子目录。 */
    private static List<ResolvedSkill> scanPersonalSkills(String uid) {
        List<ResolvedSkill> items = new ArrayList<>();
        Path root = personalSkillsRoot(uid);
        for (Path entry : listChildren(root)) {
            String name = entry.getFileName().toString();
            if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    || !isValidSkillSlug(name)) {
                log.warn("跳过非法个人 Skill 目录: uid=" + uid + ", name=" + name);
                continue;
            }
            if (dirContainsSymlink(entry)) {
                log.warn("跳过包含符号链接的个人 Skill: uid=" + uid + ", slug=" + name);
                continue;
            }
            try {
                JSONObject metadata = parseSkillDirMetadata(entry);
                if (!name.equals(metadata.getString("slug"))) {
                    throw new IllegalArgumentException("目录名必须与 SKILL.md slug 一致");
                }
                items.add(resolvedPersonalSkill(uid, root, metadata));
            } catch (IOException | RuntimeException exc) {
                log.warn("跳过无法解析的个人 Skill: uid=" + uid + ", slug=" + name + ", error=" + exc.getMessage());
            }
        }
        return items;
    }

    /** {@code _install_personal_skill_dir_sync}：将一个 Skill 原子复制到个人目录。 */
    private static ResolvedSkill installPersonalSkillDirSync(String uid, Path sourceDir, String expectedSlug) {
        Path source = sourceDir.toAbsolutePath().normalize();
        Path root = personalSkillsRoot(uid);
        Path tempTarget = root.resolve(".install.tmp-" + shortUuid());
        Path targetDir = null;
        JSONObject metadata = null;
        try {
            metadata = copySkillSnapshot(source, tempTarget, expectedSlug, null);
            String slug = metadata.getString("slug");
            targetDir = root.resolve(slug);
            if (Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetDir)) {
                throw new IllegalArgumentException("个人 Skill 源已存在同名 Skill: " + slug);
            }
            moveDirectory(tempTarget, targetDir);
        } catch (IOException exc) {
            if (targetDir == null || !Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            throw new IllegalArgumentException(
                    "个人 Skill 源已存在同名 Skill: " + (metadata == null ? "" : metadata.getString("slug")));
        } finally {
            if (Files.exists(tempTarget, LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeQuietly(tempTarget);
            }
        }
        return resolvedPersonalSkill(uid, root, metadata);
    }

    // ---------------------------------------------------------------- 草稿暂存

    /** {@code _stage_skill_draft_item}。 */
    private JSONObject stageSkillDraftItem(Path sourceSkillDir, Path draftItemsDir) throws IOException {
        String itemId = UUID.randomUUID().toString().replace("-", "");
        Path itemDir = draftItemsDir.resolve(itemId);
        JSONObject parsed = copySkillSnapshot(sourceSkillDir, itemDir, null, null);
        String originalSlug = parsed.getString("slug");
        String finalSlug = generateAvailableSlug(originalSlug);

        JSONObject item = new JSONObject();
        item.put("draft_item_id", itemId);
        item.put("source_dir", "items/" + itemId);
        item.put("slug", finalSlug);
        item.put("name", parsed.getString("name"));
        item.put("original_name", originalSlug);
        item.put("description", parsed.getString("description"));
        item.put("tool_dependencies", parsed.get("tool_dependencies"));
        item.put("mcp_dependencies", parsed.get("mcp_dependencies"));
        item.put("skill_dependencies", parsed.get("skill_dependencies"));
        List<String> warnings = new ArrayList<>();
        if (!finalSlug.equals(originalSlug)) {
            warnings.add("原始 slug " + originalSlug + " 已存在，将安装为 " + finalSlug);
        }
        item.put("warnings", warnings);
        item.put("success", true);
        return item;
    }

    /** {@code _build_default_share_payload}。 */
    private static Map<String, Object> buildDefaultSharePayload(User operator) {
        Set<String> allowed = new LinkedHashSet<>(getAllowedSkillAccessLevels(operator));
        JSONObject defaultShareConfig = normalizeSkillShareConfig(null, operator.getUid(), "upload", allowed);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("default_share_config", defaultShareConfig);
        payload.put("allowed_access_levels", getAllowedSkillAccessLevels(operator));
        return payload;
    }

    // ---------------------------------------------------------------- 路径解析与树

    /** {@code _resolve_skill_dir}。 */
    static Path resolveSkillDir(Skill item) {
        Path dirPath = Path.of(item.getDirPath());
        if (dirPath.isAbsolute()) {
            return dirPath;
        }
        return RuntimePaths.getSkillDataDir().resolve(dirPath).toAbsolutePath().normalize();
    }

    /** {@code _resolve_relative_path} 的返回二元组。 */
    private record ResolvedRelative(Path target, String normalizedPath) {}

    /** {@code _resolve_relative_path}。 */
    private static ResolvedRelative resolveRelativePath(Path skillDir, String relativePath, boolean allowRoot) {
        String rel = relativePath == null ? "" : relativePath.strip().replace('\\', '/');
        rel = stripLeadingSlashes(rel);
        if (rel.isEmpty() && !allowRoot) {
            throw new IllegalArgumentException("path 不能为空");
        }
        PosixPathLite pure = rel.isEmpty() ? PosixPathLite.parse(".") : PosixPathLite.parse(rel);
        if (pure.partsContain("..")) {
            throw new IllegalArgumentException("非法路径：不允许上级路径引用");
        }

        Path target = SafeFiles.ensureWithinRoot(
                skillDir.resolve(rel.isEmpty() ? "." : rel).toAbsolutePath().normalize(), skillDir, "非法路径：越界访问被拒绝");
        return new ResolvedRelative(target, rel);
    }

    private static String stripLeadingSlashes(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '/') {
            index++;
        }
        return value.substring(index);
    }

    /** {@code _is_text_path}。 */
    private static boolean isTextPath(Path path) {
        if ("SKILL.md".equals(path.getFileName().toString())) {
            return true;
        }
        String suffix = PosixPathLite.suffixOf(path.getFileName().toString());
        return suffix != null && TEXT_FILE_EXTENSIONS.contains(suffix.toLowerCase());
    }

    /** {@code _build_tree}。 */
    private static List<Map<String, Object>> buildTree(Path path, Path baseDir) {
        List<Map<String, Object>> children = new ArrayList<>();
        List<Path> entries = listChildren(path);
        entries.sort(Comparator.comparing((Path child) -> Files.isDirectory(child) ? 1 : 0)
                .thenComparing(child -> child.getFileName().toString().toLowerCase()));
        for (Path child : entries) {
            String rel = baseDir.relativize(child).toString().replace('\\', '/');
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", child.getFileName().toString());
            node.put("path", rel);
            if (Files.isDirectory(child)) {
                node.put("is_dir", true);
                node.put("children", buildTree(child, baseDir));
            } else {
                node.put("is_dir", false);
            }
            children.add(node);
        }
        return children;
    }

    // ---------------------------------------------------------------- 上传与安装草稿

    /** {@code prepare_skill_upload}。 */
    public JSONObject prepareSkillUpload(String filename, byte[] fileBytes, User operator) throws IOException {
        String normalizedFilename = filename == null ? "" : filename.toLowerCase();
        boolean isZipUpload = normalizedFilename.endsWith(".zip");
        boolean isSkillMdUpload = normalizedFilename.endsWith("skill.md");
        if (!isZipUpload && !isSkillMdUpload) {
            throw new IllegalArgumentException("仅支持上传 .zip 或 SKILL.md 文件");
        }

        Path draftDir = getSkillDraftsRootDir().resolve(UUID.randomUUID().toString());
        Path itemsDir = draftDir.resolve("items");
        Files.createDirectories(draftDir);
        Files.createDirectories(itemsDir);

        Path tempRoot = Files.createTempDirectory(getSkillsRootDir().getParent(), ".skill-prepare-");
        try {
            Path extractDir = tempRoot.resolve("extract");
            Files.createDirectories(extractDir);
            Path sourceSkillDir;
            if (isZipUpload) {
                Path zipPath = tempRoot.resolve("upload.zip");
                Files.write(zipPath, fileBytes);
                try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
                    validateZipPaths(zipFile);
                    extractZip(zipFile, extractDir);
                }
                List<Path> skillMdFiles = new ArrayList<>();
                try (java.util.stream.Stream<Path> stream = Files.walk(extractDir)) {
                    stream.filter(p -> "SKILL.md".equals(p.getFileName().toString())).forEach(skillMdFiles::add);
                }
                if (skillMdFiles.size() != 1) {
                    throw new IllegalArgumentException("ZIP 必须且只能包含一个技能（检测到一个 SKILL.md）");
                }
                sourceSkillDir = skillMdFiles.get(0).getParent();
            } else {
                sourceSkillDir = extractDir;
                Files.write(sourceSkillDir.resolve("SKILL.md"), fileBytes);
            }

            JSONObject item = stageSkillDraftItem(sourceSkillDir, itemsDir);

            JSONObject data = new JSONObject();
            data.put("draft_id", draftDir.getFileName().toString());
            data.put("created_by", operator.getUid());
            data.put("source_type", "upload");
            data.put("source", filename);
            data.put("created_at", System.currentTimeMillis() / 1000.0);
            data.put("expires_at", System.currentTimeMillis() / 1000.0 + SKILL_DRAFT_TTL_SECONDS);
            data.put("items", new ArrayList<>(List.of(item)));
            for (Map.Entry<String, Object> entry : buildDefaultSharePayload(operator).entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }
            Files.writeString(
                    draftDir.resolve("metadata.json"),
                    JSON.toJSONString(data, JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat),
                    StandardCharsets.UTF_8);
            return data;
        } catch (RuntimeException | IOException exc) {
            deleteTreeQuietly(draftDir);
            throw exc;
        } finally {
            deleteTreeQuietly(tempRoot);
        }
    }

    /** {@code prepare_remote_skill_install}。 */
    public JSONObject prepareRemoteSkillInstall(String source, List<String> skills, User operator) throws IOException {
        Path draftDir = getSkillDraftsRootDir().resolve(UUID.randomUUID().toString());
        Path itemsDir = draftDir.resolve("items");
        Files.createDirectories(draftDir);
        Files.createDirectories(itemsDir);

        SkillRemoteInstall.RemoteSkillsPreparation preparation = null;
        try {
            preparation = skillRemoteInstall.prepareRemoteSkillsBatch(source, skills);
            List<JSONObject> items = new ArrayList<>();
            for (JSONObject result : preparation.results()) {
                String slug = result.getString("slug") == null ? "" : result.getString("slug");
                if (!Boolean.TRUE.equals(result.getBoolean("success"))) {
                    JSONObject item = new JSONObject();
                    item.put("slug", slug);
                    item.put("success", false);
                    item.put("error", result.getString("error") == null ? "安装失败" : result.getString("error"));
                    items.add(item);
                    continue;
                }
                try {
                    items.add(stageSkillDraftItem(Path.of(result.getString("source_dir")), itemsDir));
                } catch (IOException | RuntimeException exc) {
                    JSONObject item = new JSONObject();
                    item.put("slug", slug);
                    item.put("success", false);
                    item.put("error", exc.getMessage());
                    items.add(item);
                }
            }

            JSONObject data = new JSONObject();
            data.put("draft_id", draftDir.getFileName().toString());
            data.put("created_by", operator.getUid());
            data.put("source_type", "remote");
            data.put("source", source);
            data.put("created_at", System.currentTimeMillis() / 1000.0);
            data.put("expires_at", System.currentTimeMillis() / 1000.0 + SKILL_DRAFT_TTL_SECONDS);
            data.put("items", items);
            for (Map.Entry<String, Object> entry : buildDefaultSharePayload(operator).entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }
            Files.writeString(
                    draftDir.resolve("metadata.json"),
                    JSON.toJSONString(data, JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat),
                    StandardCharsets.UTF_8);
            return data;
        } catch (RuntimeException | IOException exc) {
            deleteTreeQuietly(draftDir);
            throw exc;
        } finally {
            if (preparation != null) {
                preparation.cleanup().run();
            }
        }
    }

    /** {@code confirm_skill_install_draft}。 */
    @Transactional
    public List<JSONObject> confirmSkillInstallDraft(
            String draftId, JSONObject shareConfig, List<String> slugs, User operator) {
        DraftSelection selection = loadAndSelectDraftItems(draftId, slugs, operator);
        String sourceType = selection.data().getString("source_type");

        JSONObject normalizedShareConfig = normalizeSkillShareConfig(
                shareConfig,
                operator.getUid(),
                sourceType,
                new LinkedHashSet<>(getAllowedSkillAccessLevels(operator)));

        Path skillsRoot = getSkillsRootDir();
        Path draftRoot = selection.draftDir().toAbsolutePath().normalize();
        List<JSONObject> results = new ArrayList<>();

        for (JSONObject draftItem : selection.draftItems()) {
            String slug = draftItem.getString("slug") == null ? "" : draftItem.getString("slug").strip();
            Object successFlag = draftItem.get("success");
            if (Boolean.FALSE.equals(successFlag) || "false".equals(String.valueOf(successFlag))) {
                results.add(installFailure(slug, draftItem.getString("error") == null ? "安装失败" : draftItem.getString("error")));
                continue;
            }

            if (!isValidSkillSlug(slug)) {
                results.add(installFailure(slug, "无效 skill slug"));
                continue;
            }
            if (skillRepository.existsSlug(slug) || Files.exists(skillsRoot.resolve(slug))) {
                results.add(installFailure(slug, "Skill slug 已被占用，请重新解析安装"));
                continue;
            }

            String sourceDirText = draftItem.getString("source_dir") == null ? "" : draftItem.getString("source_dir");
            Path sourceDir = draftRoot.resolve(sourceDirText).toAbsolutePath().normalize();
            if (!sourceDir.startsWith(draftRoot)) {
                results.add(installFailure(slug, "安装草稿路径非法"));
                continue;
            }

            Path tempTarget = skillsRoot.resolve("." + slug + ".tmp-" + shortUuid());
            Path finalDir = skillsRoot.resolve(slug);
            boolean published = false;
            try {
                JSONObject parsed = copySkillSnapshot(sourceDir, tempTarget, null, slug);
                if (Files.exists(finalDir)) {
                    throw new IllegalArgumentException("Skill slug 已被占用，请重新解析安装");
                }
                moveDirectory(tempTarget, finalDir);
                published = true;
                Skill item = skillRepository.create(
                        slug,
                        parsed.getString("name"),
                        parsed.getString("description"),
                        sourceType,
                        normalizeStringList(parsed.getList("tool_dependencies", String.class)),
                        normalizeStringList(parsed.getList("mcp_dependencies", String.class)),
                        normalizeStringList(parsed.getList("skill_dependencies", String.class)),
                        buildBuiltinSkillDirPath(slug),
                        normalizedShareConfig,
                        true,
                        null,
                        null,
                        operator.getUid());
                JSONObject result = new JSONObject();
                result.put("slug", item.getSlug());
                result.put("success", true);
                result.put("skill", skillToDict(item));
                results.add(result);
            } catch (IOException | RuntimeException exc) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus()
                        .setRollbackOnly();
                if (published) {
                    deleteTreeQuietly(finalDir);
                }
                results.add(installFailure(slug, exc.getMessage()));
            } finally {
                if (Files.exists(tempTarget, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTreeQuietly(tempTarget);
                }
            }
        }

        boolean anySuccess = false;
        for (JSONObject item : results) {
            if (Boolean.TRUE.equals(item.getBoolean("success"))) {
                anySuccess = true;
                break;
            }
        }
        if (anySuccess) {
            deleteTreeQuietly(selection.draftDir());
        }
        return results;
    }

    private static JSONObject installFailure(String slug, String error) {
        JSONObject result = new JSONObject();
        result.put("slug", slug);
        result.put("success", false);
        result.put("error", error);
        return result;
    }

    /** {@code confirm_personal_skill_install_draft}。 */
    public List<JSONObject> confirmPersonalSkillInstallDraft(String draftId, List<String> slugs, User operator) {
        DraftSelection selection = loadAndSelectDraftItems(draftId, slugs, operator);
        Path draftRoot = selection.draftDir().toAbsolutePath().normalize();

        List<JSONObject> results = new ArrayList<>();
        for (JSONObject draftItem : selection.draftItems()) {
            String requestedSlug = draftItem.getString("slug") == null ? "" : draftItem.getString("slug").strip();
            String originalName = draftItem.getString("original_name");
            String personalSlug =
                    (originalName == null || originalName.strip().isEmpty() ? requestedSlug : originalName).strip();
            Object successFlag = draftItem.get("success");
            if (Boolean.FALSE.equals(successFlag) || "false".equals(String.valueOf(successFlag))) {
                results.add(personalInstallResult(
                        personalSlug,
                        requestedSlug,
                        false,
                        draftItem.getString("error") == null ? "安装失败" : draftItem.getString("error"),
                        null));
                continue;
            }
            if (!isValidSkillSlug(personalSlug)) {
                results.add(personalInstallResult(personalSlug, requestedSlug, false, "无效 skill slug", null));
                continue;
            }

            String sourceDirText = draftItem.getString("source_dir") == null ? "" : draftItem.getString("source_dir");
            Path sourceDir = draftRoot.resolve(sourceDirText).toAbsolutePath().normalize();
            if (!sourceDir.startsWith(draftRoot)) {
                results.add(personalInstallResult(personalSlug, requestedSlug, false, "安装草稿路径非法", null));
                continue;
            }
            try {
                ResolvedSkill item =
                        installPersonalSkillDir(String.valueOf(operator.getUid()), sourceDir, personalSlug);
                results.add(personalInstallResult(item.slug(), requestedSlug, true, null, item.toDict()));
            } catch (RuntimeException exc) {
                results.add(personalInstallResult(personalSlug, requestedSlug, false, exc.getMessage(), null));
            }
        }

        boolean anySuccess = false;
        for (JSONObject item : results) {
            if (Boolean.TRUE.equals(item.getBoolean("success"))) {
                anySuccess = true;
                break;
            }
        }
        if (anySuccess) {
            deleteTreeQuietly(selection.draftDir());
        }
        return results;
    }

    private static JSONObject personalInstallResult(
            String slug, String requestedSlug, boolean success, String error, Map<String, Object> skill) {
        JSONObject result = new JSONObject();
        result.put("slug", slug);
        result.put("requested_slug", requestedSlug);
        result.put("success", success);
        if (!success) {
            result.put("error", error);
        } else {
            result.put("skill", skill);
        }
        return result;
    }

    /** {@code discard_skill_install_draft}。 */
    public void discardSkillInstallDraft(String draftId, User operator) {
        DraftLoad loaded = loadSkillDraft(draftId);
        String createdBy = loaded.data().getString("created_by");
        if (!java.util.Objects.equals(createdBy, operator.getUid()) && !ADMIN_ROLES.contains(operator.getRole())) {
            throw new IllegalArgumentException("无权删除该安装草稿");
        }
        deleteTreeQuietly(loaded.draftDir());
    }

    // ---------------------------------------------------------------- 取用与读写

    /** {@code get_skill_or_raise}。 */
    public Skill getSkillOrRaise(String slug) {
        String candidate = slug == null ? "" : slug.strip();
        if (!isValidSkillSlug(candidate)) {
            throw new IllegalArgumentException("无效 skill slug");
        }
        Skill item = skillRepository.getBySlug(candidate, false);
        if (item == null) {
            throw new IllegalArgumentException("技能 '" + candidate + "' 不存在");
        }
        return item;
    }

    /** {@code get_management_readable_skill_or_raise}。 */
    public Skill getManagementReadableSkillOrRaise(User user, String slug) {
        Skill item = getSkillOrRaise(slug);
        if (!userCanManageSkill(user, item) && !userCanAccessSkill(user, item, true)) {
            throw new IllegalArgumentException("技能 '" + slug + "' 不存在或无权访问");
        }
        return item;
    }

    /** {@code get_manageable_skill_or_raise}。 */
    public Skill getManageableSkillOrRaise(User user, String slug) {
        Skill item = getSkillOrRaise(slug);
        if (!userCanManageSkill(user, item)) {
            throw new IllegalArgumentException("技能 '" + slug + "' 不存在或无权管理");
        }
        return item;
    }

    /** {@code get_skill_tree}。 */
    public List<Map<String, Object>> getSkillTree(String slug, User operator) {
        Skill item = getManagementReadableSkillOrRaise(operator, slug);
        Path skillDir = resolveSkillDir(item);
        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("技能目录不存在: " + item.getDirPath());
        }
        return buildTree(skillDir, skillDir);
    }

    /** {@code read_skill_file}。 */
    public Map<String, Object> readSkillFile(String slug, String relativePath, User operator) throws IOException {
        Skill item = getManagementReadableSkillOrRaise(operator, slug);
        Path skillDir = resolveSkillDir(item);
        ResolvedRelative resolved = resolveRelativePath(skillDir, relativePath, false);
        Path target = resolved.target();
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在: " + relativePath);
        }
        if (!isTextPath(target)) {
            throw new IllegalArgumentException("仅支持读取文本文件");
        }
        String content;
        try {
            content = readUtf8Strict(target);
        } catch (java.nio.charset.CharacterCodingException exc) {
            throw new IllegalArgumentException("文件编码不支持（仅支持 UTF-8）: " + exc.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", resolved.normalizedPath());
        result.put("content", content);
        return result;
    }

    /** {@code create_skill_node}。 */
    @Transactional
    public void createSkillNode(
            String slug, String relativePath, boolean isDir, String content, String updatedBy, User operator)
            throws IOException {
        Skill item = getManageableSkillOrRaise(operator, slug);
        if (isBuiltinSkill(item)) {
            throw new IllegalArgumentException("内置 skill 不允许直接修改文件");
        }
        Path skillDir = resolveSkillDir(item);
        ResolvedRelative resolved = resolveRelativePath(skillDir, relativePath, false);
        Path target = resolved.target();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标已存在");
        }

        if (isDir) {
            Files.createDirectory(target);
            return;
        }

        if (!isTextPath(target)) {
            throw new IllegalArgumentException("仅支持创建文本文件");
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);

        updateSkillMetadataIfSkillsMd(item, content == null ? "" : content, skillDir, target, updatedBy);
    }

    /** {@code update_skill_file}。 */
    @Transactional
    public void updateSkillFile(String slug, String relativePath, String content, String updatedBy, User operator)
            throws IOException {
        Skill item = getManageableSkillOrRaise(operator, slug);
        if (isBuiltinSkill(item)) {
            throw new IllegalArgumentException("内置 skill 不允许直接修改文件");
        }
        Path skillDir = resolveSkillDir(item);
        ResolvedRelative resolved = resolveRelativePath(skillDir, relativePath, false);
        Path target = resolved.target();
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (!isTextPath(target)) {
            throw new IllegalArgumentException("仅支持编辑文本文件");
        }

        updateSkillMetadataIfSkillsMd(item, content, skillDir, target, updatedBy);
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** {@code _update_skill_metadata_if_skills_md}。 */
    private void updateSkillMetadataIfSkillsMd(
            Skill item, String content, Path skillDir, Path target, String updatedBy) {
        if ("SKILL.md".equals(target.getFileName().toString())
                && target.getParent() != null
                && target.getParent().equals(skillDir)) {
            ParsedSkillMarkdown parsed = parseSkillMarkdown(content);
            if (!parsed.slug().equals(item.getSlug())) {
                throw new IllegalArgumentException("SKILL.md frontmatter.slug 必须与 skill slug 一致");
            }
            skillRepository.updateMetadata(item, parsed.name(), parsed.description(), updatedBy);
        }
    }

    /** {@code delete_skill_node}。 */
    @Transactional
    public void deleteSkillNode(String slug, String relativePath, User operator) {
        Skill item = getManageableSkillOrRaise(operator, slug);
        if (isBuiltinSkill(item)) {
            throw new IllegalArgumentException("内置 skill 不允许直接修改文件");
        }
        Path skillDir = resolveSkillDir(item);
        ResolvedRelative resolved = resolveRelativePath(skillDir, relativePath, false);
        Path target = resolved.target();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不存在");
        }
        if ("SKILL.md".equals(resolved.normalizedPath())) {
            throw new IllegalArgumentException("不允许删除根目录 SKILL.md");
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeQuietly(target);
        } else {
            try {
                Files.delete(target);
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
        }
    }

    /** {@code export_skill_zip} 的返回二元组（路径, 下载文件名）。 */
    public record ExportResult(String path, String downloadName) {}

    /** {@code export_skill_zip}。 */
    public ExportResult exportSkillZip(String slug, User operator) throws IOException {
        Skill item = getManageableSkillOrRaise(operator, slug);
        Path skillDir = resolveSkillDir(item);
        if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("技能目录不存在");
        }

        Path exportPath = Files.createTempFile("skill-" + slug + "-", ".zip");
        Files.deleteIfExists(exportPath);
        try {
            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(exportPath))) {
                List<Path> entries = new ArrayList<>();
                try (java.util.stream.Stream<Path> stream = Files.walk(skillDir)) {
                    stream.forEach(entries::add);
                }
                entries.sort(Comparator.comparing(path -> skillDir.relativize(path).toString()));
                for (Path entry : entries) {
                    String arcname = slug + "/" + skillDir.relativize(entry).toString().replace('\\', '/');
                    if (Files.isDirectory(entry)) {
                        if (!arcname.endsWith("/")) {
                            arcname = arcname + "/";
                        }
                        zipOut.putNextEntry(new ZipEntry(arcname));
                        zipOut.closeEntry();
                        continue;
                    }
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    zipOut.putNextEntry(new ZipEntry(arcname));
                    try (InputStream input = Files.newInputStream(entry)) {
                        input.transferTo(zipOut);
                    }
                    zipOut.closeEntry();
                }
            }
        } catch (IOException exc) {
            Files.deleteIfExists(exportPath);
            throw exc;
        }
        return new ExportResult(exportPath.toString(), slug + ".zip");
    }

    /** {@code delete_skill}。 */
    @Transactional
    public void deleteSkill(String slug, User operator) {
        Skill item = skillRepository.getBySlug(slug, true);
        if (item == null) {
            throw new IllegalArgumentException("技能 '" + slug + "' 不存在");
        }
        if (!userCanManageSkill(operator, item)) {
            throw new IllegalArgumentException("技能 '" + slug + "' 不存在或无权管理");
        }
        ensureNonBuiltin(item);

        Path skillDir = resolveSkillDir(item);
        Path trashDir = null;

        if (Files.exists(skillDir, LinkOption.NOFOLLOW_LINKS)) {
            trashDir = skillDir.resolveSibling(".deleted-" + slug + "-" + shortUuid());
            moveDirectory(skillDir, trashDir);
        }

        try {
            skillRepository.delete(item);
        } catch (RuntimeException exc) {
            if (trashDir != null && Files.exists(trashDir, LinkOption.NOFOLLOW_LINKS)) {
                moveDirectory(trashDir, skillDir);
            }
            throw exc;
        }

        if (trashDir != null && Files.exists(trashDir, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeQuietly(trashDir);
        }
    }

    /** {@code delete_skills_batch}：批量删除，单技能独立的子事务与回滚。 */
    public List<JSONObject> deleteSkillsBatch(List<String> slugs, User operator) {
        if (slugs != null && slugs.size() > 50) {
            throw new IllegalArgumentException("批量删除的技能数量不能超过 50 个");
        }
        List<JSONObject> results = new ArrayList<>();
        if (slugs == null) {
            return results;
        }
        for (String slug : slugs) {
            try {
                requiresNewTransaction.executeWithoutResult(status -> deleteSkill(slug, operator));
                JSONObject item = new JSONObject();
                item.put("slug", slug);
                item.put("success", true);
                results.add(item);
            } catch (RuntimeException exc) {
                JSONObject item = new JSONObject();
                item.put("slug", slug);
                item.put("success", false);
                item.put("error", exc.getMessage());
                results.add(item);
            }
        }
        return results;
    }

    /** {@code update_skill_share_config}。 */
    @Transactional
    public Skill updateSkillShareConfig(String slug, JSONObject shareConfig, User operator) {
        Skill item = getManageableSkillOrRaise(operator, slug);
        ensureNonBuiltin(item);
        JSONObject normalized = normalizeSkillShareConfig(
                shareConfig,
                operator.getUid(),
                item.getSourceType(),
                new LinkedHashSet<>(getAllowedSkillAccessLevels(operator)));
        Skill updated = skillRepository.updateShareConfig(item, normalized, operator.getUid());
        applySkillProjectionPolicyChange(slug);
        return updated;
    }

    /** {@code update_skill_enabled}。 */
    @Transactional
    public Skill updateSkillEnabled(String slug, boolean enabled, User operator) {
        Skill item = getManageableSkillOrRaise(operator, slug);
        Skill updated = skillRepository.updateEnabled(item, enabled, operator.getUid());
        applySkillProjectionPolicyChange(slug);
        return updated;
    }

    // ---------------------------------------------------------------- 内置 skill 同步

    /** {@code list_builtin_skill_specs}：解析内置 skill 目录与元数据。 */
    public static List<JSONObject> listBuiltinSkillSpecs() throws IOException {
        List<JSONObject> specs = new ArrayList<>();
        for (BuiltinSkillSpec rawSpec : getBuiltinSkillSpecs()) {
            String slug = rawSpec.slug() == null ? "" : rawSpec.slug().strip();
            Path sourceDir = rawSpec.sourceDir().toAbsolutePath().normalize();
            String configuredDescription = rawSpec.description() == null ? "" : rawSpec.description().strip();
            String version = rawSpec.version() == null ? "" : rawSpec.version().strip();
            if (version.isEmpty()) {
                version = "1.0.0";
            }
            List<String> configuredTools = normalizeStringList(rawSpec.toolDependencies());
            List<String> configuredMcps = normalizeStringList(rawSpec.mcpDependencies());
            List<String> configuredSkills = normalizeStringList(rawSpec.skillDependencies());

            if (!isValidSkillSlug(slug)) {
                throw new IllegalArgumentException("内置 skill slug 非法: " + slug);
            }
            if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
                throw new IllegalArgumentException("内置 skill 目录不存在: " + sourceDir);
            }

            Path skillMd = sourceDir.resolve("SKILL.md");
            if (!Files.exists(skillMd)) {
                throw new IllegalArgumentException("内置 skill 缺少 SKILL.md: " + sourceDir);
            }

            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            ParsedSkillMarkdown parsed = parseSkillMarkdown(content);
            if (!parsed.slug().equals(slug)) {
                throw new IllegalArgumentException("内置 skill frontmatter.slug 必须等于 slug: " + slug);
            }

            JSONObject spec = new JSONObject();
            spec.put("slug", slug);
            spec.put("name", parsed.name());
            spec.put("description", configuredDescription.isEmpty() ? parsed.description() : configuredDescription);
            spec.put("version", version);
            spec.put(
                    "tool_dependencies",
                    configuredTools.isEmpty()
                            ? normalizeStringList(asList(parsed.data().get("tool_dependencies")))
                            : configuredTools);
            spec.put(
                    "mcp_dependencies",
                    configuredMcps.isEmpty()
                            ? normalizeStringList(asList(parsed.data().get("mcp_dependencies")))
                            : configuredMcps);
            spec.put(
                    "skill_dependencies",
                    configuredSkills.isEmpty()
                            ? normalizeStringList(asList(parsed.data().get("skill_dependencies")))
                            : configuredSkills);
            spec.put("content_hash", computeDirHash(sourceDir));
            spec.put("source_dir", sourceDir.toString());
            specs.add(spec);
        }
        return specs;
    }

    /** {@code init_builtin_skills}：幂等同步内置 skill（含目录替换与元数据/依赖对齐）。 */
    @Transactional
    public List<Skill> initBuiltinSkills(String createdBy) throws IOException {
        String operator = createdBy == null || createdBy.isEmpty() ? "system" : createdBy;
        final List<Skill> syncedItems = new ArrayList<>();
        withAdvisoryLock(String.valueOf(SKILL_STORAGE_LOCK), () -> {
            try {
                for (JSONObject spec : listBuiltinSkillSpecs()) {
                    String slug = spec.getString("slug");
                    Skill existing = skillRepository.getBySlug(slug, false);
                    if (existing != null && !isBuiltinSkill(existing)) {
                        throw new IllegalArgumentException("内置 skill '" + slug + "' 与已存在的非内置 skill 冲突");
                    }

                    Path targetDir = getSkillsRootDir().resolve(slug);
                    replaceSkillTarget(targetDir, Path.of(spec.getString("source_dir")), null);

                    if (existing != null) {
                        existing.setDirPath(buildBuiltinSkillDirPath(slug));
                        if (!java.util.Objects.equals(existing.getName(), spec.getString("name"))
                                || !java.util.Objects.equals(existing.getDescription(), spec.getString("description"))) {
                            skillRepository.updateMetadata(
                                    existing, spec.getString("name"), spec.getString("description"), operator);
                        }
                        List<String> specTools = normalizeStringList(spec.getList("tool_dependencies", String.class));
                        List<String> specMcps = normalizeStringList(spec.getList("mcp_dependencies", String.class));
                        List<String> specSkills = normalizeStringList(spec.getList("skill_dependencies", String.class));
                        if (!normalizeStringListFromJson(existing.getToolDependencies()).equals(specTools)
                                || !normalizeStringListFromJson(existing.getMcpDependencies()).equals(specMcps)
                                || !normalizeStringListFromJson(existing.getSkillDependencies()).equals(specSkills)) {
                            skillRepository.updateDependencies(existing, specTools, specMcps, specSkills, operator);
                        }
                        syncedItems.add(skillRepository.updateBuiltinInstall(
                                existing, spec.getString("version"), spec.getString("content_hash"), operator));
                        continue;
                    }

                    syncedItems.add(skillRepository.create(
                            slug,
                            spec.getString("name"),
                            spec.getString("description"),
                            "builtin",
                            normalizeStringList(spec.getList("tool_dependencies", String.class)),
                            normalizeStringList(spec.getList("mcp_dependencies", String.class)),
                            normalizeStringList(spec.getList("skill_dependencies", String.class)),
                            buildBuiltinSkillDirPath(slug),
                            builtinShareConfigCopy(),
                            true,
                            spec.getString("version"),
                            spec.getString("content_hash"),
                            operator.isEmpty() ? BUILTIN_SKILL_OPERATOR : operator));
                }
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
        });
        return syncedItems;
    }

    private static JSONObject builtinShareConfigCopy() {
        JSONObject config = new JSONObject();
        config.put("version", 2);
        config.put("read_scope", builtinSkillShareConfig());
        config.put("manage_scope", null);
        return config;
    }

    // ---------------------------------------------------------------- 序列化与通用工具

    /**
     * 参考实现 {@code Skill.to_dict()}（模型方法）。
     *
     * <p>能力差异（已标注）：本工程实体为纯 MyBatis 记录，不承载序列化行为，故落在服务层。
     * 键序、默认值与 {@code format_utc_datetime} 口径逐字对齐。
     */
    public static Map<String, Object> skillToDict(Skill item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", item.getId());
        data.put("slug", item.getSlug());
        data.put("name", item.getName());
        data.put("description", item.getDescription());
        data.put("source_type", item.getSourceType());
        data.put("tool_dependencies", normalizeStringListFromJson(item.getToolDependencies()));
        data.put("mcp_dependencies", normalizeStringListFromJson(item.getMcpDependencies()));
        data.put("skill_dependencies", normalizeStringListFromJson(item.getSkillDependencies()));
        data.put("dir_path", item.getDirPath());
        data.put("version", item.getVersion());
        data.put("content_hash", item.getContentHash());
        data.put(
                "share_config",
                item.getShareConfig() == null || item.getShareConfig().isBlank()
                        ? new JSONObject()
                        : JSON.parseObject(item.getShareConfig()));
        data.put("enabled", Boolean.TRUE.equals(item.getEnabled()));
        data.put("created_by", item.getCreatedBy());
        data.put("updated_by", item.getUpdatedBy());
        data.put("created_at", DateTimeUtils.formatUtcDatetime(item.getCreatedAt()));
        data.put("updated_at", DateTimeUtils.formatUtcDatetime(item.getUpdatedAt()));
        return data;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static List<Path> listChildren(Path directory) {
        List<Path> children = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            stream.forEach(children::add);
        } catch (IOException exc) {
            throw new IllegalStateException("读取目录失败: " + directory, exc);
        }
        return children;
    }

    private static void deleteTreeQuietly(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
                    List<Path> all = new ArrayList<>();
                    stream.forEach(all::add);
                    all.sort(Comparator.reverseOrder());
                    for (Path entry : all) {
                        Files.deleteIfExists(entry);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException exc) {
            log.warn("递归删除失败: " + path + " (" + exc.getMessage() + ")");
        }
    }

    /** 同卷目录移动（参考实现 Path.rename 语义）。 */
    private static void moveDirectory(Path source, Path target) {
        try {
            Files.move(source, target);
        } catch (IOException exc) {
            throw new IllegalStateException("移动 Skill 目录失败: " + source + " -> " + target, exc);
        }
    }

    /** 严格 UTF-8 解码（对应参考实现 read_text(encoding="utf-8") 的 UnicodeDecodeError）。 */
    private static String readUtf8Strict(Path path) throws java.nio.charset.CharacterCodingException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
    }

    /** ZIP 解包（参考实现 zf.extractall）。 */
    private static void extractZip(ZipFile zipFile, Path targetDir) throws IOException {
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            Path target = targetDir.resolve(entry.getName()).toAbsolutePath().normalize();
            if (!target.startsWith(targetDir.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("ZIP 包含路径穿越片段: " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(target);
                continue;
            }
            Files.createDirectories(target.getParent());
            try (InputStream input = zipFile.getInputStream(entry);
                    OutputStream output = Files.newOutputStream(target)) {
                input.transferTo(output);
            }
        }
    }

    /** {@code utc_now_naive()} 便捷入口（供上层控制器复用）。 */
    public static LocalDateTime utcNowNaive() {
        return DateTimeUtils.utcNowNaive();
    }

    /** 供投影刷新查询全部未删除用户 uid（按 id 升序）。 */
    public List<String> listActiveUidsOrderedById() {
        List<String> uids = new ArrayList<>();
        for (User user : userRepository.listActiveOrderedById()) {
            if (user.getUid() != null) {
                uids.add(String.valueOf(user.getUid()));
            }
        }
        return uids;
    }
}
