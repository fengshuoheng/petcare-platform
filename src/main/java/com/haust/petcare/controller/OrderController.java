package com.haust.petcare.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.common.Result;
import com.haust.petcare.common.UserContext;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.Address;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.entity.TimeSlot;
import com.haust.petcare.mapper.AddressMapper;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.service.AfterSaleService;
import com.haust.petcare.service.OrderService;
import com.haust.petcare.service.PriceService;
import com.haust.petcare.service.SlotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

/**
 * 订单主流程（用户端业务域），项目最核心的 Controller。
 * <p>
 * 架构位置：/api/orders 下的用户侧订单入口，受 AuthInterceptor 保护，用户身份统一从 UserContext 取，
 * 所有 Service 调用都带上 userId 做归属校验，前端无法指定他人身份。
 * 覆盖：价格试算 → 下单（11 态状态机的起点 WAIT_PAY）→ 支付 → 履约/改约/催单 → 确认完成 → 评价/投诉/售后。
 * 关键机制下沉在 OrderService / SlotService / AfterSaleService：requestId 幂等、条件 UPDATE 抢时间槽、
 * 状态机流转与状态日志、超时关单等，本层只做参数校验、身份注入与 Result 包装。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final PriceService priceService;
    private final SlotService slotService;
    private final PetMapper petMapper;
    private final AddressMapper addressMapper;
    private final AfterSaleService afterSaleService;

    public OrderController(OrderService orderService, PriceService priceService,
                           SlotService slotService, PetMapper petMapper,
                           AddressMapper addressMapper, AfterSaleService afterSaleService) {
        this.orderService = orderService;
        this.priceService = priceService;
        this.slotService = slotService;
        this.petMapper = petMapper;
        this.addressMapper = addressMapper;
        this.afterSaleService = afterSaleService;
    }

    /**
     * 下单前价格试算（只读，不落库）。
     * URL: POST /api/orders/price；调用方：用户端（已登录）。
     * 入参：QuoteRequest，@Valid 校验 serviceId/petId/addressId 非空，slotId/addonCodes/urgent 可选。
     * 校验：宠物和地址必须属于当前用户（防越权引用他人资料）、服务项必须存在；计价由 PriceService 统一完成。
     * 权限：需登录态。返回：报价明细 Map（基础价 / 加项 / 里程费 / 夜间费 / 加急费 / 合计等）。
     */
    @PostMapping("/price")
    public Result<Map<String, Object>> price(@Valid @RequestBody Requests.QuoteRequest request) {
        Pet pet = petMapper.selectById(request.getPetId());
        if (pet == null || !pet.getUserId().equals(UserContext.userId())) {
            throw new BusinessException("宠物不存在");
        }
        Address address = addressMapper.selectById(request.getAddressId());
        if (address == null || !address.getUserId().equals(UserContext.userId())) {
            throw new BusinessException("地址不存在");
        }
        ServiceItem item = priceService.requireService(request.getServiceId());
        TimeSlot slot = request.getSlotId() == null ? null : slotService.requireSlot(request.getSlotId());
        return Result.ok(priceService.quote(item, pet, address, slot, request.getAddonCodes(),
                Boolean.TRUE.equals(request.getUrgent())));
    }

    /**
     * 提交订单。
     * URL: POST /api/orders；调用方：用户端（已登录）。
     * 入参：CreateOrderRequest，@Valid 校验 serviceId/petId/addressId/slotId 非空且 requestId 必填——
     * requestId 是幂等键：重复提交同一值会直接返回已有订单，配合 DB 唯一索引兜底防重复下单。
     * 权限：需登录态；userId 由 UserContext 注入，Service 内再校验宠物/地址归属。
     * 返回：新建（或幂等命中）的 Order，初始状态 WAIT_PAY 待支付；时间槽用条件 UPDATE 抢占，抢不到即报「已被预约」。
     */
    @PostMapping
    public Result<Order> create(@Valid @RequestBody Requests.CreateOrderRequest request) {
        return Result.ok(orderService.create(UserContext.userId(), request));
    }

    /**
     * 我的订单分页列表。
     * URL: GET /api/orders；调用方：用户端（已登录）。
     * 入参：status（可选，按状态过滤）、current（默认 1）、size（默认 10，Math.min 封顶 50 防大分页拖库）。
     * 权限：需登录态；固定按 UserContext.userId() 过滤。返回：Page<Order>。
     */
    @GetMapping
    public Result<Page<Order>> list(@RequestParam(required = false) Integer status,
                                    @RequestParam(defaultValue = "1") int current,
                                    @RequestParam(defaultValue = "10") int size) {
        return Result.ok(orderService.pageByUser(UserContext.userId(), status, current, Math.min(size, 50)));
    }

    /**
     * 订单详情。
     * URL: GET /api/orders/{id}；调用方：用户端（已登录）。
     * 入参：路径 id；admin=false 时 Service 会校验订单属于当前用户。
     * 权限：需登录态且仅本人。返回：含订单、服务者、时间槽、状态日志、支付单、评价、结算、服务记录的 Map。
     */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id, UserContext.userId(), false));
    }

    /**
     * 用户取消订单。
     * URL: POST /api/orders/{id}/cancel；调用方：用户端（已登录）。
     * 入参：路径 id + 可选 CancelRequest（reason），未传时默认「用户取消」。
     * 权限：需登录态且仅本人；Service 按状态机判断是否可取消（服务开始后引导走售后）、释放时间槽，
     * 已支付订单按距服务开始时间算违约金并可能进入退款流程。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) Requests.CancelRequest request) {
        orderService.cancel(id, UserContext.userId(), false, request == null ? "用户取消" : request.getReason());
        return Result.ok();
    }

    /**
     * 改约（更换服务时间）。
     * URL: POST /api/orders/{id}/reschedule；调用方：用户端（已登录）。
     * 入参：路径 id + RescheduleRequest，@Valid 校验新 slotId 非空。
     * 权限：需登录态且仅本人；Service 校验状态、改约次数上限（petcare.order.reschedule-limit）、
     * 服务前 4 小时内不可改约，并用条件 UPDATE 抢占新槽、释放旧槽。返回：更新后的 Order。
     */
    @PostMapping("/{id}/reschedule")
    public Result<Order> reschedule(@PathVariable Long id, @Valid @RequestBody Requests.RescheduleRequest request) {
        return Result.ok(orderService.reschedule(id, UserContext.userId(), request.getSlotId()));
    }

    /**
     * 催单。
     * URL: POST /api/orders/{id}/urge；调用方：用户端（已登录）。
     * 入参：路径 id + UrgeRequest，@Valid 校验 type 在 1~3（1 催接单 / 2 催上门 / 3 催完成）。
     * 权限：需登录态且仅本人；Service 再校验当前状态是否匹配、10 分钟频控、每类型最多 3 次。
     * 返回：空 data 的 Result.ok()。
     */
    @PostMapping("/{id}/urge")
    public Result<Void> urge(@PathVariable Long id, @Valid @RequestBody Requests.UrgeRequest request) {
        orderService.urge(id, UserContext.userId(), request.getType());
        return Result.ok();
    }

    /**
     * 用户确认服务完成（WAIT_CONFIRM → FINISHED）。
     * URL: POST /api/orders/{id}/confirm；调用方：用户端（已登录）。
     * 入参：路径 id，无请求体。
     * 权限：需登录态且仅本人；确认后 Service 生成结算单、累加服务者单量。
     * 与之对应的是定时任务 autoConfirm（超时 24h 自动确认）。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        orderService.confirm(id, UserContext.userId());
        return Result.ok();
    }

    /**
     * 提交评价。
     * URL: POST /api/orders/review；调用方：用户端（已登录）。
     * 入参：ReviewRequest，@Valid 校验 orderId 非空、rating 在 1~5 星。
     * 权限：需登录态；由 AfterSaleService 校验订单归属与可评价状态。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/review")
    public Result<Void> review(@Valid @RequestBody Requests.ReviewRequest request) {
        afterSaleService.review(UserContext.userId(), request);
        return Result.ok();
    }

    /**
     * 提交投诉。
     * URL: POST /api/orders/complaint；调用方：用户端（已登录）。
     * 入参：ComplaintRequest，@Valid 校验 orderId、type、content 非空。
     * 权限：需登录态；由 AfterSaleService 落投诉单。返回：空 data 的 Result.ok()。
     */
    @PostMapping("/complaint")
    public Result<Void> complaint(@Valid @RequestBody Requests.ComplaintRequest request) {
        afterSaleService.createComplaint(UserContext.userId(), request);
        return Result.ok();
    }

    /**
     * 我的投诉列表。
     * URL: GET /api/orders/complaints；调用方：用户端（已登录）。
     * 入参：无；admin=false 只返回当前用户提交的投诉。
     * 权限：需登录态。返回：投诉列表。
     */
    @GetMapping("/complaints")
    public Result<?> complaints() {
        return Result.ok(afterSaleService.listComplaints(UserContext.userId(), false));
    }
}
