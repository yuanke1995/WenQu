package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 进程级 checkpointer 提供方（对应参考实现 {@code pg_manager.get_langgraph_checkpointer()}）。
 *
 * <h3>为什么必须有它</h3>
 * <p>参考实现的对话图是 {@code workflow.compile(checkpointer=pg_manager.get_langgraph_checkpointer())}，
 * 进程级、持久化的 checkpointer 就是<b>对话记忆的载体</b>：{@code chat_service} 每轮只把
 * <b>新的那一条 human 消息</b>喂给图（蓝本 {@code messages = [_with_attachment_context(human_message,
 * thread_attachments)]}，本工程 {@code ChatService} 同形），历史上下文全靠 checkpoint 里的
 * {@code messages} 频道累积。
 *
 * <p><b>未装配时会怎样（本类接入前的实际状态）</b>：引擎退化为 {@code CompileConfig} 字段初始化器里那句
 * {@code new SaverConfig().register(MemorySaver.builder().build())} —— 那是<b>每次构图新建</b>的空存储，
 * 而本工程的智能体每个 Run 都会重新构图（{@code ChatbotAgent.getGraph} 不缓存），于是：
 * <ul>
 *   <li>多轮对话在<b>图内</b>丢失上下文（图中只有本轮那条消息）；</li>
 *   <li>一切基于 checkpoint 的面——{@code agent_state}（todos / artifacts / subagent_runs /
 *       token_usage）、HITL 中断审批、上下文压缩的读写——读出来<b>恒为空</b>；</li>
 *   <li>任何"另起一次构图去读状态"的路径必然读不到，只会抛错或空手而归。</li>
 * </ul>
 *
 * <h3>为什么是 MySQL 实现</h3>
 * <p>蓝本用 Postgres（{@code pg_manager}）；本工程的数据面在 MySQL，故取引擎自带的
 * {@link MysqlSaver}（{@code spring-ai-alibaba-graph-core}）。它自行建并维护两张表
 * （DDL 归引擎所有，故<b>不</b>重复写进 {@code db/schema-mysql.sql}）：
 * <pre>
 *   GRAPH_THREAD     (thread_id VARCHAR(36) PK, thread_name VARCHAR(255), is_released)
 *   GRAPH_CHECKPOINT (checkpoint_id VARCHAR(36) PK, thread_id, node_id, next_node_id,
 *                     state_data JSON, saved_at TIMESTAMP)
 * </pre>
 * 我们的线程 id 落的是 {@code thread_name}（VARCHAR(255)）：主对话是 36 位 UUID，
 * 子智能体是 {@code subagent_} + 55 位摘要（共 64 位），两者都容得下；
 * {@code thread_id} 列则是 saver 自己生成的 UUID 内部主键。
 *
 * <h3>两处刻意的选择（都不是默认值，逐条给依据）</h3>
 * <ol>
 *   <li><b>{@code maxCachedThreads(0)}：关掉 saver 的进程内"最新 checkpoint"LRU。</b>
 *       本工程是「API + Worker 双进程」：run 由 Worker 写 checkpoint、状态面板由 API 读。
 *       若两个进程各缓存一份，API 会把手里的旧快照当成当前状态返回（面板滞后一轮）。
 *       每次读都落库，换的是"面板显示的就是当前状态"这一语义正确性。</li>
 *   <li><b>把 {@code GRAPH_CHECKPOINT.saved_at} 校正到微秒精度</b>（见
 *       {@link #alignSavedAtPrecision}）：引擎的 DDL 给的是 {@code TIMESTAMP}
 *       （秒级），而它自己判定"最新 checkpoint"正是 {@code ORDER BY saved_at DESC LIMIT 1}；
 *       一次 run 会在一秒内连写多个 checkpoint（{@code CompiledGraph.addCheckpoint}
 *       每个超步插一行、且丢弃 {@code put} 回传的 checkpointId，故不做原地 UPDATE），
 *       秒级精度下同一秒内的行序是**未定义**的。实测探针：同秒连写两笔后回读，
 *       3 次里 2 次取到了**旧**的那笔 —— 直接后果是续跑从旧快照起（丢轮次 / 重放模型调用）。
 *       校正后同探针 5/5 取到最后一笔。</li>
 *   <li><b>序列化器显式给 {@code SpringAIJacksonStateSerializer(OverAllState::new)}</b>：
 *       与 {@code ReactAgent} 在未显式指定时的默认序列化器<b>同一个类、同一个构造器</b>
 *       （引擎自身也是这么混用的：{@code StateGraph.DEFAULT_JACKSON_SERIALIZER} 是另一个同构实例，
 *       {@code MysqlSaver.Builder#build} 的兜底也正是它）。checkpoint 的 {@code state_data}
 *       由该序列化器写成 {@code {"binaryPayload": "<base64>"}}，读写都在 saver 内部闭合，
 *       故只需与图<b>同构</b>，无需共享同一对象。</li>
 * </ol>
 *
 * <p><b>行为变更提示</b>：接入后 checkpoint 跨 Run 持久化（这才是蓝本语义），
 * 图会从该 thread 的上一个 checkpoint 续起 —— 同一 thread 的历史只会累积一次，
 * 不会与本轮输入重复（因为输入只带本轮那条消息）。
 */
@Component
public class MysqlLanggraphCheckpointerProvider implements BaseAgent.CheckpointerProvider {

    private static final Logger log = LoggerFactory.getLogger(MysqlLanggraphCheckpointerProvider.class);

    /** 引擎 DDL 里的秒级定义 → 需要的微秒定义（唯一目的：让"最新一笔"的判定确定）。 */
    private static final String ALIGN_SAVED_AT =
            "ALTER TABLE GRAPH_CHECKPOINT MODIFY COLUMN saved_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6)";

    private static final int REQUIRED_SAVED_AT_PRECISION = 6;

    private final MysqlSaver saver;

    public MysqlLanggraphCheckpointerProvider(DataSource dataSource) {
        // 先建 saver（含引擎自带的建表），再校正 saved_at 精度（表得先存在）
        this.saver = MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new))
                .maxCachedThreads(0)
                .build();
        alignSavedAtPrecision(dataSource);
        log.info("[Checkpointer] 已装配进程级 MySQL checkpointer（表 GRAPH_THREAD / GRAPH_CHECKPOINT，"
                + "saved_at 微秒精度，进程内 LRU 已关闭）");
    }

    @Override
    public Object getLanggraphCheckpointer() {
        return saver;
    }

    /**
     * 把 {@code GRAPH_CHECKPOINT.saved_at} 校正到微秒精度（幂等：已达标则不执行 DDL）。
     *
     * <p>为什么必须做：saver 判定"最新 checkpoint"用的是
     * {@code ORDER BY saved_at DESC LIMIT 1}，而引擎自己的 DDL 是秒级的 {@code TIMESTAMP}；
     * 一次 run 内一秒可写多个 checkpoint（每个超步一行），同秒行序未定义 ——
     * 实测同秒连写两笔后回读，3 次里 2 次拿到**旧**的那笔。
     *
     * <p>失败只告警不阻断启动：这是对引擎自带表结构的一处校正，
     * 失败时应用仍可用（代价是上面那条判定不确定性），但必须让人看见。
     */
    private void alignSavedAtPrecision(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            if (currentPrecision(connection) >= REQUIRED_SAVED_AT_PRECISION) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(ALIGN_SAVED_AT);
            }
            log.info("[Checkpointer] 已把 GRAPH_CHECKPOINT.saved_at 校正为微秒精度");
        } catch (Exception error) {
            log.warn("[Checkpointer] GRAPH_CHECKPOINT.saved_at 精度校正失败（不阻断启动），"
                    + "请手工执行：{}；原因：{}", ALIGN_SAVED_AT, error.getMessage());
        }
    }

    /** 读 {@code saved_at} 当前的小数秒位数（表不存在/视图无值时返回 -1）。 */
    private int currentPrecision(Connection connection) throws Exception {
        String sql = "SELECT DATETIME_PRECISION FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'GRAPH_CHECKPOINT'"
                + " AND COLUMN_NAME = 'saved_at'";
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
