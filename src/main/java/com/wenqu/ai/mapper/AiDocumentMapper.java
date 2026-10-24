package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.AiDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI 文档 Mapper
 * <p>
 * 回收站一族语句刻意绕开 MyBatis-Plus：{@code deleted} 是全局逻辑删列，
 * 标准查询/更新都会被自动追加 {@code deleted=0}——已删行 selectById 恒 null、
 * 逻辑删列也不能经 update wrapper 写（SET 子句被剔除）。绕行是唯一干净路径。
 *
 * @author yuanke
 */
@Mapper
public interface AiDocumentMapper extends BaseMapper<AiDocument> {

    /** 回收站列表（最近 500 条，入站时间倒序） */
    @Select("SELECT * FROM c_ai_document WHERE deleted = 1 ORDER BY deleted_at DESC, id DESC LIMIT 500")
    List<AiDocument> selectRecycled();

    /** 回收站取单条（deleted=1；selectById 对已删行恒 null） */
    @Select("SELECT * FROM c_ai_document WHERE id = #{id} AND deleted = 1")
    AiDocument selectRecycledById(@Param("id") String id);

    /** 软删入站：单语句原子落 deleted+deleted_at（deleteById 带不上时间戳） */
    @Update("UPDATE c_ai_document SET deleted = 1, deleted_at = NOW() WHERE id = #{id} AND deleted = 0")
    int softDelete(@Param("id") String id);

    /** 恢复（出回收站） */
    @Update("UPDATE c_ai_document SET deleted = 0, deleted_at = NULL WHERE id = #{id} AND deleted = 1")
    int restore(@Param("id") String id);

    /** 彻底删除：物理删行（回收站行走 deleteById 会因 WHERE deleted=0 空转） */
    @Delete("DELETE FROM c_ai_document WHERE id = #{id}")
    int hardDelete(@Param("id") String id);

    /** 恢复前同名冲突检查（<=> 空安全等值：默认库的 kb_id NULL 历史行也要参与比对） */
    @Select("SELECT COUNT(*) FROM c_ai_document WHERE deleted = 0 AND kb_id <=> #{kbId} AND file_name = #{fileName}")
    int countActiveSameName(@Param("kbId") String kbId, @Param("fileName") String fileName);
}
