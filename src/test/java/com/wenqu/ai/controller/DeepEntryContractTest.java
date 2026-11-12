package com.wenqu.ai.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 深度执行对外只有一个入口：/deep*；计划裁决收进 /deep/plan-approval*，旧 /goal* 与裸 /plan-approval* 不再暴露。 */
class DeepEntryContractTest {

    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\(\\s*\"([^\"]+)\"");
    private static final Path CONTROLLERS = Path.of("src", "main", "java", "com", "wenqu", "ai", "controller");

    @Test
    @DisplayName("旧 goal/plan 路由已删除，deep 路由齐全")
    void onlyDeepRoutesRemain() throws IOException {
        List<String> paths = new ArrayList<>();
        try (var files = Files.walk(CONTROLLERS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = MAPPING.matcher(src);
                while (m.find()) paths.add(m.group(2));
            }
        }
        assertThat(paths)
                .as("深度执行入口")
                .contains("/deep", "/deep/{goalId}", "/deep/pending",
                        "/session/{sessionId}/deep", "/session/{sessionId}/deep/events",
                        "/deep/plan-approval/{planApprovalId}", "/deep/plan-approval/pending",
                        "/{token}/deep/plan-approval/{planApprovalId}", "/{token}/deep/plan-approval/pending")
                .as("旧入口必须删干净")
                .noneMatch(p -> p.equals("/goal") || p.startsWith("/goal/")
                        || p.equals("/session/{sessionId}/goal") || p.startsWith("/session/{sessionId}/goal/")
                        || p.equals("/plan-approval") || p.startsWith("/plan-approval/")
                        || p.equals("/{token}/plan-approval") || p.startsWith("/{token}/plan-approval/"));
    }
}
