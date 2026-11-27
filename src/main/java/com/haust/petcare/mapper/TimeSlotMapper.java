package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.TimeSlot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 时间槽 Mapper，对应 time_slot 表，是本项目「时间槽并发抢占」的核心。
 * <p>
 * 与其余只继承 BaseMapper 的 Mapper 不同，这里必须写自定义 SQL：抢槽要求
 * 「判断 + 更新」在一个原子操作里完成，先 select 再 update 会被并发请求同时抢到同一个槽。
 * <p>
 * 各语句的设计意图：
 * - batchInsertIgnore：按排班批量补槽，INSERT IGNORE 撞上 uk_staff_start(staff_id, slot_start)
 *   唯一索引时静默跳过，使「每天重复生成」幂等、不会产生重复槽；
 * - lock：条件 UPDATE ... WHERE id = ? AND status = 0 相当于一次 CAS，借 InnoDB 行锁保证原子，
 *   只有仍为「可约」的槽才会被锁定，返回影响行数 1 才算抢到、0 表示已被他人抢占
 *   （SlotService.lock 据此抛「该时间刚被其他人预约」）；
 * - confirmByOrder / releaseByOrder：支付成功把「锁定」转「已约」、取消/超时把槽释放回「可约」，
 *   WHERE 带 status 条件避免误改已成交的槽；
 * - listAvailable：JOIN staff 查可约库存，WHERE s.status = 0（只卖可约槽）、
 *   st.status = 1（只卖可接单的服务者）、FIND_IN_SET(size, st.size_types)（服务者能服务该体型），
 *   再按评分降序，共同保证推荐出来的槽当下确实可下单。
 */
public interface TimeSlotMapper extends BaseMapper<TimeSlot> {

    /**
     * 批量生成时间槽，唯一索引保证重复执行不会产生重复槽。
     */
    @Insert("<script>INSERT IGNORE INTO time_slot (staff_id, slot_start, slot_end, status) VALUES " +
            "<foreach collection='list' item='s' separator=','>" +
            "(#{s.staffId}, #{s.slotStart}, #{s.slotEnd}, 0)" +
            "</foreach></script>")
    int batchInsertIgnore(@Param("list") List<TimeSlot> slots);

    /**
     * 抢占时间槽：只有 FREE 才能被锁定，返回 1 表示抢到。
     */
    @Update("UPDATE time_slot SET status = 1, order_id = #{orderId}, lock_expire_at = #{expireAt} " +
            "WHERE id = #{id} AND status = 0")
    int lock(@Param("id") Long id, @Param("orderId") Long orderId,
             @Param("expireAt") LocalDateTime expireAt);

    /** 支付成功后把该订单锁定的槽由 1 锁定 置为 2 已约，并清掉锁定过期时间（保留 order_id 关联） */
    @Update("UPDATE time_slot SET status = 2, lock_expire_at = NULL WHERE order_id = #{orderId} AND status = 1")
    int confirmByOrder(@Param("orderId") Long orderId);

    /** 取消/超时/改约时按订单释放槽：状态回 0 可约，并清空 order_id 与锁定过期时间 */
    @Update("UPDATE time_slot SET status = 0, order_id = NULL, lock_expire_at = NULL WHERE order_id = #{orderId}")
    int releaseByOrder(@Param("orderId") Long orderId);

    /**
     * 可预约时间槽：按日期与服务者体型能力筛选。
     */
    @Select("SELECT s.id, s.staff_id AS staffId, s.slot_start AS slotStart, s.slot_end AS slotEnd, " +
            "st.name AS staffName, st.rating AS staffRating, st.level AS staffLevel " +
            "FROM time_slot s JOIN staff st ON s.staff_id = st.id " +
            "WHERE s.status = 0 AND st.status = 1 AND s.slot_start >= #{start} AND s.slot_start < #{end} " +
            "AND FIND_IN_SET(#{size}, st.size_types) " +
            "ORDER BY s.slot_start, st.rating DESC")
    List<Map<String, Object>> listAvailable(@Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end,
                                            @Param("size") String size);
}
