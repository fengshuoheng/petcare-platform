package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.entity.StaffSchedule;
import com.haust.petcare.entity.TimeSlot;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.mapper.StaffMapper;
import com.haust.petcare.mapper.StaffScheduleMapper;
import com.haust.petcare.mapper.TimeSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 时间槽（可约库存）服务：按排班批量生成槽、下单时抢占、支付成功后确认、取消/超时释放。
 * <p>
 * 涉及表：time_slot（uk_staff_start(staff_id, slot_start) 唯一索引保证生成幂等；
 * status 0可约/1锁定/2已约，lock_expire_at 记录锁定到期）、staff、staff_schedule、pet。
 * <p>
 * 链路位置：查可约时间槽（listByDate）→ 下单抢槽（OrderService.create 调 lock）→ 支付成功确认
 * （PaymentService.handleCallback 调 confirm）→ 取消/改约/超时释放（release / expireStaleLocks）。
 * 由 SlotInitializer 启动时与 ScheduledJobs 每天凌晨调用 generateNextDays 补槽。
 * <p>
 * 面试要点：抢槽用「条件 UPDATE ... WHERE id=? AND status=0」而非「先查后改」，
 * 靠 MySQL 行锁 + 影响行数实现原子抢占，项目未引入 Redisson/分布式锁；
 * 锁定带过期时间，定时任务兜底释放防止槽被永久占用。
 */
@Service
public class SlotService {

    private static final Logger log = LoggerFactory.getLogger(SlotService.class);

    private final TimeSlotMapper slotMapper;
    private final StaffMapper staffMapper;
    private final StaffScheduleMapper scheduleMapper;
    private final PetMapper petMapper;
    private final PriceService priceService;

    /**
     * 注入时间槽/服务者/排班/宠物 Mapper 与 PriceService（求体型档位）；单例无状态。
     */
    public SlotService(TimeSlotMapper slotMapper, StaffMapper staffMapper,
                       StaffScheduleMapper scheduleMapper, PetMapper petMapper,
                       PriceService priceService) {
        this.slotMapper = slotMapper;
        this.staffMapper = staffMapper;
        this.scheduleMapper = scheduleMapper;
        this.petMapper = petMapper;
        this.priceService = priceService;
    }

    /**
     * 按排班生成未来 N 天的 60 分钟时间槽，唯一索引保证幂等。
     */
    @Transactional
    public int generateNextDays(int days) {
        // 只给未封禁(status!=3)的服务者生成槽；排班表再按 weekday 匹配且 status=1 启用。
        List<Staff> staffs = staffMapper.selectList(new LambdaQueryWrapper<Staff>()
                .ne(Staff::getStatus, 3));
        if (staffs.isEmpty()) {
            return 0;
        }
        List<StaffSchedule> schedules = scheduleMapper.selectList(null);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<TimeSlot> batch = new ArrayList<>();

        // 三层循环：未来每一天 → 每个服务者 → 每条启用且星期匹配的排班。
        // 只生成未来时间，历史槽不做补写，避免把已过去的时段重新放出来。
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            int weekday = date.getDayOfWeek().getValue();
            for (Staff staff : staffs) {
                for (StaffSchedule schedule : schedules) {
                    if (!schedule.getStaffId().equals(staff.getId())
                            || schedule.getWeekday() != weekday
                            || schedule.getStatus() == null || schedule.getStatus() != 1) {
                        continue;
                    }
                    LocalTime time = schedule.getStartTime();
                    // 排班区间按 1 小时步长切成 60 分钟时间槽（start.plusHours(1)）。
                    while (time.isBefore(schedule.getEndTime())) {
                        LocalDateTime start = date.atTime(time);
                        // 只保留尚未开始的时段，过去的时间不允许再被预约。
                        if (!start.isBefore(now)) {
                            TimeSlot slot = new TimeSlot();
                            slot.setStaffId(staff.getId());
                            slot.setSlotStart(start);
                            slot.setSlotEnd(start.plusHours(1));
                            batch.add(slot);
                        }
                        time = time.plusHours(1);
                    }
                }
            }
        }

