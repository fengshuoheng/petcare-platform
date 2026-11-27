package com.haust.petcare.controller;

import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.PaymentOrder;
import com.haust.petcare.service.PaymentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

/**
 * 支付入口（用户端 + 支付渠道回调业务域）。
 * <p>
 * 架构位置：/api 下，绝大多数接口受 AuthInterceptor 保护；唯独 /api/pay/callback 在 WebConfig 中放行，
 * 因为支付网关回调是服务器到服务器、没有登录态，靠请求体里的签名验签识别合法性。
 * 本项目用模拟网关：create 生成支付单与 mock-pay 链接，mockPay 构造签名后复用真实回调逻辑 handleCallback，
 * 验签 / 金额校验 / 幂等 / 订单状态推进都在 PaymentService，Controller 只做转发与 Result 包装。
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * 为订单创建支付单。
     * URL: POST /api/payments；调用方：用户端（已登录）。
     * 入参：请求体 Map 取 orderId（未加 @Valid，orderId 缺失由 Service 查库时体现）。
     * 权限：需登录态；PaymentService.create 再校验订单归属、WAIT_PAY 状态与支付是否超时。
     * 返回：payNo / amount / mock / payUrl（模拟支付跳转地址）。
     */
    @PostMapping("/payments")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Long> body) {
        Long orderId = body.get("orderId");
        return Result.ok(paymentService.create(orderId, UserContext.userId()));
    }

    /**
     * 模拟用户完成支付（演示用，替代真实渠道跳转）。
     * URL: POST /api/payments/{payNo}/mock-pay；调用方：用户端（已登录，前端点「模拟支付」）。
     * 入参：路径 payNo；Service 用支付单金额和密钥算出签名，再走与真实渠道完全相同的 handleCallback。
     * 权限：需登录态且校验订单归属。返回：回调处理结果 Map。
     */
    @PostMapping("/payments/{payNo}/mock-pay")
    public Result<Map<String, Object>> mockPay(@PathVariable String payNo) {
        return Result.ok(paymentService.mockPay(payNo, UserContext.userId()));
    }

    /**
     * 模拟渠道回调：真实接入时由支付渠道调用，需放在鉴权白名单中。
     */
    // URL: POST /api/pay/callback；调用方：支付网关 / 渠道服务器（无登录态，WebConfig 已放行）。
    // 入参：PayCallbackRequest，@Valid 校验 payNo / amount / status / sign 非空。
    // 权限：不走 AuthInterceptor，靠 PaymentService.handleCallback 的签名验签 + 金额校验 + 幂等保证安全。
    // 返回：处理结果 Map（success / payNo / idempotent 等）。
    @PostMapping("/pay/callback")
    public Result<Map<String, Object>> callback(@Valid @RequestBody Requests.PayCallbackRequest request) {
        return Result.ok(paymentService.handleCallback(request));
    }

    /**
     * 查询支付单详情。
     * URL: GET /api/payments/{payNo}；调用方：用户端（已登录）。
     * 入参：路径 payNo。
     * 权限：需登录态（路径在 /api/** 内，由 AuthInterceptor 校验 token）；Service 按 payNo 查，查不到抛业务异常。
     * 返回：PaymentOrder。
     */
    @GetMapping("/payments/{payNo}")
    public Result<PaymentOrder> detail(@PathVariable String payNo) {
        return Result.ok(paymentService.requirePayment(payNo));
    }
}
