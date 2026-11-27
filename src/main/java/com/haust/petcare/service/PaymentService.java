package com.haust.petcare.service;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.PaymentOrder;
import com.haust.petcare.entity.RefundOrder;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.PaymentOrderMapper;
import com.haust.petcare.mapper.RefundOrderMapper;
import com.haust.petcare.mapper.StaffMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟支付网关。
 * 真实接入时把 create/mockPay 换成渠道下单与跳转，回调验签、幂等、状态机保持不变。
 * <p>
 * 业务域：支付单生命周期与渠道回调，对应 payment_order（uk_pay_no）、orders、time_slot、
 * refund_order、notification、order_status_log。
 * <p>
 * 链路位置：OrderService.create 生成 WAIT_PAY 订单后由用户调 create 建支付单；
 * handleCallback 是「支付成功 → 订单 WAIT_PAY→WAIT_DISPATCH/WAIT_ACCEPT → 时间槽 1→2 确认」的唯一入口，
 * 支付单、订单、时间槽三者的更新放在同一个 @Transactional 本地事务里，异常整体回滚，保证最终一致。
 * <p>
 * 面试要点：回调三件套「验签 → 金额比对 → 幂等」；支付单 0初始化/1支付中/2成功/3失败/4关闭，
 * 退款单 0申请/2拒绝/3已退款，订单侧对应 REFUNDING(8) → REFUNDED(9) / CANCELED(7)。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    // 支付单 5 态：INIT 待支付、PAYING 处理中、SUCCESS 已成功、FAILED 失败、CLOSED 关闭；
    // 与 payment_order.status 一致，SUCCESS 是回调幂等判断的关键状态。
    public static final int INIT = 0;
    public static final int PAYING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;
    public static final int CLOSED = 4;

    private final PaymentOrderMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final RefundOrderMapper refundMapper;
    private final StaffMapper staffMapper;
    private final OrderService orderService;
    private final SlotService slotService;
    private final NotificationService notificationService;
    private final PetcareProperties properties;

    /**
     * 构造器注入支付/订单/退款单 Mapper 与订单、时间槽、通知服务；Spring 单例装配，
     * 保证 handleCallback 事务内所有写操作共用同一连接。
     */
    public PaymentService(PaymentOrderMapper paymentMapper, OrderMapper orderMapper,
                          RefundOrderMapper refundMapper, StaffMapper staffMapper,
                          OrderService orderService, SlotService slotService,
                          NotificationService notificationService, PetcareProperties properties) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.refundMapper = refundMapper;
        this.staffMapper = staffMapper;
        this.orderService = orderService;
        this.slotService = slotService;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /**
     * 建支付单：校验订单可支付后，按订单快照金额生成一笔 MOCK 渠道支付单。
     * <p>
     * 链路：OrderService.create 返回 WAIT_PAY 订单后由用户/前端调用；返回 payNo 与模拟支付 URL。
     *
     * @param orderId 订单 ID
     * @param userId  当前用户（必须是订单本人）
     * @return payNo、amount、mock=true、payUrl（指向 mockPay 的模拟收银台地址）
     * @throws BusinessException 订单不存在/非本人、订单状态不是 WAIT_PAY、支付已超时
     */
    public Map<String, Object> create(Long orderId, Long userId) {
        Order order = orderService.requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        // 支付前置校验三连：归属本人、状态仍为 WAIT_PAY、未超过 payExpireAt；
        // 超时后不允许再支付，订单会由定时任务关成 CLOSED，用户需重新下单。
        if (order.getStatus() != OrderService.WAIT_PAY) {
            throw new BusinessException("订单当前状态不可支付");
        }
        if (order.getPayExpireAt() != null && order.getPayExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("支付已超时，请重新下单");
        }

        PaymentOrder payment = new PaymentOrder();
        // payNo = PY + 秒级时间戳 + 4 位随机数，靠 uk_pay_no 唯一索引兜底；
        // amount 直接取订单 payAmount（下单时的计价快照），不重新计价，避免支付金额与订单不符。
        payment.setPayNo("PY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ThreadLocalRandom.current().nextInt(1000, 9999));
        payment.setOrderId(orderId);
        payment.setAmount(order.getPayAmount());
        payment.setChannel("MOCK");
        payment.setStatus(INIT);
        payment.setCreateTime(LocalDateTime.now());
        paymentMapper.insert(payment);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payNo", payment.getPayNo());
        result.put("amount", payment.getAmount());
        result.put("mock", true);
        result.put("payUrl", "/api/payments/" + payment.getPayNo() + "/mock-pay");
        return result;
    }

    /**
     * 模拟用户完成支付：生成渠道回调所需的签名并走与真实回调相同的处理逻辑。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mockPay(String payNo, Long userId) {
        PaymentOrder payment = requirePayment(payNo);
        Order order = orderService.requireOrder(payment.getOrderId());
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("支付单不存在");
        }
        // 模拟用户点「支付」：用与真实渠道相同的 sign() 生成签名，再走 handleCallback，
        // 让测试路径与生产回调走完全相同的验签/幂等/状态机代码，避免两套逻辑不一致。
        Requests.PayCallbackRequest callback = new Requests.PayCallbackRequest();
        callback.setPayNo(payNo);
        callback.setAmount(payment.getAmount());
        callback.setStatus("SUCCESS");
        callback.setSign(sign(payNo, payment.getAmount(), "SUCCESS"));
        return handleCallback(callback);
    }

    /**
     * 渠道回调：验签 -> 金额校验 -> 幂等 -> 更新支付单与订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleCallback(Requests.PayCallbackRequest callback) {
        // 验签：MD5(payNo|金额(2位HALF_UP)|status|petcare.pay.secret)，忽略大小写比较；
        // 模拟网关用共享密钥做摘要，真实接入应换成渠道公钥验签/RSA/HMAC，且密钥不放配置文件。
        String expected = sign(callback.getPayNo(), callback.getAmount(), callback.getStatus());
        if (!expected.equalsIgnoreCase(callback.getSign())) {
            throw new BusinessException("签名校验失败");
        }
        PaymentOrder payment = requirePayment(callback.getPayNo());
        // 金额比对用 BigDecimal.compareTo（忽略 scale 差异），防止回调被篡改或串到别的支付单；
        // 签名里已经含金额，这里再做一次业务侧校验，双保险。
        if (payment.getAmount().compareTo(callback.getAmount()) != 0) {
            throw new BusinessException("回调金额与支付单不一致");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payNo", payment.getPayNo());
        // 回调幂等：渠道重试/网络重复到达时，支付单已是 SUCCESS 就直接返回，
        // 不再推进订单、不再确认时间槽、不再发通知，避免重复履约。
        if (payment.getStatus() == SUCCESS) {
            result.put("idempotent", true);
            return result;
        }
        if (!"SUCCESS".equalsIgnoreCase(callback.getStatus())) {
            payment.setStatus(FAILED);
            paymentMapper.updateById(payment);
            result.put("success", false);
            return result;
        }

        // 为什么先改支付单、再改订单、最后确认时间槽：
        // ① 支付单是「渠道已扣款」这一事实的凭证，先落 SUCCESS，重复回调才能在上面的幂等短路返回；
        // ② 时间槽确认（1→2）是链路最后一步，依赖订单已离开 WAIT_PAY 才有意义；
        // ③ 三步与通知都在同一个 @Transactional 里，任一步异常整体回滚，三者不会长期不一致；
        //    若先确认槽再改支付单，回调重放就会把已确认的槽重复处理。
        payment.setStatus(SUCCESS);
        payment.setPayTime(LocalDateTime.now());
        paymentMapper.updateById(payment);

        Order order = orderService.requireOrder(payment.getOrderId());
        // 订单侧二次幂等：订单可能已被超时任务关成 CLOSED、或已被其它回调处理，
        // 只要不再是 WAIT_PAY 就停止推进，避免把已关闭订单重新激活。
        if (order.getStatus() != OrderService.WAIT_PAY) {
            // 已处理过（例如重复支付），直接返回幂等结果
            result.put("idempotent", true);
            return result;
        }
        order.setStatus(order.getStaffId() == null ? OrderService.WAIT_DISPATCH : OrderService.WAIT_ACCEPT);
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
        // 确认时间槽：条件 UPDATE（WHERE order_id=? AND status=1）把「锁定」变「已约」，
        // 只认本订单锁定的槽；锁已过期被定时任务释放时影响 0 行，不会误占别人的槽。
        slotService.confirm(order.getId());
        orderService.log(order.getId(), OrderService.WAIT_PAY, order.getStatus(), "SYSTEM", null, "支付成功");

        notificationService.send(order.getUserId(), "PAY", "支付成功",
                "订单 " + order.getOrderNo() + " 支付成功，等待服务者接单", order.getId());
        if (order.getStaffId() != null) {
            Staff staff = staffMapper.selectById(order.getStaffId());
            notificationService.send(staff == null ? null : staff.getUserId(), "ORDER", "新订单待接单",
                    "订单 " + order.getOrderNo() + " 待接单", order.getId());
        }
        log.info("支付成功 payNo={} orderNo={} amount={}", payment.getPayNo(), order.getOrderNo(), payment.getAmount());
        result.put("success", true);
        return result;
    }

    /**
     * 管理端退款单列表，按创建时间倒序；status 为空查全部。
     *
     * @param status 可选：0 申请中 / 2 拒绝 / 3 已退款
     * @return 退款单列表
     */
    public List<RefundOrder> listRefunds(Integer status) {
        LambdaQueryWrapper<RefundOrder> wrapper = new LambdaQueryWrapper<RefundOrder>()
                .orderByDesc(RefundOrder::getCreateTime);
        if (status != null) {
            wrapper.eq(RefundOrder::getStatus, status);
        }
        return refundMapper.selectList(wrapper);
    }

    /**
     * 管理员同意退款：退款单 0→3，订单 REFUNDING(8)→REFUNDED(9)，写状态日志并通知用户。
     * <p>
     * 链路：OrderService.cancel 扣违约金后生成 RefundOrder，这里是审核收尾；
     * 与 confirm/autoConfirm 触发的结算不同，退款完成后订单直接进终态 REFUNDED，不再结算。
     *
     * @param refundId 退款单 ID
     * @param adminId  审核管理员 ID
     * @throws BusinessException 退款单不存在或已被处理（status != 0）、订单不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void approveRefund(Long refundId, Long adminId) {
        RefundOrder refund = refundMapper.selectById(refundId);
        if (refund == null || refund.getStatus() != 0) {
            throw new BusinessException("退款单不存在或已处理");
        }
        // 只处理「申请中(0)」的退款单：重复审批/并发审批会被上方 status 校验挡住，避免二次退款。
        refund.setStatus(3);
        refund.setOperatorId(adminId);
        refund.setFinishTime(LocalDateTime.now());
        refundMapper.updateById(refund);

        Order order = orderService.requireOrder(refund.getOrderId());
        order.setStatus(OrderService.REFUNDED);
        orderMapper.updateById(order);
        orderService.log(order.getId(), OrderService.REFUNDING, OrderService.REFUNDED,
                "ADMIN", adminId, "退款完成 " + refund.getAmount() + " 元");
        notificationService.send(order.getUserId(), "REFUND", "退款到账",
                "订单 " + order.getOrderNo() + " 已退款 " + refund.getAmount() + " 元", order.getId());
    }

    /**
     * 管理员拒绝退款：退款单 0→2，订单 REFUNDING(8)→CANCELED(7)，写状态日志并通知用户。
     *
     * @param refundId 退款单 ID
     * @param adminId  审核管理员 ID
     * @param reason   拒绝原因，写回退款单 reason 与状态日志
     * @throws BusinessException 退款单不存在或已被处理、订单不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectRefund(Long refundId, Long adminId, String reason) {
        RefundOrder refund = refundMapper.selectById(refundId);
        if (refund == null || refund.getStatus() != 0) {
            throw new BusinessException("退款单不存在或已处理");
        }
        // 拒绝退款同样只允许处理状态 0 的申请；订单回到 CANCELED(7)，不保留 REFUNDING 中间态。
        refund.setStatus(2);
        refund.setOperatorId(adminId);
        refund.setReason(reason);
        refund.setFinishTime(LocalDateTime.now());
        refundMapper.updateById(refund);

        Order order = orderService.requireOrder(refund.getOrderId());
        order.setStatus(OrderService.CANCELED);
        orderMapper.updateById(order);
        orderService.log(order.getId(), OrderService.REFUNDING, OrderService.CANCELED,
                "ADMIN", adminId, "退款被拒绝：" + reason);
        notificationService.send(order.getUserId(), "REFUND", "退款被拒绝",
                "订单 " + order.getOrderNo() + " 退款申请被拒绝：" + reason, order.getId());
    }

    /**
     * 按 payNo 查支付单，供 mockPay 与 handleCallback 复用，集中处理「支付单不存在」。
     *
     * @param payNo 支付单号
     * @return 支付单实体
     * @throws BusinessException 支付单不存在
     */
    public PaymentOrder requirePayment(String payNo) {
        PaymentOrder payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentOrder>()
                .eq(PaymentOrder::getPayNo, payNo));
        if (payment == null) {
            throw new BusinessException("支付单不存在");
        }
        return payment;
    }

    /**
     * 生成模拟渠道签名：MD5(payNo|金额|status|共享密钥)。
     * <p>
     * 面试要点：金额统一 setScale(2, HALF_UP) 后再拼接，否则 100 与 100.00 会算出不同签名；
     * 这是模拟网关，真实渠道应为非对称验签，密钥也应放密钥管理系统而非配置文件。
     *
     * @param payNo  支付单号
     * @param amount 支付金额
     * @param status 渠道回传的状态串
     * @return 32 位小写 MD5 摘要
     */
    public String sign(String payNo, BigDecimal amount, String status) {
        // 用 setScale(2, HALF_UP).toPlainString() 归一化金额，保证回调方与网关算出同一个签名；
        // toPlainString 避免 BigDecimal 科学计数法让签名串不一致。
        String raw = payNo + "|" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                + "|" + status + "|" + properties.getPay().getSecret();
        return SecureUtil.md5(raw);
    }
}
