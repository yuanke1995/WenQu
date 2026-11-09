package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索评估集：一次生成/导入产生一个版本，运行引用版本快照。
 * <p>同一 {@code scopeKbId} 下版本号递增；作用域为空表示全库评估集。
 * 多租户由 {@code tenant_id} 行级过滤自动隔离，业务代码不写租户条件。
 */
@Data
@TableName("c_ai_retrieval_eval_set")
public class RetrievalEvalSet {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    /** 同一作用域内递增 */
    private Integer version;

    /** 知识库范围（NULL=全库） */
    private String scopeKbId;

    private LocalDateTime generatedAt;

    /** generated / import / manual */
    private String source;

    /** 生成时剔除的失效期望块数 */
    private Integer staleExpected;

    /** 基线运行 ID（历史对比锚点） */
    private String baselineRunId;

    private String note;

    private String createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
