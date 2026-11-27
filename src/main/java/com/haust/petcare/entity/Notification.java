package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知，对应表 notification，属于「消息通知」支撑环节。
 * <p>
 * 订单状态变化、支付成功、退款到账、差评、投诉受理等事件统一由 NotificationService.send
 * 写入；biz_id 关联订单等业务主键，便于从通知跳回详情。(user_id, is_read) 联合索引
 * 支撑「我的未读消息」列表查询。
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 接收用户，(user_id, is_read) 联合索引支撑未读列表查询 */
    private Long userId;
    /** 通知类型：ORDER/PAY/REFUND/REVIEW/COMPLAINT/URGE 等 */
    private String type;
    /** 通知标题 / 正文 */
    private String title;
    private String content;
    /** 关联业务 id（通常是订单 id），前端据此跳转详情 */
    private Long bizId;
    /** 是否已读：0 未读 1 已读 */
    private Integer isRead;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
