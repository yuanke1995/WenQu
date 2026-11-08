package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wenqu.ai.mapper.ProjectItemMapper;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectItem;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区事项判定核：标题校验、状态白名单、模板复制回到 open。
 * 纯单测，Mapper 用 Mockito 假件，不起 Spring 上下文。
 */
class ProjectItemServiceTest {

    private ProjectItemMapper mapper;
    private ProjectService projectService;
    private ProjectItemService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProjectItem.class);
        mapper = mock(ProjectItemMapper.class);
        projectService = mock(ProjectService.class);
        when(projectService.ownedOrNull("u", "p")).thenReturn(new Project());
        service = new ProjectItemService(mapper, projectService);
    }

    @Test
    @DisplayName("标题为空/过长直接拒绝，不落库")
    void rejectsBadTitle() {
        assertThatThrownBy(() -> service.create("u", "p", "  ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("u", "p", "x".repeat(201), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, times(0)).insert(any(ProjectItem.class));
    }

    @Test
    @DisplayName("状态只接受 open/doing/done，非法值不静默丢弃")
    void rejectsUnknownStatus() {
        ProjectItem item = new ProjectItem();
        item.setId("i1");
        item.setUid("u");
        item.setProjectId("p");
        when(mapper.selectById("i1")).thenReturn(item);

        assertThatThrownBy(() -> service.update("u", "p", "i1", null, null, "finished", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("模板复制：事项回到 open、不继承会话关联")
    void copyItemsResetsToOpen() {
        ProjectItem src = new ProjectItem();
        src.setId("old");
        src.setUid("u");
        src.setProjectId("p");
        src.setTitle("把接口文档补完");
        src.setNote("第二章");
        src.setStatus(ProjectItem.STATUS_DONE);
        src.setSessionId("s-old");
        src.setSortNo(2);
        when(mapper.selectList(any())).thenReturn(List.of(src));

        int n = service.copyItems("u", "p", "p-new");

        assertThat(n).isEqualTo(1);
        ArgumentCaptor<ProjectItem> captor = ArgumentCaptor.forClass(ProjectItem.class);
        verify(mapper).insert(captor.capture());
        ProjectItem copy = captor.getValue();
        assertThat(copy.getProjectId()).isEqualTo("p-new");
        assertThat(copy.getStatus()).isEqualTo(ProjectItem.STATUS_OPEN);
        assertThat(copy.getSessionId()).isNull();
        assertThat(copy.getTitle()).isEqualTo("把接口文档补完");
    }
}
