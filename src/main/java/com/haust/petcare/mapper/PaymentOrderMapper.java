package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.PaymentOrder;

/**
 * 支付单 Mapper，对应 payment_order 表，服务于模拟支付网关。
 * <p>
 * 按 pay_no 定位支付单、按 order_id 查历史支付单都在 PaymentService 里用
 * LambdaQueryWrapper 完成（uk_pay_no 唯一索引保证凭证唯一）；回调幂等靠
 * 「支付单已是 SUCCESS 则直接返回」在 service 层判断，而非依赖 SQL，本接口不写自定义 SQL。
 */
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {
}
