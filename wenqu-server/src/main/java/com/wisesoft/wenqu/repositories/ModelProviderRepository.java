package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.repository.port.ModelProviderMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 模型供应商配置数据访问层（models/providers/repository.py 部分移植）。
 *
 * <p>已移植：list_model_providers / get_model_provider / create_model_provider /
 * delete_model_provider（解析器链路与凭证解析所需）。
 * 未移植：update_model_provider（其 setattr 循环依赖供应商管理服务层的字段白名单，
 * 随供应商管理模块一并移植——部分移植，非静默省略）。
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

    /** 删除模型供应商配置。 */
    public void deleteModelProvider(ModelProvider provider) {
        modelProviderMapper.deleteById(provider.getId());
    }
}
