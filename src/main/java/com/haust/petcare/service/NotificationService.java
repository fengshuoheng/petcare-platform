package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.entity.Notification;
import com.haust.petcare.mapper.NotificationMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知服务，对应 notification 表；被订单、支付、售后、AI 等服务在状态变更后调用，
 * 是主链路各节点的旁路（不参与订单状态机），只做持久化落库，不接第三方推送。
 * <p>
 * 链路位置：下单改约/取消、支付成功、退款、服务者接单/出发、差评、投诉处理等节点均通过 send()
 * 写一条带 userId + type + bizId 的通知；客户端用 list / unread / markRead 拉取与标记。
 */
@Service
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /**
     * 注入通知 Mapper；单例无状态，通知表只做 append 与已读更新。
     */
    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 写一条站内通知。userId 为 null 时直接跳过（如服务者未绑定账号），避免产生无主通知。
     * <p>
     * 链路：各业务 Service 在状态变更后调用；这是旁路操作，不构成订单状态流转的一部分。
     *
     * @param userId  接收用户 ID，可为 null
     * @param type    通知类型：ORDER / PAY / REFUND / URGE / REVIEW / COMPLAINT 等
     * @param title   标题
     * @param content 正文
     * @param bizId   关联业务 ID（订单/投诉单等），便于客户端跳转
     */
    public void send(Long userId, String type, String title, String content, Long bizId) {
        // 服务者未绑定 app_user 时 staffUserId() 会返回 null，这里静默跳过而不抛错，
        // 不能让一条发不出去的通知把订单/支付主流程带崩。
        if (userId == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setBizId(bizId);
        // 新通知统一未读(is_read=0)，createTime 取服务器时间，客户端按时间倒序拉取。
        notification.setIsRead(0);
        notification.setCreateTime(LocalDateTime.now());
        notificationMapper.insert(notification);
    }

    /**
     * 拉取某用户最近通知，按创建时间倒序；limit 上限 100，防止一次拉全表。
     *
     * @param userId 用户 ID
     * @param limit  期望条数（超过 100 按 100 截断）
     * @return 通知列表
     */
    public List<Notification> list(Long userId, int limit) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .last("limit " + Math.min(limit, 100)));
    }

    /**
     * 统计未读通知数（is_read=0），供客户端红点角标使用。
     *
     * @param userId 用户 ID
     * @return 未读条数
     */
    public long unread(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
    }

    /**
     * 标记单条通知已读：先校验通知存在且属于当前用户，再条件更新 is_read=1。
     *
     * @param userId 当前用户 ID
     * @param id     通知 ID
     * @throws BusinessException 通知不存在或不属于当前用户（防止越权标记他人通知）
     */
    public void markRead(Long userId, Long id) {
        Notification notification = notificationMapper.selectById(id);
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw new BusinessException("通知不存在");
        }
        // 用条件 UPDATE 按 id 精确置 is_read=1；归属校验已在上面做过，避免越权标记。
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .set(Notification::getIsRead, 1)
                .eq(Notification::getId, id));
    }
}
