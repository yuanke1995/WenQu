package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.repository.port.ModelProviderMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 模型供应商配置数据访问层（models/providers/repository.py 逐方法翻译）。
 *
 * <p>五个方法（list / get / create / update / delete）已全部移植。
 *
 * <p>平台差异（必要替换）：
 * <ul>
 *   <li>参考实现的 {@code flush()} 是本事务内的可见性保证，本工程每次 Mapper 调用即提交，
 *       因此写后重新读取即等价于 {@code flush + refresh}。
 *   <li>{@code update_model_provider} 用 {@code setattr} 逐个字段赋值（含显式 None，
 *       用于清空 api_key 等可空列）。MyBatis-Plus 的 {@code updateById} 默认跳过 null 列，
 *       会静默丢清空语义，故改为 {@code LambdaUpdateWrapper} 显式 set 每个出现过的列。
 *   <li>参考实现模型声明 {@code onupdate=utc_now_naive}，更新时自动刷新 updated_at；
 *       本层不经 ORM，故在更新语句里显式带上同一时刻。
 * </ul>
 */
@Repository
public class ModelProviderRepository {

    private final ModelProviderMapper modelProviderMapper;

    public ModelProviderRepository(ModelProviderMapper modelProviderMapper) {
        this.modelProviderMapper = modelProviderMapper;
    }

    /** 获取全部模型供应商配置（enabled 优先、provider_id 升序）。 */
    public List<ModelProvider> listModelProviders() {
        return modelProviderMapper.selectList(
                new LambdaQueryWrapper<ModelProvider>()
                        .orderByDesc(ModelProvider::getIsEnabled)
                        .orderByAsc(ModelProvider::getProviderId));
    }

