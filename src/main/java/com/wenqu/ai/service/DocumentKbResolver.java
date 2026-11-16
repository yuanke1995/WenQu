package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文档与知识库的归属口径：默认库识别、空 kbId 归一。
 * <p>
 * 从 {@link DocumentService} 抽出：上传、列表、检索、健康度、回收站五处都要用同一口径，
 * 任一处抄一份就会出现「同一文档在不同页面归属不同库」的口径漂移。
 */
@Component
@RequiredArgsConstructor
public class DocumentKbResolver {

    private final KnowledgeBaseMapper kbMapper;

    /** 默认知识库 ID（is_default=1 且未删除；查不到返回 null） */
    public String defaultKbId() {
        KnowledgeBase def = kbMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getIsDefault, 1)
                        .eq(KnowledgeBase::getDeleted, 0)
                        .last("LIMIT 1"));
        return def == null ? null : def.getId();
    }

    /** kb_id 为空的历史行归默认库（与上传/检索链路的口径一致）；默认库也不存在时才留空 */
    public String resolveOwnKb(Object rawKb, String defaultKbId) {
        String kbId = rawKb == null ? "" : String.valueOf(rawKb);
        return kbId.isBlank() && defaultKbId != null ? defaultKbId : kbId;
    }
}
