package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流凭据（第 3 期）：http 节点里以 {@code {{credential:名称}}} 引用，避免把密钥明文写进 DSL。
 * <p>
 * 个人资产（不共享，隔离完全靠 {@code uid}）；{@code value} 以 RSA 密文落库
 * （复用 {@link com.wenqu.ai.service.ConfigCryptoService}），出参一律脱敏为 {@code ****后4位}。
 * 明文只在节点执行瞬间解密，不进 state / trace / 日志。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_credential")
public class Credential {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属人 uid（个人资产，不共享） */
    private String uid;

    /** 凭据名称（工作流里以 {{credential:名称}} 引用；同一用户内唯一） */
    private String name;

    /** 凭据值（RSA 密文；读取需经服务层解密，实体不直接出参） */
    private String value;

    /** 用途备注 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
