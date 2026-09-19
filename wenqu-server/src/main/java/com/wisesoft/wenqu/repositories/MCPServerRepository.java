package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.MCPServer;
import com.wisesoft.wenqu.repository.port.MCPServerMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * MCP 服务器配置数据访问层。
 *
 * <p>参考实现的 {@code agents/mcp/service.py} 直接持 SQLAlchemy {@code AsyncSession} 发查询，
 * 没有独立的 repository 模块，故本类按「参考实现里出现过的每一条查询/写入」逐条对应翻译
 * （不新增参考实现没有的查询），查询条件与排序、写入列集合均逐字对齐：
 * <ul>
 *   <li>{@code select(MCPServer).filter(MCPServer.slug == slug)} → {@link #getBySlug(String)}</li>
 *   <li>{@code select(MCPServer)} → {@link #listAll()}</li>
 *   <li>{@code select(MCPServer).filter(slug == s, created_by == "system")} → {@link #getBySlugAndCreatedBy}</li>
 *   <li>{@code select(MCPServer).where(enabled == 1, or_(transport != "stdio", slug.in_(builtin)))} →
 *       {@link #listEnabled(List, List)} 与 {@link #listEnabledSlugs(List, List)}</li>
 *   <li>{@code select(MCPServer).where(transport == "stdio", ~slug.in_(builtin), enabled == 1)} →
 *       {@link #listLegacyEnabledStdio(List)}</li>
 *   <li>{@code session.add(...)} / {@code session.delete(...)} → {@link #insert(MCPServer)} /
 *       {@link #delete(MCPServer)}</li>
 *   <li>ORM 的 {@code setattr + commit} → {@link #updateColumns(MCPServer, Map)}</li>
 * </ul>
 *
 * <p>平台差异（必要替换，逐条说明）：
 * <ul>
 *   <li><b>JSON 列</b>：参考实现的 {@code args/env/headers/tags/disabled_tools} 是 SQLAlchemy
 *       {@code JSON} 列，ORM 直接给 list/dict；本工程实体把它们映射为文本列，故在写入时用
 *       {@link RepoValues#toJsonText} 序列化，读取后的反序列化由
 *       {@code agents/McpServerViews} 负责（调用方按需解析）。</li>
 *   <li><b>清空语义</b>：{@code update_mcp_server} 显式把 {@code command/args/env} 置 None。
 *       MyBatis-Plus 的 {@code updateById} 默认跳过 null 列（会静默丢清空语义），故改用
 *       {@link LambdaUpdateWrapper} 显式 set 每个出现过的列——与
 *       {@code ModelProviderRepository.updateModelProvider} 同一处理方式。</li>
 *   <li><b>时间列</b>：参考实现 {@code onupdate=utc_now_naive} 由 ORM 刷新 {@code updated_at}；
 *       本层不经 ORM，故在更新语句里显式带上同一时刻。</li>
 *   <li><b>事务</b>：参考实现由 session 上下文管理器统一 commit；本工程每次 Mapper 调用即提交，
 *       「多处改动一起落库」由服务层的 {@code @Transactional} 保证（见 {@code McpService}）。</li>
 * </ul>
 */
@Repository
public class MCPServerRepository {

    private final MCPServerMapper mcpServerMapper;

    public MCPServerRepository(MCPServerMapper mcpServerMapper) {
        this.mcpServerMapper = mcpServerMapper;
    }

    /** 按 slug 取单条配置（slug 有唯一键，等价于 {@code scalar_one_or_none}）。 */
    public MCPServer getBySlug(String slug) {
        return mcpServerMapper.selectOne(
                new LambdaQueryWrapper<MCPServer>().eq(MCPServer::getSlug, slug));
    }

    /** 按 slug + created_by 取单条（用于「已下线的内置服务器仅删系统创建的那条」判定）。 */
    public MCPServer getBySlugAndCreatedBy(String slug, String createdBy) {
        return mcpServerMapper.selectOne(new LambdaQueryWrapper<MCPServer>()
                .eq(MCPServer::getSlug, slug)
                .eq(MCPServer::getCreatedBy, createdBy));
    }

    /** 全部配置（参考实现未加排序，此处同样不排序）。 */
    public List<MCPServer> listAll() {
        return mcpServerMapper.selectList(new LambdaQueryWrapper<>());
    }

    /**
     * 已启用且「可运行」的服务器：{@code enabled == 1 AND (transport != 'stdio' OR slug IN builtinSlugs)}；
     * {@code names} 非空时再收窄到这些 slug。
     */
    public List<MCPServer> listEnabled(List<String> builtinSlugs, List<String> names) {
        LambdaQueryWrapper<MCPServer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MCPServer::getEnabled, 1);
        applyStdioGuard(wrapper, builtinSlugs);
        if (names != null && !names.isEmpty()) {
            wrapper.in(MCPServer::getSlug, names);
        }
        return mcpServerMapper.selectList(wrapper);
    }

    /** {@link #listEnabled} 的只取 slug 版本（参考实现只 select slug 列）。 */
    public List<String> listEnabledSlugs(List<String> builtinSlugs, List<String> names) {
        List<String> slugs = new ArrayList<>();
        for (MCPServer server : listEnabled(builtinSlugs, names)) {
            if (server.getSlug() != null) {
                slugs.add(server.getSlug());
            }
        }
        return slugs;
    }

    /** 需要迁移的历史用户 stdio 服务器：{@code transport == 'stdio' AND slug NOT IN builtin AND enabled == 1}。 */
    public List<MCPServer> listLegacyEnabledStdio(List<String> builtinSlugs) {
        LambdaQueryWrapper<MCPServer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MCPServer::getTransport, "stdio");
        if (builtinSlugs != null && !builtinSlugs.isEmpty()) {
            wrapper.notIn(MCPServer::getSlug, builtinSlugs);
        }
        wrapper.eq(MCPServer::getEnabled, 1);
        return mcpServerMapper.selectList(wrapper);
    }

    /** 插入一行（时间列由本层补齐，对应 ORM 的 {@code default=utc_now_naive}）。 */
    public MCPServer insert(MCPServer server) {
        if (server.getEnabled() == null) {
            server.setEnabled(1);
        }
        LocalDateTime now = DateTimeUtils.utcNowNaive();  // 同一时刻写入两列
        if (server.getCreatedAt() == null) {
            server.setCreatedAt(now);
        }
        if (server.getUpdatedAt() == null) {
            server.setUpdatedAt(now);
        }
        mcpServerMapper.insert(server);
        return server;
    }

    /** 删除一行。 */
    public void delete(MCPServer server) {
        mcpServerMapper.deleteById(server.getId());
    }

    /**
     * 按显式列集合更新一行（{@code data} 中出现的列一律写入，含 null —— 用于清空）。
     *
     * <p>未出现在 {@code data} 里的列保持原值；{@code updated_at} 总是刷新。
     */
    public MCPServer updateColumns(MCPServer server, Map<String, Object> data) {
        LambdaUpdateWrapper<MCPServer> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MCPServer::getId, server.getId());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            applyColumn(wrapper, entry.getKey(), entry.getValue());
        }
        wrapper.set(MCPServer::getUpdatedAt, DateTimeUtils.utcNowNaive());
        mcpServerMapper.update(null, wrapper);
        return mcpServerMapper.selectById(server.getId());
    }

    /**
     * {@code or_(transport != 'stdio', slug IN builtinSlugs)} 的分支。
     *
     * <p>内置 slug 集合为空时退化为「只要 transport != 'stdio'」（避免生成非法的 {@code IN ()}）。
     */
    private static void applyStdioGuard(LambdaQueryWrapper<MCPServer> wrapper, List<String> builtinSlugs) {
        if (builtinSlugs == null || builtinSlugs.isEmpty()) {
            wrapper.ne(MCPServer::getTransport, "stdio");
            return;
        }
        wrapper.and(inner -> inner.ne(MCPServer::getTransport, "stdio")
                .or()
                .in(MCPServer::getSlug, builtinSlugs));
    }

    /** 单列写入（列名 → 实体属性；JSON 列按文本序列化）。 */
    private static void applyColumn(LambdaUpdateWrapper<MCPServer> wrapper, String column, Object value) {
        switch (column) {
            case "name" -> wrapper.set(MCPServer::getName, RepoValues.asString(value));
            case "description" -> wrapper.set(MCPServer::getDescription, RepoValues.asString(value));
            case "transport" -> wrapper.set(MCPServer::getTransport, RepoValues.asString(value));
            case "url" -> wrapper.set(MCPServer::getUrl, RepoValues.asString(value));
            case "command" -> wrapper.set(MCPServer::getCommand, RepoValues.asString(value));
            case "args" -> wrapper.set(MCPServer::getArgs, RepoValues.toJsonText(value));
            case "env" -> wrapper.set(MCPServer::getEnv, RepoValues.toJsonText(value));
            case "headers" -> wrapper.set(MCPServer::getHeaders, RepoValues.toJsonText(value));
            case "timeout" -> wrapper.set(MCPServer::getTimeout, RepoValues.toInt(value));
            case "sse_read_timeout" ->
                    wrapper.set(MCPServer::getSseReadTimeout, RepoValues.toInt(value));
            case "tags" -> wrapper.set(MCPServer::getTags, RepoValues.toJsonText(value));
            case "icon" -> wrapper.set(MCPServer::getIcon, RepoValues.asString(value));
            case "enabled" -> wrapper.set(MCPServer::getEnabled, RepoValues.toInt(value));
            case "disabled_tools" ->
                    wrapper.set(MCPServer::getDisabledTools, RepoValues.toJsonText(value));
            case "created_by" -> wrapper.set(MCPServer::getCreatedBy, RepoValues.asString(value));
            case "updated_by" -> wrapper.set(MCPServer::getUpdatedBy, RepoValues.asString(value));
            case "created_at" ->
                    wrapper.set(MCPServer::getCreatedAt, RepoValues.toLocalDateTime(value));
            case "updated_at" ->
                    wrapper.set(MCPServer::getUpdatedAt, RepoValues.toLocalDateTime(value));
            default -> {
                // slug 是稳定标识（参考实现从不修改）；其余非列名键无对应列，忽略
            }
        }
    }
}
