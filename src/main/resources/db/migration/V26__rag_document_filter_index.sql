-- 检索元数据过滤的文档范围解析：按「所属库 + 生效态 + 未删」定位命中文档。
-- 检索期用它把标签/类型/时间条件先收窄成候选文档集合（≤256 条时下推进向量过滤，
-- 关键词路直接拼进 doc_id IN 子查询）；没有这个索引，条件解析会退化成文档表扫描。

ALTER TABLE `c_ai_document`
    ADD INDEX `idx_kb_status_deleted` (`kb_id`, `status`, `deleted`);
