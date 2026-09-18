package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.repository.port.KnowledgeBaseMapper;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;

/**
 * 知识库仓储。
 *
 * <p>由参考实现的 repositories/knowledge_base_repository.py 逐方法翻译：方法名、查询条件、
 * 缓存失效时机、行锁下的合并与统计刷新语义保持一致。
 *
 * <p>必要替换（MySQL 方言）：
 * <ul>
 *   <li>行锁：参考实现的 {@code with_for_update()} 在本工程用 {@code FOR UPDATE} 后缀实现，
 *       语义同为"取行时加排他锁"。
 *   <li>每个方法一个事务：参考实现每个方法开启会话（结束即提交），本工程对应每次 Mapper
 *       调用即提交；需要多语句原子性的动作由调用方事务边界承担。
 * </ul>
 */
@Repository
public class KnowledgeBaseRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRepository.class);

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeFileRepository fileRepository;
    private final KnowledgeBaseCache cache;

    public KnowledgeBaseRepository(
            KnowledgeBaseMapper kbMapper,
            @Lazy KnowledgeFileRepository fileRepository,
            KnowledgeBaseCache cache) {
        this.kbMapper = kbMapper;
        this.fileRepository = fileRepository;
        this.cache = cache;
    }

    /** 按知识库类型聚合数量。 */
    public List<Map.Entry<String, Long>> countByType() {
        QueryWrapper<KnowledgeBase> wrapper = new QueryWrapper<>();
        wrapper.select("kb_type", "COUNT(*) AS cnt").groupBy("kb_type");
        List<Map<String, Object>> rows = kbMapper.selectMaps(wrapper);
        List<Map.Entry<String, Long>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object type = row.get("kb_type");
            Object count = row.get("cnt");
            String typeText = type == null || String.valueOf(type).isEmpty() ? "unknown" : String.valueOf(type);
            Long countValue = RepoValues.toLong(count);
            result.add(new AbstractMap.SimpleEntry<>(typeText, countValue == null ? 0L : countValue));
        }
        return result;
    }

    /** 全部知识库。 */
    public List<KnowledgeBase> getAll() {
        return kbMapper.selectList(new LambdaQueryWrapper<>());
    }

    /** 按 kb_id 取单个知识库。 */
    public KnowledgeBase getByKbId(String kbId) {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getKbId, kbId));
    }

    /** 新建知识库并写入缓存快照。 */
    public KnowledgeBase create(Map<String, Object> data) {
        KnowledgeBase kb = new KnowledgeBase();
        applyFields(kb, data);
        // 时间列默认值在参考实现由 ORM 填充，本层不经 ORM，需显式补上
        if (kb.getCreatedAt() == null) {
            kb.setCreatedAt(com.wisesoft.wenqu.common.DateTimeUtils.utcNowNaive());
        }
        if (kb.getUpdatedAt() == null) {
            kb.setUpdatedAt(com.wisesoft.wenqu.common.DateTimeUtils.utcNowNaive());
        }
        kbMapper.insert(kb);
        cache.cacheKbConfig(kb);
        return kb;
    }

    /**
     * 编辑知识库。
     *
     * <p>先可靠清除旧缓存；取不到记录时返回 null（不进入写操作）。
     */
    public KnowledgeBase update(String kbId, Map<String, Object> data) {
        try (AutoCloseable ignored = cache.lock(kbId)) {
            cache.deleteCachedKbConfig(kbId);
            KnowledgeBase kb =
                    kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getKbId, kbId));
            if (kb == null) {
                return null;
            }
            applyFields(kb, data);
            kbMapper.updateById(kb);
            return kb;
        } catch (Exception exc) {
            throw wrap("知识库更新失败: kb_id=" + kbId, exc);
        }
    }

    /** 在行锁内合并知识库查询参数，避免并发部分更新互相覆盖。 */
    public KnowledgeBase mergeQueryParamsOptions(String kbId, Map<String, Object> params) {
        try (AutoCloseable ignored = cache.lock(kbId)) {
            cache.deleteCachedKbConfig(kbId);
            KnowledgeBase kb =
                    kbMapper.selectOne(
                            new LambdaQueryWrapper<KnowledgeBase>()
                                    .eq(KnowledgeBase::getKbId, kbId)
                                    .last("FOR UPDATE"));
            if (kb == null) {
                return null;
            }
            JSONObject queryParams = RepoValues.parseObject(kb.getQueryParams());
            JSONObject options = queryParams.getJSONObject("options");
            if (options == null) {
                options = new JSONObject();
            }
            options.putAll(params);
            queryParams.put("options", options);
            kb.setQueryParams(JSON.toJSONString(queryParams));
            kbMapper.updateById(kb);
            return kb;
        } catch (Exception exc) {
            throw wrap("知识库检索参数合并失败: kb_id=" + kbId, exc);
        }
    }

    /** 在行锁内从文件聚合刷新统计，保留其他附加参数。 */
    public KnowledgeBase refreshStats(String kbId) {
        try (AutoCloseable ignored = cache.lock(kbId)) {
            KnowledgeBase kb =
                    kbMapper.selectOne(
                            new LambdaQueryWrapper<KnowledgeBase>()
                                    .eq(KnowledgeBase::getKbId, kbId)
                                    .last("FOR UPDATE"));
            if (kb == null) {
                return null;
            }
            // 聚合必须在取得行锁后执行，避免较早的快照晚写覆盖新结果。
            Map<String, Object> stats = fileRepository.queryKbFileStats(kbId);
            JSONObject additionalParams = RepoValues.parseObject(kb.getAdditionalParams());
            additionalParams.put("stats", stats);
            kb.setAdditionalParams(JSON.toJSONString(additionalParams));
            kbMapper.updateById(kb);
            return kb;
        } catch (Exception exc) {
            throw wrap("知识库统计刷新失败: kb_id=" + kbId, exc);
        }
    }

    /** 删除知识库（连带清除缓存）。 */
    public void delete(String kbId) {
        try (AutoCloseable ignored = cache.lock(kbId)) {
            cache.deleteCachedKbConfig(kbId);
            KnowledgeBase kb =
                    kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>().eq(KnowledgeBase::getKbId, kbId));
            if (kb != null) {
                kbMapper.deleteById(kb);
            }
        } catch (Exception exc) {
            throw wrap("知识库删除失败: kb_id=" + kbId, exc);
        }
    }

    /** 把请求字段写入实体（仅写入 data 中出现过的键，未提及的键保持原值）。 */
    static void applyFields(KnowledgeBase kb, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "kb_id" -> kb.setKbId(RepoValues.asString(value));
                case "name" -> kb.setName(RepoValues.asString(value));
                case "description" -> kb.setDescription(RepoValues.asString(value));
                case "kb_type" -> kb.setKbType(RepoValues.asString(value));
                case "embedding_model_spec" -> kb.setEmbeddingModelSpec(RepoValues.asString(value));
                case "llm_model_spec" -> kb.setLlmModelSpec(RepoValues.asString(value));
                case "query_params" -> kb.setQueryParams(RepoValues.toJsonText(value));
                case "additional_params" -> kb.setAdditionalParams(RepoValues.toJsonText(value));
                case "share_config" -> kb.setShareConfig(RepoValues.toJsonText(value));
                case "created_by" -> kb.setCreatedBy(RepoValues.asString(value));
                case "created_at" -> kb.setCreatedAt(RepoValues.toLocalDateTime(value));
                case "updated_at" -> kb.setUpdatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键：参考实现用 setattr 会直接报错；本层保持"忽略"以免历史调用中断。
                    // 注意 knowledge_bases 没有 is_default / deleted / status 列（参考实现的模型不含软删除），
                    // 因此这几个键在本仓储不会写入。
                }
            }
        }
    }

    private static RuntimeException wrap(String message, Exception exc) {
        log.warn("{}: {}", message, exc.getMessage());
        return exc instanceof RuntimeException runtime ? runtime : new IllegalStateException(message, exc);
    }
}
