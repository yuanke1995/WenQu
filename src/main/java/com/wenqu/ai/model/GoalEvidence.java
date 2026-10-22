package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条目标证据：Verifier 的输入，也是 Outcome 的引用。
 *
 * <p>模型能提交的只是「证据引用」，不是「完成」本身。证据是否成立由
 * {@code GoalVerifier} 按 kind 分派核验：产物查登记表、文件查存在性、命令/测试查退出码、
 * URL 查格式，文本只作为说明。核验结果落在 {@link #verified}：
 * 0=未核验、1=通过、2=失败。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_goal_evidence")
public class GoalEvidence {

    public static final String KIND_ARTIFACT = "artifact";
    public static final String KIND_FILE = "file";
    public static final String KIND_COMMAND = "command";
    public static final String KIND_TEST = "test";
    public static final String KIND_URL = "url";
    public static final String KIND_TEXT = "text";

    public static final int UNVERIFIED = 0;
    public static final int PASSED = 1;
    public static final int FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属目标 */
    private String goalId;

    /** 产生/引用该证据的轮次 */
    private Integer roundN;

    /** artifact / file / command / test / url / text */
    private String kind;

    /** 产物ID/文件名/路径/URL/命令 */
    private String ref;

    /** 说明 */
    private String note;

    /** 内容指纹（可选） */
    private String hash;

    /** 0=未核验 1=通过 2=失败 */
    private Integer verified;

    /** 发生时刻 */
    private LocalDateTime createdAt;
}
