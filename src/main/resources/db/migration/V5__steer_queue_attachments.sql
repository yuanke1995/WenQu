-- Flyway V5：插话也能带文档附件。
-- 背景：插话的上下文是本轮请求构建期定型的，但附件正文不是检索结果——按 fileId 现读现抽就能赶上
-- 正在跑的这一轮。队列行要留住这份引用，刷新/换设备后 drain 出去的仍是带附件的那一句。
ALTER TABLE `c_ai_steer_item`
    ADD COLUMN `attachments` longtext DEFAULT NULL
        COMMENT 'JSON 数组：[{name,mime,size,fileId}]：随这句话送出的文档附件（排队期间回显，送达时按 fileId 现读正文）'
        AFTER `images`;
