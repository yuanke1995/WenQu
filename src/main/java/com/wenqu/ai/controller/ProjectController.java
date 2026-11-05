package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.service.ProjectFileService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.ProjectTodoService;
import com.wenqu.ai.service.ProjectTurnGuard;
import com.wenqu.ai.service.ScheduledJobService;
import com.wenqu.ai.service.TurnLease;
import com.wenqu.ai.service.WorkflowService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 工作区容器接口：把「一个工作目录 + 若干次会话 + 若干份产物」收在一个可持续的根下。
 *
 * <p>能力面：列表 / 详情 / 设置（名称·约定·默认助手）/ 归档恢复 / 新建 / 删除（可连目录）、
 * 工作区目录的分层浏览与文件管理（上传 / 新建文件夹 / 改名移动 / 删除 / 批量）/ 下载 / 整区导出、
 * 本工作区的工作流与运行记录。会话与产物的移动归属在
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
    private final WorkflowService workflowService;
    private final ScheduledJobService scheduledJobService;
    private final ProjectTodoService projectTodoService;
    private final ProjectTurnGuard projectTurnGuard;
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
        // 工作区约定与默认助手：工作区页编辑、每轮注入/解析用；列表里也返回，省一次请求
        m.put("instructions", p.getInstructions() == null ? "" : p.getInstructions());
        m.put("defaultAgentId", p.getDefaultAgentId() == null ? "" : p.getDefaultAgentId());
        // 默认检索域（绑定知识库）与两个开关：工作区页设置弹层编辑，检索收敛/沙盒挂载/记忆闸用
        m.put("kbIds", p.getKbIds() == null || p.getKbIds().isBlank()
                ? List.of() : new java.util.ArrayList<>(java.util.Arrays.asList(p.getKbIds().split(","))));
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

    @Operation(summary = "工作区详情", description = "名称 / 工作区约定 / 默认助手；不存在或不属于你返回可读错误")
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
     * 工作区轮次占用状态：共用目录的工作区当前有没有轮次在跑、是谁在跑（会话标题 / 定时任务名 /
     * 工作流子智能体名）与跑了多久。工作区页的占用条轮询它；被拒消息的「指名道姓」与这里同源
     * （都出自租约的 {@code describe()}），两处口径不会漂移。
     */
    @Operation(summary = "工作区轮次占用状态",
            description = "busy=true 时返回占用者（kind: user/scheduled/workflow，name，sessionId，startedAt，elapsedMs）；"
                    + "本机工作区同一批目录只允许一轮在跑")
    @Permission("project:read")
    @GetMapping("/{id}/turn-guard")
    public ResultJson turnGuard(@PathVariable("id") String id) {
        try {
            Project p = projectService.detail(RequestUser.uid(), id);
            TurnLease lease = projectTurnGuard.current(p.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("busy", lease != null);
            if (lease != null) {
                m.put("kind", lease.kind());
                m.put("name", lease.name());
                m.put("sessionId", lease.sessionId());
                m.put("startedAt", lease.startedAt());
                m.put("elapsedMs", System.currentTimeMillis() - lease.startedAt());
                m.put("describe", lease.describe());
            }
            m.put("local", projectService.isLocal(p));
            return ResultJson.ok(m);
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    /**
     * 更新工作区设置。各字段都是「传才改」：不传保持原值，传空串/空列表表示清空。
     * <p>工作区约定与默认助手是「真实工作区」与「分类标签」的分界，故这里单独成端点而不是混在重命名里。
     * kbIds 是默认检索域（绑定的知识库）。
     */
    @Operation(summary = "更新工作区设置",
            description = "name / instructions（工作区约定，对该工作区所有会话生效，变更自动留痕）/ defaultAgentId（工作区默认助手）/ "
                    + "kbIds（绑定知识库清单=默认检索域，传空数组清空）/ "
                    + "memoryIsolated（记忆隔离：本区会话不注入也不写入个人记忆）。传才改，空=清空")
    @Permission("project:write")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody UpdateReq req) {
        try {
            Project updated = projectService.update(RequestUser.uid(), id,
                    req == null ? null : req.name(),
                    req == null ? null : req.instructions(),
                    req == null ? null : req.defaultAgentId(),
                    req == null ? null : req.kbIds(),
                    req == null ? null : req.memoryIsolated(),
                    req == null ? null : req.icon(),
                    req == null ? null : req.color());
            return ResultJson.ok(toDto(updated), "已保存");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record UpdateReq(String name, String instructions, String defaultAgentId,
                            List<String> kbIds, Boolean memoryIsolated,
                            String icon, String color) {
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
            description = "列出工作区目录里的<b>一层</b>内容（文件夹在前、文件按修改时间倒序）；dir 为空=根目录。"
                    + "返回 {dir, entries, total, truncated, files, bytes, capped, dirs, names}——"
                    + "total 是本层条目数（truncated=true 表示只返回了最近若干条），"
                    + "根层请求（dir 为空）另带 files/bytes（整个目录的文件数与合计体积，capped=true 表示统计到 5000 个就停）、"
                    + "dirs（全目录的文件夹清单，「移动到…」用）、names（全目录的文件名，产物去重用）；"
                    + "子目录请求只返回本层数据，这四项为空，不重复扫整棵树。"
                    + "可预览的条目另带 viewUrl：一条签名地址（默认 1 小时有效），前端直接开成浏览器标签页。"
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

    @Operation(summary = "上传文件到工作区",
            description = "multipart：file 可多份（一次最多 50 个）、dir=落进哪个文件夹（空=根目录）、"
                    + "overwrite=true 时同名覆盖（默认拒绝并报出那个名字）。"
                    + "单文件上限见系统设置「工作区上传单文件上限」；归档中的工作区不收文件")
    @Permission("project:write")
    @PostMapping("/{id}/files")
    public ResultJson uploadFiles(@PathVariable("id") String id,
                                  @RequestParam("file") List<MultipartFile> files,
                                  @RequestParam(value = "dir", required = false) String dir,
                                  @RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite) {
        try {
            List<Map<String, Object>> saved =
                    projectFileService.upload(RequestUser.uid(), id, dir, files, overwrite);
            String msg = saved.size() == 1
                    ? "已上传「" + saved.get(0).get("name") + "」"
                    : "已上传 " + saved.size() + " 个文件";
            return ResultJson.ok(saved, msg);
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    @Operation(summary = "在工作区里新建文件夹", description = "body: {path: 相对路径}，父目录必须已存在；名称不能以点开头（这里不显示隐藏文件）")
    @Permission("project:write")
    @PostMapping("/{id}/dir")
    public ResultJson createDir(@PathVariable("id") String id, @RequestBody DirReq req) {
        try {
            String path = projectFileService.createDir(RequestUser.uid(), id, req == null ? null : req.path());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            return ResultJson.ok(out, "已新建文件夹");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    public record DirReq(String path) {
    }

    @Operation(summary = "重命名或移动工作条目",
            description = "body: {from, to}（都是相对工作区的 POSIX 路径）。文件和文件夹都走这一条；"
                    + "目标父目录必须已存在、位置上不能有同名条目，也不能把文件夹挪进它自己里面。"
                    + "工作区里有任务正在跑时拒绝（那一轮以为文件还在原位）")
    @Permission("project:write")
    @PutMapping("/{id}/file")
    public ResultJson moveFile(@PathVariable("id") String id, @RequestBody MoveReq req) {
        try {
            String to = projectFileService.move(RequestUser.uid(), id,
                    req == null ? null : req.from(), req == null ? null : req.to());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", to);
            return ResultJson.ok(out, "已保存新名字");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    public record MoveReq(String from, String to) {
    }

    @Operation(summary = "批量整理工作区条目",
            description = "body: {paths:[...], op: move|delete, dir?}。op 必须写——「移动」和「删除」两个分支一个是整理、"
                    + "一个是不可恢复地删文件，不能靠某个字段留没留下来猜意图。op=move 时 dir 是目标文件夹（空串＝根目录）；"
                    + "删除文件夹会连里面的内容一起删；已经不在了的条目算成功。一次最多 500 项")
    @Permission("project:write")
    @PostMapping("/{id}/files/batch")
    public ResultJson batch(@PathVariable("id") String id, @RequestBody BatchReq req) {
        try {
            String uid = RequestUser.uid();
            List<String> paths = req == null ? null : req.paths();
            String op = req == null || req.op() == null ? "" : req.op().trim().toLowerCase();
            Map<String, Object> out = new LinkedHashMap<>();
            if ("move".equals(op)) {
                int moved = projectFileService.batchMove(uid, id, paths, req.dir() == null ? "" : req.dir());
                out.put("moved", moved);
                return ResultJson.ok(out, "已移动 " + moved + " 项");
            }
            if ("delete".equals(op)) {
                int deleted = projectFileService.deleteEntries(uid, id, paths);
                out.put("deleted", deleted);
                return ResultJson.ok(out, "已删除 " + deleted + " 项");
            }
            return ResultJson.error(400, "op 只能是 move 或 delete");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return ResultJson.error(503, e.getMessage());
        }
    }

    public record BatchReq(List<String> paths, String op, String dir) {
    }

    @Operation(summary = "打包导出工作区目录",
            description = "把本机工作区的全部源文件夹打成 zip（流式；隐藏文件与依赖目录跳过）。"
                    + "文件数 >2000 或总量 >100MB 在响应头发出前明确拒绝，不会给出坏掉的包")
    @Permission("project:export")
    @GetMapping("/{id}/export")
    public void export(@PathVariable("id") String id, jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        String uid = RequestUser.uid();
        ProjectFileService.ExportPlan plan;
        try {
            plan = projectFileService.prepareExport(uid, id);
        } catch (IllegalArgumentException e) {
            writeJsonError(resp, 400, e.getMessage());
            return;
        } catch (IllegalStateException e) {
            writeJsonError(resp, 503, e.getMessage());
            return;
        }
        String encoded = java.net.URLEncoder.encode(plan.name(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        resp.setStatus(200);
        resp.setContentType("application/zip");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        try {
            projectFileService.writeExport(uid, id, plan, resp.getOutputStream());
        } catch (IllegalStateException e) {
            // 已经开流：只能中断；前端会看到下载失败，原因写进服务端日志
            log.warn("[PROJECT] 工作区导出中途失败 project={}: {}", id, e.getMessage());
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

    @Operation(summary = "工作区的工作流", description = "本工作区下本人的工作流（名称/状态/更新时间）；触发仍走 /workflow/{id}/run")
    @Permission("project:read")
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

    /**
     * 工作区运行记录（「运行」tab）：定时任务执行记录与工作流运行记录合流，按开始时刻倒序。
     * <p>两类来源各取 limit 条再合流截断——单个来源的 limit 会把另一方挤掉，合流才是
     * 「这个工作区最近发生了什么」的完整视图。定时任务的行带结果会话链接（answer 摘录
     * 在结果会话里），工作流的行带去运行历史的深链。
     */
    @Operation(summary = "工作区运行记录",
            description = "合流定时任务执行记录与工作流运行记录（按开始时刻倒序，默认 50 条）。"
                    + "kind=scheduled 的行带 sessionId（结果会话）；kind=workflow 的行带 workflowId 与 run id（运行历史深链）")
    @Permission("project:read")
    @GetMapping("/{id}/runs")
    public ResultJson runs(@PathVariable("id") String id,
                           @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        String uid = RequestUser.uid();
        projectService.detail(uid, id);   // 归属校验（不存在/他人/已删一律抛）
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(scheduledJobService.runsByProject(uid, id, n));
        out.addAll(workflowService.runsByProject(id, n));
        // 合流排序：startedAt 为 null 的行排在最后（LocalDateTime.toString 同位宽可字典序比）
        out.sort((a, b) -> {
            String x = a.get("startedAt") == null ? "" : String.valueOf(a.get("startedAt"));
            String y = b.get("startedAt") == null ? "" : String.valueOf(b.get("startedAt"));
            return y.compareTo(x);
        });
        return ResultJson.ok(out.size() > n ? out.subList(0, n) : out);
    }

    @Operation(summary = "新建本机工作区",
            description = "body: {name, executorId, roots:[{path, alias?}], fromProjectId?}。"
                    + "工作区是用户电脑上的一个或多个真实目录：roots 至少一项，第一项为主目录（cwd 与相对路径基准），"
                    + "全部目录必须在同一台执行器上。服务器只记路径映射，不保存文件副本。"
                    + "fromProjectId 可选：从另一个工作区复制约定/资料域/两个开关，目录不抄。")
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
            Project p = projectService.create(uid, req.name(), req.fromProjectId(), executorId, roots);
            return ResultJson.ok(toDto(p), "已创建");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record CreateReq(String name, String fromProjectId, String executorId, List<RootReq> roots) {
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

    @Operation(summary = "约定留痕",
            description = "本工作区约定被替换掉的旧版（按被替换时刻倒序，最多 20 条）；"
                    + "content=null 表示当时约定为空。恢复=把旧值存回约定（走更新接口，会再次留痕）")
    @Permission("project:read")
    @GetMapping("/{id}/instruction-history")
    public ResultJson instructionHistory(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(projectService.instructionHistory(RequestUser.uid(), id));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    // ==================== 工作区事项（这个场子要做的事：记下 → 去做 → 勾掉） ====================

    @Operation(summary = "工作区事项清单",
            description = "未完成在前（新添的在上），已完成按完成时刻倒序收尾。归属校验同其他工作区端点")
    @Permission("project:read")
    @GetMapping("/{id}/todos")
    public ResultJson todos(@PathVariable("id") String id) {
        try {
            return ResultJson.ok(projectTodoService.list(RequestUser.uid(), id));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "新增事项", description = "body: {title}（一句话，≤200 字）")
    @Permission("project:write")
    @PostMapping("/{id}/todos")
    public ResultJson createTodo(@PathVariable("id") String id, @RequestBody TodoReq req) {
        try {
            return ResultJson.ok(projectTodoService.create(RequestUser.uid(), id, req == null ? null : req.title()),
                    "已记下");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "更新事项", description = "body: {title?, done?}（传才改）。done=true 勾完成并记时刻，false 取消完成")
    @Permission("project:write")
    @PutMapping("/{id}/todos/{todoId}")
    public ResultJson updateTodo(@PathVariable("id") String id, @PathVariable("todoId") String todoId,
                                 @RequestBody TodoReq req) {
        try {
            return ResultJson.ok(projectTodoService.update(RequestUser.uid(), id, todoId,
                    req == null ? null : req.title(), req == null ? null : req.done()));
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    @Operation(summary = "删除事项", description = "逻辑删除；已完成与未完成都可删")
    @Permission("project:delete")
    @DeleteMapping("/{id}/todos/{todoId}")
    public ResultJson deleteTodo(@PathVariable("id") String id, @PathVariable("todoId") String todoId) {
        try {
            projectTodoService.delete(RequestUser.uid(), id, todoId);
            return ResultJson.ok(null, "已删除");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }

    public record TodoReq(String title, Boolean done) {
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
