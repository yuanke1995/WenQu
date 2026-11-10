package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 市场安装流水（追加型）：每次安装/更新/新增副本写一条，供作者的安装趋势与最近安装人使用。
 * <p>
 * 与 c_ai_market_install（每人每物品只留一条最新记录）不同，本表不覆盖，保留发生次数与时间；
 * 平台全局表（见 WenquTenantLineHandler），按 180 天滚动清理。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_market_install_log")
public class MarketInstallLog {

    public static final String ACTION_INSTALL = "install";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_COPY = "copy";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 市场物品（c_ai_market_item.id） */
    private String itemId;

    /** 安装人（c_ai_user.uid） */
    private String uid;

    /** 物品类型（冗余，便于按类型统计） */
    private String itemType;

    /** 本次安装到的版本（展示用） */
    private String version;

    /** 本次安装到的内容指纹 */
    private String contentHash;

    /** install=首次安装 / update=覆盖更新 / copy=新增副本 */
    private String action;

    private LocalDateTime createTime;
}
