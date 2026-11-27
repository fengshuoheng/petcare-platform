package com.haust.petcare.job;

import com.haust.petcare.service.OrderService;
import com.haust.petcare.service.SettlementService;
import com.haust.petcare.service.SlotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台定时任务（调度层）：把订单状态机里「没人点也会发生」的兜底动作集中在这里。
 * <p>
 * 架构位置：@Component + @Scheduled，由 Spring 内置的本地调度器在单个 JVM 内触发；
 * 业务逻辑全部委托 OrderService / SlotService / SettlementService，本类只决定「什么时候跑」并记日志。
 * 5 个任务：超时未支付关单、释放过期锁定的时间槽、24h 自动确认、T+7 结算转可提现、每天凌晨生成未来 14 天时间槽。
 * <p>
 * 为什么用 fixedDelay 而不是 fixedRate：fixedDelay 是「上一次执行结束后再等 N 毫秒」，
 * 这几个任务都是扫表批处理，单次耗时随数据量波动；fixedRate 会在上次没结束时就堆下一次执行，
 * 造成同一任务并发跑、重复扫表，fixedDelay 保证同一任务始终串行、更安全。
 * initialDelay 取 30s / 40s / 120s / 180s 且互不相同：既给应用启动、连接池预热留时间，
 * 又避免五个任务在启动瞬间同时抢 DB / Redis 连接，把执行时间点错开、削平瞬时压力。
 * 生成时间槽改用 cron "0 5 0 * * ?"（每天 00:05），因为「每天定点补槽」需要固定时刻，
 * 用固定延迟会随每次执行耗时不断漂移。
 * <p>
 * 多实例部署局限：@Scheduled 只是各 JVM 各自内存里的调度，没有分布式锁 / 选主（本项目未引入 Redisson、ShedLock 等），
 * 多副本部署时每个实例都会触发同一批任务。其中 expireStaleLocks、releaseDue 是「带状态条件的 UPDATE」、
 * generateSlots 靠唯一索引 + insert ignore，重复执行结果不变；但 closeTimeoutOrders、autoConfirm 是
 * 先查后逐条更新的循环，多实例并发下会重复扫描同一批数据、重复写 OrderStatusLog 状态日志。因此生产应只让一个实例跑定时任务，
 * 或引入分布式锁 / 分布式调度框架后再水平扩容。
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final OrderService orderService;
    private final SlotService slotService;
    private final SettlementService settlementService;

    public ScheduledJobs(OrderService orderService, SlotService slotService,
                         SettlementService settlementService) {
        this.orderService = orderService;
        this.slotService = slotService;
        this.settlementService = settlementService;
    }

    /*
     * 触发时机：启动 30s 后首跑，之后每次「执行完成」再等 60s（fixedDelay=60_000）跑一次。
     * 作用：把 WAIT_PAY 且 payExpireAt 已过期的订单置为 CLOSED 并释放其锁定的时间槽，
     * 回收「下单占坑却未付款」的库存，是支付超时兜底，与 petcare.order.pay-timeout-minutes 配套。
     * 多实例局限：先查后逐条更新的循环，多副本并发会重复扫描、重复写状态日志，见类注释。
     */
    /** 每分钟关闭超时未支付订单并释放时间槽 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeTimeoutOrders() {
        int count = orderService.closePayTimeoutOrders();
        if (count > 0) {
            log.info("关闭超时未支付订单 {} 单", count);
        }
    }

    /*
     * 触发时机：启动 40s 后首跑，之后每隔 60s（fixedDelay=60_000）；与关单任务错开 10s。
     * 作用：把 status=1（锁定中）且 lockExpireAt 已过期的槽恢复为可约（status=0，清空 orderId / lockExpireAt），
     * 兜底那些「锁定后订单既没支付成功也没被取消」而没走正常释放的槽。
     * 一条带状态条件的 UPDATE：某实例改完 status 后其它实例再跑 where 不再命中，重复执行天然幂等。
     */
    /** 每分钟兜底释放过期锁定的时间槽 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 40_000)
    public void releaseStaleLocks() {
        int count = slotService.expireStaleLocks();
        if (count > 0) {
            log.info("释放过期锁定时间槽 {} 个", count);
        }
    }

    /*
     * 触发时机：启动 120s 后首跑，之后每隔 10 分钟（fixedDelay=600_000）。
     * 作用：把 WAIT_CONFIRM 且 finishTime 早于「now - auto-confirm-hours（默认 24h）」的订单自动置为 FINISHED，
     * 并生成结算单，对应「用户不点确认、系统超时自动确认」的业务规则，与用户主动 /api/orders/{id}/confirm 互补。
     * 多实例局限：与关单任务一样是先查后逐条更新的循环，多副本并发会重复扫描、重复写状态日志。
     */
    /** 每 10 分钟自动确认完成 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void autoConfirm() {
        int count = orderService.autoConfirm();
        if (count > 0) {
            log.info("自动确认完成 {} 单", count);
        }
    }

    /*
     * 触发时机：启动 180s 后首跑，之后每隔 1 小时（fixedDelay=3_600_000）。
     * 作用：把 status=0 且 settleTime 已到期的结算单改为 status=1（可提现），实现 T+settle-days（默认 7 天）结算；
     * 服务者端 /api/staff/settlements 汇总的 withdrawable 正是 status=1 的 staffIncome 之和。
     * 一条带 status=0 条件的 UPDATE，重复执行幂等。
     */
    /** 每小时把到期结算单置为可提现 */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    public void releaseSettlement() {
        int count = settlementService.releaseDue();
        if (count > 0) {
            log.info("结算单转为可提现 {} 条", count);
        }
    }

    /*
     * 触发时机：cron "0 5 0 * * ?"，每天凌晨 00:05 定点执行一次（不用 fixedDelay，避免延迟随耗时漂移）。
     * 作用：按排班批量生成未来 14 天的 60 分钟时间槽；SlotService 用唯一索引 + insert ignore 保证幂等，
     * 因此与后台手动 /api/admin/slots/generate 或重复调度不会插入重复槽。
     */
    /** 每天凌晨补充未来 14 天的时间槽 */
    @Scheduled(cron = "0 5 0 * * ?")
    public void generateSlots() {
        slotService.generateNextDays(14);
    }
}
