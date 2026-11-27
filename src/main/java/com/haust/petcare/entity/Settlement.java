package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算单，对应表 settlement，属于「结算」环节。
 * <p>
 * 订单完成（用户确认或 24h 自动确认）时生成：平台抽成 commission = 实付 × 抽成比例
 * （默认 15%），staff_income = 实付 − 抽成；settle_time 为 T+N 到期时间（默认 T+7），
 * 定时任务到点把状态由「0 待结算」改为「1 可提现」（本期只展示金额构成、不做打款）。
 * order_id 唯一索引 uk_order 防止重复结算（见 SettlementService.createForOrder）。
 */
@Data
@TableName("settlement")
public class Settlement {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单，唯一索引 uk_order 防止重复结算 */
    private Long orderId;
    /** 结算给哪个服务者 */
    private Long staffId;
    /** 订单实付金额，单位元，结算基数 */
    private BigDecimal orderAmount;
    /** 平台抽成 = 实付 × 抽成比例（默认 15%），单位元 */
    private BigDecimal commission;
    /** 服务者收入 = 实付 − 抽成，单位元 */
    private BigDecimal staffIncome;
    /** 0 待结算 1 可提现 2 冻结 */
    private Integer status;
    /** 可提现到期时间 = 完成时间 + T+N（默认 T+7），定时任务到点把状态改为 1 */
    private LocalDateTime settleTime;
    /** 生成时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
