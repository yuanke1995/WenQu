package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 部门管理：列表供「共享范围」选择器使用，增删改供「成员管理」页使用。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/department")
@RequiredArgsConstructor
@Tag(name = "部门管理", description = "部门增删改查")
public class DepartmentController {

    private final OrgService orgService;

    @Operation(summary = "部门列表", description = "返回全部部门（按名称升序）")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(orgService.listDepartments());
    }

    @Operation(summary = "新建部门", description = "{\"name\": \"研发部\", \"description\": \"可选\"}")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, String> body) {
        return ResultJson.ok(orgService.createDepartment(body.get("name"), body.get("description")), "已创建");
    }

    @Operation(summary = "修改部门", description = "{\"name\": \"研发部\", \"description\": \"可选\"}")
    @PutMapping("/{id}")
    public ResultJson update(
            @Parameter(description = "部门 ID") @PathVariable("id") String id,
            @RequestBody Map<String, String> body) {
        orgService.updateDepartment(id, body.get("name"), body.get("description"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除部门", description = "部门下仍有用户时拒绝删除")
    @DeleteMapping("/{id}")
    public ResultJson delete(@Parameter(description = "部门 ID") @PathVariable("id") String id) {
        orgService.deleteDepartment(id);
        return ResultJson.ok("已删除");
    }
}
