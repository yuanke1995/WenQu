package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.service.ProjectFileService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.WorkflowService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区容器接口：把「一个工作目录 + 若干次会话 + 若干份产物」收在一个可持续的根下。
 *
 * <p>能力面：列表 / 详情 / 设置（名称·约定·默认助手）/ 归档恢复 / 新建 / 删除（可连目录）、
 * 工作区文件浏览与下载、本工作区的工作流。会话与产物的移动归属在
 * {@code /session/{id}/project} 与 {@code /artifact/{id}/project}（那两个端点管归属，这里管容器）。
 *
 * <p><b>归属口径</b>：一律按 {@link RequestUser#uid()} 过滤，与产物同口径。
 * 工作区不是权限边界，它只是归属与过滤维度（见 {@code c_ai_project} 建表注释）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/project")
@RequiredArgsConstructor
@Tag(name = "工作区容器", description = "个人工作容器：工作目录 / 会话 / 产物的归属根")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectFileService projectFileService;
    private final WorkflowService workflowService;

    @Operation(summary = "我的工作区列表",
            description = "implicit 兜底容器在前（每人一个、后台懒创建），其余按创建时间倒序。"
                    + "首次调用若还没有兜底容器会自动创建——用户不建工作区也能正常问答，只是产物开始有归属")
    @GetMapping("/list")
    public ResultJson list() {
        String uid = RequestUser.uid();
        // 懒创建仍保留：老客户端 / ensureSession / 内部派发还会用到 implicit 兜底容器；
        // 但它不再是工作区列表里的一个「兜底容器」，前端按 isImplicit 过滤掉
        projectService.ensureImplicit(uid);
        List<Project> projects = projectService.listOf(uid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project p : projects) {
            out.add(toDto(p));
        }
        return ResultJson.ok(out);
    }

    private static Map<String, Object> toDto(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("selectionStatus", p.getSelectionStatus());
        m.put("status", p.getStatus());
        // implicit 容器没有名字；它不是用户建的工作区，界面上就是侧栏那一栏「会话」，
        // 不另造「会话」栏「兜底容器」这种界面上不存在的名词，也不把这个词写进库里。
        m.put("displayName", p.getName() == null || p.getName().isBlank() ? "会话" : p.getName());
        // 工作区约定与默认助手：工作区页编辑、每轮注入/解析用；列表里也返回，省一次请求
        m.put("instructions", p.getInstructions() == null ? "" : p.getInstructions());
        m.put("defaultAgentId", p.getDefaultAgentId() == null ? "" : p.getDefaultAgentId());
        m.put("isImplicit", Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus()));
        m.put("workdirPath", p.getWorkdirPath());
        m.put("createTime", p.getCreateTime() == null ? null : p.getCreateTime().toString());
        return m;
    }

    @Operation(summary = "工作区详情", description = "名称 / 工作区约定 / 默认助手；不存在或不属于你返回可读错误")
    @GetMapping("/{id}")
    public ResultJson detail(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(toDto(projectService.detail(RequestUser.uid(), id)));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    /**
     * 更新工作区设置。三个字段都是「传才改」：不传保持原值，传空串表示清空。
     * <p>工作区约定与默认助手是「真实工作区」与「分类标签」的分界，故这里单独成端点而不是混在重命名里。
     */
    @Operation(summary = "更新工作区设置",
            description = "name / instructions（工作区约定，对该工作区所有会话生效）/ defaultAgentId（工作区默认助手）。传才改，空串=清空")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody UpdateReq req) {
        try {
            Project updated = projectService.update(RequestUser.uid(), id,
                    req == null ? null : req.name(),
                    req == null ? null : req.instructions(),
                    req == null ? null : req.defaultAgentId());
            return ResultJson.ok(toDto(updated), "已保存");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record UpdateReq(String name, String instructions, String defaultAgentId) {
    }

    @Operation(summary = "归档 / 恢复工作区", description = "body: {status: active|archived}。归档不动会话与产物，implicit 兜底容器不可归档")
    @PutMapping("/{id}/status")
    public ResultJson status(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        try {
            Object st = body == null ? null : body.get("status");
            return ResultJson.ok(toDto(projectService.setStatus(RequestUser.uid(), id,
                    st == null ? "" : String.valueOf(st))));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "工作区文件列表",
            description = "工作区目录下的文件（相对路径/大小/修改时间，按时间倒序）。目录不存在=正常空态；"
                    + "宿主目录读不到（如沙盒在另一台机器）如实返回不可用")
    @GetMapping("/{id}/files")
    public ResultJson files(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(projectFileService.list(RequestUser.uid(), id));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    @Operation(summary = "下载工作区文件", description = "按工作区目录内的相对路径下载；越界路径（../、绝对路径）一律 400")
    @GetMapping("/{id}/file")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable("id") String id,
            @RequestParam("path") String path) {
        try {
            java.nio.file.Path file = projectFileService.resolveDownload(RequestUser.uid(), id, path);
            String name = file.getFileName().toString();
            // 文件名可能含中文：用 RFC 5987 的 filename* 编码，避免部分浏览器把名字截成乱码
            String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                    .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                    .body(new org.springframework.core.io.FileSystemResource(file));
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "工作区的工作流", description = "本工作区下本人的工作流（名称/状态/更新时间）；触发仍走 /workflow/{id}/run")
    @GetMapping("/{id}/workflows")
    public ResultJson workflows(@PathVariable("id") String id) {
        try {
            // 先校验工作区归属（detail 会抛错），再查工作流
            projectService.detail(RequestUser.uid(), id);
            return ResultJson.ok(workflowService.listByProject(RequestUser.uid(), id));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "新建工作区", description = "用户显式创建一个工作区；其下可继续积累会话与产物。implicit 兜底容器不用建、也不可删")
    @PostMapping
    public ResultJson create(@RequestBody CreateReq req) {
        try {
            Project p = projectService.create(RequestUser.uid(), req == null ? null : req.name());
            return ResultJson.ok(toDto(p), "已创建");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record CreateReq(String name) {
    }

    /**
     * 删除工作区。语义：<b>只摘归属，不删东西</b>——其下会话与产物的 {@code project_id} 置 NULL
     * （成果归人这条设计意图不许被级联删除破坏），工作区行逻辑删除。兜底容器不可删。
     * <p>{@code deleteFiles=true} 时连同工作目录一起删（先删文件、失败即整单失败，行保留可重试）；
     * 不传则文件留在盘上——删了行就再也找不到那个目录，这是给用户的显式选择而不是默认行为。
     */
    @Operation(summary = "删除工作区",
            description = "删除后其下会话与产物仍然保留（只摘掉工作区归属）。implicit 兜底容器不可删。"
                    + "deleteFiles=true 时连工作目录一起删（不可恢复，先删文件失败则整单失败）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id,
                             @RequestParam(value = "deleteFiles", required = false, defaultValue = "false") boolean deleteFiles) {
        try {
            projectService.delete(RequestUser.uid(), id, deleteFiles);
            return ResultJson.ok(null, deleteFiles
                    ? "已删除（其下会话与产物已保留，工作目录已一起删除）"
                    : "已删除（其下会话与产物已保留，仅摘掉归属）");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }
}
