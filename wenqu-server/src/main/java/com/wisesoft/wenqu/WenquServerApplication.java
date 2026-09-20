package com.wisesoft.wenqu;

import com.wisesoft.wenqu.config.AccessLogFilter;
import com.wisesoft.wenqu.config.CorsConfig;
import com.wisesoft.wenqu.config.LoginRateLimitFilter;
import com.wisesoft.wenqu.config.McpStartupInitializer;
import com.wisesoft.wenqu.config.OptionStartupInitializer;
import com.wisesoft.wenqu.config.ProcessRole;
import com.wisesoft.wenqu.config.QualifiedMapperBeanNameGenerator;
import com.wisesoft.wenqu.config.StartupDataInitializer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 问渠服务端入口（<b>api 进程</b>）。
 *
 * <p>分层约定（与平台化形态一致）：
 * <ul>
 *   <li>{@code controller} — HTTP 边界，只做参数校验与响应装配，不写业务</li>
 *   <li>{@code service} — 用例编排（检索链路、问答链路、知识库管理等）</li>
 *   <li>{@code repository} — 持久化边界（MyBatis-Plus Mapper），SQL 只在这层出现</li>
 *   <li>{@code model} / {@code dto} — 实体与传输对象</li>
 *   <li>{@code config} — 基础设施装配（模型、存储、鉴权、schema 演进）</li>
 * </ul>
 *
 * <h3>对位参考实现 {@code server/main.py}（147 行）</h3>
 *
 * <p>参考实现 {@code main.py} 的职责是「建 app + 装路由 + 装三道中间件」，代码集中在一个文件里；
 * 本工程无独立入口类——这些职责已分散落在既有类上，本类只承担其中「进程入口 + 路由装配 + 端口」三项。
 * 各段落点按参考文件自上而下逐一指认（<b>本表即 {@code main} 条目的核验凭据</b>）：
 *
 * <ul>
 *   <li><b>Windows {@code ProactorEventLoop} 兼容段</b>（{@code sys.platform == "win32"} 时注入
 *       {@code sys.path} 并设置 {@code WindowsSelectorEventLoopPolicy}）→ <b>无对位</b>：
 *       JVM 无「事件循环策略」概念，依赖解析由 Maven classpath 承担。</li>
 *   <li><b>{@code setup_logging()}</b> → {@code src/main/resources/logback-spring.xml}
 *       （含 {@code access_logger} 专用 logger，回溯与不向根 logger 传播的语义见该类注释）。</li>
 *   <li><b>{@code RATE_LIMIT_MAX_ATTEMPTS} / {@code RATE_LIMIT_WINDOW_SECONDS} /
 *       {@code RATE_LIMIT_ENDPOINTS} / {@code _login_attempts} / {@code _attempt_lock}</b>
 *       → {@link LoginRateLimitFilter}（常量取值、触发文案、响应头全部照搬；
 *       {@code asyncio.Lock} 由 {@code ConcurrentHashMap.compute} 的原子段承担——
 *       参考实现亦为单进程内存计数）。</li>
 *   <li><b>{@code _parse_cors_origins()} / {@code _build_cors_options()}</b> → {@link CorsConfig}。</li>
 *   <li><b>{@code app = FastAPI(lifespan=lifespan)}</b> → Spring 上下文生命周期 +
 *       {@link OptionStartupInitializer} → {@link McpStartupInitializer} →
 *       {@link StartupDataInitializer} 三段启动组件。
 *       <b>注</b>：lifespan 本身尚未全量对位（清单 {@code [~]} 项），缺口见下「能力差异」。</li>
 *   <li><b>{@code app.include_router(router, prefix="/api")}</b> → 本类的
 *       {@code @SpringBootApplication} 组件扫描 + 各控制器的 {@code @RequestMapping("/api/...")}。
 *       参考实现 {@code server/routers/__init__.py} 的「集中注册 + 统一前缀」在本工程由注解声明表达，
 *       两者等价（无中心注册表）。</li>
 *   <li><b>CORS 中间件装配</b> → {@link CorsConfig}（{@code WebMvcConfigurer}）。</li>
 *   <li><b>{@code _extract_client_ip(request)}</b> → {@link AccessLogFilter} 与
 *       {@link LoginRateLimitFilter} 各自的私有同实现（参考实现中它是模块级函数，由
 *       {@code access_log_middleware.py} 与 {@code main.py} 各定义一份，本工程保持两份）。</li>
 *   <li><b>{@code AccessLogMiddleware} / {@code LoginRateLimitMiddleware} 及其装配</b>
 *       → {@link AccessLogFilter} / {@link LoginRateLimitFilter}（叠放顺序见下节）。</li>
 *   <li><b>{@code if __name__ == "__main__": uvicorn.run(...)}</b> → 本类 {@link #main(String[])}
 *       + {@code application.yml} 的 {@code server.port}（参考实现 5050 → 本工程 8095，
 *       {@code context-path} {@code /v2}）。{@code reload=True} / {@code reload_dirs} 无对位
 *       （开发期热重载交由 IDE 与 Spring devtools）。</li>
 * </ul>
 *
 * <h3>中间件叠放顺序（照搬，勿对调）</h3>
 *
 * <p>参考实现的 {@code app.add_middleware()} 是<b>栈式</b>——后添加者包在更外层、先执行。
 * 其装配序为 CORS → {@code AccessLogMiddleware} → {@code LoginRateLimitMiddleware}，
 * 故请求的实际执行序是：
 *
 * <pre>
 * LoginRateLimitMiddleware → AccessLogMiddleware → CORSMiddleware → router(/api/**)
 * </pre>
 *
 * <p>本工程以 {@code @Order} 升序表达同一顺序：{@link LoginRateLimitFilter} 取
 * {@code HIGHEST_PRECEDENCE + 10}、{@link AccessLogFilter} 取 {@code HIGHEST_PRECEDENCE + 20}
 * （<b>勿对调</b>）。该顺序有可观察差异：被限流短路的 {@code 429} 在限流层即返回，
 * <b>不产生访问日志</b>；对调后该请求会多记一条。
 * CORS 在本工程由 {@code HandlerMapping} 处理，天然位于所有 {@code Filter} 之后，
 * 与参考实现「CORS 最内层」的位置一致。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li><b>环境变量品牌前缀</b>：{@code WENQU_CORS_ORIGINS} / {@code WENQU_ENV}
 *       （品牌前缀替换；默认值、逗号分隔语义、production/prod 判定分支均不变）。</li>
 *   <li><b>上下文路径</b>：参考实现的 {@code /api} 前缀来自 {@code include_router}；本工程的对外 URL
 *       另带 {@code server.servlet.context-path}（{@code /v2}），最终形态为 {@code /v2/api/...}——
 *       这是本产品既有对外口径，非本次移植引入。</li>
 *   <li><b>两套路由契约并存</b>：{@code /api/ai/**}（本产品既有契约）与 {@code /api/**}
 *       （参考实现契约，如 {@code /api/knowledge/**}、{@code /api/auth/**}）由不同控制器的
 *       {@code @RequestMapping} 共同装配，鉴权粒度差异见
 *       {@code config/SecurityConfig}。</li>
 *   <li><b>持久层双轨</b>：{@link MapperScan} 只注册 {@code com.wisesoft.wenqu.repository}
 *       （单数包）下的旧契约 Mapper 接口；照搬来的 {@code repositories}（复数包）是
 *       {@code @Repository} 类，走组件扫描。两者注册机制不同，<b>新增仓库类时勿放错包</b>。</li>
 *   <li><b>进程角色</b>：参考实现的 {@code main.py} 是唯一的 api 进程入口；本工程的
 *       {@code WenquWorkerApplication} 复用同一套装配，由 {@link ProcessRole}（{@code wenqu.role}）
 *       区分——本类未显式设置，故默认即 {@code server} 角色，启动组件按 {@code matchIfMissing=true} 生效。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li><b>lifespan 未全量对位</b>（属清单 {@code [~] lifespan} 项，不在本类范围）：已落位的启动组件为
 *       {@code builtin_mcp_servers}（非 required）、{@code builtin_skills} / {@code default_agents} /
 *       {@code model_providers} / {@code model_cache}（四者 required，按原顺序串行）、
 *       {@code ensure_options_in_db}。尚缺 {@code security_secrets} / {@code knowledge_base} /
 *       {@code sandbox_provider}，以及 {@code _shutdown_component} 的补偿清理链
 *       （{@code sandbox_provider} / {@code queue_clients} / {@code neo4j} / {@code postgres}）。
 *       其中 {@code security_secrets} 在参考实现里是 api 进程的<b>首个 required 组件</b>
 *       （{@code AuthUtils.requireSecuritySecrets()} 已就绪，但目前<b>仅 worker 侧调用</b>），
 *       接上会新增「三个 ≥32 位且互不相同的密钥必须配置」这一启动前置——是否照搬待定。</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan(
        basePackages = "com.wisesoft.wenqu.repository",
        nameGenerator = QualifiedMapperBeanNameGenerator.class)
public class WenquServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WenquServerApplication.class, args);
    }
}
