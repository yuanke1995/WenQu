package com.wisesoft.wenqu.agents.backends.sandbox;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * 沙盒 runtime 远程客户端（对应参考实现 backend.py 经 {@code agent_sandbox} Python SDK 持有的
 * {@code Sandbox} / {@code AsyncSandbox} 文件与 shell 端点表面）。
 *
 * <p>必要替换：Python SDK {@code agent_sandbox} 不在本工程 Java 依赖中（能力差异，沙盒 runtime
 * 未部署），故以接口契约形式保留其 wire 表面；实际实现（经 JDK HttpClient 与沙盒 runtime 的
 * 文件/shell HTTP 接口对话）在能力差异解除前由 {@link ProvisionerSandboxBackend#buildClient}
 * 统一抛 {@link SandboxProvisionerException}——<b>绝不静默成功</b>。
 *
 * <p>方法的返回对象逐字段对齐参考实现 {@code result.data.*}：
 * <ul>
 *   <li>{@code read_file(...)} → {@link SandboxReadFileResult#content()} 为原始字节，
 *       {@code encoding} 为 {@code "base64"} 时由调用方解码；</li>
 *   <li>{@code list_path(...)} / {@code find_files(...)} → {@code .files}；</li>
 *   <li>{@code write_file} / {@code str_replace_editor} / {@code upload_file} →
 *       {@code success} / {@code message}；</li>
 *   <li>{@code shell.exec_command(...)} → {@code output} / {@code exit_code}。</li>
 * </ul>
 */
public interface SandboxRuntimeClient {

    /** 按行窗口读取文件（对应 file.read_file）。 */
    SandboxReadFileResult readFile(String file, Integer startLine, Integer endLine);

    /** 写入文本文件（对应 file.write_file，encoding 默认 utf-8）。 */
    SandboxWriteResult writeFile(String file, String content);

    /** 写入（可带 base64 编码的）文件内容（对应 file.write_file(encoding=...)）。 */
    SandboxWriteResult writeFile(String file, String content, String encoding);

    /** 字符串替换编辑（对应 file.str_replace_editor）。 */
    SandboxWriteResult strReplaceEditor(String command, String path, String oldStr, String newStr, String replaceMode);

    /** 列目录（对应 file.list_path(recursive, include_size)）。 */
    SandboxListPathResult listPath(String path, boolean recursive, boolean includeSize);

    /** 按 glob 查找文件（对应 file.find_files）。 */
    SandboxFindFilesResult findFiles(String path, String glob);

    /** 流式下载文件内容（对应 file.download_file 的迭代器；未部署时抛错）。 */
    Iterable<byte[]> downloadFile(String path, Duration timeout);

    /** 上传本地文件到沙盒（对应 file.upload_file）。 */
    SandboxWriteResult uploadFile(File source, String path);

    /** 执行 shell 命令（对应 shell.exec_command）。 */
    SandboxExecResult execCommand(String command, Duration timeout, boolean truncate);

    /** read_file 结果：content 为原始字节，encoding 为 "base64" 时需解码。 */
    record SandboxReadFileResult(byte[] content, String encoding) {}

    /** 写/编辑/上传结果。 */
    record SandboxWriteResult(boolean success, String message) {}

    /** 目录条目（对应 list_path 返回的 entry dict）。 */
    record SandboxFileEntry(String path, boolean isDirectory, Long size, String modifiedTime) {}

    /** list_path 结果。 */
    record SandboxListPathResult(List<SandboxFileEntry> files) {}

    /** find_files 结果。 */
    record SandboxFindFilesResult(List<String> files) {}

    /** exec_command 结果：output 为合并后的 stdout/stderr，exitCode 为进程退出码。 */
    record SandboxExecResult(String output, Integer exitCode) {}
}
