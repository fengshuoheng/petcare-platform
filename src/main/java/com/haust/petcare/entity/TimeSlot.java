package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可预约时间槽，对应表 time_slot，是本项目并发控制的核心。
 * <p>
 * 每个服务者每 60 分钟一个槽，(staff_id, slot_start) 唯一索引 uk_staff_start
 * 保证批量生成（INSERT IGNORE）幂等、同一服务者同一时段不会重复。
 * 抢占靠条件 UPDATE：只有 status=0（可约）才能改为 1（锁定）并绑定 order_id，
 * 一条 SQL 借行锁保证原子性，影响行数为 0 即表示被他人抢走（见 TimeSlotMapper.lock）；
 * 支付成功转 2（已约），取消/超时释放回 0，lock_expire_at 用于兜底回收过期锁。
 */
@Data
@TableName("time_slot")
public class TimeSlot {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 服务者 id，与 slot_start 组成唯一索引 uk_staff_start，防同一时段重复生成/重复占用 */
    private Long staffId;
    /** 时段起止，粒度 60 分钟 */
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;
    /** 0 可约 1 锁定 2 已约 */
    private Integer status;
    /** 锁定该槽的订单 id；下单抢占时写入，取消/超时释放时清空 */
    private Long orderId;
    /** 锁定过期时间，与支付超时一致；过期锁由定时任务回收，避免热门时段被长期占用 */
    private LocalDateTime lockExpireAt;
}
