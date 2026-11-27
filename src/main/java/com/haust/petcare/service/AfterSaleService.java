package com.haust.petcare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.Complaint;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.Review;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.mapper.ComplaintMapper;
import com.haust.petcare.mapper.ReviewMapper;
import com.haust.petcare.mapper.StaffMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后域：订单完成后的评价、投诉与差评通知，对应 review（uk_order 保证一单一评）、
 * complaint、staff（评分回填）、notification。
 * <p>
 * 链路位置：主链路末端「评价/投诉退款」环节；只有 OrderService.FINISHED(6) 的订单才能评价。
 * 售后退款单本身由 OrderService.cancel 生成（按违约金扣减），由 PaymentService.approveRefund /
 * rejectRefund 审核，对应订单 REFUNDING(8) → REFUNDED(9) / CANCELED(7)；本类不直接改订单状态。
 */
@Service
public class AfterSaleService {

    private final ReviewMapper reviewMapper;
    private final ComplaintMapper complaintMapper;
    private final StaffMapper staffMapper;
    private final OrderService orderService;
    private final NotificationService notificationService;

    /**
     * 注入评价/投诉/服务者 Mapper 与订单、通知服务；单例无状态，评分重算在事务内完成。
     */
    public AfterSaleService(ReviewMapper reviewMapper, ComplaintMapper complaintMapper,
                            StaffMapper staffMapper, OrderService orderService,
                            NotificationService notificationService) {
        this.reviewMapper = reviewMapper;
        this.complaintMapper = complaintMapper;
        this.staffMapper = staffMapper;
        this.orderService = orderService;
        this.notificationService = notificationService;
    }

