package com.wenqu.ai.skill;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 同名技能按来源/归属精确解析，不用当前用户的同名版本顶替。 */
class WenquSkillRepositoryRefTest {

    @TempDir Path root;

    @Test
    @DisplayName("user:u1:review 只取 u1 的正文，u2 的同名技能不顶替")
    void userRefPicksOwner() throws Exception {
        Path builtin = root.resolve("builtin");
        Path users = root.resolve("users");
        writeSkill(builtin.resolve("review"), "builtin review");
        writeSkill(users.resolve("u1").resolve("review"), "u1 review");
        writeSkill(users.resolve("u2").resolve("review"), "u2 review");

        var store = new UserSkillStore(users.toString(), null);
        var repo = new WenquSkillRepository(builtin.toString(), mock(com.wenqu.ai.service.SandboxService.class), store);
        RuntimeContext ctx = RuntimeContext.builder().userId("u2").sessionId("s1").build();

        var mine = repo.resolveRef(ctx, null, "user:u2:review");
        assertThat(mine).isNotNull();
        assertThat(mine.skill().getSkillContent()).contains("u2 review");

        // 指定 u1 的技能时，即便当前用户有同名技能也不能顶替
        assertThat(repo.resolveRef(ctx, null, "user:u1:review")).isNull();
        // 内置按来源精确取，不被用户同名覆盖
        assertThat(repo.resolveRef(ctx, null, "builtin:review").skill().getSkillContent()).contains("builtin review");
        // 历史裸名仍按当前用户优先
        assertThat(repo.resolveRef(ctx, null, "review").skill().getSkillContent()).contains("u2 review");
        // 历史 uid/name 精确解析
        assertThat(repo.resolveRef(ctx, null, "u2/review").skill().getSkillContent()).contains("u2 review");
    }

    private static void writeSkill(Path dir, String body) throws Exception {
        Files.createDirectories(dir);
        String md = "---\nname: review\ndescription: test skill\n---\n\n" + body + "\n";
        Files.writeString(dir.resolve("SKILL.md"), md, StandardCharsets.UTF_8);
    }
}
