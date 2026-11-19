package com.wenqu.ai.ssh;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行审计：远端文件/命令/终端动作逐条写 {@code c_ai_executor_audit}。
 *
 * <p><b>为什么异步</b>：审计不能成为工具调用的固定延迟——一次 {@code ls} 不该多等一次 DB 往返。
 * 这里有界队列 + 单线程批量落库（每批最多 200 条），队列满时丢弃并计数告警：
 * 审计是「可观测」不是「正确性」依赖，宁可少记一条也不该把用户这一轮卡死。
 *
 * <p><b>不记什么</b>：不记文件内容、不记终端输入输出、不记密码/私钥/口令。路径尽量相对化为
 * {@code alias/rel}，避免把用户远端目录结构整棵堆进审计表。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SshAuditService {

    private static final int QUEUE_CAPACITY = 4096;
    private static final int BATCH = 200;

    private record Entry(String id, String tenantId, String uid, String kind, String executorId,
                         String hostId, String projectId, String sessionId, String turnId,
                         String op, String path, boolean ok, String errorCode,
                         long durationMs, long bytesIn, long bytesOut) {
    }

    private final JdbcTemplate jdbc;
    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    private volatile boolean running = true;

    public SshAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.worker = new Thread(this::drainLoop, "ssh-audit");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 记一条审计（非阻塞）。任何异常都被吞掉——审计失败绝不影响工具调用本身。
     */
    public void record(String tenantId, String uid, String kind, String executorId, String hostId,
                       String projectId, String sessionId, String turnId,
                       String op, String path, boolean ok, String errorCode,
                       long durationMs, long bytesIn, long bytesOut) {
        Entry e = new Entry(UUID.randomUUID().toString().replace("-", ""),
                tenantId == null || tenantId.isBlank() ? "default" : tenantId,
                uid == null ? "" : uid, kind == null ? "ssh" : kind,
                executorId, hostId, projectId, sessionId, turnId,
                op, path, ok, errorCode, durationMs, bytesIn, bytesOut);
        if (!queue.offer(e)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 100 == 0) {
                log.warn("[SSH-AUDIT] 审计队列已满，丢弃 {} 条（不影响工具调用）", n);
            }
        }
    }

    private void drainLoop() {
        java.util.List<Entry> batch = new java.util.ArrayList<>(BATCH);
        while (running || !queue.isEmpty()) {
            try {
                Entry first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[SSH-AUDIT] 落库失败（丢弃本批 {} 条）: {}", batch.size(), e.getMessage());
            }
        }
    }

    private void flush(java.util.List<Entry> batch) {
        if (batch.isEmpty()) return;
        String sql = "insert into c_ai_executor_audit "
                + "(id, tenant_id, uid, executor_kind, executor_id, host_id, project_id, session_id, turn_id,"
                + " op, path, ok, error_code, duration_ms, bytes_in, bytes_out) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        java.util.List<Object[]> args = new java.util.ArrayList<>(batch.size());
        for (Entry e : batch) {
            args.add(new Object[]{e.id(), e.tenantId(), e.uid(), e.kind(), e.executorId(), e.hostId(),
                    e.projectId(), e.sessionId(), e.turnId(), e.op(), trimPath(e.path()),
                    e.ok() ? 1 : 0, e.errorCode(), e.durationMs(), e.bytesIn(), e.bytesOut()});
        }
        jdbc.batchUpdate(sql, args);
    }

    private static String trimPath(String path) {
        if (path == null) return null;
        return path.length() > 1000 ? path.substring(0, 1000) : path;
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 丢弃计数（排障/自检用） */
    public long droppedCount() {
        return dropped.get();
    }
}
