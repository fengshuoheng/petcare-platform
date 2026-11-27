package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.RefundOrder;

/**
 * 退款单 Mapper，对应 refund_order 表，服务于已支付订单取消与退款审批。
 * <p>
 * 按状态列退款单、按 order_id 关联、审批后更新状态都是单表操作，在
 * PaymentService / OrderService 用 LambdaQueryWrapper、updateById 完成；
 * uk_refund_no 保证凭证唯一，本接口不写自定义 SQL。
 */
public interface RefundOrderMapper extends BaseMapper<RefundOrder> {
}
