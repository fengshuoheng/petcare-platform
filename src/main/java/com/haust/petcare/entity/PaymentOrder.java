package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单，对应表 payment_order，属于「模拟支付网关」环节。
 * <p>
 * 业务订单与支付单分离：一个订单可因补差价、支付失败重试产生多张支付单，
 * 订单只关心业务状态，金额与凭证独立管理。回调按
 * 「验签 → 金额校验 → 幂等判断 → 更新支付单/订单 → 确认时间槽」处理（见 PaymentService），
 * pay_no 唯一索引 uk_pay_no 保证凭证唯一；channel=MOCK 表明是模拟渠道，不对接真实微信/支付宝。
 */
@Data
@TableName("payment_order")
public class PaymentOrder {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 支付单号（PY+时间戳+随机数），唯一索引 uk_pay_no，回调按其定位支付单 */
    private String payNo;
    /** 关联业务订单；一个订单可有多张支付单（补差价/失败重试） */
    private Long orderId;
    /** 本单应收金额，单位元；回调时与回调金额比对，不一致直接拒绝 */
    private BigDecimal amount;
    /** 支付渠道，本项目固定 MOCK（模拟网关，不对接真实微信/支付宝） */
    private String channel;
    /** 0 初始化 1 支付中 2 成功 3 失败 4 关闭 */
    private Integer status;
    /** 支付成功时间，回调处理成功时写入 */
    private LocalDateTime payTime;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
