package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.workspace.Workdir;
import com.wisesoft.wenqu.workspace.Workspace;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import org.springframework.stereotype.Service;

/**
 * 用同一个有界实时扫描搜索当前 Workdir 与 UserWorkspace（@ 提及候选）。
 *
 * <p>由参考实现的 services/mention_search_service.py 逐函数翻译：排名、runtime
 * 路径换算、双源搜索与来源选择。
 *
 * <p>必要替换：{@code asyncio.to_thread} → 调用方线程直接执行（阻塞扫描由请求线程承担）；
 * {@code MentionThreadNotFoundError(LookupError)} / {@code InvalidMentionThreadError(ValueError)}
 * → 同名运行时异常类；{@code current_user} → {@link User} 实体（仅取 uid）。
 */
@Service
public class MentionSearchService {

    public static final Set<String> MENTION_EXCLUDE_DIRS = Set.of(
            ".git",
            "node_modules",
            ".venv",
            "venv",
            "__pycache__",
            ".idea",
            ".vscode",
            "dist",
            "build",
            ".tox",
            ".mypy_cache",
            ".pytest_cache",
            ".ruff_cache");
    public static final int MAX_MENTION_RESULTS = 50;
    public static final int MAX_MENTION_CANDIDATES = 500;

    /** 当前用户不可见指定 mention thread（对应 LookupError）。 */
    @Getter
    public static class MentionThreadNotFoundError extends RuntimeException {
        public MentionThreadNotFoundError(String message) {
            super(message);
        }
    }

    /** mention thread id 不满足运行时 identity 约束（对应 ValueError）。 */
    @Getter
    public static class InvalidMentionThreadError extends RuntimeException {
        public InvalidMentionThreadError(String message) {
            super(message);
        }
    }

    private final ConversationRepository conversationRepository;
    private final WorkdirService workdirService;

    public MentionSearchService(ConversationRepository conversationRepository, WorkdirService workdirService) {
        this.conversationRepository = conversationRepository;
        this.workdirService = workdirService;
    }

    /** 排名：名称匹配优于路径匹配，名称按精确/前缀/后缀/位置/长度打分。 */
    static List<Map<String, Object>> rankEntries(List<Map<String, Object>> entries, String query, String source) {
        String queryLower = query.toLowerCase();
        record Named(double score, Map<String, Object> entry) {}
        List<Named> nameMatches = new ArrayList<>();
        record Pathed(int length, Map<String, Object> entry) {}
        List<Pathed> pathMatches = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String name = String.valueOf(entry.get("name"));
            String path = String.valueOf(entry.get("path"));
            String nameLower = name.toLowerCase();
            String pathLower = path.toLowerCase();
            if (nameLower.contains(queryLower)) {
                double score;
                if (nameLower.equals(queryLower)) {
                    score = 1000.0;
                } else {
                    score = 500.0;
                    if (nameLower.startsWith(queryLower)) {
                        score += 50.0;
                    }
                    if (nameLower.endsWith(queryLower)) {
                        score += 20.0;
                    }
                    score -= Math.min(Math.max(nameLower.indexOf(queryLower), 0), 30.0);
                    score -= Math.min(name.length() * 0.5, 50.0);
                }
                nameMatches.add(new Named(score, entry));
            } else if (pathLower.contains(queryLower)) {
                pathMatches.add(new Pathed(path.length(), entry));
            }
        }

