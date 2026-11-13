package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.WorkflowMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 工作流事件触发派发（第 4 期）：平台内部事件 → 已订阅的工作流。
 * <p>
 * 位置纪律（与 DocumentService 的旁路约定一致）：
 * <ul>
 *   <li><b>监听侧全兜异常</b>：@EventListener 默认在发布线程（解析收尾线程）同步执行——
 *       本方法体只允许做「链深判断 → 预筛快查 → 扔工作流派发池」三步轻活，
 *       任何一步抛错都吞掉只记 log，绝不让解析管线跟着遭殃；</li>
 *   <li><b>订阅粒度</b>：只有显式配置了事件触发器的工作流派发；</li>
 *   <li><b>防环</b>：文档链深查 {@link WorkflowEventChainRegistry}（kb_write 写入的文档有登记），
 *       超过上限不再派发——「写入→解析完成→再触发→再写入」的自环跑到上限自动停。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WorkflowEventTriggerService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowService workflowService;
    private final WorkflowEventChainRegistry chainRegistry;

    public WorkflowEventTriggerService(WorkflowMapper workflowMapper, WorkflowService workflowService,
                                       WorkflowEventChainRegistry chainRegistry) {
        this.workflowMapper = workflowMapper;
        this.workflowService = workflowService;
        this.chainRegistry = chainRegistry;
    }

    /** 文档解析完成（kb.docIndexed）：按订阅派发。监听侧全兜——发布线程是解析收尾，一行都不许炸 */
    @EventListener
    public void onDocIndexed(DocIndexedEvent event) {
        if (event == null || event.docId() == null) return;
        try {
            // 链深：kb_write 写入的文档有登记（= 写入 run 深度 + 1）；外部上传按 1 层起算。
            // 超上限直接放弃——这一代文档不再产生任何触发
            int depth = chainRegistry.chainDepth(event.docId());
            if (depth > WorkflowEventChainRegistry.MAX_CHAIN_DEPTH) {
                log.info("[WORKFLOW-EVT] 链深达上限，放弃派发：doc={} depth={}（max={}）",
                        event.docId(), depth, WorkflowEventChainRegistry.MAX_CHAIN_DEPTH);
                return;
            }
            // 预筛：已发布且配了订阅的行（工作流量级小，内存过滤类型与库）
            List<com.wenqu.ai.model.Workflow> candidates = workflowMapper.selectList(
                    new LambdaQueryWrapper<com.wenqu.ai.model.Workflow>()
                            .eq(com.wenqu.ai.model.Workflow::getStatus, "published")
                            .isNotNull(com.wenqu.ai.model.Workflow::getEventTriggers));
            for (com.wenqu.ai.model.Workflow w : candidates) {
                boolean hit = WorkflowService.parseEventTriggers(w.getEventTriggers()).stream().anyMatch(sub ->
                        "kb.docIndexed".equals(sub.get("type"))
                                && (sub.get("kbId") == null || String.valueOf(sub.get("kbId")).isBlank()
                                || event.kbId() != null && event.kbId().equals(sub.get("kbId"))));
                if (!hit) continue;
                dispatch(w, event, depth);
            }
        } catch (Exception e) {
            log.warn("[WORKFLOW-EVT] 文档入库事件处理失败（已忽略）：doc={} err={}", event.docId(), e.getMessage());
        }
    }

    /** 单个工作流的派发：守卫失败（未发布/含审批节点/归档）按「跳过并告警」，与定时触发同口径 */
    private void dispatch(com.wenqu.ai.model.Workflow w, DocIndexedEvent event, int depth) {
        try {
            workflowService.runByEvent(w.getId(), Map.of(
                    "kbId", event.kbId() == null ? "" : event.kbId(),
                    "docId", event.docId(),
                    "docTitle", event.docTitle() == null ? "" : event.docTitle(),
                    "_wfChainDepth", depth));
            log.info("[WORKFLOW-EVT] 事件触发：workflow={}（{}）doc={} depth={}",
                    w.getName(), w.getId(), event.docId(), depth);
        } catch (com.wenqu.ai.common.BizException e) {
            log.warn("[WORKFLOW-EVT] 事件触发跳过：workflow={}（{}）{}", w.getName(), w.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("[WORKFLOW-EVT] 事件触发派发异常（已忽略）：workflow={} err={}", w.getId(), e.getMessage());
        }
    }
}
