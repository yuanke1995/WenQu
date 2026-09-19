package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.repositories.ModelProviderRepository;
import org.springframework.stereotype.Service;

/**
 * 模型供应商服务（models/providers/service.py 部分移植）。
 *
 * <p>已移植：resolve_api_key / get_model_provider_by_id（解析器引擎构造参数所需）。
 * 未移植：payload 归一化、内置 provider 模板、供应商 CRUD 用例等
 * （随供应商管理模块一并移植——部分移植，非静默省略）。
 */
@Service
public class ModelProviderService {

    private final ModelProviderRepository modelProviderRepository;

    public ModelProviderService(ModelProviderRepository modelProviderRepository) {
        this.modelProviderRepository = modelProviderRepository;
    }

    /** 解析 provider 的 API Key，优先直接配置，其次从环境变量读取。 */
    public static String resolveApiKey(ModelProvider provider) {
        if (provider.getApiKey() != null && !provider.getApiKey().isEmpty()) {
            return provider.getApiKey();
        }
        if (provider.getApiKeyEnv() != null && !provider.getApiKeyEnv().isEmpty()) {
            return System.getenv(provider.getApiKeyEnv());
        }
        return null;
    }

    /** 按 provider_id 获取独立模型供应商配置。 */
    public ModelProvider getModelProviderById(String providerId) {
        return modelProviderRepository.getModelProvider(providerId);
    }
}
