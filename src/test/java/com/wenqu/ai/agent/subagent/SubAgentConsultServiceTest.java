package com.wenqu.ai.agent.subagent;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * agent-as-tool 核心服务的单元测试：SubAgentConsultService。
 * <p>
 * 覆盖范围：
 * <ul>
 *   <li>输入校验（空 agent_id / question / runner 空）</li>
 *   <li>自咨询拦截 / 查无助手 / 权限检查 / preConsulted 拦截</li>
 *   <li>台账去重（同助手同问题 → 不真跑）</li>
 *   <li>咨询预算上限（AGENT_CONSULT_MAX_PER_TURN = 3）</li>
 *   <li>执行器异常/回报不可用 的降级路径</li>
 *   <li>render 的子轮编号清洗、引用登记 sink</li>
 *   <li>Ledger 独立行为（缓存键、次数统计、取走子对话）</li>
 * </ul>
 *
 * @author wenqu-demo
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubAgentConsultServiceTest {

    @Mock ConfigService configService;
    @Mock AgentService agentService;

    SubAgentConsultService service;

    Agent selfAgent;
    Agent targetAgent;
    Agent otherAgent;

    @BeforeEach
    void setUp() {
        service = new SubAgentConsultService(configService, agentService);
        // 默认配置：上限 3 次 / 超时 300s
        when(configService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(configService.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));

        selfAgent = new Agent();
        selfAgent.setId("self-agent-id");
        selfAgent.setName("主助手");

        targetAgent = new Agent();
        targetAgent.setId("agent-zhibao");
        targetAgent.setName("智报助手");
        targetAgent.setDescription("负责报表取数与分析");

        otherAgent = new Agent();
        otherAgent.setId("agent-ops");
        otherAgent.setName("运维助手");
        otherAgent.setDescription("负责服务器与运维");
    }

    // ---------------------------------------------------------------------------------------------
    // 输入校验
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("输入校验")
    class InputValidation {

        @Test
        @DisplayName("agent_id 为空 → 返回错误文本")
        void emptyAgentId() {
            String r = service.consult(newLedger(), ctx(), mock(BranchRunner.class), null, "", "查询数据");
            assertTrue(r.contains("agent_id 不能为空"), "实际: " + r);
        }

        @Test
        @DisplayName("question 为空 → 返回错误文本")
        void emptyQuestion() {
            String r = service.consult(newLedger(), ctx(), mock(BranchRunner.class), null, "agent-zhibao", "  ");
            assertTrue(r.contains("question 不能为空"), "实际: " + r);
        }

        @Test
        @DisplayName("runner 为 null → 返回通道未就绪")
        void runnerNull() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            String r = service.consult(newLedger(), ctx(), null, null, "agent-zhibao", "查询数据");
            assertTrue(r.contains("通道本轮未就绪"), "实际: " + r);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 拦截路径
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("拦截路径")
    class GuardPaths {

        @Test
        @DisplayName("咨询自己 → 拦截")
        void consultSelf() {
            when(agentService.get("self-agent-id")).thenReturn(selfAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));
            String r = service.consult(newLedger(), ctx(), mock(BranchRunner.class), null,
                    "self-agent-id", "你自己是什么");
            assertTrue(r.contains("无需咨询自己"), "实际: " + r);
        }

        @Test
        @DisplayName("查无这位助手 → 拦截")
        void agentNotFound() {
            when(agentService.get("agent-nonexist")).thenReturn(null);
            BranchRunner runner = mock(BranchRunner.class);
            String r = service.consult(newLedger(), ctx(), runner, null,
                    "agent-nonexist", "查询数据");
            assertTrue(r.contains("查无这位助手"), "实际: " + r);
            verify(runner, never()).run(any());
        }

        @Test
        @DisplayName("不在可读范围内 → 权限拦截")
        void notInCandidates() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            // selfAgent 的候选清单里没有智报
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(otherAgent));
            BranchRunner runner = mock(BranchRunner.class);
            String r = service.consult(newLedger(), ctx(), runner, null,
                    "agent-zhibao", "查询数据");
            assertTrue(r.contains("没有权限咨询"), "实际: " + r);
            verify(runner, never()).run(any());
        }

        @Test
        @DisplayName("preConsulted 已强制咨询过 → 拦截避免重复开销")
        void preConsulted() {
            SubAgentConsultService.Ctx ctxWithPre = new SubAgentConsultService.Ctx(
                    "parent-sess", "parent-turn", "user-1", "model-v1", false, 0,
                    "self-agent-id", "agent-zhibao", null);
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));
            BranchRunner runner = mock(BranchRunner.class);
            String r = service.consult(newLedger(), ctxWithPre, runner, null,
                    "agent-zhibao", "查询数据");
            assertTrue(r.contains("本轮已经咨询过这位助手"), "实际: " + r);
            verify(runner, never()).run(any());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 台账去重
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("台账去重")
    class LedgerDedup {

        @Test
        @DisplayName("同助手同问题 → 不真跑 runner，直接回缓存")
        void sameAgentSameQuestionDedup() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = mock(BranchRunner.class);
            when(runner.run(any())).thenReturn(doneReport("智报的回报正文", List.of()));

            SubAgentConsultService.Ledger ledger = service.newLedger();

            // 第一次：真跑
            String r1 = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "查 Q1");
            assertTrue(r1.contains("智报助手"));
            verify(runner, times(1)).run(any());

            // 第二次同助手同问题：缓存命中
            String r2 = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "查 Q1");
            assertTrue(r2.contains("已经问过这位助手"), "实际: " + r2);
            // runner 只被调了一次
            verify(runner, times(1)).run(any());
        }

        @Test
        @DisplayName("同助手不同问题 → 真跑第二条子轮")
        void sameAgentDifferentQuestions() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = mock(BranchRunner.class);
            when(runner.run(any())).thenReturn(doneReport("智报的回报正文", List.of()));

            SubAgentConsultService.Ledger ledger = service.newLedger();

            service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q1");
            service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q2");
            verify(runner, times(2)).run(any());
        }

        @Test
        @DisplayName("同问题不同助手 → 真跑两条子轮")
        void sameQuestionDifferentAgents() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.get("agent-ops")).thenReturn(otherAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent, otherAgent));

            BranchRunner runner = mock(BranchRunner.class);
            when(runner.run(any())).thenReturn(doneReport("回报正文", List.of()));

            SubAgentConsultService.Ledger ledger = service.newLedger();

            service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");
            service.consult(ledger, ctx(), runner, null, "agent-ops", "Q");
            verify(runner, times(2)).run(any());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 预算上限
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("咨询预算上限")
    class BudgetCap {

        @Test
        @DisplayName("达到上限后拒绝后续咨询")
        void capReached() {
            // 上限设为 2
            when(configService.getInt(eq("agent.consultMaxPerTurn"), anyInt())).thenReturn(2);
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.get("agent-ops")).thenReturn(otherAgent);
            when(agentService.consultCandidates("self-agent-id"))
                    .thenReturn(List.of(targetAgent, otherAgent));

            BranchRunner runner = mock(BranchRunner.class);
            when(runner.run(any())).thenReturn(doneReport("回报", List.of()));

            SubAgentConsultService.Ledger ledger = service.newLedger();

            String r1 = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q1");
            assertFalse(r1.contains("额度已用完"), "第一次不该触顶: " + r1);
            String r2 = service.consult(ledger, ctx(), runner, null, "agent-ops", "Q2");
            assertFalse(r2.contains("额度已用完"), "第二次不该触顶: " + r2);
            // 第三次换了个不存在的助手走拦截前，但缓存已不命中，会先检查额度
            // 这里换一个新助手，但仍在候选里
            Agent third = new Agent();
            third.setId("agent-third");
            third.setName("第三助手");
            when(agentService.get("agent-third")).thenReturn(third);
            when(agentService.consultCandidates("self-agent-id"))
                    .thenReturn(List.of(targetAgent, otherAgent, third));
            String r3 = service.consult(ledger, ctx(), runner, null, "agent-third", "Q3");
            assertTrue(r3.contains("额度已用完") || r3.contains("最多咨询 2 位"),
                    "第三次应该被预算挡住: " + r3);
            verify(runner, times(2)).run(any());
        }

        @Test
        @DisplayName("默认上限 3（ConfigDefaults.AGENT_CONSULT_MAX_PER_TURN）")
        void defaultCapIsThree() {
            assertEquals(3, ConfigDefaults.AGENT_CONSULT_MAX_PER_TURN);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 执行器异常 / 回报不可用
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("失败降级路径")
    class FailureDegrade {

        @Test
        @DisplayName("runner 抛异常 → 兜住并返回错误文本，台账记账")
        void runnerThrows() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> { throw new RuntimeException("timeout connecting"); };

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertTrue(r.contains("咨询") && r.contains("失败"), "实际: " + r);
            // 台账有失败记录
            assertNotNull(ledger.takeReport("agent-zhibao"));
        }

        @Test
        @DisplayName("BranchReport 不可用（status=failed）→ 返回没拿到结果")
        void reportFailed() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> BranchReport.failed(BranchReport.FAILED, "没查到相关资料", 1000, List.of());

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertTrue(r.contains("没有拿到结果"), "实际: " + r);
            assertNotNull(ledger.takeReport("agent-zhibao"));
        }

        @Test
        @DisplayName("BranchReport 超时 → 返回没拿到结果")
        void reportTimeout() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> BranchReport.failed(BranchReport.TIMEOUT, "超过 300s", 300_000L, List.of());

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertTrue(r.contains("没有拿到结果"), "实际: " + r);
        }

        @Test
        @DisplayName("BranchReport.done 但 answer 空 → 视为不可用")
        void doneButEmptyAnswer() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> new BranchReport(BranchReport.DONE, "", List.of(), List.of(),
                    3, List.of(), List.of(), List.of(), 2000, null);

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertTrue(r.contains("没有拿到结果"), "实际: " + r);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // render：子轮编号清洗 + 引用登记
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("render 清洗与引用登记")
    class RenderSanitize {

        @Test
        @DisplayName("子轮正文中的 [N] 和 [图片N] 角标全部清掉")
        void childRefsStripped() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> doneReport(
                    "查得营收数据 [1]，同比增长 [2]，见配图 [图片1]",
                    List.of());

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            // 角标被清掉
            assertFalse(r.contains("[1]"), "子轮 [1] 应该被清掉: " + r);
            assertFalse(r.contains("[2]"), "子轮 [2] 应该被清掉: " + r);
            assertFalse(r.contains("[图片1]"), "子轮 [图片1] 应该被清掉: " + r);
            // 正文保留
            assertTrue(r.contains("查得营收数据") && r.contains("同比增长"), "正文应该保留: " + r);
        }

        @Test
        @DisplayName("有 SourceSink → 登记成功的引用号出现在回报里")
        void withSink() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            List<Map<String, Object>> childSources = new ArrayList<>();
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("knowledgeId", "kb-1");
            s1.put("title", "Q3 财报");
            childSources.add(s1);
            Map<String, Object> s2 = new LinkedHashMap<>();
            s2.put("knowledgeId", "kb-2");
            s2.put("title", "年报摘要");
            childSources.add(s2);

            BranchRunner runner = req -> doneReport("营收 1 亿 [1]", childSources);

            AtomicInteger sinkCalls = new AtomicInteger(0);
            SubAgentConsultService.SourceSink sink = childList -> {
                sinkCalls.incrementAndGet();
                // 全部登记成功
                List<SubAgentConsultService.SourceSink.Registered> regs = new ArrayList<>();
                regs.add(new SubAgentConsultService.SourceSink.Registered(5, "Q3 财报"));
                regs.add(new SubAgentConsultService.SourceSink.Registered(6, "年报摘要"));
                return regs;
            };

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, sink, "agent-zhibao", "Q");

            assertEquals(1, sinkCalls.get(), "SourceSink.registerAll 应被调一次");
            assertTrue(r.contains("[5]"), "父轮引用号 [5] 应该出现: " + r);
            assertTrue(r.contains("[6]"), "父轮引用号 [6] 应该出现: " + r);
            assertTrue(r.contains("Q3 财报") && r.contains("年报摘要"), "标题应该出现: " + r);
        }

        @Test
        @DisplayName("SourceSink 全部拒绝登记 → 回报里不提引用")
        void sinkAllRejected() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            List<Map<String, Object>> childSources = List.of(Map.of("title", "无关资料"));
            BranchRunner runner = req -> doneReport("OK", childSources);

            SubAgentConsultService.SourceSink sink = childList -> List.of();  // 全部挡掉

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, sink, "agent-zhibao", "Q");

            // 回报里应该提到"另有 1 处资料未通过"
            assertTrue(r.contains("未通过"), "未通过校验的提示应该出现: " + r);
        }

        @Test
        @DisplayName("SourceSink 部分登记 → 如实列出登记号 + 说明未登记数")
        void sinkPartial() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            List<Map<String, Object>> childSources = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", "资料" + (i + 1));
                childSources.add(m);
            }
            BranchRunner runner = req -> doneReport("全部 4 条 [1]", childSources);

            SubAgentConsultService.SourceSink sink = childList -> {
                List<SubAgentConsultService.SourceSink.Registered> regs = new ArrayList<>();
                regs.add(new SubAgentConsultService.SourceSink.Registered(10, "资料1"));
                regs.add(new SubAgentConsultService.SourceSink.Registered(11, "资料2"));
                // 2 条被门挡掉
                return regs;
            };

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, sink, "agent-zhibao", "Q");

            assertTrue(r.contains("[10]") && r.contains("[11]"), "登记号应该出现: " + r);
            assertTrue(r.contains("另有 2 处"), "未登记数 2 应该出现: " + r);
        }

        @Test
        @DisplayName("sink=null 时回报里不提引用（兼容无上下文的调用）")
        void sinkNull() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            List<Map<String, Object>> childSources = List.of(Map.of("title", "有资料"));
            BranchRunner runner = req -> doneReport("OK", childSources);

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertFalse(r.contains("引用表"), "sink=null 时不应提引用表: " + r);
        }

        @Test
        @DisplayName("回报里包含工具步数、用时、产物名")
        void toolStepsAndArtifacts() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> new BranchReport(BranchReport.DONE,
                    "正文 [1]", List.of(), List.of(),
                    7, List.of(),
                    List.of("report.xlsx"), List.of(),
                    5000, null);

            SubAgentConsultService.Ledger ledger = service.newLedger();
            String r = service.consult(ledger, ctx(), runner, null, "agent-zhibao", "Q");

            assertTrue(r.contains("7") && r.contains("步工具"), "工具步数应该出现: " + r);
            assertTrue(r.contains("report.xlsx"), "产物文件名应该出现: " + r);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // onConsulted 回调
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("onConsulted 回调")
    class OnConsultedCallback {

        @Test
        @DisplayName("成功咨询触发回调")
        void callbackOnSuccess() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> doneReport("OK", List.of());

            AtomicInteger callbackCount = new AtomicInteger(0);
            String[] capturedAgentId = new String[1];
            String[] capturedAgentName = new String[1];

            SubAgentConsultService.Ctx ctxWithCb = new SubAgentConsultService.Ctx(
                    "ps", "pt", "u1", "m1", false, 0,
                    "self-agent-id", "",
                    (id, name) -> {
                        callbackCount.incrementAndGet();
                        capturedAgentId[0] = id;
                        capturedAgentName[0] = name;
                    });

            service.consult(service.newLedger(), ctxWithCb, runner, null,
                    "agent-zhibao", "Q");

            assertEquals(1, callbackCount.get(), "回调应该被调一次");
            assertEquals("agent-zhibao", capturedAgentId[0]);
            assertEquals("智报助手", capturedAgentName[0]);
        }

        @Test
        @DisplayName("失败/异常不触发回调")
        void noCallbackOnFailure() {
            when(agentService.get("agent-zhibao")).thenReturn(targetAgent);
            when(agentService.consultCandidates("self-agent-id")).thenReturn(List.of(targetAgent));

            BranchRunner runner = req -> BranchReport.failed(BranchReport.FAILED, "挂了", 1000, List.of());

            AtomicInteger callbackCount = new AtomicInteger(0);
            SubAgentConsultService.Ctx ctxWithCb = new SubAgentConsultService.Ctx(
                    "ps", "pt", "u1", "m1", false, 0,
                    "self-agent-id", "",
                    (id, name) -> callbackCount.incrementAndGet());

            service.consult(service.newLedger(), ctxWithCb, runner, null,
                    "agent-zhibao", "Q");

            assertEquals(0, callbackCount.get(), "失败不应触发回调");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ledger 独立行为
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Ledger 独立行为")
    class LedgerBehavior {

        @Test
        @DisplayName("缓存键 = agentId\\u0000 + question.strip()")
        void cacheKeyIsAgentIdPlusQuestion() {
            SubAgentConsultService.Ledger ledger = service.newLedger();
            ledger.remember("a1", " Q ", "text");
            assertNotNull(ledger.cached("a1", "Q"));
            assertNotNull(ledger.cached("a1", "  Q  "));  // strip 后相同
            assertNull(ledger.cached("a1", "q"));         // 大小写敏感
            assertNull(ledger.cached("a2", "Q"));         // 不同 agent
        }

        @Test
        @DisplayName("report 按 agentId 存，takeReport 取走即摘")
        void reportsMap() {
            SubAgentConsultService.Ledger ledger = service.newLedger();
            ledger.report("a1", Map.of("status", "done"));
            ledger.report("a2", Map.of("status", "failed"));

            assertNotNull(ledger.takeReport("a1"));
            assertNull(ledger.takeReport("a1"));   // 取走即摘
            assertNotNull(ledger.takeReport("a2"));
        }

        @Test
        @DisplayName("并发 start 累加次数")
        void usedCount() {
            SubAgentConsultService.Ledger ledger = service.newLedger();
            assertEquals(0, ledger.used());
            ledger.start();
            ledger.start();
            assertEquals(2, ledger.used());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------------------------------------

    private SubAgentConsultService.Ctx ctx() {
        return new SubAgentConsultService.Ctx(
                "parent-sess", "parent-turn", "user-1", "model-v1", false, 0,
                "self-agent-id", "", null);
    }

    private SubAgentConsultService.Ledger newLedger() {
        return service.newLedger();
    }

    private BranchReport doneReport(String answer, List<Map<String, Object>> sources) {
        return new BranchReport(BranchReport.DONE, answer, sources, List.of(),
                3, List.of(), List.of(), List.of(), 2000, null);
    }
}
