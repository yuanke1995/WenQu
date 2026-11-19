package com.wenqu.ai.ssh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 「本机 OpenSSH 别名」虚拟执行器 id 编解码。 */
class SshAliasIdsTest {

    @Test
    void roundTripsDeviceAndAlias() {
        String id = SshAliasIds.virtualId("ex-123", "wenqu");
        assertThat(id).startsWith("ssh-alias:ex-123:");
        SshAliasIds.Parsed p = SshAliasIds.parse(id);
        assertThat(p).isNotNull();
        assertThat(p.executorId()).isEqualTo("ex-123");
        assertThat(p.alias()).isEqualTo("wenqu");
    }

    @Test
    void rejectsForeignIds() {
        assertThat(SshAliasIds.parse("ssh:host-1")).isNull();
        assertThat(SshAliasIds.parse("ex-123")).isNull();
        assertThat(SshAliasIds.parse("ssh-alias:onlydevice")).isNull();
    }

    @Test
    void handlesAwkwardAliasCharacters() {
        String id = SshAliasIds.virtualId("ex-1", "a@b.c_d-e");
        assertThat(SshAliasIds.parse(id).alias()).isEqualTo("a@b.c_d-e");
    }
}