    /** 按 provider_id 获取模型供应商配置。 */
    public ModelProvider getModelProvider(String providerId) {
        return modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProvider>().eq(ModelProvider::getProviderId, providerId));
    }

    /** 创建模型供应商配置。 */
    public ModelProvider createModelProvider(ModelProvider provider) {
        modelProviderMapper.insert(provider);
        return provider;
    }

    /**
     * 按字段表创建模型供应商配置（对应参考实现的 {@code ModelProvider(**data)}）。
     *
     * <p>时间列默认值在参考实现由 ORM 填充（{@code default=utc_now_naive}），本层不经 ORM，
     * 故在插入前显式补上同一时刻；未知键被忽略（列集合固定）。
     */
    public ModelProvider createModelProvider(Map<String, Object> data) {
        ModelProvider provider = new ModelProvider();
        applyFields(provider, data);
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        if (provider.getCreatedAt() == null) {
            provider.setCreatedAt(now);
        }
        if (provider.getUpdatedAt() == null) {
            provider.setUpdatedAt(now);
        }
        modelProviderMapper.insert(provider);
        return provider;
    }

    /** 把字段表写入实体（仅列集合内的键）。 */
    private static void applyFields(ModelProvider provider, Map<String, Object> data) {
        if (data == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "provider_id" -> provider.setProviderId(RepoValues.asString(value));
                case "display_name" -> provider.setDisplayName(RepoValues.asString(value));
                case "provider_type" -> provider.setProviderType(RepoValues.asString(value));
                case "default_protocol" -> provider.setDefaultProtocol(RepoValues.asString(value));
                case "base_url" -> provider.setBaseUrl(RepoValues.asString(value));
                case "embedding_base_url" -> provider.setEmbeddingBaseUrl(RepoValues.asString(value));
                case "rerank_base_url" -> provider.setRerankBaseUrl(RepoValues.asString(value));
                case "models_endpoint" -> provider.setModelsEndpoint(RepoValues.asString(value));
                case "embedding_models_endpoint" ->
                        provider.setEmbeddingModelsEndpoint(RepoValues.asString(value));
                case "rerank_models_endpoint" ->
                        provider.setRerankModelsEndpoint(RepoValues.asString(value));
                case "api_key_env" -> provider.setApiKeyEnv(RepoValues.asString(value));
                case "api_key" -> provider.setApiKey(RepoValues.asString(value));
                case "capabilities" -> provider.setCapabilities(RepoValues.toJsonText(value));
                case "enabled_models" -> provider.setEnabledModels(RepoValues.toJsonText(value));
                case "headers_json" -> provider.setHeadersJson(RepoValues.toJsonText(value));
                case "extra_json" -> provider.setExtraJson(RepoValues.toJsonText(value));
                case "is_enabled" -> provider.setIsEnabled(RepoValues.toBoolean(value));
                case "is_builtin" -> provider.setIsBuiltin(RepoValues.toBoolean(value));
                case "created_by" -> provider.setCreatedBy(RepoValues.asString(value));
                case "updated_by" -> provider.setUpdatedBy(RepoValues.asString(value));
                case "created_at" -> provider.setCreatedAt(RepoValues.toLocalDateTime(value));
                case "updated_at" -> provider.setUpdatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 未知键忽略（参考实现的 ModelProvider(**data) 遇未知键会报 TypeError，
                    // 但到达此处的键已由服务层的字段表收敛，不存在该分支）
                }
            }
        }
    }

    /**
     * 更新模型供应商配置。
     *
     * <p>{@code data} 中出现的每个列都会显式写入（含 null，用于清空）；
     * 未出现在 {@code data} 里的列保持原值。{@code provider_id} 不可改（参考实现显式跳过），
     * 非列名的键被忽略（参考实现的 {@code setattr} 对未映射属性同样不影响落库）。
     */
    public ModelProvider updateModelProvider(ModelProvider provider, Map<String, Object> data) {
        LambdaUpdateWrapper<ModelProvider> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ModelProvider::getId, provider.getId());
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            applyColumn(wrapper, entry.getKey(), entry.getValue());
        }
        wrapper.set(ModelProvider::getUpdatedAt, DateTimeUtils.utcNowNaive());
        modelProviderMapper.update(null, wrapper);
        return modelProviderMapper.selectById(provider.getId());
    }

    /** 删除模型供应商配置。 */
    public void deleteModelProvider(ModelProvider provider) {
        modelProviderMapper.deleteById(provider.getId());
    }

    private static void applyColumn(
            LambdaUpdateWrapper<ModelProvider> wrapper, String column, Object value) {
        switch (column) {
            case "display_name" -> wrapper.set(ModelProvider::getDisplayName, RepoValues.asString(value));
            case "provider_type" -> wrapper.set(ModelProvider::getProviderType, RepoValues.asString(value));
            case "default_protocol" ->
                    wrapper.set(ModelProvider::getDefaultProtocol, RepoValues.asString(value));
            case "base_url" -> wrapper.set(ModelProvider::getBaseUrl, RepoValues.asString(value));
            case "embedding_base_url" ->
                    wrapper.set(ModelProvider::getEmbeddingBaseUrl, RepoValues.asString(value));
            case "rerank_base_url" ->
                    wrapper.set(ModelProvider::getRerankBaseUrl, RepoValues.asString(value));
            case "models_endpoint" ->
                    wrapper.set(ModelProvider::getModelsEndpoint, RepoValues.asString(value));
            case "embedding_models_endpoint" ->
                    wrapper.set(ModelProvider::getEmbeddingModelsEndpoint, RepoValues.asString(value));
            case "rerank_models_endpoint" ->
                    wrapper.set(ModelProvider::getRerankModelsEndpoint, RepoValues.asString(value));
            case "api_key_env" -> wrapper.set(ModelProvider::getApiKeyEnv, RepoValues.asString(value));
            case "api_key" -> wrapper.set(ModelProvider::getApiKey, RepoValues.asString(value));
            case "capabilities" -> wrapper.set(ModelProvider::getCapabilities, RepoValues.toJsonText(value));
            case "enabled_models" ->
                    wrapper.set(ModelProvider::getEnabledModels, RepoValues.toJsonText(value));
            case "headers_json" -> wrapper.set(ModelProvider::getHeadersJson, RepoValues.toJsonText(value));
            case "extra_json" -> wrapper.set(ModelProvider::getExtraJson, RepoValues.toJsonText(value));
            case "is_enabled" -> wrapper.set(ModelProvider::getIsEnabled, RepoValues.toBoolean(value));
            case "is_builtin" -> wrapper.set(ModelProvider::getIsBuiltin, RepoValues.toBoolean(value));
            case "created_by" -> wrapper.set(ModelProvider::getCreatedBy, RepoValues.asString(value));
            case "updated_by" -> wrapper.set(ModelProvider::getUpdatedBy, RepoValues.asString(value));
            case "created_at" ->
                    wrapper.set(ModelProvider::getCreatedAt, RepoValues.toLocalDateTime(value));
            case "updated_at" ->
                    wrapper.set(ModelProvider::getUpdatedAt, RepoValues.toLocalDateTime(value));
            default -> {
                // provider_id 不可改；其余非列名键无对应列，忽略（与参考实现的 setattr 行为一致）
            }
        }
    }
}
