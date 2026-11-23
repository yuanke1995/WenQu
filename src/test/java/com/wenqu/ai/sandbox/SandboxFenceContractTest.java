package com.wenqu.ai.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态契约：注释声明的行为必须跟实现一致。
 * <p>{@code SandboxService} 曾长期挂着"scope=工作区 id、workdir=projects/{id}"的旧注释，
 * 而 {@code scopeOf()} 实际只返回 sessionId / uid、workdir 恒 null——这类漂移比没有文档更误导。
 */
class SandboxFenceContractTest {

    private static final Path SERVICE = Path.of("src", "main", "java", "com", "wenqu", "ai",
            "service", "SandboxService.java");
    private static final Path FS = Path.of("src", "main", "java", "com", "wenqu", "ai",
            "sandbox", "ProvisionerSandboxFilesystem.java");
    private static final Path BACKEND = Path.of("src", "main", "java", "com", "wenqu", "ai",
            "sandbox", "ProvisionerSandboxBackend.java");

    @Test
    void sandboxServiceCommentMatchesScopeOfImplementation() throws Exception {
        String source = Files.readString(SERVICE);
        int classDocEnd = source.indexOf("public class SandboxService");
        assertThat(classDocEnd).isGreaterThan(0);
        String classDoc = source.substring(0, classDocEnd);

        assertThat(classDoc).doesNotContain("工作区优先")
                .doesNotContain("scope = <b>工作区 id</b>")
                .doesNotContain("workdir = 工作区的")
                .doesNotContain("共用同一个容器");
        assertThat(classDoc).contains("服务器沙盒只服务无工作区会话");
    }

    @Test
    void everyStructuredIoOverridePassesGuard() throws Exception {
        String fs = Files.readString(FS);
        // 五条主链路 override 各自先过 guard，不许直连 client 漏判
        assertMethodGuards(fs, "public List<FileUploadResponse> uploadFiles(", "backend.guardWrite(");
        assertMethodGuards(fs, "public List<FileDownloadResponse> downloadFiles(", "backend.guardRead(");
        assertMethodGuards(fs, "public LsResult ls(", "backend.guardList(");
        assertMethodGuards(fs, "public GlobResult glob(", "backend.guardList(");
        assertMethodGuards(fs, "public ReadResult read(", "backend.read("); // read 经 backend.read 内部 guard
        assertMethodGuards(fs, "public EditResult edit(", "backend.guardWrite(");
        assertMethodGuards(fs, "public GrepResult grep(", "backend.guardList(");
    }

    @Test
    void shellBoundaryIsDocumented() throws Exception {
        String backend = Files.readString(BACKEND);
        assertThat(backend).contains("cd 只是默认工作目录，不是访问边界");
    }

    /** 从方法起点截到下个 override/方法注释，断言这段里出现 guard 调用。 */
    private static void assertMethodGuards(String source, String signature, String guardCall) {
        int start = source.indexOf(signature);
        assertThat(start).as("缺少方法 " + signature).isGreaterThan(0);
        int end = source.indexOf("\n    @Override", start + signature.length());
        if (end < 0) end = source.length();
        assertThat(source.substring(start, end)).as(signature + " 未过 " + guardCall).contains(guardCall);
    }
}
