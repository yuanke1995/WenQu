package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Skill;
import com.wisesoft.wenqu.repository.port.SkillMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 仓储。
 *
 * <p>由参考实现的 agents/skills/repository.py 逐方法翻译：列表（按更新时间倒序）、
 * slug 查询（可加行锁）、创建、内置安装/依赖/元数据/共享配置/启用状态更新、删除。
 *
 * <p>必要替换：AsyncSession 的 add/flush/refresh → MyBatis-Plus
 * insert/updateById/deleteById（主键回填等价 refresh）；JSON 列（依赖清单与
 * share_config）在实体上以 JSON 字符串承载，此处统一序列化。更新类方法的字段均非空，
 * 不触发框架默认更新策略跳过 null 列的问题。
 */
@Repository
public class SkillRepository {

    private final SkillMapper skillMapper;

    public SkillRepository(SkillMapper skillMapper) {
        this.skillMapper = skillMapper;
    }

    public List<Skill> listAll() {
        return skillMapper.selectList(
                new LambdaQueryWrapper<Skill>().orderByDesc(Skill::getUpdatedAt).orderByDesc(Skill::getId));
    }

    public List<Skill> listEnabled() {
        return skillMapper.selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getEnabled, true)
                .orderByDesc(Skill::getUpdatedAt)
                .orderByDesc(Skill::getId));
    }

    public Skill getBySlug(String slug, boolean forUpdate) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>().eq(Skill::getSlug, slug);
        if (forUpdate) {
            wrapper.last("FOR UPDATE");
        }
        return skillMapper.selectOne(wrapper);
    }

    public boolean existsSlug(String slug) {
        return getBySlug(slug, false) != null;
    }

    @Transactional
    public Skill create(
            String slug,
            String name,
            String description,
            String sourceType,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies,
            String dirPath,
            Object shareConfig,
            boolean enabled,
            String version,
            String contentHash,
            String createdBy) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        Skill item = new Skill();
        item.setSlug(slug);
        item.setName(name);
        item.setDescription(description);
        item.setSourceType(sourceType);
        item.setToolDependencies(JSON.toJSONString(toolDependencies == null ? List.of() : toolDependencies));
        item.setMcpDependencies(JSON.toJSONString(mcpDependencies == null ? List.of() : mcpDependencies));
        item.setSkillDependencies(JSON.toJSONString(skillDependencies == null ? List.of() : skillDependencies));
        item.setDirPath(dirPath);
        item.setVersion(version);
        item.setContentHash(contentHash);
        item.setShareConfig(JSON.toJSONString(shareConfig, JSONWriter.Feature.WriteMapNullValue));
        item.setEnabled(enabled);
        item.setCreatedBy(createdBy);
        item.setUpdatedBy(createdBy);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        skillMapper.insert(item);
        return item;
    }

    @Transactional
    public Skill updateBuiltinInstall(Skill item, String version, String contentHash, String updatedBy) {
        item.setVersion(version);
        item.setContentHash(contentHash);
        item.setSourceType("builtin");
        // 参考实现 manage_scope 为 None（JSON null），保持键序
        java.util.LinkedHashMap<String, Object> shareConfig = new java.util.LinkedHashMap<>();
        shareConfig.put("version", 2);
        java.util.LinkedHashMap<String, Object> readScope = new java.util.LinkedHashMap<>();
        readScope.put("access_level", "global");
        readScope.put("department_ids", List.of());
        readScope.put("user_uids", List.of());
        shareConfig.put("read_scope", readScope);
        shareConfig.put("manage_scope", null);
        item.setShareConfig(JSON.toJSONString(shareConfig, JSONWriter.Feature.WriteMapNullValue));
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        skillMapper.updateById(item);
        return item;
    }

    @Transactional
    public Skill updateDependencies(
            Skill item,
            List<String> toolDependencies,
            List<String> mcpDependencies,
            List<String> skillDependencies,
            String updatedBy) {
        item.setToolDependencies(JSON.toJSONString(toolDependencies));
        item.setMcpDependencies(JSON.toJSONString(mcpDependencies));
        item.setSkillDependencies(JSON.toJSONString(skillDependencies));
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        skillMapper.updateById(item);
        return item;
    }

    @Transactional
    public Skill updateMetadata(Skill item, String name, String description, String updatedBy) {
        item.setName(name);
        item.setDescription(description);
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        skillMapper.updateById(item);
        return item;
    }

    @Transactional
    public Skill updateShareConfig(Skill item, Object shareConfig, String updatedBy) {
        item.setShareConfig(JSON.toJSONString(shareConfig, JSONWriter.Feature.WriteMapNullValue));
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        skillMapper.updateById(item);
        return item;
    }

    @Transactional
    public Skill updateEnabled(Skill item, boolean enabled, String updatedBy) {
        item.setEnabled(enabled);
        item.setUpdatedBy(updatedBy);
        item.setUpdatedAt(DateTimeUtils.utcNowNaive());
        skillMapper.updateById(item);
        return item;
    }

    @Transactional
    public void delete(Skill item) {
        skillMapper.deleteById(item.getId());
    }
}
