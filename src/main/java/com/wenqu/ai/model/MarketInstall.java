package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 市场安装记录：谁在哪份内容指纹上装过哪个物品。
 * <p>
 * 只服务「已安装 / 有更新」徽标（记录的指纹 ≠ 条目当前指纹即提示更新），
 * <b>不是</b>已装清单——卸载技能不删记录，重装覆盖既有行（uk_uid_item）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_market_install")
public class MarketInstall {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 安装人（c_ai_user.uid） */
    private String uid;

    /** 市场物品（c_ai_market_item.id） */
    private String itemId;

    /** 物品类型（冗余，便于按类型对账） */
    private String itemType;

    /** 安装时的物品版本（展示用） */
    private String version;

    /** 安装时的内容指纹（条目当前指纹 ≠ 此值即提示「有更新」） */
    private String contentHash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
