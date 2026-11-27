package com.haust.petcare.controller;

import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知（用户端业务域）。
 * <p>
 * 架构位置：/api/notifications 下的展示与已读接口，受 AuthInterceptor 保护，按 UserContext.userId() 隔离。
 * 通知由订单流转、支付、退款等 Service 在关键节点写入，本类只负责读取与标记已读，不生产通知。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 我的通知列表（最近 30 条）。
     * URL: GET /api/notifications；调用方：用户端（已登录）。
     * 入参：无；数量上限 30 由 Service 固定，避免一次拉全表。
     * 权限：需登录态；按当前用户过滤。返回：通知列表。
     */
    @GetMapping
    public Result<?> list() {
        return Result.ok(notificationService.list(UserContext.userId(), 30));
    }

    /**
     * 未读通知数（用于前端红点角标）。
     * URL: GET /api/notifications/unread；调用方：用户端（已登录）。
     * 入参：无。权限：需登录态；按当前用户统计。返回：未读条数 Long。
     */
    @GetMapping("/unread")
    public Result<Long> unread() {
        return Result.ok(notificationService.unread(UserContext.userId()));
    }

    /**
     * 标记单条通知为已读。
     * URL: POST /api/notifications/{id}/read；调用方：用户端（已登录）。
     * 入参：路径 id；userId 一并传给 Service，确保只能改自己的通知，防止越权标记他人消息。
     * 权限：需登录态。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(UserContext.userId(), id);
        return Result.ok();
    }
}
