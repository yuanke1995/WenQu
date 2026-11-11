package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 用户 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 成员域概览（成员管理页首屏统计条用；只读聚合）。
     * <p>一次算完，避免前端为了凑几个数字发六七个请求。
     * <p>租户口径由 {@code WenquTenantLineHandler} 在 SQL 上拼——本方法不写 tenant_id
     * （写了就会与拦截器拼的��份漂）。
     */
    @Select("<script>SELECT" +
            " COUNT(*) AS total," +
            " SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS active," +
            " SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS disabled," +
            " SUM(CASE WHEN department_id IS NULL OR department_id = '' THEN 1 ELSE 0 END) AS unassigned," +
            " SUM(CASE WHEN last_login_time &gt;= #{todayStart} THEN 1 ELSE 0 END) AS loggedInToday," +
            " SUM(CASE WHEN last_login_time IS NULL OR last_login_time &lt; #{inactiveCutoff} THEN 1 ELSE 0 END) AS inactive30d," +
            " SUM(CASE WHEN locked_until &gt; #{now} THEN 1 ELSE 0 END) AS locked" +
            " FROM c_ai_user</script>")
    Map<String, Object> memberStats(@Param("todayStart") LocalDateTime todayStart,
                                    @Param("inactiveCutoff") LocalDateTime inactiveCutoff,
                                    @Param("now") LocalDateTime now);

    /**
     * 认证方式分布：providerId → 绑定该认证源的账号数（认证方式筛选与概览用）。
     * <p>「本地账号」= 没有任何外部身份绑定，单独算（不能靠总数减，否则重复绑定会算错）。
     */
    @Select("SELECT provider_id AS providerId, COUNT(DISTINCT uid) AS cnt FROM c_ai_user_identity GROUP BY provider_id")
    List<Map<String, Object>> authSourceCounts();

    /** 没有任何外部身份绑定的账号数（= 本地账号口径） */
    @Select("SELECT COUNT(*) FROM c_ai_user u WHERE NOT EXISTS " +
            "(SELECT 1 FROM c_ai_user_identity i WHERE i.uid = u.uid)")
    long localAccountCount();

    /** 部门人数分布：departmentId（空串=未分配）→ 人数（部门树的人数徽标用） */
    @Select("SELECT COALESCE(NULLIF(department_id, ''), '__unassigned__') AS departmentId, COUNT(*) AS cnt " +
            "FROM c_ai_user GROUP BY COALESCE(NULLIF(department_id, ''), '__unassigned__')")
    List<Map<String, Object>> departmentMemberCounts();
}
