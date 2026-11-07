package com.wenqu.ai.service;

/**
 * 知识库文档解析完成事件（第 4 期事件触发的首个内部源，type=kb.docIndexed）。
 * <p>
 * 中立载体：DocumentService（发布方）与 WorkflowEventTriggerService（订阅方）互不依赖，
 * 经 Spring {@code ApplicationEventPublisher}/{@code @EventListener} 解耦——
 * 发布失败不影响解析管线（订阅方一侧自行兜异常）。
 *
 * @param docId    文档 id（kb_write 防环登记的键）
 * @param kbId     文档归属知识库 id（订阅过滤条件）
 * @param docTitle 文档名（触发入参 docTitle）
 * @param uid      文档归属人（= 创建者；事件触发的运行以它为身份起点之一）
 * @author yuanke
 */
public record DocIndexedEvent(String docId, String kbId, String docTitle, String uid) {
}
