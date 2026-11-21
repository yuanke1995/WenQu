-- 上传前置去重：文件字节 SHA-256（与块级 content_hash 不同，后者是解析后正文指纹）
ALTER TABLE `c_ai_document`
    ADD COLUMN `content_sha256` VARCHAR(64) DEFAULT NULL
        COMMENT '文件字节 SHA-256（上传前置去重/版本提示；空=存量文档）'
        AFTER `source_hash`;
