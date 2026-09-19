package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.common.PosixPathLite;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.wisesoft.wenqu.config.OptionsService;

/**
 * 远程 Skill 安装。
 *
 * <p>由参考实现的 agents/skills/remote_install.py（345 行）翻译：常量（ANSI 控制序列、
 * CLI 超时、GitHub 仓库样式、非法来源文案、沙盒输出根）与纯函数面
 * （{@code _normalize_source} / {@code _normalize_skill_name} / {@code _clean_cli_output} /
 * {@code _parse_available_skills} / {@code _parse_search_skills}）逐字对位。
 *
 * <p><b>能力缺口（显式标注，非遗漏）</b>：参考实现的三个入口
 * （{@code list_remote_skills} / {@code prepare_remote_skills_batch} / {@code search_remote_skills}）
 * 都通过 {@code _RemoteSkillSandbox} 在一次性沙盒里执行 {@code npx -y skills …}，
 * 而沙盒由外置 provisioner 服务提供
 * （{@code ProvisionerSandboxProvider} 只支持 {@code SANDBOX_PROVIDER=provisioner}，
 * 见参考实现 agents/backends/sandbox/provider.py:99-101）。
 * 本工程未部署该外部服务，故这三个入口保留签名与非法来源校验，实际执行抛
 * {@link SkillRemoteExecutionUnavailableException}。这与清单 §二
 * 「dify/notion：参数面已搬入、外部 HTTP 接口未搬」的处理口径一致。
 * 沙盒数据面（backend/download/provider/provisioner_client，约 1764 行）未搬，
 * 需要时另行立项。
 */
@Component
public class SkillRemoteInstall {

    private static final Logger log = LoggerFactory.getLogger(SkillRemoteInstall.class);

    // ---------------------------------------------------------------- 常量（逐字对齐）

    private static final Pattern ANSI_ESCAPE_RE = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern CONTROL_SEQUENCE_RE =
            Pattern.compile("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]");
    private static final int CLI_TIMEOUT_SECONDS = 300;
    private static final Pattern GITHUB_REPO_PATTERN =
            Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?/(?!\\.{1,2}$)[A-Za-z0-9_.-]+$");
    private static final String GITHUB_HOST = "github.com";
    private static final String INVALID_SOURCE_MESSAGE = "source 仅支持远程 Skill 来源白名单中的 HTTPS 地址";

    /** 前缀装饰符清洗（参考实现 {@code ^[│┌└◇◒◐◓◑■●]+\s*}）。 */
    private static final Pattern LEADING_GLYPH_RE = Pattern.compile("^[\u2502\u250C\u2514\u25C7\u25D2\u25D0\u25D3\u25D1\u25A0\u25CF]+\\s*");

    /** 搜索输出条目样式：{@code owner/repo@skill-name [installs]}。 */
    private static final Pattern SEARCH_ENTRY_PATTERN =
            Pattern.compile("^([a-zA-Z0-9_\\-.]++/[a-zA-Z0-9_\\-.]++)\\@([a-zA-Z0-9_\\-.]++)(?:\\s+(.*))?$");

    /** 远程 Skill 沙盒输出根（{@code f"{VIRTUAL_PATH_PREFIX.rstrip('/')}/outputs"}）。 */
    public static final String REMOTE_SKILL_SANDBOX_ROOT =
            stripTrailingSlashes(BackendPaths.VIRTUAL_PATH_PREFIX) + "/outputs";

    /**
     * 沙盒不可用时抛出的异常（能力缺口，见类注释）。
     *
     * <p>继承 {@link IllegalArgumentException} 是<b>本工程的选择</b>（需要显式标注）：
     * 本工程未部署 provisioner，属服务端能力缺失，路由层会把该异常映射成
     * {@code _raise_from_value_error} 的 400 分支并原样回传文案，前端可直接看到
     * 「沙盒未部署」的提示。对照参考实现的实际行为（已读代码）：
     * {@code SANDBOX_PROVIDER} 取值非法时抛 {@code ValueError} → 400；
     * provisioner 地址不可达时抛连接错误 → 500。两种都可能，本工程固定走 400。
     */
    public static class SkillRemoteExecutionUnavailableException extends IllegalArgumentException {
        public SkillRemoteExecutionUnavailableException(String message) {
            super(message);
        }
    }

    /** {@code RemoteSkillsBatchPreparation}。 */
    public record RemoteSkillsPreparation(String tempHome, List<com.alibaba.fastjson2.JSONObject> results) {

        /** {@code cleanup()}：删除宿主临时目录。 */
        public Runnable cleanup() {
            return () -> {
                if (tempHome != null) {
                    deleteTreeQuietly(Path.of(tempHome));
                }
            };
        }
    }

    private final ObjectProvider<OptionsService> optionsServiceProvider;

