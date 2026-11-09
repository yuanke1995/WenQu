/**
 * 启动期数据操作的总账（<b>本包存在的原因</b>）。
 *
 * <h3>为什么要有这个包</h3>
 * 启动钩子（{@code @PostConstruct} / {@code ApplicationRunner} / {@code ApplicationReadyEvent}）
 * 天然是「没人盯着也会跑」的代码——写的时候不起眼，出问题时表现为「重启后额度莫名少了」
 * 或「文档莫名其妙全被重置」，极难归因。
 * 散落在 {@code config}/{@code service}/{@code schedule} 各处时，没人说得清「启动到底会动哪些数据」。
 * 故把<b>所有「启动即动数据」的代码收进本包</b>——收口的目的不是好看，是<b>可审计、可清理</b>：
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
 * <b>不在启动路径上</b>。本条红线只有文档、没有代码守卫：以后有人想在启动时做点「顺手的事」，
 * 先回来看这段，而不是散落在各处注释里的口头约定。
 *
 * <h3>本包现状</h3>
 * <table border="1">
 *   <caption>启动钩子清单</caption>
 *   <tr><th>类</th><th>触发</th><th>动什么数据</th><th>是否烧额度</th></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.SchemaMigrator}</td><td>ApplicationRunner @Order(0)</td>
 *       <td>按 schema.sql 增量补建表/缺列/缺索引，并把 {@code tenant_id} 归一到默认租户（只增不删）</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.RbacSeedRunner}</td><td>ApplicationRunner @Order(1)</td>
 *       <td>角色/菜单种子，仅表空或缺项时补齐</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.TenantSeedRunner}</td><td>ApplicationRunner @Order(1)</td>
 *       <td>登记 {@code default} 租户行</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.ApiEndpointScanner}</td><td>ApplicationRunner</td>
 *       <td>扫描 Spring 端点清单入库（供权限页勾选）</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.RbacUserRoleSeeder}</td><td>ApplicationRunner @Order(12)</td>
 *       <td>给 {@code user} 角色按问答白名单推导授权点（该租户已有授权点行则整租户跳过）</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.ContentSafetySeedRunner}</td><td>ApplicationRunner @Order(6)</td>
 *       <td>逐租户登记两条只告警的个人信息规则（该租户名下已有规则则一条不加）</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.MarketBuiltinSeeder}</td><td>ApplicationRunner @Order(20)</td>
 *       <td>官方工作流模板种入市场（幂等；管理员下架过的不复活）</td><td>否</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.OrphanTurnRunner}</td><td><b>无启动钩子</b>——由
 *       {@code ScheduleCenter} 按内置节拍 2 分钟调度（服务一起来先接一次）</td>
 *       <td>扫中断轮并重建跟随循环</td>
 *       <td><b>接续的那一轮会继续调模型</b>；节拍不做成配置项，也不给暂停——停掉等于让中断的轮
 *           一直停在「只有问题没有回答」那一行</td></tr>
 *   <tr><td>{@link com.wenqu.ai.startup.ManualSeedService}</td><td><b>无启动钩子（手动触发）</b></td>
 *       <td>官方手册同步：建库 + 逐篇分块向量化</td><td><b>是（手动触发）</b></td></tr>
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
package com.wenqu.ai.startup;
