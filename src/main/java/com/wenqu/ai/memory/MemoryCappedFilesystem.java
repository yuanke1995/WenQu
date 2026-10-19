package com.wenqu.ai.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆根上的本地文件系统：所有写入 {@code MEMORY.md} 的路径都过一道 {@link MemoryBudget} 硬上限。
 *
 * <p>为什么不在 {@code MemoryConfig} 上调：框架只把 token 上限写进 consolidation prompt，
 * 模型可以不听（实测 57 KB / 目标 4k token）。而 {@code MemoryConsolidator} 与
 * {@code MemorySaveTool} 最终都走 {@code WorkspaceManager} → {@code AbstractFilesystem#uploadFiles}，
 * 在这里截是唯一能保证「落盘即不超限」的位置。日账文件不裁剪（append-only 的原始记录），
 * 只裁 {@code MEMORY.md}。
 */
@Slf4j
public class MemoryCappedFilesystem extends LocalFilesystem {

    private final int maxChars;

    public MemoryCappedFilesystem(Path rootDir, int maxFileSizeMb, int maxChars) {
        super(rootDir, true, maxFileSizeMb);
        this.maxChars = maxChars;
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        if (files == null) return List.of();
        if (maxChars <= 0 || files.isEmpty()) {
            return super.uploadFiles(runtimeContext, files);
        }
        List<Map.Entry<String, byte[]>> capped = new ArrayList<>(files.size());
        for (Map.Entry<String, byte[]> entry : files) {
            if (entry == null || !MemoryPaths.isMemoryMd(entry.getKey())) {
                capped.add(entry);
                continue;
            }
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            String trimmed = MemoryBudget.trim(text, maxChars);
            if (trimmed.length() < text.length()) {
                log.warn("[MEMORY] MEMORY.md 写入超上限，已裁剪 {} → {} 字符（上限 {}）",
                        text.length(), trimmed.length(), maxChars);
            }
            capped.add(Map.entry(entry.getKey(), trimmed.getBytes(StandardCharsets.UTF_8)));
        }
        return super.uploadFiles(runtimeContext, capped);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (maxChars > 0 && MemoryPaths.isMemoryMd(filePath) && content != null) {
            content = MemoryBudget.trim(content, maxChars);
        }
        return super.write(runtimeContext, filePath, content);
    }

    @Override
    public EditResult edit(RuntimeContext runtimeContext, String filePath, String oldText,
                           String newText, boolean replaceAll) {
        EditResult result = super.edit(runtimeContext, filePath, oldText, newText, replaceAll);
        if (maxChars > 0 && result != null && result.isSuccess() && MemoryPaths.isMemoryMd(filePath)) {
            enforceFile(runtimeContext, filePath);
        }
        return result;
    }

    private void enforceFile(RuntimeContext runtimeContext, String filePath) {
        try {
            ReadResult read = super.read(runtimeContext, filePath, 0, 0);
            if (read == null || !read.isSuccess() || read.fileData() == null) {
                return;
            }
            String text = read.fileData().content();
            String trimmed = MemoryBudget.trim(text, maxChars);
            if (trimmed.length() < text.length()) {
                super.write(runtimeContext, filePath, trimmed);
                log.warn("[MEMORY] MEMORY.md 编辑后超上限，已裁剪 {} → {} 字符（上限 {}）",
                        text.length(), trimmed.length(), maxChars);
            }
        } catch (RuntimeException e) {
            log.warn("[MEMORY] MEMORY.md 编辑后裁剪失败（下次写入会再裁）: {}", e.getMessage());
        }
    }
}