        // 每 200 条一批提交，控制单条 INSERT 的长度；
        // batchInsertIgnore 底层是 INSERT IGNORE，配合 time_slot.uk_staff_start(staff_id, slot_start)
        // 唯一索引：定时任务重跑、多实例同时生成、启动预热重复执行都不会产生重复槽，天然幂等。
        int inserted = 0;
        for (int from = 0; from < batch.size(); from += 200) {
            int to = Math.min(from + 200, batch.size());
            inserted += slotMapper.batchInsertIgnore(batch.subList(from, to));
        }
        log.info("生成时间槽 {} 个（未来 {} 天）", inserted, days);
        return inserted;
    }

    /**
     * 查某天可约时间槽：先按宠物求体型档位，再交给 listAvailable 筛选。
     * <p>
     * 链路：用户选服务/宠物后、下单前的「查可约时间」入口。
     *
     * @param date  查询日期（当天 00:00 ~ 次日 00:00）
     * @param petId 宠物 ID，用于求体型并过滤服务者能力
     * @return 可约槽列表（含服务者姓名/评分/等级），按时间升序、评分降序
     * @throws BusinessException 宠物不存在
     */
    public List<Map<String, Object>> listByDate(LocalDate date, Long petId) {
        Pet pet = petMapper.selectById(petId);
        if (pet == null) {
            throw new BusinessException("宠物不存在");
        }
        String size = priceService.sizeOf(pet);
        // SQL 侧筛选条件：槽 status=0（可约）、服务者 status=1（可接单）、slot_start 落在当天范围、
        // FIND_IN_SET(size, staff.size_types)（该服务者支持当前宠物体型），
        // 并按 slot_start 升序、服务者评分降序，优先把评分高的服务者排在前面。
        LocalDateTime start = date.atStartOfDay();
        return slotMapper.listAvailable(start, start.plusDays(1), size);
    }

    /**
     * 按 ID 取时间槽，供下单/改约/详情复用，集中处理「时间段不存在」。
     *
     * @param slotId 时间槽 ID
     * @return 时间槽实体
     * @throws BusinessException 时间段不存在
     */
    public TimeSlot requireSlot(Long slotId) {
        TimeSlot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BusinessException("时间段不存在");
        }
        return slot;
    }

    /**
     * 原子抢占时间槽：0可约 → 1锁定，并写入 order_id 与锁过期时间。
     * <p>
     * 链路：OrderService.create 插订单成功后、reschedule 换新槽时调用；
     * 抢不到时抛异常，由调用方事务回滚订单插入。
     *
     * @param slotId   时间槽 ID
     * @param orderId  占用该槽的订单 ID
     * @param expireAt 锁过期时间（下单传支付截止时间，改约传 30 分钟后）
     * @throws BusinessException 该时间刚被别人预约（条件 UPDATE 影响 0 行）
     */
    public void lock(Long slotId, Long orderId, LocalDateTime expireAt) {
        // 原子抢槽：底层是条件 UPDATE time_slot SET status=1, order_id=?, lock_expire_at=?
        // WHERE id=? AND status=0，靠 MySQL 行锁保证多个下单请求只有一个能改到这一行，
        // 影响行数 0 表示被别人抢走，直接抛业务异常（在 OrderService 事务里触发整体回滚）。
        // 不用「先 SELECT 判 status 再 UPDATE」：那两步之间有时间窗，并发下会超卖；
        // 项目也没有 Redisson 分布式锁，用单行条件 UPDATE 是最轻量的互斥方案。
        if (slotMapper.lock(slotId, orderId, expireAt) == 0) {
            throw new BusinessException("该时间刚被其他人预约，请重新选择");
        }
    }

    /**
     * 支付成功后确认时间槽：1锁定 → 2已约，并清空锁过期时间。
     * <p>
     * 链路：PaymentService.handleCallback 在支付单 SUCCESS、订单离开 WAIT_PAY 之后调用。
     *
     * @param orderId 订单 ID（条件 UPDATE 按 order_id 匹配仍锁定 1 的槽）
     */
    public void confirm(Long orderId) {
        // 条件 UPDATE 带 status=1 与 order_id，只认本订单锁定的槽；
        // 锁若已过期被定时任务释放，该 UPDATE 影响 0 行，不会误改别人的槽。
        slotMapper.confirmByOrder(orderId);
    }

    /**
     * 释放时间槽：按 order_id 把槽置回 0可约，清空 order_id 与 lock_expire_at。
     * <p>
     * 链路：取消订单、改约释放旧槽、超时未支付关闭都复用它。
     *
     * @param orderId 订单 ID
     */
    public void release(Long orderId) {
        // 条件只按 order_id，不挑原状态：取消、改约、超时都走同一句；
        // 重复释放影响 0 行，天然幂等。
        slotMapper.releaseByOrder(orderId);
    }

    /**
     * 兜底释放过期锁定：把 status=1 且 lock_expire_at 已过的槽重置为 0可约。
     * <p>
     * 链路：由 ScheduledJobs.releaseStaleLocks 每分钟调用，是「槽不会永久被锁死」的最后防线。
     *
     * @return 本次释放的槽数量
     */
    public int expireStaleLocks() {
        // 正常路径由 closePayTimeoutOrders 关闭订单并 release，但服务重启、回调丢失或事务异常
        // 都可能让槽停在「锁定(1)」没人释放；没有这个每分钟的定时任务，槽就永久不可约了。
        // 条件带 status=1 与 lock_expire_at < now，重复执行影响 0 行，幂等安全。
        return slotMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TimeSlot>()
                .set(TimeSlot::getStatus, 0)
                .set(TimeSlot::getOrderId, null)
                .set(TimeSlot::getLockExpireAt, null)
                .eq(TimeSlot::getStatus, 1)
                .lt(TimeSlot::getLockExpireAt, LocalDateTime.now()));
    }
}
