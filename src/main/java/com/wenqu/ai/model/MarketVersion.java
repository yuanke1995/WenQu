package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 市场版本历史：每次内容指纹变化写一条，附带作者填写的发布说明。
 * <p>
 * 只用于「更新说明 / 版本历史」展示；「有更新」仍由安装记录与条目当前指纹比对得出，
 * 版本号只做展示，不参与排序与判定。表是平台全局表（见 WenquTenantLineHandler）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_market_version")
public class MarketVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 市场物品（c_ai_market_item.id） */
    private String itemId;

    /** 发布时的版本号（展示用） */
    private String version;

    /** 发布时的内容指纹（SHA-256） */
    private String contentHash;

    /** 这次改了什么（重新上架必填；平台种子为系统文案） */
    private String releaseNote;

    /**
     * 发布序号（毫秒时间戳，进程内单调递增）。
     * <p>create_time 只到秒，同一秒内多次发布（测试/脚本）会并列，导致「已装版本之后的新说明」切错区间；
     * 这里单独存毫秒序号，查询与裁剪统一按它倒序。</p>
     */
    private Long releasedAtMs;

    private LocalDateTime createTime;
}
