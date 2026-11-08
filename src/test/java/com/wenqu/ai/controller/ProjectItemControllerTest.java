package com.wenqu.ai.controller;

import com.wenqu.ai.model.ProjectItem;
import com.wenqu.ai.service.ProjectFileService;
import com.wenqu.ai.service.ProjectItemService;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.util.RequestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作区事项接口契约：路由、字段名与状态码（standalone MockMvc，不起 Spring 上下文）。
 * 业务判定在 {@code ProjectItemServiceTest} 覆盖。
 */
class ProjectItemControllerTest {

    private MockMvc mvc;
    private ProjectItemService itemService;

    @BeforeEach
    void setUp() {
        RequestUser.set("u1", null, List.of("user"));
        ProjectService projectService = mock(ProjectService.class);
        ProjectFileService fileService = mock(ProjectFileService.class);
        itemService = mock(ProjectItemService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectController(
                projectService, fileService, itemService,
                mock(com.wenqu.ai.executor.ExecutorService.class),
                mock(com.wenqu.ai.executor.ExecutorRegistry.class))).build();
    }

    @AfterEach
    void cleanup() {
        RequestUser.clear();
    }

    private static ProjectItem item(String id, String status) {
        ProjectItem it = new ProjectItem();
        it.setId(id);
        it.setProjectId("p1");
        it.setTitle("把接口文档补完");
        it.setStatus(status);
        return it;
    }

    @Test
    @DisplayName("GET 事项清单：返回 projectId/title/status 字段")
    void listsItems() throws Exception {
        when(itemService.list("u1", "p1")).thenReturn(List.of(item("i1", "open")));
        mvc.perform(get("/api/ai/project/p1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("i1"))
                .andExpect(jsonPath("$.data[0].title").value("把接口文档补完"))
                .andExpect(jsonPath("$.data[0].status").value("open"));
    }

    @Test
    @DisplayName("POST 新增事项：标题透传，返回新行")
    void createsItem() throws Exception {
        when(itemService.create(eq("u1"), eq("p1"), eq("补测试"), any(), any()))
                .thenReturn(item("i2", "open"));
        mvc.perform(post("/api/ai/project/p1/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"补测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("i2"));
    }

    @Test
    @DisplayName("PUT 勾掉事项：状态透传")
    void updatesItemStatus() throws Exception {
        when(itemService.update("u1", "p1", "i1", null, null, "done", null))
                .thenReturn(item("i1", "done"));
        mvc.perform(put("/api/ai/project/p1/items/i1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("done"));
    }

    @Test
    @DisplayName("DELETE 事项：只删这一条")
    void deletesItem() throws Exception {
        mvc.perform(delete("/api/ai/project/p1/items/i1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(itemService).delete("u1", "p1", "i1");
    }
}
