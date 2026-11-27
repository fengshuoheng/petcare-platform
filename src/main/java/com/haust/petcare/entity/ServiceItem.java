package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 可预约的服务项目，对应表 service_item，属于「预约域」的服务目录。
 * <p>
 * 按宠物体型分三档定价 price_s/price_m/price_l（单位：元）；PriceService.basePrice
 * 依体型取其中一档作为「基础价」，再叠加附加项、距离费、夜间费、加急费得到订单应付总额。
 * status=1 才在列表展示、才允许下单（requireService 会拦截已下架服务），
 * sort 控制展示顺序，duration_min 为单次服务时长。
 */
@Data
@TableName("service_item")
public class ServiceItem {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 服务名称，快照进订单 service_name */
    private String name;
    /** 分类：洗护/美容/喂养/遛狗/寄养/陪诊 */
    private String category;
    /** 服务描述 */
    private String description;
    /** 单次服务时长（分钟），试算时随报价返回 */
    private Integer durationMin;
    /** 按宠物体型划分的三档基础价（单位元）：S/M/L 各一档，PriceService.basePrice
     * 依体型取其一，再叠加附加项、距离费、夜间费、加急费得到订单应付总额 */
    private BigDecimal priceS;
    private BigDecimal priceM;
    private BigDecimal priceL;
    /** 上架状态：1 上架 0 下架；下架后列表不展示、下单被 requireService 拦截 */
    private Integer status;
    /** 展示排序值，越小越靠前 */
    private Integer sort;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
