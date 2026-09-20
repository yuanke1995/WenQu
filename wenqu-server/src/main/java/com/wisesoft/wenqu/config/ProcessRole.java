package com.wisesoft.wenqu.config;

/**
 * 进程角色开关：参考实现的启动逻辑天然按「进程」分成两份，
 * 本工程两个入口复用同一套 Spring 装配，故以本属性区分「该跑哪一侧的启动逻辑」。
 *
 * <p>参考实现（平台差异的必要适配）：api 进程与 worker 进程是<b>两个入口文件</b>，
 * 各自只 import 自己需要的东西 ——
 * <ul>
 *   <li>api 进程 {@code server/main.py}：装配 {@code FastAPI(lifespan=lifespan)}，
 *       于是 {@code server/utils/lifespan.py} 的整套启动组件只在这个进程里执行；</li>
 *   <li>worker 进程 {@code server/worker_main.py}：装配 {@code WorkerSettings.on_startup=_worker_startup}，
 *       <b>不装配 lifespan</b>，故 {@code default_agents} / {@code model_providers} / {@code model_cache}
 *       这些只在 lifespan 里的组件在 worker 进程里<b>不会</b>执行。</li>
 * </ul>
 *
 * <p>本工程两个入口（{@link com.wisesoft.wenqu.WenquServerApplication} /
 * {@link com.wisesoft.wenqu.WenquWorkerApplication}）共用同一个
 * {@code @SpringBootApplication} 装配，组件扫描面相同，因此必须显式把
 * 「lifespan 对位组件」限定在 {@link #SERVER} 角色下，worker 进程才与参考实现行为一致
 * （否则 worker 进程会多跑一遍 {@code default_agents} 等组件：虽幂等但会拖慢启动，
 * 且 {@code knowledge_base} 那类 fail-fast 语义会让 worker 因与它无关的原因拒绝启动）。
 *
 * <p>两侧口径都在 {@code @ConditionalOnProperty} 与 {@code WenquWorkerApplication} 的
 * {@code setDefaultProperties} 里指向本类常量，避免字面量在两处各自漂移。
 */
public final class ProcessRole {

    private ProcessRole() {}

    /** 进程角色属性名（Spring 宽松绑定，故环境变量 {@code WENQU_ROLE} 亦可生效）。 */
    public static final String PROPERTY = "wenqu.role";

    /** api / Web 进程（默认角色）：跑 lifespan 对位的整套启动组件，并提供 HTTP 服务。 */
    public static final String SERVER = "server";

    /**
     * Run 队列执行进程：只跑 {@code RunWorker.workerStartup}
     * （对位 {@code _worker_startup}），不提供 HTTP 服务。
     */
    public static final String WORKER = "worker";
}
