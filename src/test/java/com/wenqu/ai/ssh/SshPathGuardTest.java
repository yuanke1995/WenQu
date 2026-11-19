package com.wenqu.ai.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路径围栏纯逻辑：折叠、边界、越界。远端 realpath 二次校验不在这层（见 SshRemoteExecutor）。
 */
class SshPathGuardTest {

    private static final List<SshPathGuard.Root> ROOTS = List.of(
            new SshPathGuard.Root("app", "/srv/app", true),
            new SshPathGuard.Root("data", "/data/shared", false));

    @Test
    void normalizesDoubleSlashDotAndDotDot() {
        assertThat(SshPathGuard.normalize("/srv//app/./a/../b")).isEqualTo("/srv/app/b");
        assertThat(SshPathGuard.normalize("/../etc")).isEqualTo("/etc");
        assertThat(SshPathGuard.normalize("/")).isEqualTo("/");
    }

    @Test
    void rejectsRelativeAndNull() {
        assertThatThrownBy(() -> SshPathGuard.normalize("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshPathGuard.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshPathGuard.normalize("/a/\u0000b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixBoundaryIsSegmentAware() {
        assertThat(SshPathGuard.isSameOrChild("/srv/app", "/srv/app")).isTrue();
        assertThat(SshPathGuard.isSameOrChild("/srv/app/sub", "/srv/app")).isTrue();
        assertThat(SshPathGuard.isSameOrChild("/srv/apple", "/srv/app")).isFalse();
        assertThat(SshPathGuard.isSameOrChild("/srv/application", "/srv/app")).isFalse();
    }

    @Test
    void checkAcceptsInsideRootsAndRejectsOutside() {
        assertThat(SshPathGuard.check("/srv/app/a/b.txt", ROOTS, false)).isEqualTo("/srv/app/a/b.txt");
        assertThat(SshPathGuard.check("/data/shared/x", ROOTS, false)).isEqualTo("/data/shared/x");
        assertThatThrownBy(() -> SshPathGuard.check("/etc/passwd", ROOTS, false))
                .isInstanceOf(SshPathGuard.PathOutsideException.class);
        // 词法折叠后越界同样被拒
        assertThatThrownBy(() -> SshPathGuard.check("/srv/app/../../etc/passwd", ROOTS, false))
                .isInstanceOf(SshPathGuard.PathOutsideException.class);
    }

    @Test
    void unrestrictedOnlyNormalizes() {
        assertThat(SshPathGuard.check("/etc/passwd", ROOTS, true)).isEqualTo("/etc/passwd");
    }

    @Test
    void relativeToProducesAliasPath() {
        assertThat(SshPathGuard.relativeTo("/srv/app/a/b.txt", ROOTS)).isEqualTo("app/a/b.txt");
        assertThat(SshPathGuard.relativeTo("/data/shared", ROOTS)).isEqualTo("data");
        assertThat(SshPathGuard.relativeTo("/opt/other", ROOTS)).isEqualTo("/opt/other");
    }
}
