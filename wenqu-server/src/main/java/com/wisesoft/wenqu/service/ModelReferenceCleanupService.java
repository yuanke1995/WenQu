package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.models.ConfigOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型下线的引用清理 —— 模型从供应商 {@code enabled_models} 里被摘掉后，
 * 把散落在各处的同一 model spec 引用一并撤下，避免留下悬空引用。
 *
 * <p>背景（本项目新增，参考实现无对应模块）：删除模型原本只改写供应商自己的
 * {@code enabled_models}，被引用的位置一律不动，于是：
 * <ul>
 *   <li>{@code config_options.system_options} 的 {@code default_model} 等仍指向已下线模型，
 *       前端取到它当默认 spec，发送时才在 {@code ModelSelectors.resolveModelSpec} 抛错；</li>
 *   <li>{@code agents.config_json.context.model} 保留旧模型，优先级高于系统默认
 *       （见 {@code AgentChatComponent.currentModelSpec}），新建对话直接继承这个悬空值；</li>
 *   <li>{@code conversations.extra_metadata.model_spec} 同理，历史会话永久带着不可用的模型；</li>
 *   <li>{@code scheduled_agent_jobs.model_spec} 指向已下线模型时每次定时执行都会失败。</li>
 * </ul>
 *
 * <p>清理口径是「撤下引用」而不是「替换成别的模型」：挑另一个可用模型属于猜用户意图，
 * 各消费方本就有「会话 → 智能体 → 系统默认」的优先级链（空值自然会回落到下一级），
 * 这里只负责把不该再存在的引用抹掉。
 *
 * <p>能力差异（标注）：参考实现没有这一环，因此本类属于本工程自建的数据一致性维护；
 * 涉及的 JSON 列改写用 MySQL 的 {@code JSON_REMOVE} + {@code JSON_UNQUOTE(JSON_EXTRACT(...))}
 * 精确定位，避免整列读改写带来的并发覆盖。
 */
