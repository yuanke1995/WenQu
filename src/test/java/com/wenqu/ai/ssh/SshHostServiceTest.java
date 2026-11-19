package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.mapper.SshHostMapper;
import com.wenqu.ai.service.ConfigCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 凭据托管不变量：明文只加密落库、出参永不回密文/明文、编辑时留空不覆盖。
 */
class SshHostServiceTest {

    @SuppressWarnings("unchecked")
    private SshHostService service(SshHostMapper mapper, ConfigCryptoService crypto) {
        ObjectProvider<ExecutorRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new SshHostService(mapper, crypto, mock(SshConnectionManager.class), provider,
                mock(JdbcTemplate.class));
    }

    @Test
    void createEncryptsSecretsAndDtoNeverLeaksThem() {
        SshHostMapper mapper = mock(SshHostMapper.class);
        ConfigCryptoService crypto = mock(ConfigCryptoService.class);
        when(crypto.encrypt("p@ss")).thenReturn("JWE:cipher-password");
        SshHostService svc = service(mapper, crypto);

        SshHost h = svc.create("u1", new SshHostService.Input(
                "host", "10.0.0.1", 22, "ubuntu", "password", "p@ss", null, null, null));

        assertThat(h.getPasswordCipher()).isEqualTo("JWE:cipher-password");
        assertThat(h.getAuthType()).isEqualTo(SshHost.AUTH_PASSWORD);
        Map<String, Object> dto = svc.toDto(h);
        assertThat(dto.get("hasPassword")).isEqualTo(true);
        assertThat(dto.get("hasPrivateKey")).isEqualTo(false);
        // 出参里不能出现任何密文/明文
        assertThat(dto.values().toString()).doesNotContain("JWE:").doesNotContain("p@ss");
    }

    @Test
    void passwordRequiredForPasswordAuth() {
        SshHostService svc = service(mock(SshHostMapper.class), mock(ConfigCryptoService.class));
        assertThatThrownBy(() -> svc.create("u1", new SshHostService.Input(
                "host", "h", 22, "u", "password", "", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("密码");
    }

    @Test
    void editWithBlankSecretKeepsExistingCipher() {
        SshHostMapper mapper = mock(SshHostMapper.class);
        ConfigCryptoService crypto = mock(ConfigCryptoService.class);
        SshHost existing = new SshHost();
        existing.setId("ssh-1");
        existing.setUid("u1");
        existing.setName("old");
        existing.setHost("h");
        existing.setPort(22);
        existing.setUsername("u");
        existing.setAuthType(SshHost.AUTH_PASSWORD);
        existing.setPasswordCipher("JWE:keep");
        existing.setStatus(SshHost.STATUS_ACTIVE);
        when(mapper.selectById("ssh-1")).thenReturn(existing);
        SshHostService svc = service(mapper, crypto);

        SshHost updated = svc.update("u1", "ssh-1", new SshHostService.Input(
                "new", "h", 22, "u", "password", "", null, null, null));

        assertThat(updated.getName()).isEqualTo("new");
        assertThat(updated.getPasswordCipher()).isEqualTo("JWE:keep");
    }
}
