package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.service.ProjectFileService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import com.wenqu.ai.security.Permission;

/**
 * 工作区容器接口：把「一个工作目录 + 若干次会话 + 若干份产物」收在一个可持续的根下。
 *
 * <p>能力面：列表 / 详情 / 设置（名称·外观·记忆隔离）/ 归档恢复 / 新建 / 删除、
 * 工作区目录的分层浏览 / 下载 / 在电脑上打开 / 工作区记忆。会话与产物的移动归属在
 * {@code /session/{id}/project} 与 {@code /artifact/{id}/project}（那两个端点管归属，这里管容器）。
 *
 * <p><b>归属口径</b>：一律按 {@link RequestUser#uid()} 过滤，与产物同口径。
 * 工作区不是权限边界，它只是归属与过滤维度（见 {@code c_ai_project} 建表注释）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/project")
@RequiredArgsConstructor
@Tag(name = "工作区容器", description = "个人工作容器：工作目录 / 会话 / 产物的归属根")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectFileService projectFileService;
    private final com.wenqu.ai.executor.ExecutorService executorService;
    private final com.wenqu.ai.executor.ExecutorRegistry executorRegistry;

    @Operation(summary = "我的工作区列表",
            description = "implicit 兜底容器在前（每人一个、后台懒创建），其余按创建时间倒序。"
                    + "首次调用若还没有兜底容器会自动创建——用户不建工作区也能正常问答，只是产物开始有归属")
    @Permission("project:read")
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

    private Map<String, Object> toDto(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("selectionStatus", p.getSelectionStatus());
        m.put("status", p.getStatus());
        // implicit 容器没有名字；它不是用户建的工作区，界面上就是侧栏那一栏「会话」，
        // 不另造「会话」栏「兜底容器」这种界面上不存在的名词，也不把这个词写进库里。
        m.put("displayName", p.getName() == null || p.getName().isBlank() ? "会话" : p.getName());
        m.put("memoryIsolated", p.getMemoryIsolated() == null ? 0 : p.getMemoryIsolated());
        m.put("isImplicit", Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus()));
        m.put("isLocal", projectService.isLocal(p));
        m.put("executorId", p.getExecutorId() == null ? "" : p.getExecutorId());
        m.put("executorOnline", p.getExecutorId() != null && !p.getExecutorId().isBlank()
                && executorRegistry.online(p.getExecutorId()));
        List<Map<String, Object>> roots = new ArrayList<>();
        for (ProjectRoot r : projectService.rootsOf(p)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", r.getId());
            one.put("alias", r.getAlias());
            one.put("path", r.getPath());
            one.put("primary", r.getPrimaryRoot() != null && r.getPrimaryRoot() == ProjectRoot.PRIMARY);
            roots.add(one);
        }
        m.put("roots", roots);
        m.put("icon", p.getIcon() == null ? "" : p.getIcon());
        m.put("color", p.getColor() == null ? "" : p.getColor());
        m.put("createTime", p.getCreateTime() == null ? null : p.getCreateTime().toString());
        return m;
    }

    @Operation(summary = "工作区详情", description = "名称 / 外观 / 记忆隔离状态；不存在或不属于你返回可读错误")
    @Permission("project:read")
    @GetMapping("/{id}")
    public ResultJson detail(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(toDto(projectService.detail(RequestUser.uid(), id)));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    /**
     * 工作区级记忆（独立于用户级记忆）：读工作区根目录 .wenqu/MEMORY.md。
     * 无工作区 / 未绑目录 / 文件不存在 / 执行器离线 → 返回空 content，不报错（记忆只是辅助上下文）。
     */
    @Operation(summary = "读取工作区记忆", description = "工作区根目录 .wenqu/MEMORY.md 的内容；不存在返回空串")
    @Permission("project:read")
    @GetMapping("/{id}/workspace-memory")
    public ResultJson workspaceMemory(@PathVariable("id") String id) {
        String uid = RequestUser.uid();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", projectFileService.readWorkspaceMemory(uid, id));
        return ResultJson.ok(m);
    }

    /**
     * 写回工作区级记忆（工作区根目录 .wenqu/MEMORY.md），经执行器原子写。与「记忆隔离」开关互不干扰。
     */
    @Operation(summary = "保存工作区记忆", description = "写回工作区根目录 .wenqu/MEMORY.md（经执行器原子写）")
    @Permission("project:write")
    @PutMapping("/{id}/workspace-memory")
    public ResultJson saveWorkspaceMemory(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        String uid = RequestUser.uid();
        String content = body == null ? "" : (body.get("content") == null ? "" : body.get("content"));
        try {
            projectFileService.saveWorkspaceMemory(uid, id, content);
            return ResultJson.ok(true);
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    /**
     * 更新工作区设置。各字段都是「传才改」：不传保持原值。
     */
    @Operation(summary = "更新工作区设置",
            description = "name / memoryIsolated（记忆隔离：本区会话不注入也不写入个人记忆）/ icon / color。传才改")
    @Permission("project:write")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody UpdateReq req) {
        try {
            Project updated = projectService.update(RequestUser.uid(), id,
                    req == null ? null : req.name(),
                    req == null ? null : req.memoryIsolated(),
                    req == null ? null : req.icon(),
                    req == null ? null : req.color());
            return ResultJson.ok(toDto(updated), "已保存");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record UpdateReq(String name, Boolean memoryIsolated, String icon, String color) {
    }

    @Operation(summary = "归档 / 恢复工作区", description = "body: {status: active|archived}。归档不动会话与产物，implicit 兜底容器不可归档")
    @Permission("project:write")
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
            description = "列出工作区目录里的<b>一层</b>内容（文件夹在前、文件按修改时间倒序）；dir 为空=源文件夹清单。"
                    + "返回 {dir, entries, total, truncated}。可预览的条目带 viewUrl（签名地址，默认 1 小时有效）。"
                    + "目录不存在=正常空态；宿主目录读不到（如沙盒在另一台机器）如实返回不可用")
    @Permission("project:read")
    @GetMapping("/{id}/files")
    public ResultJson files(@PathVariable("id") String id,
                            @RequestParam(value = "dir", required = false) String dir) {
        try {
            return ResultJson.ok(projectFileService.listWithMeta(RequestUser.uid(), id, dir));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    private static void writeJsonError(jakarta.servlet.http.HttpServletResponse resp, int status, String msg)
            throws java.io.IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        java.util.Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", status);
        body.put("msg", msg);
        body.put("data", null);
        resp.getWriter().write(com.alibaba.fastjson2.JSON.toJSONString(body));
    }

    @Operation(summary = "下载工作区文件",
            description = "按本机工作区内的虚拟路径（alias/相对路径）流式下载；越界路径一律 400，执行器离线 503")
    @Permission("project:read")
    @GetMapping("/{id}/file")
    public void download(@PathVariable("id") String id,
                         @RequestParam("path") String path,
                         jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        ProjectFileService.Download d;
        try {
            d = projectFileService.download(RequestUser.uid(), id, path);
        } catch (IllegalArgumentException e) {
            writeJsonError(resp, 400, e.getMessage());
            return;
        } catch (IllegalStateException e) {
            writeJsonError(resp, 503, e.getMessage());
            return;
        }
        String encoded = java.net.URLEncoder.encode(d.name(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        resp.setStatus(200);
        resp.setContentType("application/octet-stream");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        if (d.size() <= Integer.MAX_VALUE) resp.setContentLengthLong(d.size());
        d.writer().writeTo(resp.getOutputStream());
        resp.getOutputStream().flush();
    }

    @Operation(summary = "读工作区文件的文本原文",
            description = "预览台里「读字」那一档用（Markdown / 代码 / 分隔符表）：按 uid + 工作区 + 相对路径逐条校归属，"
                    + "非文本类型与越界路径回可读错误；超过上限只回前一段并带 truncated 标记，不假装是全文")
    @Permission("project:read")
    @GetMapping("/{id}/file/content")
    public ResultJson fileContent(@PathVariable("id") String id,
                                  @RequestParam("path") String path) {
        try {
            return ResultJson.ok(projectFileService.readText(RequestUser.uid(), id, path));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "新建本机工作区",
            description = "body: {name, executorId, roots:[{path, alias?}]}。"
                    + "工作区是用户电脑上的一个或多个真实目录：roots 至少一项，第一项为主目录（cwd 与相对路径基准），"
                    + "全部目录必须在同一台执行器上。服务器只记路径映射，不保存文件副本。")
    @Permission("project:write")
    @PostMapping
    public ResultJson create(@RequestBody CreateReq req) {
        try {
            String uid = RequestUser.uid();
            String executorId = req == null || req.executorId() == null ? "" : req.executorId().trim();
            if (executorId.isEmpty()) {
                return ResultJson.error(400, "请先选择一台在线的本机执行器，再选文件夹");
            }
            if (!executorService.belongsTo(uid, executorId)) {
                return ResultJson.error(403, "这台执行器不属于你，请先在这台电脑上授权");
            }
            List<com.wenqu.ai.service.ProjectService.RootSpec> roots = new ArrayList<>();
            if (req.roots() != null) {
                for (RootReq r : req.roots()) {
                    if (r != null) roots.add(new com.wenqu.ai.service.ProjectService.RootSpec(r.path(), r.alias()));
                }
            }
            Project p = projectService.create(uid, req.name(), executorId, roots);
            return ResultJson.ok(toDto(p), "已创建");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record CreateReq(String name, String executorId, List<RootReq> roots) {
    }

    public record RootReq(String path, String alias) {
    }

    @Operation(summary = "在电脑上打开工作区文件夹",
            description = "让那台电脑用系统文件管理器打开主源文件夹（Finder / Explorer / 文件管理器）。"
                    + "可选 query 参数 path（虚拟路径「别名/相对路径」，裸别名=源文件夹本身）指定打开哪个子目录，"
                    + "缺省＝主目录。执行器离线 503；没有绑定目录/路径越界 400")
    @Permission("project:read")
    @PostMapping("/{id}/open")
    public ResultJson openFolder(@PathVariable("id") String id,
                                 @RequestParam(value = "path", required = false) String path) {
        try {
            projectFileService.openInOS(RequestUser.uid(), id, path);
            return ResultJson.ok(null, "已在电脑上打开文件夹");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    @Operation(summary = "绑定本机目录（替换式）",
            description = "body: {executorId, roots:[{path, alias?}]}。"
                    + "这一批目录就是工作区的全部源文件夹，第一项为主目录；只改服务器记的路径映射，不动用户硬盘上的文件。"
                    + "executorId 必须属于当前用户。想解绑目录请直接删除工作区（文件会留在电脑上）。")
    @Permission("project:write")
    @PutMapping("/{id}/executor")
    public ResultJson bindExecutor(@PathVariable("id") String id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        try {
            String uid = RequestUser.uid();
            Object ex = body == null ? null : body.get("executorId");
            String executorId = ex == null ? "" : String.valueOf(ex).trim();
            if (executorId.isEmpty()) {
                return ResultJson.error(400, "工作区必须绑定一台本机执行器");
            }
            if (!executorService.belongsTo(uid, executorId)) {
                return ResultJson.error(403, "这台执行器不属于你，请先在这台电脑上授权");
            }
            List<com.wenqu.ai.service.ProjectService.RootSpec> roots = new ArrayList<>();
            Object rawRoots = body == null ? null : body.get("roots");
            if (rawRoots instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object path = m.get("path");
                        Object alias = m.get("alias");
                        roots.add(new com.wenqu.ai.service.ProjectService.RootSpec(
                                path == null ? null : String.valueOf(path),
                                alias == null ? null : String.valueOf(alias)));
                    }
                }
            }
            return ResultJson.ok(toDto(projectService.bindRoots(uid, id, executorId, roots)), "已保存");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    /**
     * 删除工作区。语义：<b>只摘归属，不删东西</b>——其下会话与产物的 {@code project_id} 置 NULL
     * （成果归人这条设计意图不许被级联删除破坏），工作区行逻辑删除。兜底容器不可删。
     * <p>本机目录里的文件一个都不动：删工作区只摘服务器上的归属。
     */
    @Operation(summary = "删除工作区",
            description = "删除后其下会话与产物仍然保留（只摘掉工作区归属）；本机目录里的文件一个都不动。"
                    + "implicit 兜底容器不可删。")
    @Permission("project:delete")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        try {
            projectService.delete(RequestUser.uid(), id);
            return ResultJson.ok(null,
                    "已删除（其下会话与产物已保留；本机文件留在你的电脑上）");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }
}
