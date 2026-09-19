package com.wisesoft.wenqu.storage.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 连接管理（含共享连接与读写简写）。
 *
 * <p>由参考实现的 storage/neo4j/manager.py 逐项翻译：{@code safe_neo4j_label}、
 * {@code neo4j_write}、{@code neo4j_read}、{@code Neo4jConnectionManager}、
 * {@code get_shared_neo4j_connection}、{@code close_shared_neo4j_connection}。
 *
 * <p>连接参数与参考实现一致，取自环境变量并用相同默认值兜底：
 * {@code NEO4J_URI}（bolt://localhost:7687）、{@code NEO4J_USERNAME}（neo4j）、
 * {@code NEO4J_PASSWORD}（0123456789）。
 *
 * <p>连接是<b>懒建</b>的：只有真正用到图谱能力时才会创建（与参考实现的
 * property 懒加载一致），因此未部署 Neo4j 时应用照常启动。
 */
public class Neo4jConnectionManager {

    private static final java.util.regex.Pattern SAFE_NEO4J_LABEL_RE =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final Object SHARED_LOCK = new Object();
    private static volatile Neo4jConnectionManager sharedConnection;

    private volatile Driver driver;
    private volatile String status = "closed";

    public Neo4jConnectionManager() {
        connect();
    }

    /** 校验并返回安全的 Neo4j 标签（防止 Cypher 标签注入）。 */
    public static String safeNeo4jLabel(String value) {
        if (value == null || !SAFE_NEO4J_LABEL_RE.matcher(value).matches()) {
            throw new IllegalArgumentException("非法 Neo4j 标签: " + value);
        }
        return value;
    }

    /** 在写事务中执行 Cypher 操作的简写（对应参考实现的 neo4j_write 上下文管理器）。 */
    public static <T> T neo4jWrite(Driver driver, TransactionCallback<T> work) {
        try (Session session = driver.session()) {
            return session.executeWrite(work);
        }
    }

    /** 执行只读 Cypher 查询并返回结果列表。 */
    public static List<Map<String, Object>> neo4jRead(
            Driver driver, String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params == null ? Map.of() : params);
            List<Map<String, Object>> records = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                records.add(record.asMap());
            }
            return records;
        }
    }

    private void connect() {
        if (driver != null && isConnected()) {
            return;
        }

        String uri = environment("NEO4J_URI", "bolt://localhost:7687");
        String username = environment("NEO4J_USERNAME", "neo4j");
        String password = environment("NEO4J_PASSWORD", "0123456789");

        Driver created = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        try (Session session = created.session()) {
            session.run("RETURN 1");
            this.driver = created;
            this.status = "open";
        } catch (RuntimeException exc) {
            created.close();
            this.status = "closed";
            throw exc;
        }
    }

    private boolean isConnected() {
        if (driver == null) {
            return false;
        }
        try (Session session = driver.session()) {
            session.run("RETURN 1");
            return true;
        } catch (RuntimeException exc) {
            return false;
        }
    }

    /** 连接是否可用。 */
    public boolean isRunning() {
        return "open".equals(status) || "processing".equals(status);
    }

    /** 关闭连接。 */
    public void close() {
        Driver current = this.driver;
        if (current != null) {
            current.close();
            this.driver = null;
            this.status = "closed";
        }
    }

    public Driver getDriver() {
        return driver;
    }

    public String getStatus() {
        return status;
    }

    /** 进程级共享连接（懒建，双重检查）。 */
    public static Neo4jConnectionManager getSharedNeo4jConnection() {
        Neo4jConnectionManager current = sharedConnection;
        if (current == null || current.getDriver() == null) {
            synchronized (SHARED_LOCK) {
                if (sharedConnection == null || sharedConnection.getDriver() == null) {
                    sharedConnection = new Neo4jConnectionManager();
                }
                current = sharedConnection;
            }
        }
        return current;
    }

    /** 关闭进程级共享连接。 */
    public static void closeSharedNeo4jConnection() {
        synchronized (SHARED_LOCK) {
            if (sharedConnection != null) {
                sharedConnection.close();
            }
        }
    }

    private static String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
