package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单，对应表 refund_order，属于「退款/售后」环节。
 * <p>
 * 已支付订单取消或售后时生成，amount 为扣除阶梯违约金后的应退金额
 * （服务前 ≥24h 全退、2~24h 扣 20%、<2h 扣 50%，见 OrderService.cancel）。
 * 退款需管理员审批：通过(3)后订单转「已退款」，拒绝(2)则回到「已取消」；
 * refund_no 唯一索引 uk_refund_no 保证退款凭证唯一。
 */
@Data
@TableName("refund_order")
public class RefundOrder {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 退款单号（RF+订单号），唯一索引 uk_refund_no */
    private String refundNo;
    /** 关联订单 */
    private Long orderId;
    /** 关联原支付单，可为空（取消时按订单整体计算退款） */
    private Long paymentId;
    /** 应退金额，单位元；已扣除阶梯违约金（见 OrderService.cancel） */
    private BigDecimal amount;
    /** 退款原因，如「取消订单，违约金 20%」 */
    private String reason;
    /** 0 申请 1 同意 2 拒绝 3 已退款 */
    private Integer status;
    /** 审批管理员 id，approveRefund/rejectRefund 时写入 */
    private Long operatorId;
    /** 申请时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
    /** 审批完成时间 */
    private LocalDateTime finishTime;
}
