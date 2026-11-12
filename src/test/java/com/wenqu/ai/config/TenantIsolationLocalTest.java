package com.wenqu.ai.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.wenqu.ai.common.TenantResolutionException;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 本地 MySQL 租户隔离集成测试：真实 MyBatis-Plus 租户拦截器 + 真实库。
 *
 * <p>覆盖三条判据：t1/t2 互相看不见；系统线程可跨租户；带真实身份但没有租户时
 * fail-closed（抛 {@link TenantResolutionException}），不退化成“看全平台”。
 */
class TenantIsolationLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void tenantFilterIsolatesAndFailsClosed() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_tenant_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        try {
            createDatabase(db, dbUrl);

            SqlSessionFactory factory = sqlSessionFactory(dbUrl);
            try (SqlSession session = factory.openSession(true)) {
                TenantProbeMapper mapper = session.getMapper(TenantProbeMapper.class);

                TenantContext.bind("t1");
                mapper.insert("a", "alpha");
                assertThat(mapper.count()).isEqualTo(1);

                TenantContext.bind("t2");
                mapper.insert("b", "beta");
                assertThat(mapper.count()).isEqualTo(1);

                TenantContext.clear();
                RequestUser.clear();
                assertThat(mapper.count()).as("系统线程跨租户").isEqualTo(2);
            } finally {
                TenantContext.clear();
                RequestUser.clear();
            }

            try (SqlSession session = factory.openSession(true)) {
                TenantProbeMapper mapper = session.getMapper(TenantProbeMapper.class);
                TenantContext.setResolver(uid -> null);
                RequestUser.set("u-without-tenant", null, List.of("user"));
                assertThatThrownBy(mapper::count)
                        .as("真实身份但无租户必须 fail-closed")
                        .hasRootCauseInstanceOf(TenantResolutionException.class);
            } finally {
                TenantContext.setResolver(null);
                TenantContext.clear();
                RequestUser.clear();
            }
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static void createDatabase(String db, String dbUrl) throws Exception {
        try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
             Statement st = root.createStatement()) {
            st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE tenant_probe ("
                    + "id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, name VARCHAR(64))");
        }
    }

    private static SqlSessionFactory sqlSessionFactory(String url) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, USER, PASSWORD);
        MybatisPlusInterceptor mp = new MybatisPlusInterceptor();
        mp.addInnerInterceptor(new TenantLineInnerInterceptor(new WenquTenantLineHandler()));

        Configuration configuration = new Configuration();
        configuration.addMapper(TenantProbeMapper.class);

        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(configuration);
        bean.setPlugins(mp);
        return bean.getObject();
    }

    private static boolean canConnect() {
        try (Connection ignored = DriverManager.getConnection(BASE_URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    interface TenantProbeMapper {
        @org.apache.ibatis.annotations.Insert("insert into tenant_probe(id, name) values(#{id}, #{name})")
        int insert(@org.apache.ibatis.annotations.Param("id") String id,
                   @org.apache.ibatis.annotations.Param("name") String name);

        @org.apache.ibatis.annotations.Select("select count(*) from tenant_probe")
        long count();
    }
}
