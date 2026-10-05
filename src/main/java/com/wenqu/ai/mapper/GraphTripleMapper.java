package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.GraphTriple;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * GraphRAG GraphTriple Mapper
 *
 * @author yuanke
 */
public interface GraphTripleMapper extends BaseMapper<GraphTriple> {

    /** 实体作为主体的三元组数（§5 图谱视图聚合度数用） */
    @Select("SELECT subject_id AS entityId, COUNT(*) AS cnt FROM c_ai_graph_triple WHERE kb_id = #{kbId} GROUP BY subject_id")
    List<Map<String, Object>> subjectDegree(@Param("kbId") String kbId);

    /** 实体作为客体的三元组数（§5 图谱视图聚合度数用） */
    @Select("SELECT object_id AS entityId, COUNT(*) AS cnt FROM c_ai_graph_triple WHERE kb_id = #{kbId} GROUP BY object_id")
    List<Map<String, Object>> objectDegree(@Param("kbId") String kbId);

    /**
     * 选中实体集合内部的关系聚合（§5）：同一 (主体,谓词,客体) 的多条三元组合并为一条带计数的关系边。
     * predicate 在 MySQL 8 非保留字，仍加反引号防将来升格。
     */
    @Select("<script>SELECT subject_id AS sourceId, `predicate` AS predicate, object_id AS targetId, COUNT(*) AS cnt"
            + " FROM c_ai_graph_triple WHERE kb_id = #{kbId}"
            + " AND subject_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND object_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY subject_id, `predicate`, object_id ORDER BY cnt DESC</script>")
    List<Map<String, Object>> aggregateEdges(@Param("kbId") String kbId, @Param("ids") List<String> ids);
}
