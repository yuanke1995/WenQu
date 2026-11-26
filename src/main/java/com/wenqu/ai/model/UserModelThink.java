package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人按模型的思考档位记忆。
 * <p>
 * 一行 = 一个用户在一个模型上的选择（{@code off} / {@code on} / 具体档位），
 * 与前端「运行设置」里那句「思考档按模型记住」同一口径：切换模型不串档。
 * 没有行 = 该模型没单独选过，运行时回落模型登记的默认档位。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_model_think")
public class UserModelThink {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** 模型引用 providerId/modelId（与 c_ai_message.model 同口径） */
    private String modelRef;

    /** 思考档位：off=关闭 / on=开启但不指定强度 / low|medium|high|xhigh|max=具体强度 */
    private String thinkLevel;

    /** 更新时间（最后一次选择该模型的档位） */
    private LocalDateTime updateTime;
}