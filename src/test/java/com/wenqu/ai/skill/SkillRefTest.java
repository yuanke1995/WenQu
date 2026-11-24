package com.wenqu.ai.skill;

import com.wenqu.ai.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 技能引用解析：规范格式、历史格式、非法输入。 */
class SkillRefTest {

    @Test
    @DisplayName("三类规范引用解析出来源/归属/裸名")
    void canonical() {
        SkillRef builtin = SkillRef.parse("builtin:dsh-doc");
        assertThat(builtin.source()).isEqualTo(WenquSkillRepository.SOURCE_BUILTIN);
        assertThat(builtin.name()).isEqualTo("dsh-doc");
        assertThat(builtin.filterName()).isEqualTo("dsh-doc");

        SkillRef user = SkillRef.parse("user:u:abc:my-review");
        assertThat(user.source()).isEqualTo(WenquSkillRepository.SOURCE_USER);
        assertThat(user.owner()).isEqualTo("u:abc");
        assertThat(user.name()).isEqualTo("my-review");
        assertThat(user.canonicalText()).isEqualTo("user:u:abc:my-review");

        SkillRef ws = SkillRef.parse("workspace:s-1:scratch");
        assertThat(ws.source()).isEqualTo(WenquSkillRepository.SOURCE_WORKSPACE);
        assertThat(ws.owner()).isEqualTo("s-1");
    }

    @Test
    @DisplayName("历史 uid/name 与裸名保持可用")
    void legacy() {
        SkillRef legacy = SkillRef.parse("u1/review");
        assertThat(legacy.source()).isEqualTo(WenquSkillRepository.SOURCE_USER);
        assertThat(legacy.owner()).isEqualTo("u1");
        assertThat(legacy.canonicalText()).isEqualTo("user:u1:review");

        SkillRef bare = SkillRef.parse("Review");
        assertThat(bare.source()).isNull();
        assertThat(bare.name()).isEqualTo("review");
        assertThat(bare.canonical()).isFalse();
    }

    @Test
    @DisplayName("非法引用 fail-loud")
    void invalid() {
        assertThatThrownBy(() -> SkillRef.parse("user:review")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SkillRef.parse("Bad Name")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> SkillRef.parse("")).isInstanceOf(BizException.class);
    }
}
