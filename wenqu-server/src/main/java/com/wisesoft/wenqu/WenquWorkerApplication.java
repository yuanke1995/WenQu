package com.wisesoft.wenqu;

import com.wisesoft.wenqu.config.ProcessRole;
import com.wisesoft.wenqu.service.RunWorker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 问渠 Run 队列 worker 进程入口（对应参考实现 {@code server/worker_main.py}）。
 *
 * <p>参考实现是「同一份代码库、两个入口、两个进程」：api 进程走
 * {@code server/main.py}（{@code uvicorn.run(app)}），worker 进程走
 * {@code server/worker_main.py}（{@code run_worker(WorkerSettings)}）。本工程对应
 * {@link WenquServerApplication}（api）与本类（worker）；worker 进程<b>不提供 HTTP 服务</b>，
 * 只消费 Run 队列并按 {@code WorkerSettings} 的生命周期启停 —— 与参考实现同为两个进程、
 * 各自只跑自己那一侧的启动逻辑（见 {@link ProcessRole}）。
 *
 * <h3>逐段对位 {@code worker_main.main()}</h3>
 * <ol>
 *   <li>{@code logging.config.dictConfig(default_log_config(False))} → 无调用点：本工程日志由
 *       {@code logback-spring.xml} 统一接管（ARQ 的默认日志配置无对位）。</li>
 *   <li>{@code from …services.run_worker import WorkerSettings} /
 *       {@code __all__ = ["WorkerSettings"]} → 无对位：{@code WorkerSettings} 的字段已映射为
 *       {@link RunWorker} 自身的常量与方法（见该类 Javadoc 的字段映射表），
 *       {@code RunWorker} 本身就是「WorkerSettings + 执行侧」的合体，无须再重导出。</li>
 *   <li>{@code run_worker(WorkerSettings)}（{@code services/arq_worker.py}）→ 本类
 *       {@link #main(String[])}：启 Spring 上下文 → {@link RunWorker#workerStartup} →
 *       常驻 → {@link RunWorker#workerShutdown}。其中 {@code arq_worker} 里那个
 *       {@code Worker} 子类覆盖的 {@code start_jobs}（即「跳过本进程仍在执行的候选」的领取适配）
 *       已落在 {@link RunWorker} 的 {@code inflightRuns} 去重里（准入即 {@code add} 失败则跳过、
 *       结束即移除 ≡ {@code self.tasks.get(job_id).done()} 的过滤），故 {@code arq_worker} 无独立类。</li>
 * </ol>
 *
 * <h3>平台差异（必要替换）</h3>
 * <ol>
 *   <li><b>无 HTTP 服务</b>：ARQ worker 进程不监听端口，故容器以
 *       {@link WebApplicationType#NONE} 启动（若仍按 Servlet 启动，会额外起一个对本进程无意义的
 *       HTTP 端点，且与 api 进程争端口）。</li>
 *   <li><b>{@code asyncio} 事件循环 → Spring 上下文 + {@link CountDownLatch}</b>：
 *       {@code Worker.run()} 的「阻塞至收到关闭信号」由 {@link CountDownLatch#await()} 承担；
 *       上下文本身不阻塞主线程（{@code WebApplicationType.NONE} 下 Spring 启动完即返回）。</li>
 *   <li><b>关闭顺序照 {@code _worker_shutdown} 收口，故关闭 Spring 自带的 shutdown hook</b>：
 *       参考实现先 {@code task.cancel()} 停两条收敛循环、再 {@code close_queue_clients()} /
 *       {@code pg_manager.close()}；本工程同样先 {@link RunWorker#workerShutdown}（停收敛循环与健康续租）、
 *       再 {@link ConfigurableApplicationContext#close()}（回收 {@code @PreDestroy} 线程池与连接）。
 *       若保留 Spring 默认 hook，两条收口路径会竞争且顺序不定（可能出现「先关连接、再停循环」）。</li>
 *   <li><b>{@code default_log_config(False)} 无对位</b>：见上「逐段对位」第 1 条。</li>
 *   <li><b>Windows 兼容段无对位</b>：参考实现在我平台插入 {@code sys.path} 并设
 *       {@code WindowsSelectorEventLoopPolicy}（绕 psycopg 不支持 ProactorEventLoop）；
 *       JVM 无事件循环策略，且类路径由启动命令给定，故整段不搬。</li>
 *   <li><b>进程角色必须在入口显式设为 {@link ProcessRole#WORKER}</b>：两个入口共用同一套装配，
 *       lifespan 对位的启动组件以 {@code @ConditionalOnProperty(ProcessRole.PROPERTY)} 限定在
 *       api 进程，worker 进程只走 {@code _worker_startup} 那一条路径（理由见 {@link ProcessRole}）。</li>
 * </ol>
 *
 * <p>启动方式（与 api 进程同一份 classpath，仅入口类不同）：
 * <pre>
 * java -cp target/classes:&lt;deps&gt; com.wisesoft.wenqu.WenquWorkerApplication
 * </pre>
 * 角色属性走 Spring 默认属性，可被命令行 / 环境变量覆盖（如需临时叠加 api 侧启动组件）。
 */
public final class WenquWorkerApplication {

    private static final Logger log = LoggerFactory.getLogger(WenquWorkerApplication.class);

    private WenquWorkerApplication() {}

    public static void main(String[] args) throws InterruptedException {
        SpringApplication application = new SpringApplication(WenquServerApplication.class);
        // 平台差异①：ARQ worker 进程不提供 HTTP 服务。
        application.setWebApplicationType(WebApplicationType.NONE);
        // 进程角色：只跑 _worker_startup 那条启动路径，跳过 lifespan 对位的启动组件。
        application.setDefaultProperties(Map.of(ProcessRole.PROPERTY, ProcessRole.WORKER));
        // 平台差异③：关闭顺序由本类收口（先停 worker 收敛循环，再关 Spring 容器），
        // 故关闭 Spring 自带的 shutdown hook，避免两条收口路径竞争、顺序不确定。
        application.setRegisterShutdownHook(false);

        ConfigurableApplicationContext context = application.run(args);
        RunWorker worker = context.getBean(RunWorker.class);

        // 对位 WorkerSettings.on_startup=_worker_startup（内含 require_security_secrets /
        // ensure_options_in_db / builtin_mcp_servers / builtin_skills / 启动期收敛 / 两条收敛循环）。
        Map<String, Object> workerContext = new LinkedHashMap<>();
        worker.workerStartup(workerContext);
        log.info("Run worker started: worker_id={}, role={}", RunWorker.WORKER_ID, ProcessRole.WORKER);

        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            shutdown(worker, workerContext, context);
                            keepAlive.countDown();
                        },
                        "wenqu-worker-shutdown"));

        // 平台差异②：ARQ Worker.run() 阻塞至收到关闭信号，本工程以 latch 常驻同一语义。
        keepAlive.await();
    }

    /** 对位 {@code WorkerSettings.on_shutdown=_worker_shutdown}，并按参考实现的顺序收口容器。 */
    private static void shutdown(
            RunWorker worker, Map<String, Object> workerContext, ConfigurableApplicationContext context) {
        try {
            worker.workerShutdown(workerContext);
        } catch (RuntimeException error) {
            log.warn("Run worker shutdown failed: {}", error.getMessage());
        } finally {
            // 平台差异：close_queue_clients() / pg_manager.close() 无对位，
            // 连接与连接池由 Spring 容器统一回收（含 RunWorker 的 @PreDestroy 线程池回收）。
            context.close();
        }
    }
}
