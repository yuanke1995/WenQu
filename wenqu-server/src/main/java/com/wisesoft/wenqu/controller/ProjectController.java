package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目（Project）路由，逐端点对齐参考实现 {@code server/routers/project_router.py}。
 *
 * <p>响应体即服务视图的返回值（列表为数组、单项为对象），不做外层包装。
 *
 * <p>平台差异（必要替换）：
 * <ul>
 *   <li>{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；</li>
 *   <li>请求体 {@code ProjectCreate/ProjectUpdate}（pydantic）→ {@code Map} 读取 snake_case 键
 *       （本工程 controller 层既有做法），**能力差异**：pydantic 的 {@code extra="forbid"}
 *       （多余字段报 422）在 Jackson 下不生效——多余键被忽略，不做拒绝（如需逐字对齐需自定义反序列化）；</li>
 *   <li>{@code Depends(get_db)} → 服务层各自的事务/仓储边界（本工程服务方法内部自取 Mapper）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "projects", description = "项目创建与选择")
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "列出当前用户可选择的 Project")
    @GetMapping
    public List<Map<String, Object>> listProjects() {
        String uid = AuthGuards.requireUser();
        return projectService.listProjectsView(uid);
    }

    @Operation(summary = "独立创建 managed 或 linked Project",
            description = "body: request_id / name / workdir{mode,path}")
    @PostMapping
    public Map<String, Object> createProject(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        String requestId = body.get("request_id") == null ? null : String.valueOf(body.get("request_id"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        Object rawWorkdir = body.get("workdir");
        String directoryMode = "managed";
        String workdirPath = null;
        if (rawWorkdir instanceof Map<?, ?> workdir) {
            if (workdir.get("mode") != null) {
                directoryMode = String.valueOf(workdir.get("mode"));
            }
            if (workdir.get("path") != null) {
                workdirPath = String.valueOf(workdir.get("path"));
            }
        }
        return projectService.createProjectView(uid, requestId, name, directoryMode, workdirPath);
    }

    @Operation(summary = "列出可作为目录快捷选择的历史 Conversation")
    @GetMapping("/history-candidates")
    public Map<String, Object> listHistoryCandidates(
            @RequestParam(value = "q", defaultValue = "") String query,
            @Parameter(description = "1..100，默认 20")
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        String uid = AuthGuards.requireUser();
        return projectService.listHistoryCandidatesView(uid, query, limit, Math.max(0, offset));
    }

    @Operation(summary = "重命名当前用户的 Project", description = "body: name")
    @PutMapping("/{project_id}")
    public Map<String, Object> renameProject(
            @PathVariable("project_id") String projectId,
            @RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        return projectService.renameProjectView(uid, projectId, name);
    }

    @Operation(summary = "软删除当前用户的 Project 及其中对话")
    @DeleteMapping("/{project_id}")
    public Map<String, Object> deleteProject(@PathVariable("project_id") String projectId) {
        String uid = AuthGuards.requireUser();
        return projectService.deleteProjectView(uid, projectId);
    }
}
