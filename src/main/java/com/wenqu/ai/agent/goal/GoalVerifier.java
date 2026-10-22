package com.wenqu.ai.agent.goal;

import com.wenqu.ai.mapper.GoalEvidenceMapper;
import com.wenqu.ai.model.Artifact;
import com.wenqu.ai.model.Goal;
import com.wenqu.ai.model.GoalEvidence;
import com.wenqu.ai.service.ArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标完成校验器：模型只能提交「完成申请」，这里按证据决定是否允许 COMPLETED。
 *
 * <p>对应文档 2.6.2 的分层，先落最硬的三层：
 * <ul>
 *   <li><b>结构验证</b>：必须有 summary 与至少一条可解析证据；</li>
 *   <li><b>环境验证</b>：artifact 查产物登记表（id 或文件名），file 可选查宿主/工作区存在性；</li>
 *   <li><b>确定性验证</b>：command/test 必须有退出码且为 0（当前由模型随证据提交，后续可接
 *       工具执行账本做二次核对）。</li>
 * </ul>
 *
 * <p>刻意不做的事：不运行命令（Verifier 不能有副作用）、不把模型自述当证据、
 * 不因为「回答写得像完成了」就放行。独立模型评审与人工验收留在后续层，接口已按
 * {@link GoalVerdict} 预留。
 *
 * @author yuanke
 */