    /**
     * 用户评价订单：写评价、刷新服务者评分，低分（<=2 星）额外通知服务者。
     * <p>
     * 链路：订单 FINISHED(6)（用户确认或 24h 自动确认）之后；一单只允许评价一次。
     *
     * @param userId  当前用户（必须是订单本人）
     * @param request 评价请求：orderId、rating、tags、content
     * @throws BusinessException 订单不存在/非本人、订单未完成、该订单已评价
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Long userId, Requests.ReviewRequest request) {
        Order order = orderService.requireOrder(request.getOrderId());
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        // 前置校验：只有已完成(6)的订单能评价，未完成的连入口都不给，避免提前刷分。
        if (order.getStatus() != OrderService.FINISHED) {
            throw new BusinessException("订单完成后才能评价");
        }
        // 防重复评价：先按 orderId 查数量，数据库 review.uk_order(order_id) 唯一索引再兜底；
        // 双保险防止用户并发重复提交产生两条评价、把平均分算高。
        Long exists = reviewMapper.selectCount(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, order.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException("该订单已评价");
        }
        Review review = new Review();
        review.setOrderId(order.getId());
        review.setUserId(userId);
        review.setStaffId(order.getStaffId());
        review.setRating(request.getRating());
        review.setTags(request.getTags());
        review.setContent(request.getContent());
        review.setCreateTime(LocalDateTime.now());
        reviewMapper.insert(review);

        // 评价落库后立刻重算服务者平均分，保证列表/派单展示的评分是最新值。
        refreshStaffRating(order.getStaffId());
        if (request.getRating() <= 2) {
            notificationService.send(order.getStaffId() == null ? null : staffUserId(order.getStaffId()),
                    "REVIEW", "收到差评", "订单 " + order.getOrderNo() + " 获得 " + request.getRating() + " 星评价",
                    order.getId());
        }
    }

    /**
     * 查某服务者的最近 50 条评价，按时间倒序，供服务者主页展示。
     *
     * @param staffId 服务者 ID
     * @return 评价列表（最多 50 条）
     */
    public List<Review> listByStaff(Long staffId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getStaffId, staffId)
                .orderByDesc(Review::getCreateTime)
                .last("limit 50"));
    }

    /**
     * 提交投诉：建一条 status=0（待受理）的投诉单并通知用户「已受理」。
     * <p>
     * 链路：主链路末端售后入口；投诉本身不改订单状态，处理结果由 resolveComplaint 回填。
     *
     * @param userId  当前用户（必须是订单本人）
     * @param request 投诉请求：orderId、type、content、images
     * @throws BusinessException 订单不存在/非本人
     */
    public void createComplaint(Long userId, Requests.ComplaintRequest request) {
        Order order = orderService.requireOrder(request.getOrderId());
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        Complaint complaint = new Complaint();
        complaint.setOrderId(order.getId());
        complaint.setUserId(userId);
        complaint.setType(request.getType());
        complaint.setContent(request.getContent());
        complaint.setImages(request.getImages());
        // 投诉单初始状态 0 待受理（1 处理中 / 2 已解决 / 3 已关闭）；只落库与通知，不动订单状态。
        complaint.setStatus(0);
        complaint.setCreateTime(LocalDateTime.now());
        complaintMapper.insert(complaint);
        notificationService.send(order.getUserId(), "COMPLAINT", "投诉已受理",
                "投诉单 #" + complaint.getId() + " 已提交，客服会在 24 小时内处理", order.getId());
    }

    /**
     * 投诉列表：管理员看全部，普通用户只看自己的，按创建时间倒序。
     *
     * @param userId 当前用户 ID
     * @param admin  是否管理员
     * @return 投诉单列表
     */
    public List<Complaint> listComplaints(Long userId, boolean admin) {
        LambdaQueryWrapper<Complaint> wrapper = new LambdaQueryWrapper<Complaint>()
                .orderByDesc(Complaint::getCreateTime);
        if (!admin) {
            wrapper.eq(Complaint::getUserId, userId);
        }
        return complaintMapper.selectList(wrapper);
    }

    /**
     * 管理员处理投诉：回填处理结果、状态与完成时间，并通知用户。
     * <p>
     * 链路：客服后台对投诉单收尾；这里不自动触发退款，需要退款仍走退款单审核流程。
     *
     * @param id      投诉单 ID
     * @param request 处理结果：result、可选 status（不传默认 2 已解决）
     * @param adminId 处理管理员 ID（当前实现仅作入参留存，未写入投诉单）
     * @throws BusinessException 投诉单不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolveComplaint(Long id, Requests.ComplaintResolveRequest request, Long adminId) {
        Complaint complaint = complaintMapper.selectById(id);
        if (complaint == null) {
            throw new BusinessException("投诉单不存在");
        }
        complaint.setResult(request.getResult());
        // 处理结果默认置 2（已解决）；管理员也可显式传 status 改成 3 已关闭等。
        complaint.setStatus(request.getStatus() == null ? 2 : request.getStatus());
        complaint.setFinishTime(LocalDateTime.now());
        complaintMapper.updateById(complaint);
        notificationService.send(complaint.getUserId(), "COMPLAINT", "投诉处理完成",
                "投诉单 #" + id + " 处理结果：" + request.getResult(), complaint.getOrderId());
    }

    private void refreshStaffRating(Long staffId) {
        List<Review> reviews = reviewMapper.selectList(new LambdaQueryWrapper<Review>()
                .eq(Review::getStaffId, staffId));
        if (reviews.isEmpty()) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Review review : reviews) {
            sum = sum.add(BigDecimal.valueOf(review.getRating()));
        }
        // 全量重算平均分：评价量小，按 staffId 查一次重算即可，避免维护增量字段产生不一致；
        // BigDecimal.divide(…, 2, HALF_UP) 保留两位，避免浮点误差与无限小数。
        BigDecimal avg = sum.divide(BigDecimal.valueOf(reviews.size()), 2, RoundingMode.HALF_UP);
        staffMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Staff>()
                .set(Staff::getRating, avg)
                .eq(Staff::getId, staffId));
    }

    // 差评通知要发到服务者绑定的 app_user.id；未绑定账号时返回 null，NotificationService.send 会跳过。
    private Long staffUserId(Long staffId) {
        Staff staff = staffMapper.selectById(staffId);
        return staff == null ? null : staff.getUserId();
    }
}
