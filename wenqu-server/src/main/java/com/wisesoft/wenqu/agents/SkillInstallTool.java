package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.agents.ToolkitsRegistry.ToolDefinition;
import com.wisesoft.wenqu.common.PosixPathLite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 安装技能工具（对应参考实现 {@code agents/toolkits/buildin/install_skill.py}）。
 *
 * <p>职责与参考实现逐条对位：
 * <ul>
 *   <li>{@code source} 以 {@code /} 开头 → 从沙盒目录安装（先落暂存目录再 {@code installPersonalSkillDir}）；</li>
 *   <li>否则按 {@code owner/repo} 或 Git URL 远程安装（必须先给 {@code skill_names}）；</li>
 *   <li>安装成功后把 slug 写回当前会话的智能体配置；</li>
 *   <li>逐条拼装结果文案（已安装 / 路径 / 失败项 / 配置未更新 / 无可执行项）。</li>
 * </ul>
 *
 * <p>结果文案与参考实现逐字一致（含「错误：…」「安装异常：…」「已安装 Skill: 」等前缀）。
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code runtime: ToolRuntime} → {@link #runtimeContext} 实例字段 + {@link #boundTo}
 *       （与 {@link KnowledgeTools} 同一手法：构图方按 Run 派生绑定副本）。
 *       {@code InjectedToolCallId} 在 Spring AI 的 {@link ToolCallback} 上没有入参对位，
 *       且本工程返回 content 而非 {@code Command}（见能力差异 1），故不再承载该值。</li>
 *   <li>{@code Command(update={"messages":[ToolMessage(...)]})} → 工具直接返回该 ToolMessage 的
 *       content 文本（见能力差异 1）。</li>
 *   <li>{@code tempfile.TemporaryDirectory} → {@link Files#createTempDirectory}。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>Command 状态写回</b>：参考实现把 ToolMessage 塞进 {@code Command.update["messages"]}；
 *       本工程工具只能返回字符串，故返回 content 本身（模型可见内容一致），由构图方包成 ToolMessage。</li>
 *   <li><b>沙盒路径安装尚未接线</b>：{@code ProvisionerSandboxBackend} /
 *       {@code download_sandbox_directory}（{@code backends/sandbox}）
 *       **已随 §三 backends 落地**（{@code agents/backends/sandbox/}，含
 *       {@link com.wisesoft.wenqu.agents.backends.sandbox.SandboxDownload#downloadSandboxDirectory}），
 *       但本类该分支未接线（原文写「未搬」，2026-09-20 更正）。本类保留 slug 校验与
 *       路径白名单校验（{@link #prepareSkillFromSandbox}），到「下载目录」这一步抛
 *       {@link IllegalStateException} —— 与参考实现 ValueError 一样被外层捕获成
 *       {@code 安装异常：…} 文案，不静默成功（同 {@code dify} / {@code notion} 口径）。</li>
 *   <li><b>远程安装依赖沙盒</b>：{@code prepareRemoteSkillsBatch} 的三个沙盒入口同样不可用
 *       （批次⑨ 已标注），失败路径与能力差异 2 一致。</li>
 * </ol>
 */
@Slf4j
@Component
public final class SkillInstallTool implements ToolDefinition, ToolCallback {

    /** 参考实现 {@code SANDBOX_PATH_HINT}（逐字）。 */
    public static final String SANDBOX_PATH_HINT = "请使用当前 Project Workdir 下的目录，或 /home/gem/user-data/...";

    /** 参考实现 {@code install_skill} 工具名（逐字）。 */
    public static final String TOOL_NAME = "install_skill";

    /** 参考实现工具描述（逐字）。 */
    public static final String DESCRIPTION = "安装新的 Skill 到当前用户私有空间，并返回可直接读取的 Skill 路径。";

    /** 参考实现 {@code InstallSkillInput} 的入参 schema。 */
    public static final String ARGS_SCHEMA = """
            {"type":"object","properties":{
              "source":{"type":"string","description":"Skill 来源，支持两种格式:\\n1. Sandbox 路径: 当前 Project Workdir 或 用户数据目录下的绝对路径\\n2. Git 仓库: owner/repo 或完整 Git URL"},
              "skill_names":{"type":"array","items":{"type":"string"},"description":"Git 安装时指定要安装的 skill slug 列表（至少一个）。Sandbox 路径安装时忽略此参数。"}
            },"required":["source"]}""";

    private final SkillService skillService;
    private final SkillRemoteInstall remoteInstall;

    /** 本次 Run 的运行时上下文（对应参考实现按调用注入的 {@code ToolRuntime.context}）。 */
    private final BaseContext runtimeContext;

    /** 容器装配用主构造器（本类另有私有的「绑定副本」构造器，故显式标注 {@link Autowired}）。 */
    @Autowired
    public SkillInstallTool(SkillService skillService, SkillRemoteInstall remoteInstall) {
        this(skillService, remoteInstall, null);
    }

    private SkillInstallTool(
            SkillService skillService, SkillRemoteInstall remoteInstall, BaseContext runtimeContext) {
        this.skillService = skillService;
        this.remoteInstall = remoteInstall;
        this.runtimeContext = runtimeContext;
    }

    /** 注册到 {@link ToolkitsRegistry}（对应 {@code @tool(category="buildin", ...)} 装饰器）。 */
    public SkillInstallTool register() {
        ToolkitsRegistry.register(this, new ToolkitsRegistry.ToolExtraMetadata(
                "buildin", List.of("skill", "安装"), "安装技能", "", ""));
        return this;
    }

    /** 派生绑定到本次 Run 的工具副本（对应参考实现按调用注入的 {@code ToolRuntime}）。 */
    public SkillInstallTool boundTo(BaseContext context) {
        return new SkillInstallTool(skillService, remoteInstall, context);
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> getArgsSchema() {
        return com.alibaba.fastjson2.JSON.parseObject(ARGS_SCHEMA);
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(ARGS_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args = KnowledgeTools.parseArgs(toolInput);
        Object rawSkills = args.get("skill_names");
        List<String> skillNames = new ArrayList<>();
        if (rawSkills instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    skillNames.add(String.valueOf(item));
                }
            }
        }
        return runInstallTask(KnowledgeTools.stringArg(args, "source"), skillNames, "");
    }

    /**
     * 执行安装任务的核心逻辑（对应 {@code _run_install_task}）。
     *
     * <p>返回值为本该写进 {@code Command.update["messages"][0].content} 的文本（见类注释「能力差异 1」）。
     */
    public String runInstallTask(String rawSource, List<String> skillNames, String toolCallId) {
        BaseContext runtimeContext = this.runtimeContext;
        if (runtimeContext != null && Boolean.TRUE.equals(
                runtimeContext.getDynamic("is_subagent_runtime", Boolean.FALSE))) {
            return "错误：install_skill 只能在主智能体中使用，子智能体无法安装 Skill";
        }

        String source = rawSource == null ? "" : rawSource.strip();
        String uid = runtimeContext == null ? null : runtimeContext.getString("uid");
        String threadId = runtimeContext == null ? null : runtimeContext.getString("thread_id");
        log.info("install_skill called with uid={}, thread_id={}, source={}", uid, threadId, source);

        if (uid == null || uid.isEmpty() || threadId == null || threadId.isEmpty()) {
            return "错误：无法获取当前会话信息";
        }
        if (source.isEmpty()) {
            return "错误：Skill 来源不能为空";
        }

        try {
            List<String> installedSlugs = new ArrayList<>();
            List<Map<String, Object>> failedItems = new ArrayList<>();
            boolean configSuccess = true;

            if (source.startsWith("/")) {
                Path stagingRoot;
                try {
                    stagingRoot = Files.createTempDirectory(".skill-install-");
                } catch (java.io.IOException exc) {
                    return "安装异常：" + exc;
                }
                try {
                    Path sourceDir = prepareSkillFromSandbox(
                            source,
                            threadId,
                            uid,
                            stagingRoot,
                            runtimeContext.getString("workdir_relative_path"),
                            runtimeContext.getString("workdir_path"));
                    ResolvedSkill item = skillService.installPersonalSkillDir(uid, sourceDir, null);
                    installedSlugs.add(item.slug());
                } finally {
                    deleteRecursively(stagingRoot);
                }
            } else {
                if (skillNames == null || skillNames.isEmpty()) {
                    return "错误：从 Git 安装时必须通过 skill_names 指定技能名称";
                }
                SkillRemoteInstall.RemoteSkillsPreparation preparation =
                        remoteInstall.prepareRemoteSkillsBatch(source, skillNames);
                try {
                    for (Object rawResult : preparation.results()) {
                        Map<String, Object> result = toMap(rawResult);
                        if (!Boolean.TRUE.equals(result.get("success"))) {
                            failedItems.add(result);
                            continue;
                        }
                        try {
                            ResolvedSkill item = skillService.installPersonalSkillDir(
                                    uid, Path.of(String.valueOf(result.get("source_dir"))), null);
                            installedSlugs.add(item.slug());
                        } catch (RuntimeException exc) {
                            Map<String, Object> failed = new LinkedHashMap<>();
                            failed.put("slug", result.get("slug"));
                            failed.put("success", false);
                            failed.put("error", exc.getMessage());
                            failedItems.add(failed);
                        }
                    }
                } finally {
                    preparation.cleanup().run();
                }
            }

            if (!installedSlugs.isEmpty()) {
                configSuccess = skillService.enablePersonalSkillsForAgentConfig(threadId, uid, installedSlugs);
            }

            List<String> lines = new ArrayList<>();
            if (!installedSlugs.isEmpty()) {
                lines.add("已安装 Skill: " + String.join(", ", installedSlugs));
                for (String slug : installedSlugs) {
                    lines.add("Skill 路径: " + BackendPaths.VIRTUAL_PERSONAL_SKILLS_PATH + "/" + slug + "/SKILL.md");
                }
            }
            for (Map<String, Object> item : failedItems) {
                Object error = item.get("error");
                lines.add("安装失败 (" + item.get("slug") + "): "
                        + (error == null ? "未知错误" : error));
            }
            if (!configSuccess) {
                lines.add("Skill 已安装，但当前 Agent 配置未更新，请手动启用");
            }
            if (installedSlugs.isEmpty() && failedItems.isEmpty()) {
                lines.add("未发现需要安装的 Skill");
            }
            return String.join("\n", lines);
        } catch (RuntimeException exc) {
            log.error("install_skill 异常", exc);
            return "安装异常：" + exc;
        }
    }

    /**
     * 从沙盒路径准备 skill 目录（对应 {@code _prepare_skill_from_sandbox}）。
     *
     * <p>slug 与路径白名单校验与参考实现逐字一致；下载步骤见类注释「能力差异 2」。
     */
    static Path prepareSkillFromSandbox(
            String sandboxPath,
            String threadId,
            String uid,
            Path stagingRoot,
            String workdirRelativePath,
            String workdirPath) {
        String slug = PosixPathLite.nameOf(sandboxPath.replaceAll("/+$", ""));
        if (!SkillService.isValidSkillSlug(slug)) {
            throw new IllegalArgumentException("slug '" + slug + "' 不合法（仅允许小写字母、数字和连字符）");
        }
        boolean allowed = sandboxPath.startsWith(BackendPaths.VIRTUAL_PATH_PREFIX.replaceAll("/+$", "") + "/");
        allowed = allowed
                || (workdirPath != null && sandboxPath.startsWith(workdirPath.replaceAll("/+$", "") + "/"));
        if (!allowed) {
            throw new IllegalArgumentException("不支持的沙盒路径: " + sandboxPath + "。" + SANDBOX_PATH_HINT);
        }
        throw new IllegalStateException("沙盒校验未接线，无法从沙盒路径安装 Skill");
    }

    private static Map<String, Object> toMap(Object value) {
        // fastjson2 的 JSONObject 本身实现 Map，故只需按键名归一化
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static void deleteRecursively(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // 与参考实现 TemporaryDirectory 的清理语义一致：清理失败不影响安装结果
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }
}
