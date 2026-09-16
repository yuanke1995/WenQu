package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.KnowledgeRef;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 知识块引用关系 Mapper（纯派生数据，物理删）
 *
 * @author yuanke
 */
@Mapper
public interface KnowledgeRefMapper extends BaseMapper<KnowledgeRef> {

    /** 物理删除某文档全部引用（重解析/删文档/回滚重建用） */
    @Delete("DELETE FROM c_ai_knowledge_ref WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") String docId);

    /** 删除某块的全部出边（A 引用 B：编辑/删除块时重建） */
    @Delete("DELETE FROM c_ai_knowledge_ref WHERE from_knowledge_id = #{knowledgeId}")
    int deleteByFromId(@Param("knowledgeId") String knowledgeId);

    /** 删除某块的全部入边（C 引用 A：删除块时清理） */
    @Delete("DELETE FROM c_ai_knowledge_ref WHERE to_knowledge_id = #{knowledgeId}")
    int deleteByToId(@Param("knowledgeId") String knowledgeId);

    /** 出边查询：这些块引用了谁（命中 A → 带出 B） */
    @Select("<script>SELECT * FROM c_ai_knowledge_ref WHERE from_knowledge_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<KnowledgeRef> selectByFromIds(@Param("ids") List<String> ids);

    /** 入边查询：谁引用了这些块（命中 B → 带出 C，默认关） */
    @Select("<script>SELECT * FROM c_ai_knowledge_ref WHERE to_knowledge_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<KnowledgeRef> selectByToIds(@Param("ids") List<String> ids);
}
