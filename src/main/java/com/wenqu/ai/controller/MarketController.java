package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.MarketService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 市场（v1 技能市场）：发现 → 一键安装；自己的技能可上架。
 *
 * <h3>归属边界（与技能/MCP 同口径）</h3>
 * 所有操作取登录态 {@link RequestUser#uid()}：浏览对全员开放（上架中的物品），
 * 上架/管理只作用于自己发布的条目（管理员可代管）；安装把快照复制进<b>自己的</b>技能目录。
 * 端点在 {@code SecurityConfig} 个人资产白名单里（登录即可用），资源级判定在 {@link MarketService} 做。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/market")
@RequiredArgsConstructor
@Tag(name = "市场（Marketplace）", description = "技能市场 v1：上架 → 发现 → 一键安装（安装即复制，装完是自己的独立副本）")
public class MarketController {

    private final MarketService market;

    // ==================== 读 ====================

    @Operation(summary = "市场列表",
            description = "上架中的物品 + 我上架的（含已下架）；每条带 installed/hasUpdate/mine 徽标。"
                    + "counts 给页签打标：byType 按技能/应用模板/工作流各一份、mine 跨类型，"
                    + "与列表同关键词标签条件、不受分页影响。"
                    + "sort=update_time|install_count；page 从 1 起，pageSize 默认 24、上限 100")
    @Permission("market:read")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "type", required = false) String type,
                           @RequestParam(value = "keyword", required = false) String keyword,
                           @RequestParam(value = "tag", required = false) String tag,
                           @Parameter(description = "true=只看我上架的（管理视图）")
                           @RequestParam(value = "mine", required = false, defaultValue = "false") boolean mine,
                           @Parameter(description = "update_time=最新（默认）、install_count=最多安装")
                           @RequestParam(value = "sort", required = false) String sort,
                           @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                           @RequestParam(value = "pageSize", required = false, defaultValue = "24") int pageSize) {
        String uid = uid();
        List<Map<String, Object>> items = market.list(uid, type, keyword, tag, mine, sort, page, pageSize);
        return ResultJson.ok(Map.of(
                "items", items,
                "total", market.total(uid, type, keyword, tag, mine),
                "page", Math.max(1, page),
                "pageSize", pageSize <= 0 ? 24 : Math.min(pageSize, 100),
                "counts", market.counts(uid, keyword, tag)));
    }

    @Operation(summary = "物品详情", description = "元信息 + 详细介绍 + SKILL.md 正文预览（装前可审阅模型将读到的原文）")
    @Permission("market:read")
    @GetMapping("/{id}")
    public ResultJson detail(@PathVariable("id") String id) {
        return ResultJson.ok(market.detail(uid(), id));
    }

    @Operation(summary = "我安装的", description = "安装记录 join 现存条目；按最近安装时间倒序；MCP 只展示「已在 MCP」，不参与更新")
    @Permission("market:read")
    @GetMapping("/installed")
    public ResultJson installed(@RequestParam(value = "type", required = false) String type,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                @RequestParam(value = "pageSize", required = false, defaultValue = "24") int pageSize) {
        String uid = uid();
        Map<String, Object> out = new java.util.LinkedHashMap<>(market.installed(uid, type, keyword, page, pageSize));
        // 已装视图复用页签角标与汇总条：counts 走全量（不带关键词），避免筛选后角标抖动
        out.put("counts", market.counts(uid, null, null));
        return ResultJson.ok(out);
    }

    @Operation(summary = "可更新清单", description = "已装且内容指纹变化、条目仍上架中；MCP 与已下架条目不参与。侧栏红点与批量更新共用")
    @Permission("market:read")
    @GetMapping("/updates")
    public ResultJson updates() {
        return ResultJson.ok(market.updates(uid()));
    }

    @Operation(summary = "版本历史", description = "返回全部历史（最多 20 条）+ 已装版本之后的新说明（newItems）；按内容指纹切区间，不看版本号")
    @Permission("market:read")
    @GetMapping("/{id}/versions")
    public ResultJson versions(@PathVariable("id") String id) {
        return ResultJson.ok(market.versions(uid(), id));
    }

    @Operation(summary = "编辑展示信息", description = "body: {summary, description?, tags?}；只改卡片文案，不动内容、版本与指纹，不产生新版本")
    @Permission("market:publish")
    @PutMapping("/{id}/meta")
    public ResultJson updateMeta(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(market.updateMeta(uid(), id,
                text(body.get("summary")), text(body.get("description")), text(body.get("tags"))), "已保存");
    }

    @Operation(summary = "我的市场数据", description = "近 N 天安装趋势 + 最近安装人（仅本人上架内容；days 默认 30，范围 7-90）")
    @Permission("market:read")
    @GetMapping("/mine/stats")
    public ResultJson myStats(@RequestParam(value = "days", required = false, defaultValue = "30") int days) {
        return ResultJson.ok(market.myStats(uid(), days));
    }

    @Operation(summary = "下载物品包", description = "快照包（skill=zip、agent/workflow=JSON；装前留档/离线分发）；可见即可下")
    @Permission("market:read")
    @GetMapping("/{id}/download")
    public void download(@PathVariable("id") String id, HttpServletResponse response) throws IOException {
        com.wenqu.ai.model.MarketItem row = market.requireItem(id);
        boolean isSkill = com.wenqu.ai.model.MarketItem.TYPE_SKILL.equals(row.getItemType());
        Path file = market.payloadZip(uid(), id);
        String filename = URLEncoder.encode(row.getName() + (isSkill ? ".zip" : ".json"),
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(isSkill ? "application/zip" : "application/json");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        try (var in = java.nio.file.Files.newInputStream(file)) {
            in.transferTo(response.getOutputStream());
        }
    }

    // ==================== 写 ====================

    @Operation(summary = "上架",
            description = "body: {type: skill|agent|workflow|mcp, ref, summary?, description?, tags?, version?}——"
                    + "ref：skill=技能名（仅「我的技能」）、agent=智能体 id（须本人可管理）、workflow=工作流 id（须本人可管理）。"
                    + "type=mcp 为管理员专用目录上架（body 直接带 name/url/mcpType/requiresAuth，无 ref，条目挂平台官方名下）。"
                    + "同一物品重复上架=更新同一条（快照覆盖）；「有更新」按内容指纹判定，与版本号无关")
    @Permission("market:publish")
    @PostMapping("/publish")
    public ResultJson publish(@RequestBody Map<String, Object> body) {
        String type = text(body.get("type"));
        if ("mcp".equalsIgnoreCase(type == null ? "" : type.trim())) {
            return ResultJson.ok(market.publishMcp(uid(), body), "已上架");
        }
        Map<String, Object> item = market.publish(uid(),
                type, text(body.get("ref")),
                text(body.get("summary")), text(body.get("description")),
                text(body.get("tags")), text(body.get("version")), text(body.get("releaseNote")));
        return ResultJson.ok(item, "已上架");
    }

    @Operation(summary = "一键安装",
            description = "body: {overwrite?}。skill=复制进本人技能目录（同名时按 overwrite 整包替换）；"
                    + "agent/workflow=每次安装都新建一份副本（fork），引用按安装人权限裁剪，warnings 逐项说明")
    @Permission("market:install")
    @PostMapping("/{id}/install")
    public ResultJson install(@PathVariable("id") String id,
                              @RequestBody(required = false) Map<String, Object> body) {
        boolean overwrite = body != null && flag(body.get("overwrite"));
        return ResultJson.ok(market.install(uid(), id, overwrite), "已安装");
    }

    @Operation(summary = "上架/下架我的物品", description = "body: {online: true|false}；下架后列表不可见、安装被拒，已装副本不受影响")
    @Permission("market:publish")
    @PutMapping("/{id}/status")
    public ResultJson status(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        boolean online = flag(body.get("online"));
        return ResultJson.ok(market.setStatus(uid(), id, online), online ? "已上架" : "已下架");
    }

    @Operation(summary = "删除我的物品", description = "连快照与安装记录一起删；已装进别人目录的技能副本动不了，也不该动")
    @Permission("market:publish")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        market.delete(uid(), id);
        return ResultJson.ok(Map.of("id", id), "已删除");
    }

    // ==================== 内部 ====================

    /** 写操作不接受 SYSTEM（与 SkillController 同口径：不能往 SYSTEM 名下装东西）。 */
    private static String uid() {
        String uid = RequestUser.uid();
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) {
            throw new BizException(401, "请先登录后再使用市场");
        }
        return uid;
    }

    private static String text(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean flag(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }
}
