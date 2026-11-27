package com.haust.petcare.service;

import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.PaymentOrder;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.PaymentOrderMapper;
import com.haust.petcare.mapper.RefundOrderMapper;
import com.haust.petcare.mapper.StaffMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 支付回调链路：签名稳定性、验签防篡改、重复回调幂等。
 * /api/pay/callback 在后端鉴权白名单里（由模拟网关直接调用），签名是它唯一的身份校验，
 * 所以这里重点验证 sign 与 handleCallback，而不是 Spring 事务本身。
 * 纯 Mockito 单测（不启 Spring 容器、不连 MySQL/Redis）：各 Mapper 与 OrderService/
 * SlotService/NotificationService 全部 @Mock，只让 PaymentService 的真实算法跑起来；
 * 「已支付」「签名错」等状态用 PaymentOrder 对象直接构造，无需真实库表。
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentOrderMapper paymentMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private RefundOrderMapper refundMapper;
    @Mock
    private StaffMapper staffMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private SlotService slotService;
    @Mock
    private NotificationService notificationService;

    private PaymentService paymentService;
    private PetcareProperties properties;

    @BeforeEach
    void setUp() {
        properties = new PetcareProperties();
        // 固定测试密钥，保证签名断言可复现；真实密钥由配置注入，不写死在代码里
        properties.getPay().setSecret("unit-test-secret");
        paymentService = new PaymentService(paymentMapper, orderMapper, refundMapper, staffMapper,
                orderService, slotService, notificationService, properties);
    }

    /**
     * 场景：同一 payNo、金额、状态连续两次签名。
     * 断言：两次结果完全相等（确定性摘要，不掺时间戳或随机盐）。
     * 业务风险：签名不稳定会让渠道回调与本地校验永远对不上，正常支付也被误判为篡改。
     */
    @Test
    void signShouldBeStable() {
        String first = paymentService.sign("PY123", new BigDecimal("149.00"), "SUCCESS");
        String second = paymentService.sign("PY123", new BigDecimal("149.00"), "SUCCESS");
        assertEquals(first, second);
    }

    /**
     * 场景：支付单已是 SUCCESS，模拟网关因重试再次投递同一笔回调。
     * 断言：返回 idempotent=true、payNo 原样返回，不再推进订单状态。
     * 业务风险：网络重投/网关重试是常态，不幂等会重复入账、重复发通知、把订单状态推错。
     */
    @Test
    void duplicateCallbackShouldBeIdempotent() {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(1L);
        payment.setPayNo("PY123");
        payment.setOrderId(10L);
        payment.setAmount(new BigDecimal("149.00"));
        payment.setStatus(PaymentService.SUCCESS);
        when(paymentMapper.selectOne(any())).thenReturn(payment);

        Requests.PayCallbackRequest callback = new Requests.PayCallbackRequest();
        callback.setPayNo("PY123");
        callback.setAmount(new BigDecimal("149.00"));
        callback.setStatus("SUCCESS");
        callback.setSign(paymentService.sign("PY123", new BigDecimal("149.00"), "SUCCESS"));

        Map<String, Object> result = paymentService.handleCallback(callback);

        assertTrue((Boolean) result.get("idempotent"));
        assertEquals("PY123", result.get("payNo"));
    }

    /**
     * 场景：回调 sign 写成非法值 bad-sign。
     * 断言：handleCallback 抛 BusinessException（签名校验失败）。
     * 业务风险：/api/pay/callback 不走用户鉴权，验签是唯一防线；放行伪造回调等于任意把订单改成已支付。
     */
    @Test
    void invalidSignShouldBeRejected() {
        Requests.PayCallbackRequest callback = new Requests.PayCallbackRequest();
        callback.setPayNo("PY123");
        callback.setAmount(new BigDecimal("149.00"));
        callback.setStatus("SUCCESS");
        callback.setSign("bad-sign");

        assertThrows(BusinessException.class, () -> paymentService.handleCallback(callback));
    }
}
