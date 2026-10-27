package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.Quota;

/** 配额上限表：只有 CRUD（用量聚合在 {@link UsageLogMapper}，账目归台账） */
public interface QuotaMapper extends BaseMapper<Quota> {
}
