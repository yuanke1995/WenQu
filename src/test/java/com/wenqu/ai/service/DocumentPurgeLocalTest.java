package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.AiDocumentVersionMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * 文档彻底删除真实 MySQL 集成测试：知识块 / 版本快照 / 文档行同一事务；
 * 任一删除失败时三者都必须回滚（S1-4）。
 */
class DocumentPurgeLocalTest {

    private static final String BASE_URL = System.getProperty("it.mysql.baseUrl",
            "jdbc:mysql://127.0.0.1:3306/?useUnicode=true&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
    private static final String USER = System.getProperty("it.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("it.mysql.password", "");

    @Test
    void purgeRemovesRowsAtomically() throws Exception {
        assumeTrue(canConnect(), "本机 MySQL 不可达，跳过本地集成测试");
        String db = "wenqu_doc_it_" + System.currentTimeMillis();
        String dbUrl = BASE_URL.replace("/?", "/" + db + "?");
        Path dataDir = Files.createTempDirectory("wenqu-doc-it-");

        try {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("CREATE DATABASE `" + db + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
            Flyway.configure().dataSource(dbUrl, USER, PASSWORD)
                    .locations("classpath:db/migration").load().migrate();

            DriverManagerDataSource ds = new DriverManagerDataSource(dbUrl, USER, PASSWORD);
            SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(ds));
            AiDocumentMapper docMapper = template.getMapper(AiDocumentMapper.class);
            KnowledgeMapper knowledgeMapper = template.getMapper(KnowledgeMapper.class);
            AiDocumentVersionMapper versionMapper = template.getMapper(AiDocumentVersionMapper.class);
            DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);

            seed(dbUrl, "doc-ok");
            migrate(dataDir);
            newDocumentService(docMapper, knowledgeMapper, versionMapper, dataDir, txManager)
                    .purge("doc-ok");
            assertThat(count(dbUrl, "select count(*) from c_ai_document where id='doc-ok' and deleted=0")).isZero();
            assertThat(count(dbUrl, "select count(*) from c_ai_knowledge where doc_id='doc-ok' and deleted=0")).isZero();
            assertThat(count(dbUrl, "select count(*) from c_ai_document_version where doc_id='doc-ok' and deleted=0")).isZero();

            seed(dbUrl, "doc-rb");
            AiDocumentVersionMapper failing = spy(versionMapper);
            doThrow(new RuntimeException("版本行删除失败")).when(failing).delete(any(Wrapper.class));
            assertThatThrownBy(() -> newDocumentService(docMapper, knowledgeMapper, failing, dataDir, txManager)
                    .purge("doc-rb"))
                    .hasMessageContaining("版本行删除失败");
            assertThat(count(dbUrl, "select count(*) from c_ai_document where id='doc-rb' and deleted=0"))
                    .as("版本删除失败时文档行必须回滚").isEqualTo(1);
            assertThat(count(dbUrl, "select count(*) from c_ai_knowledge where doc_id='doc-rb' and deleted=0"))
                    .as("知识块同一事务回滚").isEqualTo(1);
        } finally {
            try (Connection root = DriverManager.getConnection(BASE_URL, USER, PASSWORD);
                 Statement st = root.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + db + "`");
            }
        }
    }

    private static void seed(String dbUrl, String docId) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO c_ai_document(id, file_name) VALUES('" + docId + "','a.md')");
            st.execute("INSERT INTO c_ai_knowledge(id, doc_id, title, content) VALUES('k-" + docId + "','" + docId + "','t','c')");
            st.execute("INSERT INTO c_ai_document_version(id, doc_id) VALUES('v-" + docId + "','" + docId + "')");
        }
    }

    private static void migrate(Path dataDir) {
        try {
            Files.createDirectories(dataDir.resolve("images").resolve("doc-ok"));
            Files.createDirectories(dataDir.resolve("images").resolve("doc-rb"));
        } catch (Exception ignored) {
        }
    }

    private static DocumentService newDocumentService(AiDocumentMapper docMapper, KnowledgeMapper knowledgeMapper,
                                                      AiDocumentVersionMapper versionMapper, Path dataDir,
                                                      DataSourceTransactionManager txManager) {
        AppProperties properties = new AppProperties();
        properties.getImages().setDir(dataDir.toString());
        return new DocumentService(
                mock(AuditService.class), txManager, docMapper, knowledgeMapper, versionMapper,
                mock(KbVectorStoreRegistry.class), mock(com.wenqu.ai.mapper.KnowledgeBaseMapper.class),
                mock(KnowledgeBaseService.class), properties, mock(DocumentMetaCache.class),
                mock(DocumentAclTags.class), mock(com.wenqu.ai.mapper.QaLogMapper.class),
                List.of(), mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(ConfigService.class), mock(KeywordIndexService.class), mock(VisionService.class),
                mock(ModelRegistryService.class), mock(DynamicEmbeddingModel.class), mock(com.wenqu.ai.parser.DocxParser.class),
                mock(QaIndexService.class), mock(ChildChunkService.class), mock(NotificationService.class),
                mock(StringRedisTemplate.class), mock(ParseQueueService.class), mock(GraphRagService.class));
    }

    private static long count(String dbUrl, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static SqlSessionFactory sqlSessionFactory(DriverManagerDataSource ds) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AiDocumentMapper.class);
        configuration.addMapper(KnowledgeMapper.class);
        configuration.addMapper(AiDocumentVersionMapper.class);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(configuration);
        return bean.getObject();
    }

    private static boolean canConnect() {
        try (Connection ignored = DriverManager.getConnection(BASE_URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