@Service
public class GoalVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoalVerifier.class);

    /** 判定器标识：写进 Outcome，便于以后区分不同版本的验收口径。 */
    public static final String VERIFIER_ID = "wenqu-goal-verifier-v1";

    private final ArtifactService artifactService;
    private final GoalEvidenceMapper evidenceMapper;
    private final GoalSettings settings;

    public GoalVerifier(ArtifactService artifactService, GoalEvidenceMapper evidenceMapper,
                        GoalSettings settings) {
        this.artifactService = artifactService;
        this.evidenceMapper = evidenceMapper;
        this.settings = settings;
    }

    /**
     * 核验一次完成申请，并把每条证据的核验结果落库。
     *
     * @param goal     目标（取 uid 做产物归属校验）
     * @param proposal 模型提交的申请
     * @param roundN   轮次
     */
    public GoalVerdict verify(Goal goal, GoalProposal proposal, int roundN) {
        List<String> gaps = new ArrayList<>();
        if (proposal == null) {
            return GoalVerdict.fail(List.of("没有提交完成申请（goal_submit）"), "缺少完成申请", VERIFIER_ID);
        }
        if (proposal.isBlocked()) {
            String reason = proposal.getBlockedReason();
            return GoalVerdict.fail(List.of(reason == null || reason.isBlank()
                    ? "模型报告目标受阻" : reason), "模型自报受阻", VERIFIER_ID);
        }
        if (proposal.getSummary() == null || proposal.getSummary().isBlank()) {
            gaps.add("缺少交付说明（goal_submit.summary）");
        }
        if (!proposal.hasEvidence()) {
            gaps.add("没有提供任何可核验的证据（goal_submit.evidence）");
        }

        int passed = 0;
        for (GoalProposal.Item item : proposal.getEvidence()) {
            String kind = normalizeKind(item.getKind());
            String check = checkOne(goal, item, kind);
            boolean ok = check == null;
            if (ok) passed++;
            else gaps.add(check);
            persist(goal, roundN, kind, item, ok ? GoalEvidence.PASSED : GoalEvidence.FAILED, check);
        }

        if (passed == 0 && !proposal.getEvidence().isEmpty()) {
            gaps.add("所有证据都未通过核验");
        }
        if (!gaps.isEmpty()) {
            log.info("[GOAL] 完成申请未通过: goal={} round={} gaps={}", goal.getId(), roundN, gaps.size());
            return GoalVerdict.fail(gaps, "证据不足", VERIFIER_ID);
        }
        log.info("[GOAL] 完成申请通过: goal={} round={} evidence={}", goal.getId(), roundN, passed);
        return GoalVerdict.pass("证据核验通过（" + passed + " 条）", VERIFIER_ID);
    }

    /** @return null=通过；否则是一句可执行的缺口描述 */
    private String checkOne(Goal goal, GoalProposal.Item item, String kind) {
        if (item == null) return "证据项为空";
        String ref = item.getRef() == null ? "" : item.getRef().strip();
        switch (kind) {
            case GoalEvidence.KIND_ARTIFACT:
                if (ref.isEmpty()) return "产物证据缺少 ref（产物ID或文件名）";
                if (!artifactExists(goal.getUserId(), ref)) {
                    return "产物不存在或不属于当前用户：" + ref;
                }
                return null;
            case GoalEvidence.KIND_FILE:
                if (ref.isEmpty()) return "文件证据缺少 ref（工作区路径）";
                // 沙盒文件在容器里，宿主侧默认查不到；开了严格开关才要求宿主可见。
                if (settings.verifyLocalFiles()) {
                    try {
                        if (!Files.exists(Path.of(ref))) return "文件不存在：" + ref;
                    } catch (Exception e) {
                        return "文件路径无法解析：" + ref;
                    }
                }
                return null;
            case GoalEvidence.KIND_COMMAND:
            case GoalEvidence.KIND_TEST:
                if (ref.isEmpty()) return "命令/测试证据缺少 ref";
                if (item.getExitCode() == null) return "命令/测试证据缺少 exitCode，无法核验：" + ref;
                if (item.getExitCode() != 0) return "命令/测试未通过（exitCode=" + item.getExitCode() + "）：" + ref;
                return null;
            case GoalEvidence.KIND_URL:
                if (ref.isEmpty()) return "URL 证据缺少 ref";
                try {
                    URI uri = URI.create(ref);
                    if (uri.getScheme() == null || uri.getHost() == null) return "URL 格式不合法：" + ref;
                } catch (Exception e) {
                    return "URL 格式不合法：" + ref;
                }
                return null;
            case GoalEvidence.KIND_TEXT:
                if ((item.getNote() == null || item.getNote().isBlank()) && ref.isEmpty()) {
                    return "文本证据缺少说明";
                }
                return null;
            default:
                return "未知证据类型：" + item.getKind();
        }
    }

    private boolean artifactExists(String uid, String ref) {
        if (uid == null || uid.isBlank()) return false;
        try {
            Artifact byId = artifactService.getOwned(ref, uid);
            if (byId != null) return true;
        } catch (RuntimeException ignored) {
            // 不是产物ID，继续按文件名找
        }
        try {
            return !artifactService.list(uid, ref).isEmpty();
        } catch (RuntimeException e) {
            log.warn("[GOAL] 产物证据查询失败 uid={} ref={}: {}", uid, ref, e.toString());
            return false;
        }
    }

    private void persist(Goal goal, int roundN, String kind, GoalProposal.Item item, int verified,
                         String failure) {
        if (item == null) return;
        try {
            GoalEvidence e = new GoalEvidence();
            e.setGoalId(goal.getId());
            e.setRoundN(roundN);
            e.setKind(kind);
            e.setRef(clip(item.getRef(), 1000));
            e.setNote(clip(failure == null ? item.getNote() : failure, 2000));
            e.setVerified(verified);
            e.setCreatedAt(LocalDateTime.now());
            evidenceMapper.insert(e);
        } catch (Exception ex) {
            log.warn("[GOAL] 证据落库失败（不影响判定）goal={} {}", goal.getId(), ex.getMessage());
        }
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return GoalEvidence.KIND_TEXT;
        String k = kind.trim().toLowerCase();
        return switch (k) {
            case "artifact", "产物" -> GoalEvidence.KIND_ARTIFACT;
            case "file", "文件" -> GoalEvidence.KIND_FILE;
            case "command", "命令" -> GoalEvidence.KIND_COMMAND;
            case "test", "测试" -> GoalEvidence.KIND_TEST;
            case "url", "link", "链接" -> GoalEvidence.KIND_URL;
            default -> GoalEvidence.KIND_TEXT;
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String v = s.strip();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
}
