package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.Settlement;
import com.haust.petcare.mapper.SettlementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算域：订单完成后按「实付 × 平台抽成」生成结算单，T+N 到期后置为可提现。
 * <p>
 * 涉及表：settlement（uk_order(order_id) 保证一单一结算）、orders；配置来自
 * petcare.settlement.commission-rate（默认 0.15）与 settle-days（默认 7，即 T+7）。
 * <p>
 * 链路位置：用户确认或 24h 自动确认把订单置 FINISHED(6) 后，由 OrderService.confirm /
 * autoConfirm 调用 createForOrder；每小时的 ScheduledJobs.releaseSettlement 调用 releaseDue 批量转可提现。
 * <p>
 * 面试要点：并发下避免重复结算靠「先按 orderId 查重 + settlement.uk_order 唯一索引兜底」，
 * 重复写入会因唯一键冲突失败（并随外层事务回滚），没有引入分布式锁；
 * releaseDue 用条件 UPDATE status 0→1 且 settle_time <= now，天然幂等。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementMapper settlementMapper;
    private final PetcareProperties properties;

    /**
     * 注入结算 Mapper 与配置（抽成比例、结算天数）；单例无状态。
     */
    public SettlementService(SettlementMapper settlementMapper, PetcareProperties properties) {
        this.settlementMapper = settlementMapper;
        this.properties = properties;
    }

    /**
     * 订单完成后生成结算单：平台抽成 = 实付 × 比例，T+N 后转为可提现。
     * <p>
     * 链路：OrderService.confirm / autoConfirm 在订单置 FINISHED 后调用；
     * 无服务者（未派单）的订单跳过，已有结算单直接返回（幂等）。
     *
     * @param order 已完成订单（读取 payAmount 与 staffId）
     */
    public void createForOrder(Order order) {
        // 未派单（staffId 为空）的订单没有服务者分成对象，直接跳过不生成结算单。
        if (order.getStaffId() == null) {
            return;
        }
        // 幂等两层：先按 orderId 查重做快速短路；并发下若两个请求都查不到，
        // settlement.uk_order(order_id) 唯一索引会让第二次 insert 冲突失败，
        // 且 createForOrder 在外层 @Transactional 的 confirm() 里被调用，冲突会随事务回滚，
        // 不会出现一单两份结算。项目没有分布式锁，靠唯一索引 + 事务保证。
        Long count = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, order.getId()));
        if (count != null && count > 0) {
            return;
        }
        BigDecimal amount = order.getPayAmount() == null ? BigDecimal.ZERO : order.getPayAmount();
        // 平台抽成 = 实付金额 × commissionRate（配置 petcare.settlement.commission-rate，默认 0.15），
        // HALF_UP 保留 2 位；服务者收入 = 实付 - 抽成，全程 BigDecimal 避免分厘误差。
        BigDecimal commission = amount.multiply(BigDecimal.valueOf(properties.getSettlement().getCommissionRate()))
                .setScale(2, RoundingMode.HALF_UP);
        Settlement settlement = new Settlement();
        settlement.setOrderId(order.getId());
        settlement.setStaffId(order.getStaffId());
        settlement.setOrderAmount(amount);
        settlement.setCommission(commission);
        settlement.setStaffIncome(amount.subtract(commission));
        settlement.setStatus(0);
        // T+7：结算时间 = 当前时间 + settleDays（默认 7 天），到期前 status=0 待结算；
        // 由 releaseDue 定时任务批量转成 1 可提现，中间这段时间给售后/投诉留出追回窗口。
        settlement.setSettleTime(LocalDateTime.now().plusDays(properties.getSettlement().getSettleDays()));
        settlement.setCreateTime(LocalDateTime.now());
        settlementMapper.insert(settlement);
        log.info("生成结算单 orderId={} staffId={} amount={}", order.getId(), order.getStaffId(), amount);
    }

    /**
     * 到期结算单批量转可提现：status 0→1，且 settle_time <= 当前时间。
     * <p>
     * 链路：由 ScheduledJobs.releaseSettlement 每小时调用；条件带 status=0，重复执行天然幂等。
     *
     * @return 本次转为可提现的结算单数量
     */
    public int releaseDue() {
        // 条件 UPDATE 自带 status=0 与 settle_time<=now：只处理到期且未释放的单，
        // 并发或定时任务重跑都不会把同一单重复置为可提现。
        return settlementMapper.update(null, new LambdaUpdateWrapper<Settlement>()
                .set(Settlement::getStatus, 1)
                .eq(Settlement::getStatus, 0)
                .le(Settlement::getSettleTime, LocalDateTime.now()));
    }

    /**
     * 查某服务者的结算单，按创建时间倒序，供服务者「收入明细」使用。
     *
     * @param staffId 服务者 ID
     * @return 结算单列表
     */
    public List<Settlement> listByStaff(Long staffId) {
        return settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStaffId, staffId)
                .orderByDesc(Settlement::getCreateTime));
    }

    /**
     * 按订单 ID 查结算单，供 OrderService.detail 聚合进订单详情；一单最多一条（uk_order）。
     *
     * @param orderId 订单 ID
     * @return 结算单，未生成时为 null
     */
    public Settlement findByOrder(Long orderId) {
        return settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, orderId));
    }
}
