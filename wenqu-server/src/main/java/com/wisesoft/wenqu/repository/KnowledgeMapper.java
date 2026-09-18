package com.wisesoft.wenqu.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.model.Knowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 知识片段 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface KnowledgeMapper extends BaseMapper<Knowledge> {

    /**
     * 物理删除某文档的全部知识块（版本回滚时释放主键，以便按快照原 id 重建）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM c_ai_knowledge WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") String docId);

    /**
     * 物理删除单个知识块（重解析增量对比：清理内容变更/删除的旧块）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM c_ai_knowledge WHERE id = #{id}")
    int physicalDeleteById(@Param("id") String id);
}
