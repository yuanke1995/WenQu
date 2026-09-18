package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.KnowledgeFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * knowledge_files Mapper
 */
@Mapper
public interface KnowledgeFileMapper extends BaseMapper<KnowledgeFile> {
}
