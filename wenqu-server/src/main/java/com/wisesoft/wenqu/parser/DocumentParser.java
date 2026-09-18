package com.wisesoft.wenqu.parser;

import com.wisesoft.wenqu.model.Chunk;

import java.util.List;
import java.util.Set;

/**
 * 文档解析器接口
 * 各格式解析器将上传文件解析为统一的分块列表（供向量化与元数据存储）
 * 使用 Path（源文件已持久落盘）而非 MultipartFile：支持上传后的异步解析（请求结束文件流已失效），
 * 且避免异步任务把整个文件 readAllBytes 读入堆内存（大文件 OOM 风险）；各解析器按需自行打开流
 *
 * @author yuanke
 */
public interface DocumentParser {

    /**
     * 是否支持该文件扩展名（小写，不含点，如 "docx"、"pdf"、"xlsx"）
     */
    boolean supports(String ext);

    /**
     * 支持的文件扩展名集合（小写不含点；供前端上传校验与接口动态获取，默认空）
     */
    default Set<String> supportedExts() {
        return Set.of();
    }

    /**
     * 解析文件为分块列表
     *
     * @param file     源文件路径（已持久落盘，解析期间保证存在）
     * @param fileName 原始文件名（用于日志/标题兜底）
     * @param docId    文档ID（用于图片落盘目录等）
     */
    List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws Exception;

    /**
     * 解析进度回调：解析器在耗时子任务（如逐张图片视觉识别）完成时上报
     */
    @FunctionalInterface
    interface ParseProgress {
        void onProgress(int percent, String desc);
    }

    /**
     * 带进度回调的解析（默认实现不回调，保持与旧调用兼容）
     *
     * @param progress 进度回调（可为 null 表示不关心进度）
     */
    default List<Chunk> parse(java.nio.file.Path file, String fileName, String docId, ParseProgress progress) throws Exception {
        return parse(file, fileName, docId);
    }
}
