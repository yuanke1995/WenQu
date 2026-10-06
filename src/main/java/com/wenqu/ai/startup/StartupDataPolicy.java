package com.wenqu.ai.startup;

/**
 * 启动期数据操作的总账（<b>本包存在的原因</b>）。
 *
 * <h3>为什么要有这个包</h3>
 * 启动钩子（{@code @PostConstruct} / {@code ApplicationRunner} / {@code ApplicationReadyEvent}）
 * 天然是「没人盯着也会跑」的代码——写的时候不起眼，出问题时表现为「重启后额度莫名少了」
 * 或「文档莫名其妙全被重置」，极难归因。历史上就踩过两次：
 * <ul>
 *   <li>手册同步挂在 {@code ApplicationReadyEvent} 上，每次启动逐篇向量化（数十次远程 embedding），
 *       扣的是供应商额度，用户什么都没做；</li>
 *   <li>{@code RedisVectorStore.delete()} 隐式调 {@code dimensions()}，
 *       把「删向量」绑到了远程 embedding 可用性上（见 {@code LocalDimensionEmbeddingModel}）。</li>
 * </ul>
 * 散落在 {@code config}/{@code service}/{@code schedule} 各处时，没人说得清「启动到底会动哪些数据」。
 * 故把<b>所有「启动即动数据」的代码收进本包</b>——收口的目���不是好看，是<b>可审计、可清理</b>：
 * 想确认「重启一次到底会发生什么」，只看本包即可，不必在十几个包里翻钩子。
 *
 * <h3>本包收录标准（新增启动钩子必须归位）</h3>
 * 满足任一条即应收进本包：
 * <ol>
 *   <li>在 {@code @PostConstruct} / {@code ApplicationRunner} / {@code ApplicationReadyEvent}
 *       里<b>写库</b>（INSERT/UPDATE/DELETE/ALTER）；</li>
 *   <li>在启动路径里<b>发外部请求</b>（模型、网关、第三方 HTTP）——哪怕只发一次；</li>
 *   <li>在启动路径里<b>扫描全量数据</b>（对账、巡检、全表比对）。</li>
 * </ol>
 * 只读配置、只建线程池、只算常量的（如 {@code AuthService} 解析密钥、{@code ParseQueueService}
 * 起 worker 池）留在原处——它们既不动数据也不发请求，收进来反而稀释了本包的可信度。
 *
 * <h3>铁律：启动期不许烧用户额度</h3>
 * 任何会调模型（向量/对话/重排）的动作<b>不得</b>挂在启动路径上。理由有三：
 * <ol>
 *   <li><b>用户无感知</b>：重启是运维动作，成本却落在某个用户的供应商额度上；</li>
 *   <li><b>故障放大</b>：额度耗尽时「删旧向量」也会一起失败（同一供应商），
 *       导致数据删不掉、库卡死——本该是只读启动的动作变成了故障放大器；</li>
 *   <li><b>不可预期</b>：多副本同时启动会各自跑一遍，成本翻倍且难以对账。</li>
 * </ol>
 * 确需自动执行的重活，一律走 {@code ScheduleCenter} 周期任务 + 配置开关（可暂停、可观测、可手动触发），
 * <b>不在启动路径上</b>。
 *
 * <h3>本包现状（2026-10-06 审计）</h3>
 * <table border="1">
 *   <caption>启动钩子清单</caption>
 *   <tr><th>类</th><th>触发</th><th>动什么数据</th><th>是否烧额度</th></tr>
 *   <tr><td>{@link SchemaMigrator}</td><td>ApplicationRunner</td>
 *       <td>按 schema.sql 增量补缺列/索引（只增不删不建全量）</td><td>否</td></tr>
 *   <tr><td>{@link RbacSeedRunner}</td><td>ApplicationRunner</td>
 *       <td>角色/菜单种子，仅表空时插入</td><td>否</td></tr>
 *   <tr><td>{@link ApiEndpointScanner}</td><td>ApplicationRunner</td>
 *       <td>扫描 Spring 端点清单入库（供权限页勾选）</td><td>否</td></tr>
 *   <tr><td>{@link ManualSeedService}</td><td><b>无启动钩子（手动触发）</b></td>
 *       <td>官方手册同步：建库 + 逐篇分块向量化</td><td><b>是，已改为手动</b></td></tr>
 * </table>
 *
 * <p>另外两个<b>不在本包但仍会在启动期动数据</b>的（散在 service 里，刻意未搬动——搬动会牵连大量注入关系）：
 * <ul>
 *   <li>{@code DocumentService.recoverStuckParsing}：复位崩溃残留的 status=2 文档
 *       → 开关 {@code parse.recoverStuckOnStartup}（默认开，多副本应置 false）；</li>
 *   <li>{@code ScheduleCenter} 里 {@code keyword.reconcileOnStartup} 为真时的启动首轮
 *       → 关键词索引对账（只比 Meilisearch 与 MySQL，<b>不调任何模型</b>）。</li>
 * </ul>
 * 二者都是<b>不烧额度</b>的幂等操作，且各有开关，故保留。
 *
 * @author yuanke
 */
public final class StartupDataPolicy {

    private StartupDataPolicy() {
    }

    /**
     * 启动期是否允许触发<b>会调模型</b>的动作。
     *
     * <p>当前恒为 false：手册同步已改为手动触发（{@code POST /api/ai/manual/sync}），
     * 启动路径上不再有任何模型调用。这个方法存在的意义是<b>让规矩可执行</b>——
     * 以后有人想在启动时做点「顺手的事」，能在本类看到一条明确的红线，
     * 而不是散落在各处注释里的口头约定。
     *
     * @return 一律 false；保留返回值形式便于将来引入「按配置放开」时不必改调用方
     */
    public static boolean allowsModelCallsOnStartup() {
        return false;
    }
}
