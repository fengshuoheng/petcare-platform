package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Notification;

/**
 * 站内通知 Mapper，对应 notification 表，服务于各类事件后的消息写入与未读列表查询。
 * <p>
 * 单表 CRUD 走 BaseMapper；「某用户的未读通知」由 (user_id, is_read) 联合索引支撑，
 * 查询条件在 NotificationService 用 LambdaQueryWrapper 构造，本接口无需自定义 SQL。
 */
public interface NotificationMapper extends BaseMapper<Notification> {
}
