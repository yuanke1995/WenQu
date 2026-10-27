package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ContentRule;

/** 内容安全规则表：只有 CRUD（判定与缓存在 {@code ContentSafetyService}） */
public interface ContentRuleMapper extends BaseMapper<ContentRule> {
}