    public SkillRemoteInstall(ObjectProvider<OptionsService> optionsServiceProvider) {
        this.optionsServiceProvider = optionsServiceProvider;
    }

    // ---------------------------------------------------------------- 纯函数面

    /** {@code _normalize_source}：来源白名单校验与规范化。 */
    public static String normalizeSource(String source, List<String> allowedHosts) {
        String value = source == null ? "" : source.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("source 包含非法字符");
        }

        Set<String> hosts = new LinkedHashSet<>();
        for (String host : allowedHosts == null ? List.<String>of() : allowedHosts) {
            hosts.add(host.strip().toLowerCase().replaceAll("\\.$", ""));
        }
        Matcher repoMatch = GITHUB_REPO_PATTERN.matcher(value);
        if (repoMatch.matches()) {
            if (!hosts.contains(GITHUB_HOST)) {
                throw new IllegalArgumentException(INVALID_SOURCE_MESSAGE);
            }
            return "https://" + GITHUB_HOST + "/" + value;
        }

        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException(INVALID_SOURCE_MESSAGE);
        }
        String hostname;
        Integer port;
        try {
            hostname = parsed.getHost() == null ? "" : parsed.getHost().replaceAll("\\.$", "").toLowerCase();
            port = parsed.getPort() < 0 ? null : parsed.getPort();
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException(INVALID_SOURCE_MESSAGE);
        }

        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        if (!"https".equals(scheme)
                || hostname.isEmpty()
                || parsed.getUserInfo() != null && !parsed.getUserInfo().isEmpty()
                || (port != null && port != 443)
                || parsed.getQuery() != null
                || parsed.getFragment() != null
                || !hosts.contains(hostname)) {
            throw new IllegalArgumentException(INVALID_SOURCE_MESSAGE);
        }

        String path = parsed.getPath() == null ? "" : parsed.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (GITHUB_HOST.equals(hostname)) {
            String repoPath = path;
            while (repoPath.startsWith("/")) {
                repoPath = repoPath.substring(1);
            }
            while (repoPath.endsWith("/")) {
                repoPath = repoPath.substring(0, repoPath.length() - 1);
            }
            if (repoPath.endsWith(".git")) {
                repoPath = repoPath.substring(0, repoPath.length() - 4);
            }
            if (!GITHUB_REPO_PATTERN.matcher(repoPath).matches()) {
                throw new IllegalArgumentException(INVALID_SOURCE_MESSAGE);
            }
            return "https://" + GITHUB_HOST + "/" + repoPath;
        }

        String rebuilt = "https://" + hostname + path;
        return stripTrailingSlashes(rebuilt);
    }

    /** {@code _normalize_skill_name}。 */
    public static String normalizeSkillName(String skill) {
        String value = skill == null ? "" : skill.strip();
        if (!SkillService.isValidSkillSlug(value)) {
            throw new IllegalArgumentException("skill 名称不合法");
        }
        return value;
    }

    /** {@code _clean_cli_output}：剥离 ANSI/控制序列与行首装饰符，逐行 strip。 */
    public static List<String> cleanCliOutput(String output) {
        String cleaned = ANSI_ESCAPE_RE.matcher(output == null ? "" : output).replaceAll("");
        cleaned = CONTROL_SEQUENCE_RE.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("\r", "\n");
        List<String> normalizedLines = new ArrayList<>();
        for (String line : splitLines(cleaned)) {
            String stripped = line.strip();
            stripped = LEADING_GLYPH_RE.matcher(stripped).replaceFirst("");
            normalizedLines.add(stripped.strip());
        }
        return normalizedLines;
    }

    /** {@code _parse_available_skills}：解析 {@code skills add --list} 的输出。 */
    public static List<Map<String, String>> parseAvailableSkills(String output) {
        List<String> lines = cleanCliOutput(output);
        List<Map<String, String>> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean collecting = false;

        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx);
            if (!collecting) {
                if (line.contains("Available Skills")) {
                    collecting = true;
                }
                continue;
            }

            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("Use --skill ")) {
                break;
            }
            if (!SkillService.isValidSkillSlug(line)) {
                continue;
            }
            if (seen.contains(line)) {
                continue;
            }

            String description = "";
            int nextIndex = idx + 1;
            while (nextIndex < lines.size()) {
                String nextLine = lines.get(nextIndex);
                nextIndex++;
                if (nextLine.isEmpty()) {
                    continue;
                }
                if (nextLine.contains("Use --skill ")) {
                    break;
                }
                if (SkillService.isValidSkillSlug(nextLine)) {
                    break;
                }
                if (!nextLine.isEmpty() && Character.isLetter(nextLine.charAt(0))) {
                    description = nextLine;
                } else {
                    continue;
                }
                break;
            }

            seen.add(line);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", line);
            entry.put("description", description);
            items.add(entry);
        }

        return items;
    }

    /** {@code _parse_search_skills}：解析 {@code npx skills find} 的输出。 */
    public static List<Map<String, String>> parseSearchSkills(String output) {
        List<String> lines = cleanCliOutput(output);
        List<Map<String, String>> results = new ArrayList<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher match = SEARCH_ENTRY_PATTERN.matcher(line);
            if (match.matches()) {
                String source = match.group(1);
                String name = match.group(2);
                String extra = match.group(3);
                String installs = extra == null ? "" : extra.strip();
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("source", source);
                entry.put("name", name);
                entry.put("installs", installs);
                results.add(entry);
            }
        }
        return results;
    }

    /** Python {@code str.splitlines()} 语义（含 {@code \v}、{@code \f}、{@code \u2028} 等分隔符）。 */
    private static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean isBreak = ch == '\n'
                    || ch == '\r'
                    || ch == '\u000B'
                    || ch == '\f'
                    || ch == '\u001C'
                    || ch == '\u001D'
                    || ch == '\u001E'
                    || ch == '\u0085'
                    || ch == '\u2028'
                    || ch == '\u2029';
            if (isBreak) {
                lines.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    // ---------------------------------------------------------------- 沙盒执行入口（能力缺口）

    /** 远程来源白名单策略（{@code remote_skill_source_policy.get()["allowed_hosts"]}）。 */
    public List<String> allowedHosts() {
        OptionsService service = optionsServiceProvider.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        Map<String, Object> policy = service.get(OptionsService.REMOTE_SKILL_SOURCE_POLICY);
        Object hosts = policy == null ? null : policy.get("allowed_hosts");
        List<String> result = new ArrayList<>();
        if (hosts instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    /** {@code list_remote_skills}（沙盒路径，见类注释的能力缺口说明）。 */
    public List<Map<String, String>> listRemoteSkills(String source) {
        normalizeSource(source, allowedHosts());
        throw sandboxUnavailable();
    }

    /** {@code prepare_remote_skills_batch}（沙盒路径，见类注释的能力缺口说明）。 */
    public RemoteSkillsPreparation prepareRemoteSkillsBatch(String source, List<String> skills) {
        normalizeSource(source, allowedHosts());
        if (skills == null || skills.isEmpty()) {
            throw new IllegalArgumentException("skills 列表不能为空");
        }
        // 与参考实现一致：先按请求顺序预分配，非法名就地记录失败，合法名保持 "unset"
        List<com.alibaba.fastjson2.JSONObject> results = new ArrayList<>();
        List<String> normalizedSkills = new ArrayList<>();
        for (String skill : skills) {
            com.alibaba.fastjson2.JSONObject item = new com.alibaba.fastjson2.JSONObject();
            item.put("slug", "");
            item.put("success", false);
            item.put("error", "unset");
            try {
                normalizedSkills.add(normalizeSkillName(skill));
            } catch (IllegalArgumentException exc) {
                item.put("slug", skill);
                item.put("error", exc.getMessage());
            }
            results.add(item);
        }
        if (normalizedSkills.isEmpty()) {
            return new RemoteSkillsPreparation(null, results);
        }
        throw sandboxUnavailable();
    }

    /** {@code search_remote_skills}（沙盒路径，见类注释的能力缺口说明）。 */
    public List<Map<String, String>> searchRemoteSkills(String query) {
        String queryVal = query == null ? "" : query.strip();
        if (queryVal.isEmpty()) {
            return new ArrayList<>();
        }
        if (queryVal.indexOf('\n') >= 0 || queryVal.indexOf('\r') >= 0 || queryVal.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("搜索关键字包含非法字符");
        }
        throw sandboxUnavailable();
    }

    private static SkillRemoteExecutionUnavailableException sandboxUnavailable() {
        return new SkillRemoteExecutionUnavailableException(
                "远程 Skill 安装依赖一次性沙盒（外置 provisioner 服务），本工程未部署该服务");
    }

    // ---------------------------------------------------------------- 工具

    private static String stripTrailingSlashes(String value) {
        String result = value == null ? "" : value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static void deleteTreeQuietly(Path path) {
        try {
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
                List<Path> all = new ArrayList<>();
                stream.forEach(all::add);
                all.sort(java.util.Comparator.reverseOrder());
                for (Path entry : all) {
                    Files.deleteIfExists(entry);
                }
            }
        } catch (IOException exc) {
            log.warn("删除临时目录失败: " + path + " (" + exc.getMessage() + ")");
        }
    }

    /** 保留以对齐参考实现 {@code PosixPathLite} 的路径片段校验入口。 */
    static boolean isSafeRelative(String value) {
        PosixPathLite pure = PosixPathLite.parse(value);
        return !pure.isAbsolute() && !pure.partsContain("..");
    }
}
