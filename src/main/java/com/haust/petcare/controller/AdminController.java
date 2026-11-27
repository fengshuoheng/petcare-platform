package com.haust.petcare.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.Complaint;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.RefundOrder;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.mapper.ServiceItemMapper;
import com.haust.petcare.service.AfterSaleService;
import com.haust.petcare.service.OrderService;
import com.haust.petcare.service.PaymentService;
import com.haust.petcare.service.SlotService;
import com.haust.petcare.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 管理后台（管理端业务域）。
 * <p>
 * 架构位置：/api/admin 下，受 AuthInterceptor 保护，且每个接口开头都调 requireAdmin() 要求 role==2。
 * 本项目没有 Spring Security，权限判断是「登录态 + 手写 role 校验」，因此后台接口必须逐个显式调用该方法。
 * 覆盖：经营看板、全量订单查询/改派/取消、退款审核、服务项维护、投诉处理、手动生成时间槽。
 * 与用户端/服务者端复用同一套 OrderService / PaymentService，靠 admin 参数或独立方法放宽归属校验范围。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final StatsService statsService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final AfterSaleService afterSaleService;
    private final ServiceItemMapper serviceItemMapper;
    private final SlotService slotService;

    public AdminController(StatsService statsService, OrderService orderService,
                           PaymentService paymentService, AfterSaleService afterSaleService,
                           ServiceItemMapper serviceItemMapper, SlotService slotService) {
        this.statsService = statsService;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.afterSaleService = afterSaleService;
        this.serviceItemMapper = serviceItemMapper;
        this.slotService = slotService;
    }

    /**
     * 经营看板数据。
     * URL: GET /api/admin/stats；调用方：管理后台（已登录）。
     * 入参：无。权限：需管理员（requireAdmin，role==2）。返回：StatsService.dashboard() 汇总 Map。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        requireAdmin();
        return Result.ok(statsService.dashboard());
    }

    /**
     * 全量订单分页查询（管理视角，不受 userId 限制）。
     * URL: GET /api/admin/orders；调用方：管理后台（已登录）。
     * 入参：status（可选）、keyword（可选，模糊匹配订单号 / 宠物名）、current（默认 1）、size（默认 10，封顶 50）。
     * 权限：需管理员（role==2）。返回：Page<Order>。
     */
    @GetMapping("/orders")
    public Result<Page<Order>> orders(@RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") int current,
                                      @RequestParam(defaultValue = "10") int size) {
        requireAdmin();
        return Result.ok(orderService.adminPage(status, keyword, current, Math.min(size, 50)));
    }

    /**
     * 任意订单详情。
     * URL: GET /api/admin/orders/{id}；调用方：管理后台（已登录）。
     * 入参：路径 id；以 admin=true 调 detail，跳过用户归属校验。
     * 权限：需管理员（role==2）。返回：订单详情 Map。
     */
    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        requireAdmin();
        return Result.ok(orderService.detail(id, null, true));
    }

    /**
     * 改派订单给指定服务者（状态置回 WAIT_ACCEPT 并清空接单时间）。
     * URL: POST /api/admin/orders/{id}/reassign；调用方：管理后台（已登录）。
     * 入参：路径 id + ReassignRequest，@Valid 校验 staffId 非空。
     * 权限：需管理员（role==2）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/reassign")
    public Result<Void> reassign(@PathVariable Long id, @Valid @RequestBody Requests.ReassignRequest request) {
        requireAdmin();
        orderService.reassign(id, request.getStaffId());
        return Result.ok();
    }

    /**
     * 平台取消订单。
     * URL: POST /api/admin/orders/{id}/cancel；调用方：管理后台（已登录）。
     * 入参：路径 id + 可选 CancelRequest（reason），未传默认「平台取消」；admin=true 跳过归属校验。
     * 权限：需管理员（role==2）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) Requests.CancelRequest request) {
        requireAdmin();
        orderService.cancel(id, null, true, request == null ? "平台取消" : request.getReason());
        return Result.ok();
    }

    /**
     * 退款单列表。
     * URL: GET /api/admin/refunds；调用方：管理后台（已登录）。
     * 入参：status（可选，按状态过滤）。权限：需管理员（role==2）。返回：RefundOrder 列表。
     */
    @GetMapping("/refunds")
    public Result<List<RefundOrder>> refunds(@RequestParam(required = false) Integer status) {
        requireAdmin();
        return Result.ok(paymentService.listRefunds(status));
    }

    /**
     * 审核通过退款（订单置 REFUNDED）。
     * URL: POST /api/admin/refunds/{id}/approve；调用方：管理后台（已登录）。
     * 入参：路径 id；操作人取 UserContext.userId() 记入退款单。
     * 权限：需管理员（role==2）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/refunds/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        requireAdmin();
        paymentService.approveRefund(id, UserContext.userId());
        return Result.ok();
    }

    /**
     * 驳回退款申请（订单置 CANCELED）。
     * URL: POST /api/admin/refunds/{id}/reject；调用方：管理后台（已登录）。
     * 入参：路径 id + 请求体 Map 取 reason，缺省「平台驳回」；操作人取 UserContext.userId()。
     * 权限：需管理员（role==2）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/refunds/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requireAdmin();
        paymentService.rejectRefund(id, UserContext.userId(), body.getOrDefault("reason", "平台驳回"));
        return Result.ok();
    }

    /**
     * 全部服务项（含已下架，selectList(null) 不加条件）。
     * URL: GET /api/admin/services；调用方：管理后台（已登录）。
     * 入参：无。权限：需管理员（role==2）。返回：ServiceItem 列表。
     */
    @GetMapping("/services")
    public Result<List<ServiceItem>> services() {
        requireAdmin();
        return Result.ok(serviceItemMapper.selectList(null));
    }

    /**
     * 新增或编辑服务项（按 id 是否为 null 决定 insert / updateById）。
     * URL: POST /api/admin/services；调用方：管理后台（已登录）。
     * 入参：ServiceRequest，@Valid 校验 name、category 非空；durationMin / status / sort 为空时填默认值（60 / 1 / 99）。
     * 权限：需管理员（role==2）。返回：保存后的 ServiceItem。
     */
    @PostMapping("/services")
    public Result<ServiceItem> saveService(@Valid @RequestBody Requests.ServiceRequest request) {
        requireAdmin();
        ServiceItem item = new ServiceItem();
        item.setId(request.getId());
        item.setName(request.getName());
        item.setCategory(request.getCategory());
        item.setDescription(request.getDescription());
        item.setDurationMin(request.getDurationMin() == null ? 60 : request.getDurationMin());
        item.setPriceS(request.getPriceS());
        item.setPriceM(request.getPriceM());
        item.setPriceL(request.getPriceL());
        item.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        item.setSort(request.getSort() == null ? 99 : request.getSort());
        if (item.getId() == null) {
            serviceItemMapper.insert(item);
        } else {
            serviceItemMapper.updateById(item);
        }
        return Result.ok(item);
    }

    /**
     * 全量投诉列表（admin=true，不过滤提交人）。
     * URL: GET /api/admin/complaints；调用方：管理后台（已登录）。
     * 入参：无。权限：需管理员（role==2）。返回：Complaint 列表。
     */
    @GetMapping("/complaints")
    public Result<List<Complaint>> complaints() {
        requireAdmin();
        return Result.ok(afterSaleService.listComplaints(null, true));
    }

    /**
     * 处理（结案）投诉。
     * URL: POST /api/admin/complaints/{id}/resolve；调用方：管理后台（已登录）。
     * 入参：路径 id + ComplaintResolveRequest，@Valid 校验 result 非空（status 可选）；操作人取 UserContext.userId()。
     * 权限：需管理员（role==2）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/complaints/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id,
                                @Valid @RequestBody Requests.ComplaintResolveRequest request) {
        requireAdmin();
        afterSaleService.resolveComplaint(id, request, UserContext.userId());
        return Result.ok();
    }

    /**
     * 手动补生成未来 N 天时间槽（与每日定时任务走同一个 SlotService.generateNextDays）。
     * URL: POST /api/admin/slots/generate；调用方：管理后台（已登录）。
     * 入参：days（默认 14，Math.min 封顶 30 防止一次生成过多）；靠唯一索引 + insert ignore 幂等，重复调用不产生重复槽。
     * 权限：需管理员（role==2）。返回：实际新增的时间槽条数。
     */
    @PostMapping("/slots/generate")
    public Result<Integer> generateSlots(@RequestParam(defaultValue = "14") int days) {
        requireAdmin();
        return Result.ok(slotService.generateNextDays(Math.min(days, 30)));
    }

    /**
     * 管理员权限校验：从 UserContext 取 LoginUser，role 必须等于 2。
     * 本项目无 Spring Security / 注解式鉴权，因此靠每个后台方法首行显式调用它来兜底；
     * 抽成私有方法避免每处重复判断，但「必须调用」仍依赖开发者自觉（面试可指出这一点）。
     */
    private void requireAdmin() {
        if (UserContext.get() == null || UserContext.get().getRole() == null
                || UserContext.get().getRole() != 2) {
            throw new BusinessException("需要管理员权限");
        }
    }
}
