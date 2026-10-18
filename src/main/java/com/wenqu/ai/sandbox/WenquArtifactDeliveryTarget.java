package com.wenqu.ai.sandbox;

import com.wenqu.ai.service.ArtifactService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 框架 {@link ArtifactDeliveryTarget} 的问渠实现 —— 让 {@code deliver_artifact} 落到问渠的产物库。
 *
 * <p><b>为什么必须接</b>：框架 {@code ArtifactDeliveryTarget} 注释原文
 * *"When set, the {@code deliver_artifact} tool is registered and the sandbox system prompt instructs
 * the model to use it; when unset, no delivery tool is exposed."*
 * —— 不接这个 SPI，模型就没有把沙盒产物带出容器的手段，产物会烂在容器里。
 *
 * <p><b>不做二次实现</b>：框架工具负责从沙盒下载文件字节，本类只做转发。
 * 产物入库、列表、下载、前端面板全部仍走问渠既有的 {@link ArtifactService}，
 * 因此界面上产物区与迁移前逐字一致。
 *
 * <p><b>归属解析</b>：{@link ArtifactService} 按 {@code sessionId} 存产物，
 * 而框架传入的是 {@link RuntimeContext}（其 sessionId 来自 {@code RuntimeContext.builder().sessionId(...)}），
 * 取它即可；取不到（如分享链路）则按失败处理——产物存到别人的会话里比存不进去更糟。
 */
public class WenquArtifactDeliveryTarget implements ArtifactDeliveryTarget {

    private static final Logger log = LoggerFactory.getLogger(WenquArtifactDeliveryTarget.class);

    private final ArtifactService artifactService;

    public WenquArtifactDeliveryTarget(ArtifactService artifactService) {
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService is required");
    }

    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext runtimeContext, ArtifactDeliveryRequest request) {
        if (request == null) {
            return ArtifactDeliveryResult.fail("no artifact to deliver");
        }
        String sessionId = runtimeContext == null ? null : runtimeContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[SANDBOX] deliver_artifact 拿不到 sessionId，产物未交付 file={}", request.filePath());
            return ArtifactDeliveryResult.fail(
                    "产物无法交付：这一轮没有会话标识，请重试或改用 execute 把结果打印出来。");
        }
        try {
            Map<String, Object> stored = artifactService.writeBytes(
                    sessionId, request.fileName(), request.content(), request.description());
            if (stored == null) {
                return ArtifactDeliveryResult.fail("产物入库失败：产物服务返回空结果");
            }
            // 产物卡片实时推送：框架的 deliver_artifact 只管「存进去」，
            // 界面上「产物已生成」的即时反馈是问渠自己的 SSE 通道（publish），
            // 少了它用户要等整轮结束才在产物页看到东西。
            artifactService.publish(sessionId, "artifact", com.alibaba.fastjson2.JSON.toJSONString(stored));
            return ArtifactDeliveryResult.success("已交付产物：" + stored.get("filename"));
        } catch (Exception e) {
            // 交付失败要让模型知道（它可能改走 execute 打印结果），所以带原因回绝而不是静默
            log.warn("[SANDBOX] deliver_artifact 失败 file={} session={}: {}",
                    request.filePath(), sessionId, e.toString());
            return ArtifactDeliveryResult.fail("产物保存失败：" + e.getMessage());
        }
    }
}