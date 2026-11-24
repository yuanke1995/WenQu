package com.wenqu.ai.controller;

import com.wenqu.ai.agent.team.AgentTeamService;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 智能体团队：会话级开关、成员列表、成员消息（UI 面板用；模型侧走 team_* 工具）。
 *
 * <p>全部按真实登录用户隔离；团队开关只写会话行（不做系统配置）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/team")
@Tag(name = "智能体团队", description = "会话级团队开关、成员发现与消息")
public class TeamController {

    private final AgentTeamService teamService;
    private final SessionService sessionService;
    private final com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway;
    private final com.wenqu.ai.agent.team.TeamTaskEventService taskEventService;
    private final com.wenqu.ai.agentx.team.TeamMemberLiveFeed liveFeed;

    public TeamController(AgentTeamService teamService, SessionService sessionService,
                          com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway,
                          com.wenqu.ai.agent.team.TeamTaskEventService taskEvents,
                          com.wenqu.ai.agentx.team.TeamMemberLiveFeed liveFeed) {
        this.teamService = teamService;
        this.sessionService = sessionService;
        this.teamGateway = teamGateway;
        this.taskEventService = taskEvents;
        this.liveFeed = liveFeed;
    }

    @Operation(summary = "读取会话团队开关", description = "从会话行读取（不是系统设置）")
    @GetMapping("/{sessionId}/enabled")
    public ResultJson<Map<String, Object>> isEnabled(@PathVariable String sessionId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(sessionId, uid);
        return ResultJson.ok(Map.of("enabled", sessionService.isTeamEnabled(sessionId)));
    }

    @Operation(summary = "会话团队开关", description = "开启/关闭当前会话的智能体团队模式（写会话行，不是系统设置）")
    @PostMapping("/{sessionId}/enabled")
    public ResultJson<Map<String, Object>> setEnabled(@PathVariable String sessionId,
                                                      @RequestParam boolean enabled) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(sessionId, uid);
        teamService.setEnabled(uid, sessionId, enabled);
        return ResultJson.ok(Map.of("sessionId", sessionId, "enabled", enabled));
    }

    @Operation(summary = "团队成员列表", description = "一棵树的成员名字、会话 id、状态（在线由进程内活实例判定）")
    @GetMapping("/{rootId}/members")
    public ResultJson<List<AgentTeamService.Member>> members(@PathVariable String rootId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        return ResultJson.ok(teamService.listMembers(rootId, uid));
    }

    @Operation(summary = "最近团队消息", description = "跨成员聚合的最近消息（成员消息卡，读框架 TeamClient 邮箱）")
    @GetMapping("/{rootId}/messages")
    public ResultJson<Object> messages(@PathVariable String rootId,
                                       @RequestParam(defaultValue = "20") int limit) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        return ResultJson.ok(teamGateway.messages(rootId, Math.min(Math.max(1, limit), 100)));
    }

    @Operation(summary = "向成员发消息", description = "用户从面板向成员发消息（走框架 TeamClient 邮箱 + 唤醒）")
    @PostMapping("/{rootId}/send")
    public ResultJson<Map<String, Object>> send(@PathVariable String rootId,
                                                @RequestParam String member,
                                                @RequestParam String message) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        boolean ok = teamGateway.send(rootId, "lead", member, message);
        return ResultJson.ok(Map.of("delivered", ok));
    }

    @Operation(summary = "叫停成员当前轮", description = "只停目标成员当前轮，排队消息保留；祖先/根可用")
    @PostMapping("/{rootId}/interrupt")
    public ResultJson<Map<String, Object>> interrupt(@PathVariable String rootId,
                                                     @RequestParam String memberSessionId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        boolean ok = teamService.interruptMember(uid, memberSessionId);
        return ResultJson.ok(Map.of("interrupted", ok));
    }

    @Operation(summary = "收起整棵团队树", description = "根会话结束时叫停全部在线成员；显式操作，不随普通轮结束触发")
    @PostMapping("/{rootId}/drain")
    public ResultJson<Map<String, Object>> drain(@PathVariable String rootId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        return ResultJson.ok(Map.of("stopped", teamService.drain(uid, rootId)));
    }

    @Operation(summary = "成员会话回放", description = "查看某位团队成员自己的问答历史；归属按根会话校验，成员必须属于这棵树")
    @GetMapping("/{rootId}/member/{memberId}/history")
    public ResultJson<Object> memberHistory(@PathVariable String rootId, @PathVariable String memberId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        com.wenqu.ai.model.Session member = sessionService.sessionById(memberId);
        if (member == null || !com.wenqu.ai.service.SessionService.KIND_TEAM.equals(member.getKind())
                || !rootId.equals(member.getRootSessionId())
                || !uid.equals(member.getUserId())) {
            throw new com.wenqu.ai.common.BizException(403, "该会话不是本团队的成员");
        }
        return ResultJson.ok(sessionService.getHistory(memberId));
    }

    @Operation(summary = "成员轮直播流", description = "订阅某位团队成员<b>正在跑的那一轮</b>，事件与主问答完全相同"
            + "（token/thinking/tool_status/approval_required/artifact… 直到 done），区别只是出口：成员轮由框架唤醒在"
            + "后台起跑、没有请求线程，这条独立流把它的帧实时转给成员会话页。"
            + "第一帧恒定是 snapshot（含这一段已产生的帧，按原顺序；done=true 表示这一轮已结束，前端改读历史），"
            + "之后逐帧增量。没有在跑的轮（未唤醒 / 别的副本起的 / 已过保留期）时返回 204，前端按常规历史回显，不算错误。")
    @GetMapping(value = "/member/{memberId}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter memberLive(@PathVariable String memberId) {
        String uid = RequestUser.uid();
        com.wenqu.ai.model.Session member = sessionService.sessionById(memberId);
        // 归属按成员会话所属的树校验：能看成员页的人才能接它的直播
        if (member == null || !com.wenqu.ai.service.SessionService.KIND_TEAM.equals(member.getKind())
                || !uid.equals(member.getUserId())) {
            throw new com.wenqu.ai.common.BizException(403, "该会话不是你的团队成员");
        }
        SseEmitter emitter = liveFeed.subscribe(memberId);
        if (emitter == null) {
            // 没有直播：回一条立即结束的空流，前端读不到 snapshot 即走历史
            SseEmitter empty = new SseEmitter(0L);
            empty.complete();
            return empty;
        }
        return emitter;
    }

    @Operation(summary = "团队任务图", description = "任务板节点与依赖边（读框架 TeamClient，写入由 team 工具承担）")
    @GetMapping("/{rootId}/tasks")
    public ResultJson<Object> tasks(@PathVariable String rootId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        return ResultJson.ok(teamGateway.tasks(rootId));
    }

    @Operation(summary = "任务事件时间线", description = "任务变更账本（WenquTeamClient 投影）：创建/依赖/指派/认领/完成/失败，供面板与 DAG 画布展示")
    @GetMapping("/{rootId}/tasks/{taskId}/events")
    public ResultJson<Object> taskEvents(@PathVariable String rootId, @PathVariable String taskId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(rootId, uid);
        return ResultJson.ok(taskEventService.list(rootId, taskId));
    }
}