@Service
public class ModelReferenceCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ModelReferenceCleanupService.class);

    /** system_options 中存放模型 spec 的字段。 */
    private static final Set<String> SYSTEM_OPTION_MODEL_KEYS =
            Set.of("default_model", "fast_model", "embed_model", "reranker");

    private final JdbcTemplate jdbc;
    private final OptionsService optionsService;

    public ModelReferenceCleanupService(JdbcTemplate jdbc, OptionsService optionsService) {
        this.jdbc = jdbc;
        this.optionsService = optionsService;
    }

    /**
     * 撤下所有指向 {@code removedSpecs} 的引用。
     *
     * @return 各来源的实际清理数量（key：system_options / agents / conversations /
     *         scheduled_agent_jobs），供调用方写入日志或操作流水。
     */
    @Transactional
    public Map<String, Object> cleanupRemovedSpecs(Collection<String> removedSpecs, String operator) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (removedSpecs == null || removedSpecs.isEmpty()) {
            return summary;
        }

        List<String> specs = new ArrayList<>();
        for (String spec : removedSpecs) {
            if (spec != null && !spec.isBlank() && !specs.contains(spec)) {
                specs.add(spec);
            }
        }
        if (specs.isEmpty()) {
            return summary;
        }

        List<String> clearedOptions = clearSystemOptions(specs, operator);
        int agents = removeAgentContextModel(specs);
        int conversations = removeConversationModelSpec(specs);
        int jobs = clearScheduledJobModelSpec(specs);

        summary.put("system_options", clearedOptions);
        summary.put("agents", agents);
        summary.put("conversations", conversations);
        summary.put("scheduled_agent_jobs", jobs);
        log.info(
                "模型下线引用清理完成: specs={}, 系统配置项={}, 智能体={}, 会话={}, 定时任务={}",
                specs, clearedOptions, agents, conversations, jobs);
        return summary;
    }

    /** 清空命中 agent 的系统配置项（置空后按既有优先级链回落到代码内默认值）。 */
    private List<String> clearSystemOptions(List<String> specs, String operator) {
        ConfigOption record = optionsService.getOption(OptionsService.SYSTEM_OPTIONS.getKey());
        if (record == null) {
            return List.of();
        }
        Object value = optionsService.serializeOption(record).get("value");
        if (!(value instanceof Map<?, ?> current)) {
            return List.of();
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        for (String key : SYSTEM_OPTION_MODEL_KEYS) {
            Object configured = current.get(key);
            if (configured == null) {
                continue;
            }
            String configuredSpec = String.valueOf(configured).strip();
            if (specs.contains(configuredSpec)) {
                patch.put(key, "");
            }
        }
        if (patch.isEmpty()) {
            return List.of();
        }

        optionsService.updateOptionValue(
                OptionsService.SYSTEM_OPTIONS.getKey(), patch, operator == null ? "system" : operator);
        optionsService.invalidateOptionCache(OptionsService.SYSTEM_OPTIONS.getKey());
        return new ArrayList<>(patch.keySet());
    }

    /** 摘掉智能体行配置里的模型覆盖（只删 $.context.model 这一个键，其余配置字段不动）。 */
    private int removeAgentContextModel(List<String> specs) {
        int total = 0;
        for (String spec : specs) {
            total += jdbc.update(
                    "UPDATE agents SET config_json = JSON_REMOVE(config_json, '$.context.model') "
                            + "WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.context.model')) = ?",
                    spec);
        }
        return total;
    }

    /** 摘掉会话行元数据里的模型覆盖，使其回落到智能体/系统默认模型。 */
    private int removeConversationModelSpec(List<String> specs) {
        int total = 0;
        for (String spec : specs) {
            total += jdbc.update(
                    "UPDATE conversations SET extra_metadata ="
                            + " JSON_REMOVE(extra_metadata, '$.model_spec') "
                            + "WHERE JSON_UNQUOTE(JSON_EXTRACT(extra_metadata, '$.model_spec')) = ?",
                    spec);
        }
        return total;
    }

    /** 定时任务模型置空：运行时按既有规则取系统默认模型，而不是每次执行都撞已下线模型。 */
    private int clearScheduledJobModelSpec(List<String> specs) {
        int total = 0;
        for (String spec : specs) {
            total += jdbc.update(
                    "UPDATE scheduled_agent_jobs SET model_spec = NULL WHERE model_spec = ?", spec);
        }
        return total;
    }

    /** 是否有任何来源真的被清理（供调用方决定是否告警/记录）。 */
    public static boolean hasChanges(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return false;
        }
        Object options = summary.get("system_options");
        if (options instanceof Collection<?> cleared && !cleared.isEmpty()) {
            return true;
        }
        for (String key : List.of("agents", "conversations", "scheduled_agent_jobs")) {
            Object count = summary.get(key);
            if (count instanceof Number number && number.intValue() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 把清理结果渲染成一句人话（日志/提示用）。 */
    public static String describe(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        Object options = summary.get("system_options");
        StringBuilder text = new StringBuilder();
        if (options instanceof Collection<?> cleared && !cleared.isEmpty()) {
            text.append("系统配置项 ").append(String.join("、", cleared.stream().map(String::valueOf).toList()));
        }
        appendCount(text, "智能体", summary.get("agents"));
        appendCount(text, "会话", summary.get("conversations"));
        appendCount(text, "定时任务", summary.get("scheduled_agent_jobs"));
        String result = text.toString().strip();
        return result.isEmpty() ? "" : "已自动清理引用：" + result;
    }

    private static void appendCount(StringBuilder text, String label, Object count) {
        if (count instanceof Number number && number.intValue() > 0) {
            if (!text.isEmpty()) {
                text.append("，");
            }
            text.append(label).append(' ').append(number.intValue()).append(" 处");
        }
    }
}
