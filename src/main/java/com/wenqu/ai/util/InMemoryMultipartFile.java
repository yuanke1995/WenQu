package com.wenqu.ai.util;

import org.springframework.lang.Nullable;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 内存字节 → MultipartFile 的最小桥接（知识库导入把 zip 内源文件递交上传管线用）。
 * <p>
 * 不能用 spring-test 的 MockMultipartFile：它是 test scope 依赖，进不了运行时 classpath。
 * 只实现 {@link MultipartFile} 接口的方法，getOriginalFilename 返回文件名（上传链路按它取扩展名/校验魔数）。
 *
 * @author yuanke
 */
public class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final byte[] bytes;

    public InMemoryMultipartFile(String name, String originalFilename, byte[] bytes) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.bytes = bytes;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    @Nullable
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes == null ? new byte[0] : bytes;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(bytes == null ? new byte[0] : bytes);
        }
    }
}
