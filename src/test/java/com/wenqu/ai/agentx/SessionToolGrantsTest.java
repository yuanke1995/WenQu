package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionToolGrantsTest {

    private final SessionService sessions = mock(SessionService.class);
    private final SessionToolGrants grants = new SessionToolGrants(
            mock(StringRedisTemplate.class), sessions, mock(ConfigService.class));

    @Test
    @DisplayName("临时子会话没有 root 字段：按 parentSessionId 归到根，而不是把自己当根")
    void childWithoutRootUsesParent() {
        Session child = new Session();
        child.setId("child");
        child.setUserId("u1");
        when(sessions.sessionById("child")).thenReturn(child);
        when(sessions.sessionById("parent")).thenReturn(null);

        ChatCommand cmd = ChatCommand.builder()
                .sessionId("child").userId("u1").parentSessionId("parent").build();
        assertThat(grants.scopeKeyOf(cmd)).isEqualTo("u1|parent");
    }

    @Test
    @DisplayName("团队成员会话按 root_session_id / rootSessionId 归到团队根")
    void teamMemberUsesTeamRoot() {
        Session member = new Session();
        member.setId("member");
        member.setUserId("u1");
        member.setRootSessionId("root");
        when(sessions.sessionById("member")).thenReturn(member);

        ChatCommand fromCmd = ChatCommand.builder()
                .sessionId("member").userId("u1").teamMode(true).rootSessionId("root").build();
        assertThat(grants.scopeKeyOf(fromCmd)).isEqualTo("u1|root");

        ChatCommand fromRow = ChatCommand.builder()
                .sessionId("member").userId("u1").teamMode(true).build();
        assertThat(grants.scopeKeyOf(fromRow)).isEqualTo("u1|root");
    }

    @Test
    @DisplayName("审批行按会话行 root_session_id 找最顶层，不认执行侧临时会话")
    void approvalUsesRootRow() {
        Session member = new Session();
        member.setId("member");
        member.setUserId("u1");
        member.setRootSessionId("root");
        when(sessions.sessionById("member")).thenReturn(member);

        ToolApproval row = new ToolApproval();
        row.setSessionId("member");
        row.setUserId("u1");
        row.setParentSessionId("member");
        assertThat(grants.scopeKeyForApproval(row)).isEqualTo("u1|root");
    }

    @Test
    @DisplayName("根会话自身落在自己的作用域；空会话解析不抛")
    void rootFallsBackToSelf() {
        Session root = new Session();
        root.setId("root");
        root.setUserId("u1");
        when(sessions.sessionById("root")).thenReturn(root);

        ChatCommand cmd = ChatCommand.builder().sessionId("root").userId("u1").build();
        assertThat(grants.scopeKeyOf(cmd)).isEqualTo("u1|root");
        assertThat(grants.scopeKeyOf(null)).isNull();
    }
}