        nameMatches.sort(Comparator.comparingDouble(Named::score).reversed());
        pathMatches.sort(Comparator.comparingInt(Pathed::length));
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Named item : nameMatches) {
            ranked.add(item.entry());
        }
        for (Pathed item : pathMatches) {
            ranked.add(item.entry());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : ranked) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(entry.get("name")));
            item.put(
                    "path",
                    Boolean.TRUE.equals(entry.get("is_dir")) ? entry.get("path") + "/" : String.valueOf(entry.get("path")));
            item.put("is_dir", Boolean.TRUE.equals(entry.get("is_dir")));
            item.put("source", source);
            result.add(item);
        }
        return result;
    }

    /** 把搜索结果转换为可直接写入 Agent mention 的 runtime 路径。 */
    private static Map<String, Object> runtimeEntry(Map<String, Object> entry, String runtimePath) {
        Map<String, Object> result = new LinkedHashMap<>(entry);
        String path = Boolean.TRUE.equals(entry.get("is_dir")) ? runtimePath + "/" : runtimePath;
        result.put("path", path);
        return result;
    }

    private List<Map<String, Object>> searchWorkspace(String uid, String query) {
        List<Map<String, Object>> entries;
        try {
            entries = new Workspace(uid).searchAuthorizedTree(
                    "/", query, true, MENTION_EXCLUDE_DIRS, true, MAX_MENTION_CANDIDATES, 600, 15, 500, 10000);
        } catch (Workspace.NoSuchFileRuntime exc) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> ranked = rankEntries(entries, query, "workspace");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : ranked) {
            result.add(runtimeEntry(entry, BackendPaths.runtimeUserDataPath(String.valueOf(entry.get("path")))));
        }
        return result;
    }

    private List<Map<String, Object>> searchWorkdir(Workdir workdir, String query) {
        List<Map<String, Object>> entries;
        try {
            entries = workdir.search(
                    query, true, MENTION_EXCLUDE_DIRS, true, MAX_MENTION_CANDIDATES, 600, 15, 500, 10000);
        } catch (Workspace.NoSuchFileRuntime exc) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> ranked = rankEntries(entries, query, "thread");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : ranked) {
            result.add(runtimeEntry(
                    entry,
                    BackendPaths.runtimePathForWorkdirScope(workdir.getRelativePath(), String.valueOf(entry.get("path")))));
        }
        return result;
    }

    /** 用同一个有界实时扫描搜索当前 Workdir 与 UserWorkspace。 */
    public List<Map<String, Object>> searchMentions(String threadId, String query, String sources, User currentUser) {
        String normalizedQuery = query == null ? "" : String.valueOf(query).strip();
        if (normalizedQuery.isEmpty()) {
            return new ArrayList<>();
        }

        String uid = String.valueOf(currentUser.getUid());
        String effectiveThreadId = null;
        if (threadId != null && !threadId.isEmpty()) {
            com.wisesoft.wenqu.models.Conversation conversation =
                    conversationRepository.getConversationByThreadId(threadId);
            if (conversation != null) {
                if (!uid.equals(conversation.getUid()) || "deleted".equals(conversation.getStatus())) {
                    throw new MentionThreadNotFoundError("对话线程不存在");
                }
                effectiveThreadId = threadId;
            } else {
                try {
                    WorkspacePaths.validateThreadId(threadId);
                } catch (RuntimeException exc) {
                    throw new InvalidMentionThreadError("非法的 thread_id 格式");
                }
            }
        }

        List<String> sourceList = sources == null
                ? null
                : java.util.Arrays.stream(sources.split(",")).map(s -> s.strip().toLowerCase()).toList();
        List<String> selectedSources = sourceList != null
                ? sourceList
                : (effectiveThreadId != null ? List.of("thread", "workspace") : List.of("workspace"));
        List<Map<String, Object>> results = new ArrayList<>();
        if (selectedSources.contains("thread") && effectiveThreadId != null) {
            WorkdirService.AuthorizedWorkdir access =
                    workdirService.resolveAuthorizedWorkdir(effectiveThreadId, uid);
            results.addAll(searchWorkdir(access.workdir(), normalizedQuery));
        }
        if (selectedSources.contains("workspace") && results.size() < MAX_MENTION_RESULTS) {
            results.addAll(searchWorkspace(uid, normalizedQuery));
        }
        return results.subList(0, Math.min(results.size(), MAX_MENTION_RESULTS));
    }
}
