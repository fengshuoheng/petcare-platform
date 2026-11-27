package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单 Mapper，对应 orders 表，是交易域的核心 Mapper。
 * <p>
 * 除 BaseMapper 的单表 CRUD 外，这里只放运营看板需要的两条聚合统计；
 * 带业务条件的查询（按用户/服务者/状态分页、超时未支付扫描、自动确认扫描等）
 * 都在 OrderService 用 LambdaQueryWrapper 构造，不下沉到 SQL，便于状态机规则集中维护。
 */
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 统计某时间点以来的实收 GMV（后台看板用）。
     * <p>
     * 只累加已有 pay_time 的订单，并用 status NOT IN (7, 9, 10) 排除
     * 7 已取消 / 9 已退款 / 10 已关闭：这三类是未成交或已退钱的终结态，计入会虚高营收；
     * 用「排除无效态」而非「枚举有效态」，将来新增中间状态时这条 SQL 也不必改。
     * IFNULL 保证区间内无支付单时返回 0 而非 null。
     */
    @Select("SELECT IFNULL(SUM(pay_amount), 0) FROM orders " +
            "WHERE pay_time >= #{start} AND status NOT IN (7, 9, 10)")
    BigDecimal sumPaidSince(@Param("start") LocalDateTime start);

    /** 统计某时间点以来创建的订单数（含未支付），用于看板「今日订单量」 */
    @Select("SELECT COUNT(*) FROM orders WHERE create_time >= #{start}")
    long countSince(@Param("start") LocalDateTime start);
}
