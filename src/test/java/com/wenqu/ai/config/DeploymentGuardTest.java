package com.wenqu.ai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentGuardTest {

    @Test
    void singleIsAllowed() {
        assertDoesNotThrow(() -> new DeploymentGuard("single", "standard").check());
        assertDoesNotThrow(() -> new DeploymentGuard(" SINGLE ", "ENTERPRISE").check());
        assertDoesNotThrow(() -> new DeploymentGuard("single", "").check());
        assertEquals("enterprise", new DeploymentGuard("single", "ENTERPRISE").profile());
    }

    @Test
    void multiFailsFast() {
        assertThrows(IllegalStateException.class, () -> new DeploymentGuard("multi", "standard").check());
    }

    @Test
    void blankModeIsNotSilentlyTreatedAsSingle() {
        assertThrows(IllegalStateException.class, () -> new DeploymentGuard("", "standard").check());
        assertThrows(IllegalStateException.class, () -> new DeploymentGuard(null, "standard").check());
    }

    @Test
    void unknownProfileFailsFast() {
        assertThrows(IllegalStateException.class, () -> new DeploymentGuard("single", "team").check());
    }
}
