-- 智能体检索的文档级条件（JSON）：{"fileTypes":["pdf","docx"],"updatedWithinDays":90}
-- 与 knowledge_tags / knowledge_base_ids 叠加生效；检索期参与召回配额，不做召回后削薄。
-- 标签仍是历史字段 knowledge_tags（不迁数据），本列只承载新增的类型/更新时间维度。

ALTER TABLE `c_ai_agent`
    ADD COLUMN `knowledge_filter` TEXT NULL
        COMMENT '检索文档条件（JSON：fileTypes/updatedWithinDays；空=不限；标签仍存 knowledge_tags）'
        AFTER `knowledge_tags`;
