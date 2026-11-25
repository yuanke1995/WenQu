package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
会话分享（分享列表 / 开启 / 停止 / 清除记录）。
从 ChatSessionController 拆出：分享面向「未登录的游客链接」，与会话的增删改查是两套生命周期
（会话删了分享记录仍可悬空存在、要单独停用），放在一起只会让两边都多带一堆用不上的依赖。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "会话分享", description = "会话分享的开启、停用与访问统计")
public class ChatShareController {

    /** 会话分享的开关、清理与访问统计 */
    private final com.wenqu.ai.service.SessionShareService sessionShareService;
    /** 仅用于「只能操作自己的会话」这一道归属校验 */
    private final SessionService sessionService;


    /**
     * 我分享过的会话列表（个人设置 → 分享管理）。
     *
     * <p>与 {@code /session/{id}/share} 的区别是那是"逐个会话单查"，这里枚举全部——
     * 分享出去的链接一旦发出就散在各处，用户需要一个能回看"哪些还在开着、被访问过几次"的入口。
     *
     * <p>返回项含 {@code orphaned}：会话已被删除时仍列出该行（enabled 强制呈现为 false），
     * 否则用户会以为"没分享过"，而实际是链接散出去了但自己已经无从关闭。
     */
    @Operation(summary = "我的会话分享列表", description = "枚举当前用户分享过的全部会话，含状态、访问量与悬空标记")
    @Permission("session:share")
    @GetMapping("/session-shares")
    public ResultJson listMySessionShares() {
        return ResultJson.ok(sessionShareService.listMine(RequestUser.uid()));
    }

    /**
     * 按会话停用分享（分享管理页用）。
     *
     * <p>刻意不复用 {@code /session/{id}/share} 的 DELETE：那个端点先 assertOwned，
     * 会话已删除时直接 404，而这恰恰是分享管理页最需要能操作的场景（悬空行只能停、不能再开）。
     * 这里按 created_by 鉴权——分享记录本身就是归属凭据。
     */
    @Operation(summary = "按会话停用分享", description = "分享管理页停用；会话已删除的悬空记录同样可停")
    @Permission("session:share")
    @DeleteMapping("/session-shares/{sessionId}")
    public ResultJson stopShareBySession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        String uid = RequestUser.uid();
        var share = sessionShareService.get(sessionId);
        // 先查后改：不存在或非本人分享都按 404（不泄露他人会话是否存在分享记录）
        if (share == null || !uid.equals(share.getCreatedBy())) {
            throw new BizException(404, "分享记录不存在");
        }
        sessionShareService.disable(sessionId);
        return ResultJson.ok("已停止分享");
    }

    /**
     * 彻底清除一条分享记录（与"停止分享"分开：那个让链接失效、这个让记录消失）。
     *
     * <p>只接受已停用的记录——生效中的必须先停止，否则用户会在"链接还开着"的同时
     * 把记录删掉，既失去感知也让访问量断档。会话已删除的悬空记录天然是停用态，
     * 这是它们唯一的清理出口。
     */
    @Operation(summary = "清除分享记录", description = "物理删除一条**已停止**的分享记录；生效中的返回 400，须先停止分享")
    @Permission("session:share")
    @DeleteMapping("/session-shares/{sessionId}/record")
    public ResultJson purgeShareRecord(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        // 归属校验先做（分享记录是归属凭据），再由 service 判停用态
        var share = sessionShareService.get(sessionId);
        if (share == null || !RequestUser.uid().equals(share.getCreatedBy())) {
            throw new BizException(404, "分享记录不存在");
        }
        if (share.getEnabled() != null && share.getEnabled() == 1) {
            throw new BizException(400, "该链接仍在生效中，请先停止分享再清除记录");
        }
        sessionShareService.purge(sessionId, RequestUser.uid());
        return ResultJson.ok("记录已清除");
    }

    @Operation(summary = "查询会话分享状态", description = "返回 {enabled, token, visitCount, lastVisitAt}；未分享返回 enabled=false。仅会话所有者可查")
    @Permission("session:share")
    @GetMapping("/session/{sessionId}/share")
    public ResultJson getSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        var s = sessionShareService.get(sessionId);
        boolean on = s != null && s.getEnabled() != null && s.getEnabled() == 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", on);
        out.put("token", on ? s.getToken() : "");
        out.put("visitCount", s == null || s.getVisitCount() == null ? 0 : s.getVisitCount());
        out.put("lastVisitAt", s == null ? null : s.getLastVisitAt());
        return ResultJson.ok(out);
    }

    @Operation(summary = "开启会话分享", description = "生成（或重新生成）只读分享链接；停止后重新开启会换新 token，"
            + "旧链接立即失效。仅会话所有者可操作")
    @Permission("session:share")
    @PostMapping("/session/{sessionId}/share")
    public ResultJson enableSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        var s = sessionShareService.enable(sessionId, RequestUser.uid());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("token", s.getToken());
        return ResultJson.ok(out, "分享链接已生成");
    }

    @Operation(summary = "停止会话分享", description = "停用后链接立即失效（记录保留，再次开启会换新 token）")
    @Permission("session:share")
    @DeleteMapping("/session/{sessionId}/share")
    public ResultJson disableSessionShare(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        sessionShareService.disable(sessionId);
        return ResultJson.ok("已停止分享");
    }
}
