package com.wisesoft.wenqu.knowledge;

import java.util.List;
import java.util.Map;

/**
 * 只读外部检索连接器基类（对应 implementations/read_only_connectors.py）。
 *
 * <p>这类知识库只负责保存连接参数和执行 Query，不承载文档上传、解析、索引和文件预览能力。
 *
 * <p>语言差异标注：参考实现用子类覆写每个方法并 {@code raise self._readonly_error()}；
 * 本工程的知识库运行时是单一 Spring 组件（{@link KnowledgeBaseRuntime}），
 * 无法按类型多态分发到不同子类，因此改为在此集中提供「只读能力判定 + 统一报错」，
 * 由运行时按 {@code supports_documents=false} 判定后调用——报错文案与能力口径逐字对齐参考实现。
 */
public final class ReadOnlyConnectors {

    private ReadOnlyConnectors() {}

    /** 只读连接器默认不要求 embedding 模型（对应 requires_embedding_model=False）。 */
    public static final boolean REQUIRES_EMBEDDING_MODEL = false;

    /** 只读连接器不支持文档全文操作（对应 supports_documents=False）。 */
    public static final boolean SUPPORTS_DOCUMENTS = false;

    /** 只读连接器不补充分块默认值（对应 apply_chunk_defaults=False）。 */
    public static final boolean APPLY_CHUNK_DEFAULTS = false;

    /** 只读操作统一报错（对应 _readonly_error）。 */
    public static IllegalArgumentException readonlyError() {
        return new IllegalArgumentException("只读检索连接器不支持该操作");
    }

    /** 文件树预览报错（对应 list_file_tree）。 */
    public static IllegalArgumentException fileTreeError() {
        return new IllegalArgumentException("只读检索连接器不支持文件树预览");
    }

    /** 文件下载报错（对应 get_file_download）。 */
    public static IllegalArgumentException fileDownloadError() {
        return new IllegalArgumentException("只读检索连接器不支持文件下载");
    }

    /** 不支持文档操作的方法名 → 中文标签，用于运行时统一抛错（对应 manager._require_kb_supports_documents）。 */
    public static final Map<String, String> OPERATION_LABELS = Map.of(
            "add", "文件添加",
            "parse", "解析",
            "index", "索引",
            "open", "文档查看",
            "find", "文档查找",
            "update_params", "参数更新",
            "delete", "删除");

    /** 只读连接器覆盖的全部操作（对应 ReadOnlyConnectors 覆写的方法集合）。 */
    public static final List<String> OVERRIDDEN_OPERATIONS = List.of(
            "add", "parse", "update_params", "index", "delete",
            "create_folder", "move_file", "rename_folder", "delete_folder",
            "get_file_basic_info", "get_file_content", "open_file_content",
            "find_file_content", "get_file_info", "list_file_tree", "get_file_download");
}
