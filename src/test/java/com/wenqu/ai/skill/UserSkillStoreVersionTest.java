package com.wenqu.ai.skill;

import com.wenqu.ai.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 技能版本快照 / 回滚 / 分类 frontmatter。 */
class UserSkillStoreVersionTest {

    @TempDir
    Path tmp;

    private UserSkillStore store;

    @BeforeEach
    void setUp() {
        store = new UserSkillStore(tmp.toString(), Mockito.mock(AuditService.class));
    }

    @Test
    @DisplayName("编辑留快照，回滚写回旧版且当前内容先留新快照")
    void updateSnapshotAndRollback() {
        store.create("u1", "demo", "演示技能", "# 做法\n第一版内容", false);
        String v1 = store.readContent("u1", "demo");

        store.update("u1", "demo", "演示技能", "# 做法\n第二版内容", null);
        String v2 = store.readContent("u1", "demo");
        assertThat(v2).contains("第二版内容");

        List<Map<String, Object>> versions = store.listVersions("u1", "demo");
        assertThat(versions).hasSize(1);
        long ts = ((Number) versions.get(0).get("ts")).longValue();
        assertThat(versions.get(0).get("description")).isEqualTo("演示技能");

        store.rollback("u1", "demo", ts);
        assertThat(store.readContent("u1", "demo")).isEqualTo(v1);
        // 回滚前当前版（v2）也留了快照：现在应有两份
        assertThat(store.listVersions("u1", "demo")).hasSize(2);
    }

    @Test
    @DisplayName("覆盖安装同样留旧版快照")
    void overwriteInstallSnapshots() {
        store.create("u1", "demo", "旧描述", "旧内容", false);
        store.create("u1", "demo", "新描述", "新内容", true);
        List<Map<String, Object>> versions = store.listVersions("u1", "demo");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("description")).isEqualTo("旧描述");
    }

    @Test
    @DisplayName("category 落 frontmatter 并随元信息读出；编辑保留未显式覆盖的分类")
    void categoryRoundTrip() {
        store.create("u1", "form-demo", "描述", "内容", false, java.util.Set.of(), "表单表格");
        assertThat(store.find("u1", "form-demo").category()).isEqualTo("表单表格");
        assertThat(store.readContent("u1", "form-demo")).contains("category: 表单表格");

        // 编辑不传 category → 沿用内容 frontmatter 里那份
        store.update("u1", "form-demo", "描述2", "内容2", null);
        assertThat(store.find("u1", "form-demo").category()).isEqualTo("表单表格");

        // 显式覆盖
        store.update("u1", "form-demo", "描述2", "内容2", "代码审查");
        assertThat(store.find("u1", "form-demo").category()).isEqualTo("代码审查");
    }

    @Test
    @DisplayName("删除技能连带清掉版本快照目录")
    void deleteCleansVersions() {
        store.create("u1", "demo", "描述", "旧内容", false);
        store.update("u1", "demo", "描述", "新内容", null);
        assertThat(store.listVersions("u1", "demo")).hasSize(1);
        store.delete("u1", "demo");
        assertThat(store.find("u1", "demo")).isNull();
        // 技能已删：版本查询按「技能不存在」拒绝，快照目录本身已随删除清掉
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.listVersions("u1", "demo"))
                .hasMessageContaining("技能不存在");
        assertThat(java.nio.file.Files.notExists(
                store.root().resolve("u1").resolve(".versions").resolve("demo"))).isTrue();
    }

    @Test
    @DisplayName("版本快照目录不出现在技能清单与文件列表里")
    void versionsDirInvisible() {
        store.create("u1", "demo", "描述", "旧内容", false);
        store.update("u1", "demo", "描述", "新内容", null);
        assertThat(store.list("u1")).hasSize(1);
        UserSkillStore.SkillDetail d = store.detail("u1", "demo");
        assertThat(d.files()).noneSatisfy(f -> assertThat(f.path()).contains(".versions"));
    }
}
