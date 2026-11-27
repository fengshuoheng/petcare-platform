package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.entity.Complaint;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.RefundOrder;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.mapper.AiMessageMapper;
import com.haust.petcare.mapper.AppUserMapper;
import com.haust.petcare.mapper.ComplaintMapper;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.RefundOrderMapper;
import com.haust.petcare.mapper.StaffMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台运营看板与统计服务，对应 orders、app_user、staff、refund_order、complaint、ai_message
 * 的只读聚合；不写业务表，不参与下单主链路。
 * <p>
 * 链路位置：AdminController 的看板/报表入口；讲「订单 11 态分布」时可结合
 * OrderService 的状态常量与 dashboard() 的 statusDistribution 一起说明。
 */
@Service
public class StatsService {

    private final OrderMapper orderMapper;
    private final AppUserMapper userMapper;
    private final StaffMapper staffMapper;
    private final RefundOrderMapper refundMapper;
    private final ComplaintMapper complaintMapper;
    private final AiMessageMapper aiMessageMapper;
    private final PetcareProperties properties;

    /**
     * 注入各业务表 Mapper 与配置（门店名展示用）；全部只读，单例无状态。
     */
    public StatsService(OrderMapper orderMapper, AppUserMapper userMapper, StaffMapper staffMapper,
                        RefundOrderMapper refundMapper, ComplaintMapper complaintMapper,
                        AiMessageMapper aiMessageMapper, PetcareProperties properties) {
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.staffMapper = staffMapper;
        this.refundMapper = refundMapper;
        this.complaintMapper = complaintMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.properties = properties;
    }

    /**
     * 运营看板聚合：用户/服务者/今日订单与 GMV/总订单/完成与取消数/待处理退款/未结投诉/
     * 今日 AI 提问数/门店名，以及 11 态订单分布。
     *
     * @return 看板各项指标（LinkedHashMap，顺序稳定）
     */
    public Map<String, Object> dashboard() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("users", userMapper.selectCount(null));
        map.put("staffs", staffMapper.selectCount(null));
        map.put("todayOrders", orderMapper.countSince(today));
        // 今日 GMV 走自定义 SQL 只累加已支付金额，不把待支付/已关闭订单算进成交额。
        map.put("todayGmv", orderMapper.sumPaidSince(today));
        map.put("totalOrders", orderMapper.selectCount(null));
        map.put("finishedOrders", orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderService.FINISHED)));
        // 「取消」合并三个终态 CANCELED(7)/REFUNDED(9)/CLOSED(10)，口径与订单状态机一致。
        map.put("canceledOrders", orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, OrderService.CANCELED, OrderService.REFUNDED, OrderService.CLOSED)));
        map.put("pendingRefunds", refundMapper.selectCount(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getStatus, 0)));
        map.put("openComplaints", complaintMapper.selectCount(new LambdaQueryWrapper<Complaint>()
                .in(Complaint::getStatus, 0, 1)));
        // AI 提问量只统计 role=user 的消息，避免把助手回答也算成一次提问。
        map.put("aiToday", aiMessageMapper.selectCount(new LambdaQueryWrapper<com.haust.petcare.entity.AiMessage>()
                .eq(com.haust.petcare.entity.AiMessage::getRole, "user")
                .ge(com.haust.petcare.entity.AiMessage::getCreateTime, today)));
        map.put("storeName", properties.getStore().getName());

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        // 遍历 0~10 逐个状态计数，只保留数量 >0 的状态，得到订单 11 态分布；
        // 与 OrderService.STATUS_TEXT 一一对应，看板直接据此渲染。
        for (int i = 0; i <= 10; i++) {
            long count = orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getStatus, i));
            if (count > 0) {
                distribution.put(i, count);
            }
        }
        map.put("statusDistribution", distribution);
        return map;
    }

    /**
     * 统计某服务者指定状态的订单数，供服务者维度报表使用。
     *
     * @param staffId 服务者 ID
     * @param status  订单状态码
     * @return 订单数
     */
    public long staffOrderCount(Long staffId, int status) {
        return orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getStaffId, staffId)
                .eq(Order::getStatus, status));
    }

    /**
     * 返回全部服务者，供管理端下拉/报表选择；只读查询。
     *
     * @return 服务者列表（Iterable）
     */
    public Iterable<Staff> staffList() {
        return staffMapper.selectList(null);
    }
}
