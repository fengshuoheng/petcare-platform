package com.haust.petcare.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.LoginUser;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.ServiceRecord;
import com.haust.petcare.entity.Settlement;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.mapper.ServiceRecordMapper;
import com.haust.petcare.mapper.StaffMapper;
import com.haust.petcare.service.OrderService;
import com.haust.petcare.service.SettlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务者工作台（服务者端业务域）。
 * <p>
 * 架构位置：/api/staff 下，受 AuthInterceptor 保护，且额外要求当前 LoginUser 是服务者身份
 * （currentStaffId 取 UserContext 里的 staffId，为空即拒绝；role 定义见 LoginUser：0 用户 / 1 服务者 / 2 管理员）。
 * 覆盖履约主链路：资料 → 我的订单 → 接单 → 出发 → 到达打卡 → 完成并写服务记录 → 结算与可提现金额。
 * 所有订单操作都把 currentStaffId() 传给 OrderService，由其校验「订单确实派给了该服务者」，防止越权处理他人订单。
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final OrderService orderService;
    private final SettlementService settlementService;
    private final StaffMapper staffMapper;
    private final ServiceRecordMapper recordMapper;

    public StaffController(OrderService orderService, SettlementService settlementService,
                           StaffMapper staffMapper, ServiceRecordMapper recordMapper) {
        this.orderService = orderService;
        this.settlementService = settlementService;
        this.staffMapper = staffMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 服务者个人资料。
     * URL: GET /api/staff/profile；调用方：服务者端（已登录）。
     * 入参：无；按 currentStaffId() 查。权限：需服务者身份。返回：Staff。
     */
    @GetMapping("/profile")
    public Result<Staff> profile() {
        return Result.ok(staffMapper.selectById(currentStaffId()));
    }

    /**
     * 派给我的订单分页列表。
     * URL: GET /api/staff/orders；调用方：服务者端（已登录）。
     * 入参：status（可选）、current（默认 1）、size（默认 10，封顶 50）。
     * 权限：需服务者身份；按 currentStaffId() 过滤。返回：Page<Order>。
     */
    @GetMapping("/orders")
    public Result<Page<Order>> orders(@RequestParam(required = false) Integer status,
                                      @RequestParam(defaultValue = "1") int current,
                                      @RequestParam(defaultValue = "10") int size) {
        return Result.ok(orderService.pageByStaff(currentStaffId(), status, current, Math.min(size, 50)));
    }

    /**
     * 订单详情（服务者视角）。
     * URL: GET /api/staff/orders/{id}；调用方：服务者端（已登录）。
     * 入参：路径 id；先以 admin=true 取完整详情（跳过 userId 校验），再比对 order.staffId 是否等于 currentStaffId()。
     * 权限：需服务者身份且订单派给自己，否则抛「不是你的订单」。返回：订单详情 Map。
     */
    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> detail = orderService.detail(id, null, true);
        Order order = (Order) detail.get("order");
        if (!currentStaffId().equals(order.getStaffId())) {
            throw new BusinessException("不是你的订单");
        }
        return Result.ok(detail);
    }

    /**
     * 接单（WAIT_ACCEPT / WAIT_DISPATCH → WAIT_VISIT）。
     * URL: POST /api/staff/orders/{id}/accept；调用方：服务者端（已登录）。
     * 入参：路径 id；Service 校验订单归属与当前状态。
     * 权限：需服务者身份且订单派给自己。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        orderService.accept(id, currentStaffId());
        return Result.ok();
    }

    /**
     * 出发上门（WAIT_VISIT → SERVING，记录 departTime）。
     * URL: POST /api/staff/orders/{id}/start；调用方：服务者端（已登录）。
     * 入参：路径 id；Service 校验订单归属与当前状态。
     * 权限：需服务者身份且订单派给自己。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        orderService.start(id, currentStaffId());
        return Result.ok();
    }

    /**
     * 到达打卡（SERVING 状态下记录 arriveTime，状态不变）。
     * URL: POST /api/staff/orders/{id}/arrive；调用方：服务者端（已登录）。
     * 入参：路径 id；Service 校验订单归属与当前必须为 SERVING。
     * 权限：需服务者身份且订单派给自己。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/arrive")
    public Result<Void> arrive(@PathVariable Long id) {
        orderService.arrive(id, currentStaffId());
        return Result.ok();
    }

    /**
     * 完成服务并写服务记录（SERVING → WAIT_CONFIRM，等待用户确认）。
     * URL: POST /api/staff/orders/{id}/complete；调用方：服务者端（已登录）。
     * 入参：路径 id + 请求体 Map，取 report / petCondition / beforePhotos / afterPhotos（服务报告与前后照片）。
     * 权限：需服务者身份且订单派给自己。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/orders/{id}/complete")
    public Result<Void> complete(@PathVariable Long id, @RequestBody Map<String, String> body) {
        orderService.complete(id, currentStaffId(), body.get("report"), body.get("petCondition"),
                body.get("beforePhotos"), body.get("afterPhotos"));
        return Result.ok();
    }

    /**
     * 我的结算单与可提现金额。
     * URL: GET /api/staff/settlements；调用方：服务者端（已登录）。
     * 入参：无；按 currentStaffId() 查结算列表。
     * 权限：需服务者身份。返回：{list: 结算单列表, withdrawable: status=1 的 staffIncome 合计}。
     */
    @GetMapping("/settlements")
    public Result<Map<String, Object>> settlements() {
        List<Settlement> list = settlementService.listByStaff(currentStaffId());
        java.math.BigDecimal total = list.stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                .map(Settlement::getStaffIncome)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("withdrawable", total);
        return Result.ok(result);
    }

    /**
     * 按订单查服务记录（报告、前后照片、到达/完成时间等）。
     * URL: GET /api/staff/records/{orderId}；调用方：服务者端（已登录）。
     * 入参：路径 orderId。权限：需服务者身份。返回：ServiceRecord（查不到为 null）。
     */
    @GetMapping("/records/{orderId}")
    public Result<ServiceRecord> record(@PathVariable Long orderId) {
        return Result.ok(recordMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceRecord>()
                .eq(ServiceRecord::getOrderId, orderId)));
    }

    /**
     * 解析当前服务者 id：从 UserContext 取 LoginUser.staffId，为空说明该账号没绑定服务者档案，
     * 先用 requireRole(1) 要求角色至少为服务者，再抛「当前账号不是服务者」。
     * 抽成私有方法是为了让本类所有接口统一走同一套身份校验，不依赖前端传 staffId。
     */
    private Long currentStaffId() {
        LoginUser user = UserContext.get();
        if (user == null || user.getStaffId() == null) {
            UserContext.requireRole(1);
            throw new BusinessException("当前账号不是服务者");
        }
        return user.getStaffId();
    }
}
