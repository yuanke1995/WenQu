package com.wenqu.ai.startup;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayBaselineTest {

    @Test
    void baselineIsPackagedOnClasspath() throws Exception {
        URL url = getClass().getClassLoader().getResource("db/migration/V1__baseline.sql");
        assertNotNull(url);
        String head = new String(url.openStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(head.contains("Flyway V1"));
        assertTrue(head.contains("CREATE TABLE IF NOT EXISTS"));
    }
}
