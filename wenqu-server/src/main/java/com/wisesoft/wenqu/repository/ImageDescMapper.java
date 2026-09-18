package com.wisesoft.wenqu.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.model.ImageDesc;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 图片描述缓存 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ImageDescMapper extends BaseMapper<ImageDesc> {

    /** 原子 upsert：并发写同一 key 不产生重复行，刷新描述并更新 update_time（无写撕裂） */
    @Insert("INSERT INTO c_ai_image_desc(cache_key, description, model) " +
            "VALUES(#{key}, #{description}, #{model}) " +
            "ON DUPLICATE KEY UPDATE description = VALUES(description), update_time = NOW()")
    int upsert(@Param("key") String key, @Param("description") String description, @Param("model") String model);

    /** 清理超 TTL 过期行 + key 前缀不匹配当前版本的行（版本号 bump 后旧缓存回收） */
    @Delete("DELETE FROM c_ai_image_desc WHERE update_time < #{cutoff} OR cache_key NOT LIKE #{versionPrefix}")
    int pruneExpired(@Param("cutoff") LocalDateTime cutoff, @Param("versionPrefix") String versionPrefix);

    /** 版本号变更后清理旧版本行（保留未过期的当前版本缓存） */
    @Delete("DELETE FROM c_ai_image_desc WHERE cache_key NOT LIKE #{versionPrefix}")
    int pruneOldVersion(@Param("versionPrefix") String versionPrefix);

    /** 命中后刷新"最近命中"（TTL 基准；PK 更新，幂等且便宜） */
    @Update("UPDATE c_ai_image_desc SET update_time = NOW() WHERE cache_key = #{key}")
    int touch(@Param("key") String key);

    /** 缓存统计：总条数 / 模型数 / 最新与最旧命中时间 */
    @Select("SELECT COUNT(*) AS total, COUNT(DISTINCT model) AS models, " +
            "MAX(update_time) AS newest, MIN(update_time) AS oldest FROM c_ai_image_desc")
    Map<String, Object> stats();
}
