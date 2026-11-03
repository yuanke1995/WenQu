package com.wenqu.ai.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 只用于 .json 产物的交付前语法把关；逐行 JSON（.jsonl）不做整体解析，不在此限 */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper();

    /** 同一会话同一文件的 JSON 打回上限：超过直接放行入库（fail-open），由面板校验兜底。
     * 2026-11-03 实测教训：无上限的打回会让模型无限重写（一轮烧掉 200 万+ token），与
     * form_get_draft 盲试事故同构——错误回声必须有终止性。且实测重写成功率极低
     * （坏上下文会污染后续重写，9 连坏），多打回只是烧时间，1 次足够。 */
    private static final int MAX_JSON_REJECTS = 1;

    private final java.util.concurrent.ConcurrentHashMap<String, Integer> jsonRejects =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        // .json 产物交付前语法把关：模型偶发写出括号错位的坏 JSON（2026-11-03 实测约 1/3 轮次），
        // 面板解析失败只能让用户整轮重发；在这里当轮打回，模型立即重写，用户无感。
        // 打回有硬上限：超限放行入库，绝不让模型陷入重写循环。
        String fileName = request.fileName();
        if (fileName != null && fileName.endsWith(".json")) {
            String rejectKey = sessionId + ":" + fileName;
            int rejects = jsonRejects.getOrDefault(rejectKey, 0);
            if (rejects < MAX_JSON_REJECTS) {
                ArtifactDeliveryResult bad = validateJson(fileName, request.content());
                if (bad != null) {
                    jsonRejects.put(rejectKey, rejects + 1);
                    log.warn("[SANDBOX] deliver_artifact JSON 校验不过（第 {} 次打回）file={} session={}",
                            rejects + 1, fileName, sessionId);
                    return bad;
                }
            } else {
                log.warn("[SANDBOX] deliver_artifact JSON 打回已达上限（{} 次），放行入库 file={} session={}",
                        rejects, fileName, sessionId);
                jsonRejects.remove(rejectKey);
            }
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

    /**
     * .json 产物交付前的严格语法校验；通过返回 {@code null}，不通过返回当轮打回的失败结果。
     * <p>打回文案必须是<b>可执行的当轮指令</b>（坏点位置 + 上下文 + 立即重写），不能只说"格式错了"——
     * 2026-11-02 form_get_draft 事故的教训：错误回声没有终止性/行动性，模型会原地打转。
     * <p>只拦单份 JSON；逐行 JSON 请用 .jsonl 后缀交付，不在此限。
     */
    private ArtifactDeliveryResult validateJson(String fileName, byte[] content) {
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        try {
            STRICT_JSON.readTree(text);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            long off = e.getLocation() != null ? e.getLocation().getCharOffset() : -1L;
            String ctx = "";
            if (off >= 0 && off <= text.length()) {
                int from = (int) Math.max(0, off - 60);
                int to = (int) Math.min(text.length(), off + 60);
                ctx = "……" + text.substring(from, to) + "……";
            }
            String pos = e.getLocation() == null ? "未知位置"
                    : "第 " + e.getLocation().getLineNr() + " 行第 " + e.getLocation().getColumnNr()
                    + " 列（约第 " + off + " 个字符）";
            return ArtifactDeliveryResult.fail(
                    "交付被拒：" + fileName + " 不是合法 JSON（" + e.getOriginalMessage() + "，坏点在" + pos + "，"
                            + "坏点上下文：" + ctx + "）。"
                            + "立即用 write_file 把整份文件重写一遍，重写时**改用多行缩进排版**"
                            + "（每个组件、每层括号独立成行，逐行对齐最不容易错位；不要压缩成一行），"
                            + "只修语法、内容保持原样；写完重新 deliver_artifact，"
                            + "不要向用户解释，也不要只发修正片段。");
        }
    }
}