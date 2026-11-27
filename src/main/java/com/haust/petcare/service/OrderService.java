package com.haust.petcare.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.haust.petcare.common.BusinessException;
import com.haust.petcare.config.PetcareProperties;
import com.haust.petcare.dto.Requests;
import com.haust.petcare.entity.Address;
import com.haust.petcare.entity.Order;
import com.haust.petcare.entity.OrderStatusLog;
import com.haust.petcare.entity.Pet;
import com.haust.petcare.entity.RefundOrder;
import com.haust.petcare.entity.ServiceItem;
import com.haust.petcare.entity.ServiceRecord;
import com.haust.petcare.entity.Staff;
import com.haust.petcare.entity.TimeSlot;
import com.haust.petcare.mapper.AddressMapper;
import com.haust.petcare.mapper.OrderMapper;
import com.haust.petcare.mapper.OrderStatusLogMapper;
import com.haust.petcare.mapper.PaymentOrderMapper;
import com.haust.petcare.mapper.PetMapper;
import com.haust.petcare.mapper.RefundOrderMapper;
import com.haust.petcare.mapper.ReviewMapper;
import com.haust.petcare.mapper.ServiceRecordMapper;
import com.haust.petcare.mapper.StaffMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单域核心服务：串起「查可约槽 → 五段计价 → 下单锁槽 → 支付 → 服务履约 → 确认/结算」整条主链路，
 * 是 11 态订单状态机的唯一维护者，支付、售后、结算、通知、统计都围绕这里的状态流转展开。
 * <p>
 * 涉及表：orders（uk_request(request_id) 保证 requestId 幂等、uk_order_no 保证订单号唯一）、
 * order_status_log（状态变更流水）、time_slot（经 SlotService 抢槽/确认/释放）、
 * pet / address / staff / service_item（下单校验与快照）、payment_order、refund_order、
 * review、settlement、service_record（详情聚合与服务报告）。
 * <p>
 * 链路位置：介于 Controller 与 Mapper 之间；create 与各状态流转方法带 @Transactional，
 * 保证「订单 + 时间槽 + 状态日志 + 通知」同成功同失败。超时未支付关闭、24h 自动确认由
 * job.ScheduledJobs 定时驱动，本类只暴露被定时任务调用的批量方法。
 * <p>
 * 状态机：WAIT_PAY(0) --支付成功--> WAIT_DISPATCH(1)/WAIT_ACCEPT(2) --> WAIT_VISIT(3)
 * --> SERVING(4) --> WAIT_CONFIRM(5) --> FINISHED(6)；
 * WAIT_PAY 超时 --> CLOSED(10)，取消 --> CANCELED(7)/REFUNDING(8)；
 * REFUNDING --退款通过--> REFUNDED(9)，--退款被拒--> CANCELED(7)。CANCELED/REFUNDED/CLOSED 为终态。
 * <p>
 * 并发取舍：项目没有 Redisson/分布式锁，抢槽靠 time_slot 的条件 UPDATE（WHERE status=0），
 * 幂等靠数据库唯一索引 + 事务回滚补偿，这是本项目最值得讲清的两个难点。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    // 订单 11 态：0~6 为正向履约链路，7/9/10 为终态，8 为退款中间态；与 orders.status、建表注释保持一致
    public static final int WAIT_PAY = 0;
    public static final int WAIT_DISPATCH = 1;
    public static final int WAIT_ACCEPT = 2;
    public static final int WAIT_VISIT = 3;
    public static final int SERVING = 4;
    public static final int WAIT_CONFIRM = 5;
    public static final int FINISHED = 6;
    public static final int CANCELED = 7;
    public static final int REFUNDING = 8;
    public static final int REFUNDED = 9;
    public static final int CLOSED = 10;

    // 状态码 → 中文文案；用 LinkedHashMap 保证详情、看板与下拉框的展示顺序稳定
    private static final Map<Integer, String> STATUS_TEXT = new LinkedHashMap<>();

    static {
        STATUS_TEXT.put(WAIT_PAY, "待支付");
        STATUS_TEXT.put(WAIT_DISPATCH, "待派单");
        STATUS_TEXT.put(WAIT_ACCEPT, "待接单");
        STATUS_TEXT.put(WAIT_VISIT, "待上门");
        STATUS_TEXT.put(SERVING, "服务中");
        STATUS_TEXT.put(WAIT_CONFIRM, "待确认");
        STATUS_TEXT.put(FINISHED, "已完成");
        STATUS_TEXT.put(CANCELED, "已取消");
        STATUS_TEXT.put(REFUNDING, "退款中");
        STATUS_TEXT.put(REFUNDED, "已退款");
        STATUS_TEXT.put(CLOSED, "已关闭");
    }

    private final OrderMapper orderMapper;
    private final OrderStatusLogMapper logMapper;
    private final PetMapper petMapper;
    private final AddressMapper addressMapper;
    private final StaffMapper staffMapper;
    private final ServiceRecordMapper recordMapper;
    private final PaymentOrderMapper paymentMapper;
    private final RefundOrderMapper refundMapper;
    private final ReviewMapper reviewMapper;
    private final PriceService priceService;
    private final SlotService slotService;
    private final SettlementService settlementService;
    private final NotificationService notificationService;
    private final PetcareProperties properties;
    private final RedissonClient redissonClient;

    /**
     * 构造器注入订单、时间槽、计价、结算、通知等依赖，由 Spring 容器启动时装配；
     * 各 Mapper 为单例，保证 create() 事务内所有写操作共用同一个数据库连接。
     */
    public OrderService(OrderMapper orderMapper, OrderStatusLogMapper logMapper,
                        PetMapper petMapper, AddressMapper addressMapper, StaffMapper staffMapper,
                        ServiceRecordMapper recordMapper, PaymentOrderMapper paymentMapper,
                        RefundOrderMapper refundMapper, ReviewMapper reviewMapper,
                        PriceService priceService, SlotService slotService,
                        SettlementService settlementService, NotificationService notificationService,
                        PetcareProperties properties,RedissonClient redissonClient) {
        this.orderMapper = orderMapper;
        this.logMapper = logMapper;
        this.petMapper = petMapper;
        this.addressMapper = addressMapper;
        this.staffMapper = staffMapper;
        this.recordMapper = recordMapper;
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.reviewMapper = reviewMapper;
        this.priceService = priceService;
        this.slotService = slotService;
        this.settlementService = settlementService;
        this.notificationService = notificationService;
        this.properties = properties;
        this.redissonClient=redissonClient;
    }

    /**
     * 创建订单（主链路下单入口）：校验档案与可约性 → 五段计价快照 → 插订单 → 抢时间槽 → 记状态日志。
     * <p>
     * 链路：用户在 SlotController 选中可约槽、PriceService 试算后调用；成功后返回 WAIT_PAY(0) 订单，
     * 再由 PaymentService.create 建支付单。方法带 @Transactional，抢槽失败会整体回滚。
     *
     * @param userId  当前登录用户，用于校验宠物/地址归属，防止冒用他人档案
     * @param request 下单请求：petId/addressId/serviceId/slotId、requestId（幂等键）、
     *                附加项 addonCodes、是否加急 urgent、备注 remark
     * @return 新建（或 requestId 重复时已存在）的订单，状态 WAIT_PAY
     * @throws BusinessException 宠物/地址不存在或不属于本人、时间不可约/已过期、
     *                           服务者不支持该体型、附加项未知、抢槽失败
     */
    // 注意：@Transactional 放在锁里面！不要把事务注解放这个方法上，我们抽一个内部方法做事务
// 【外层方法：抢锁】
    public Order create(Long userId, Requests.CreateOrderRequest request) {
        // 1. 拼接锁key，同一个requestId用同一个锁
        String lockKey = "order:requestId:" + request.getRequestId();
        // 获取可重入锁
        RLock lock = redissonClient.getLock(lockKey);
        String slotLockKey = "order:slot:" + request.getSlotId();
        RLock slotLock = redissonClient.getLock(slotLockKey);
        Order existOrder = null;
        try {
            // tryLock(等待时间, 锁过期时间, 时间单位)
            // 等待0秒：抢不到锁直接失败，不阻塞等待；看门狗自动续期，这里过期时间只是看门狗基线
            boolean tryLockSuccess = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!tryLockSuccess) {
                // 没抢到锁：说明别的线程正在处理这个requestId下单
                // 重新查一遍数据库，看看订单是不是已经建好了
                existOrder = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                        .eq(Order::getRequestId, request.getRequestId()));
                if (existOrder != null) {
                    return existOrder;
                }
                // 查到也没有，说明正在处理中，返回提示
                throw new BusinessException("订单正在处理中，请勿重复提交");
            }
            boolean slotLockOK = slotLock.tryLock(0, TimeUnit.SECONDS);
            if(!slotLockOK){
                throw new BusinessException("时间不可约/已过期");
            }
            // ✅抢到锁了！进入真正下单事务逻辑，抽一个单独方法加@Transactional
            existOrder = doCreateOrder(userId, request);
            return existOrder;

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁：必须判断，只有当前线程持有这个锁，才去解锁！
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (slotLock.isHeldByCurrentThread()) {
                slotLock.unlock();
            }
        }
    }

    // 【内层：真正下单逻辑，加事务】
    @Transactional(rollbackFor = Exception.class)
    public Order doCreateOrder(Long userId, Requests.CreateOrderRequest request) {
        // ========== 下面这一大段，就是你原来全部的下单代码，原样搬进来 ==========
        // 幂等第一层：按 requestId 查重（现在这一层变成二次兜底，并发冲突已经被锁挡住大部分）
        Order exist = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getRequestId, request.getRequestId()));
        if (exist != null) {
            return exist;
        }

        // 校验宠物归属
        Pet pet = petMapper.selectById(request.getPetId());
        if (pet == null || !pet.getUserId().equals(userId)) {
            throw new BusinessException("宠物不存在");
        }

        // 校验地址归属
        Address address = addressMapper.selectById(request.getAddressId());
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException("地址不存在");
        }

        ServiceItem item = priceService.requireService(request.getServiceId());
        TimeSlot slot = slotService.requireSlot(request.getSlotId());

        if (slot.getStatus() != 0) {
            throw new BusinessException("该时间已被预约，请重新选择");
        }
        if (slot.getSlotStart().isBefore(LocalDateTime.now())) {
            throw new BusinessException("不能预约已过去的时间");
        }

        String size = priceService.sizeOf(pet);
        Staff staff = staffMapper.selectById(slot.getStaffId());
        if (staff == null || !staff.getSizeTypes().contains(size)) {
            throw new BusinessException("该服务者不支持当前宠物体型");
        }

        boolean urgent = Boolean.TRUE.equals(request.getUrgent());
        Map<String, Object> quote = priceService.quote(item, pet, address, slot,
                request.getAddonCodes(), urgent);

        Order order = new Order();
        order.setOrderNo("PO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + RandomUtil.randomNumbers(4));
        order.setRequestId(request.getRequestId());
        order.setUserId(userId);
        order.setStaffId(staff.getId());
        order.setPetId(pet.getId());
        order.setAddressId(address.getId());
        order.setServiceId(item.getId());
        order.setSlotId(slot.getId());

        order.setServiceName(item.getName());
        order.setPetName(pet.getName());
        order.setPetSize(size);
        order.setAddressSnapshot(address.getCity() + address.getDistrict() + address.getDetail());
        order.setContactPhone(address.getPhone());

        order.setAmount((BigDecimal) quote.get("basePrice"));
        order.setAddonAmount((BigDecimal) quote.get("addonAmount"));
        order.setDistanceFee((BigDecimal) quote.get("distanceFee"));
        order.setNightFee((BigDecimal) quote.get("nightFee"));
        order.setUrgentFee((BigDecimal) quote.get("urgentFee"));
        order.setPayAmount((BigDecimal) quote.get("total"));

        order.setAddonJson(cn.hutool.json.JSONUtil.toJsonStr(quote.get("addonItems")));
        order.setStatus(WAIT_PAY);
        order.setUrgent(urgent ? 1 : 0);
        order.setRemark(request.getRemark());
        order.setRescheduleCount(0);
        order.setUrgeAccept(0);
        order.setUrgeVisit(0);
        order.setUrgeFinish(0);
        order.setSlotStart(slot.getSlotStart());
        order.setSlotEnd(slot.getSlotEnd());
        order.setCreateTime(LocalDateTime.now());
        order.setPayExpireAt(LocalDateTime.now().plusMinutes(properties.getOrder().getPayTimeoutMinutes()));

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            Order current = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getRequestId, request.getRequestId()));
            if (current != null) {
                return current;
            }
            throw e;
        }

        slotService.lock(slot.getId(), order.getId(),
                LocalDateTime.now().plusMinutes(properties.getOrder().getPayTimeoutMinutes()));

        log(order.getId(), null, WAIT_PAY, "USER", userId, "创建订单");
        return order;
    }


    /**
     * 订单详情聚合：把订单、状态文案、服务者、时间槽、状态日志、支付单、评价、结算单、
     * 服务报告拼成一个 Map 供前端一次性渲染，避免多次请求。
     *
     * @param orderId 订单 ID
     * @param userId  当前用户 ID；非管理员时必须是订单本人，否则按「订单不存在」处理，不泄露他人订单
     * @param admin   是否管理员，管理员跳过归属校验
     * @return 含 order/statusText/staff/slot/logs/payment/review/settlement/record 的聚合结果
     * @throws BusinessException 订单不存在、或非本人且非管理员访问
     */
    public Map<String, Object> detail(Long orderId, Long userId, boolean admin) {
        Order order = requireOrder(orderId);
        if (!admin && !order.getUserId().equals(userId)) {
            throw new BusinessException("订单不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("statusText", statusText(order.getStatus()));
        result.put("staff", order.getStaffId() == null ? null : staffMapper.selectById(order.getStaffId()));
        result.put("slot", slotService.requireSlot(order.getSlotId()));
        result.put("logs", logMapper.selectList(new LambdaQueryWrapper<OrderStatusLog>()
                .eq(OrderStatusLog::getOrderId, orderId)
                .orderByAsc(OrderStatusLog::getId)));
        result.put("payment", paymentMapper.selectList(new LambdaQueryWrapper<com.haust.petcare.entity.PaymentOrder>()
                .eq(com.haust.petcare.entity.PaymentOrder::getOrderId, orderId)
                .orderByDesc(com.haust.petcare.entity.PaymentOrder::getId)));
        result.put("review", reviewMapper.selectOne(new LambdaQueryWrapper<com.haust.petcare.entity.Review>()
                .eq(com.haust.petcare.entity.Review::getOrderId, orderId)));
        result.put("settlement", settlementService.findByOrder(orderId));
        result.put("record", recordMapper.selectOne(new LambdaQueryWrapper<ServiceRecord>()
                .eq(ServiceRecord::getOrderId, orderId)));
        return result;
    }

    /**
     * 用户端订单分页列表，按创建时间倒序；status 为空表示查全部状态。
     *
     * @param userId  用户 ID
     * @param status  可选的 11 态状态码筛选
     * @param current 页码（从 1 开始）
     * @param size    每页条数
     * @return MyBatis-Plus 分页对象
     */
    public Page<Order> pageByUser(Long userId, Integer status, int current, int size) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        return orderMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 服务者端订单分页列表，按创建时间倒序；status 为空表示查全部状态。
     *
     * @param staffId 服务者 ID（只查派给该服务者的订单）
     * @param status  可选状态码筛选
     * @param current 页码
     * @param size    每页条数
     * @return MyBatis-Plus 分页对象
     */
    public Page<Order> pageByStaff(Long staffId, Integer status, int current, int size) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getStaffId, staffId)
                .orderByDesc(Order::getCreateTime);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        return orderMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 取消订单：释放时间槽，并按是否已支付、距服务开始时长计算违约金后决定去向。
     * <p>
     * 链路：用户/管理员在服务开始前调用；服务已开始（>=SERVING）只允许走售后。
     * 未支付 → CANCELED(7)；已支付且需退款 → REFUNDING(8) 并生成 RefundOrder，
     * 由 PaymentService.approveRefund/rejectRefund 收尾为 REFUNDED(9)/CANCELED(7)。
     *
     * @param orderId 订单 ID
     * @param userId  操作人用户 ID（非管理员需为订单本人）
     * @param admin   是否管理员
     * @param reason  取消原因，写入 cancel_reason 与状态日志
     * @throws BusinessException 订单不存在/非本人、或当前状态不可取消（服务已开始、已是终态）
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, Long userId, boolean admin, String reason) {
        Order order = requireOrder(orderId);
        // 能放行的条件：是管理员 或者 是订单本人
        if (!(admin || order.getUserId().equals(userId))) {
            throw new BusinessException("无权操作该订单");
        }
        // 状态前置校验：只允许 0~3（未开始服务）且非终态的订单取消；
        // 一旦进入 SERVING(4) 说明服务已发生，只能走售后退款，不能单方面取消。
        if (order.getStatus() >= SERVING || order.getStatus() == CANCELED
                || order.getStatus() == REFUNDED || order.getStatus() == CLOSED) {
            throw new BusinessException("当前状态不可取消，服务已开始请提交售后");
        }

        // 无论是否已支付，取消都先释放该订单占用的时间槽（release 按 order_id 置回 0 可约），
        // 让库存重新可售；已支付部分的退款交给管理员审核，不在这里直接退钱。
        int from = order.getStatus();
        slotService.release(order.getId());

        if (order.getPayTime() == null) {
            order.setStatus(CANCELED);
            order.setCancelReason(reason);
            order.setCancelBy(admin ? "ADMIN" : "USER");
            order.setCloseTime(LocalDateTime.now());
            orderMapper.updateById(order);
            log(orderId, from, CANCELED, admin ? "ADMIN" : "USER", userId, reason);
            return;
        }

        // 阶梯违约金：距服务开始 >=24h 免罚、>=2h 扣 20%、否则扣 50%；
        // 用 BigDecimal 按 payAmount 计算并 HALF_UP 保留 2 位，避免 double 金额误差；
        // 退款额 >0 才进 REFUNDING 并生成退款单，全额扣完则直接 CANCELED，不产生 0 元退款单。
        long hours = Duration.between(LocalDateTime.now(), order.getSlotStart()).toHours();
        int penaltyRate = hours >= 24 ? 0 : (hours >= 2 ? 20 : 50);
        BigDecimal penalty = order.getPayAmount()
                .multiply(BigDecimal.valueOf(penaltyRate))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal refundAmount = order.getPayAmount().subtract(penalty);

        order.setStatus(refundAmount.compareTo(BigDecimal.ZERO) > 0 ? REFUNDING : CANCELED);
        order.setCancelReason(reason);
        order.setCancelBy(admin ? "ADMIN" : "USER");
        order.setCloseTime(LocalDateTime.now());
        orderMapper.updateById(order);

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            RefundOrder refund = new RefundOrder();
            refund.setRefundNo("RF" + order.getOrderNo());
            refund.setOrderId(orderId);
            refund.setAmount(refundAmount);
            refund.setReason("取消订单，违约金 " + penaltyRate + "%");
            refund.setStatus(0);
            refund.setCreateTime(LocalDateTime.now());
            refundMapper.insert(refund);
        }
        log(orderId, from, order.getStatus(), admin ? "ADMIN" : "USER", userId,
                "取消订单，扣违约金 " + penaltyRate + "%");
        notificationService.send(order.getUserId(), "ORDER", "订单已取消",
                "订单 " + order.getOrderNo() + " 已取消，退款金额 " + refundAmount + " 元", orderId);
    }

    /**
     * 改约：占用新时间槽、释放旧槽，并更新订单上的槽与服务者快照。
     * <p>
     * 链路：仅在 WAIT_DISPATCH(1)~WAIT_VISIT(3) 之间、每单最多改约 rescheduleLimit 次、
     * 且服务开始前 4 小时外可用；换服务者时状态回到 WAIT_ACCEPT(2) 让新服务者重新接单。
     *
     * @param orderId   订单 ID
     * @param userId    当前用户（必须是订单本人）
     * @param newSlotId 新的可约时间槽 ID
     * @return 更新后的订单
     * @throws BusinessException 非本人、状态不可改约、超过次数、临近服务时间、
     *                           新槽不可约/已过期、新服务者不支持体型
     */
    @Transactional(rollbackFor = Exception.class)
    public Order reschedule(Long orderId, Long userId, Long newSlotId) {
        Order order = requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("当前状态不可改约");
        }
        // 状态窗口 1~3（已支付、尚未上门）；次数上限来自 petcare.order.reschedule-limit（默认 1），
        // 且服务开始前 4 小时内不允许改约，避免服务者已排程还被临时改动。
        if (order.getStatus() < WAIT_DISPATCH || order.getStatus() > WAIT_VISIT) {
            throw new BusinessException("当前状态不可改约");
        }
        if (order.getRescheduleCount() >= properties.getOrder().getRescheduleLimit()) {
            throw new BusinessException("每单最多改约 " + properties.getOrder().getRescheduleLimit() + " 次");
        }
        if (LocalDateTime.now().isAfter(order.getSlotStart().minusHours(4))) {
            throw new BusinessException("服务开始前 4 小时内不可改约");
        }

        TimeSlot newSlot = slotService.requireSlot(newSlotId);
        if (newSlot.getStatus() != 0 || newSlot.getSlotStart().isBefore(LocalDateTime.now())) {
            throw new BusinessException("新时间不可预约");
        }
        Pet pet = petMapper.selectById(order.getPetId());
        Staff staff = staffMapper.selectById(newSlot.getStaffId());
        if (staff == null || !staff.getSizeTypes().contains(priceService.sizeOf(pet))) {
            throw new BusinessException("该服务者不支持当前宠物体型");
        }

        // 先锁新槽再放旧槽（先占后放）：若反过来先 release 旧槽，中间窗口旧槽可能被别人抢走，
        // 而新槽又没抢到，订单就会处于无槽状态。新槽锁定 30 分钟，给后续确认留缓冲。
        slotService.lock(newSlotId, order.getId(), LocalDateTime.now().plusMinutes(30));
        slotService.release(order.getId());

        int from = order.getStatus();
        // 换了服务者就必须重新接单：清空 acceptTime、状态回到 WAIT_ACCEPT，
        // 否则新服务者没经过接单确认就直接进入待上门，履约链路会断。
        boolean staffChanged = !newSlot.getStaffId().equals(order.getStaffId());
        order.setSlotId(newSlotId);
        order.setSlotStart(newSlot.getSlotStart());
        order.setSlotEnd(newSlot.getSlotEnd());
        order.setStaffId(newSlot.getStaffId());
        order.setRescheduleCount(order.getRescheduleCount() + 1);
        if (staffChanged) {
            order.setStatus(WAIT_ACCEPT);
            order.setAcceptTime(null);
        }
        orderMapper.updateById(order);
        log(orderId, from, order.getStatus(), "USER", userId,
                "改约至 " + newSlot.getSlotStart());
        notificationService.send(order.getUserId(), "ORDER", "改约成功",
                "订单 " + order.getOrderNo() + " 已改约至 " + newSlot.getSlotStart(), orderId);
        return order;
    }

    /**
     * 用户催单：按类型催接单/催上门/催完成，带频控与次数上限，并通知服务者。
     * <p>
     * 链路：用户在 WAIT_ACCEPT / WAIT_VISIT / SERVING 三个阶段分别调用；
     * 10 分钟内只能催一次、每类型最多 3 次，超过提示平台已介入。
     *
     * @param orderId 订单 ID
     * @param userId  当前用户（必须是订单本人）
     * @param type    1 催接单、2 催上门、3 催完成
     * @throws BusinessException 非本人、当前状态与催单类型不匹配、催单过频或已达 3 次
     */
    @Transactional(rollbackFor = Exception.class)
    public void urge(Long orderId, Long userId, int type) {
        Order order = requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作该订单");
        }
        if (type == 1 && order.getStatus() != WAIT_ACCEPT) {
            throw new BusinessException("当前状态无需催接单");
        }
        if (type == 2 && order.getStatus() != WAIT_VISIT) {
            throw new BusinessException("当前状态无需催上门");
        }
        if (type == 3 && order.getStatus() != SERVING) {
            throw new BusinessException("当前状态无需催完成");
        }
        // 频控用 Duration 计算分钟差：10 分钟内只能催一次，避免高频催单打扰服务者；
        // 催单只改计数与 lastUrgeTime，状态不变也要写一条 from=to 的日志留痕。
        if (order.getLastUrgeTime() != null
                && Duration.between(order.getLastUrgeTime(), LocalDateTime.now()).toMinutes() < 10) {
            throw new BusinessException("催单太频繁，请 10 分钟后再试");
        }
        int count = type == 1 ? order.getUrgeAccept() : (type == 2 ? order.getUrgeVisit() : order.getUrgeFinish());
        if (count >= 3) {
            throw new BusinessException("该类型已催 3 次，平台已介入");
        }

        if (type == 1) {
            order.setUrgeAccept(count + 1);
        } else if (type == 2) {
            order.setUrgeVisit(count + 1);
        } else {
            order.setUrgeFinish(count + 1);
        }
        order.setLastUrgeTime(LocalDateTime.now());
        orderMapper.updateById(order);

        String typeText = type == 1 ? "接单" : (type == 2 ? "上门" : "完成");
        notificationService.send(order.getStaffId() == null ? null : staffUserId(order.getStaffId()),
                "URGE", "用户催单", "订单 " + order.getOrderNo() + " 用户催" + typeText, orderId);
        log(orderId, order.getStatus(), order.getStatus(), "USER", userId, "催" + typeText);
    }

    /**
     * 用户确认完成：WAIT_CONFIRM(5) → FINISHED(6)，并触发生成结算单与服务者单量累加。
     * <p>
     * 链路：服务者 complete() 后进入 WAIT_CONFIRM；用户主动确认，或 24h 后由 autoConfirm() 自动确认。
     * 与 autoConfirm 是同一终态的两个入口，都会调用 SettlementService.createForOrder。
     *
     * @param orderId 订单 ID
     * @param userId  当前用户（必须是订单本人）
     * @throws BusinessException 订单不存在/非本人、当前状态不是 WAIT_CONFIRM
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long orderId, Long userId) {
        Order order = requireOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException("无权操作");
        }
        if (order.getStatus() != WAIT_CONFIRM) {
            throw new BusinessException("当前状态不可确认");
        }
        order.setStatus(FINISHED);
        order.setConfirmTime(LocalDateTime.now());
        orderMapper.updateById(order);
        // 确认完成即生成结算单（T+7 可提现）并给服务者 order_count +1；
        // 用 setSql("order_count = order_count + 1") 做数据库侧原子自增，避免并发下读后写丢更新。
        settlementService.createForOrder(order);
        staffMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Staff>()
                .setSql("order_count = order_count + 1")
                .eq(Staff::getId, order.getStaffId()));
        log(orderId, WAIT_CONFIRM, FINISHED, "USER", userId, "用户确认完成");
    }

    /**
     * 服务者接单：WAIT_ACCEPT(2)/WAIT_DISPATCH(1) → WAIT_VISIT(3)。
     * <p>
     * 链路：支付成功后订单进入待接单，服务者接单后等待上门；管理员改派(reassign)后也回到这一步。
     *
     * @param orderId 订单 ID
     * @param staffId 当前服务者 ID，必须等于订单绑定的 staffId
     * @throws BusinessException 不是本人订单、当前状态不可接单
     */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long orderId, Long staffId) {
        Order order = requireOrder(orderId);
        requireStaff(order, staffId);
        if (order.getStatus() != WAIT_ACCEPT && order.getStatus() != WAIT_DISPATCH) {
            throw new BusinessException("当前状态不可接单");
        }
        order.setStatus(WAIT_VISIT);
        order.setAcceptTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log(orderId, WAIT_ACCEPT, WAIT_VISIT, "STAFF", staffId, "服务者接单");
        notificationService.send(order.getUserId(), "ORDER", "服务者已接单",
                "订单 " + order.getOrderNo() + " 已接单，服务者将按预约时间上门", orderId);
    }

    /**
     * 服务者出发上门：WAIT_VISIT(3) → SERVING(4)，记录 departTime。
     *
     * @param orderId 订单 ID
     * @param staffId 当前服务者 ID（做订单归属校验）
     * @throws BusinessException 不是本人订单、当前状态不可开始服务
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(Long orderId, Long staffId) {
        Order order = requireOrder(orderId);
        requireStaff(order, staffId);
        if (order.getStatus() != WAIT_VISIT) {
            throw new BusinessException("当前状态不可开始服务");
        }
        order.setStatus(SERVING);
        order.setDepartTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log(orderId, WAIT_VISIT, SERVING, "STAFF", staffId, "出发上门");
        notificationService.send(order.getUserId(), "ORDER", "服务者已出发",
                "订单 " + order.getOrderNo() + " 服务者已出发", orderId);
    }

    /**
     * 到达打卡：仅记录 arriveTime，状态保持 SERVING(4) 不变。
     * <p>
     * 链路：出发与完成之间的打卡节点，用于服务报告与纠纷举证，不推进状态机。
     *
     * @param orderId 订单 ID
     * @param staffId 当前服务者 ID
     * @throws BusinessException 不是本人订单、当前状态不是 SERVING
     */
    public void arrive(Long orderId, Long staffId) {
        Order order = requireOrder(orderId);
        requireStaff(order, staffId);
        if (order.getStatus() != SERVING) {
            throw new BusinessException("当前状态不可到达打卡");
        }
        order.setArriveTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log(orderId, SERVING, SERVING, "STAFF", staffId, "到达打卡");
    }

    /**
     * 服务者完成服务：SERVING(4) → WAIT_CONFIRM(5)，并 upsert 服务报告。
     * <p>
     * 链路：打卡后提交服务报告（文字、宠物状况、前后照片）；订单随后等用户确认或 24h 自动确认。
     * 服务报告按 orderId 查，没有则 insert、已有则 update，保证重复提交不产生多份报告。
     *
     * @param orderId      订单 ID
     * @param staffId      当前服务者 ID
     * @param report       服务报告文字
     * @param petCondition 宠物状况描述
     * @param beforePhotos 服务前照片（URL/JSON 串）
     * @param afterPhotos  服务后照片（URL/JSON 串）
     * @throws BusinessException 不是本人订单、当前状态不是 SERVING
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long orderId, Long staffId, String report, String petCondition,
                         String beforePhotos, String afterPhotos) {
        Order order = requireOrder(orderId);
        requireStaff(order, staffId);
        if (order.getStatus() != SERVING) {
            throw new BusinessException("当前状态不可完成服务");
        }
        order.setStatus(WAIT_CONFIRM);
        order.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(order);

        ServiceRecord record = recordMapper.selectOne(new LambdaQueryWrapper<ServiceRecord>()
                .eq(ServiceRecord::getOrderId, orderId));
        if (record == null) {
            record = new ServiceRecord();
            record.setOrderId(orderId);
            record.setStaffId(staffId);
            record.setCreateTime(LocalDateTime.now());
        }
        record.setDepartTime(order.getDepartTime());
        record.setArriveTime(order.getArriveTime());
        record.setBeforePhotos(beforePhotos);
        record.setAfterPhotos(afterPhotos);
        record.setPetCondition(petCondition);
        record.setReport(report);
        if (record.getId() == null) {
            recordMapper.insert(record);
        } else {
            recordMapper.updateById(record);
        }

        log(orderId, SERVING, WAIT_CONFIRM, "STAFF", staffId, "服务完成");
        notificationService.send(order.getUserId(), "ORDER", "服务已完成",
                "订单 " + order.getOrderNo() + " 服务完成，请确认并评价", orderId);
    }

    /**
     * 批量关闭超时未支付订单：WAIT_PAY(0) 且 payExpireAt 已过 → CLOSED(10)，同时释放时间槽。
     * <p>
     * 链路：由 ScheduledJobs.closeTimeoutOrders 每分钟调用；与 SlotService.expireStaleLocks
     * 一起构成「订单关闭 + 槽释放」的兜底，避免未支付订单长期占用库存。
     *
     * @return 本次关闭的订单数
     */
    public int closePayTimeoutOrders() {
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, WAIT_PAY)
                .lt(Order::getPayExpireAt, LocalDateTime.now()));
        int count = 0;
        for (Order order : orders) {
            slotService.release(order.getId());
            order.setStatus(CLOSED);
            order.setCloseTime(LocalDateTime.now());
            orderMapper.updateById(order);
            log(order.getId(), WAIT_PAY, CLOSED, "SYSTEM", null, "超时未支付自动关闭");
            count++;
        }
        return count;
    }

    /**
     * 超时自动确认：WAIT_CONFIRM(5) 且 finishTime 早于 now - autoConfirmHours(默认 24h) → FINISHED(6)。
     * <p>
     * 链路：由 ScheduledJobs.autoConfirm 每 10 分钟调用，是用户迟迟不点确认时的兜底；
     * 与 confirm() 一样会生成结算单，保证服务者能按时进入 T+7 结算。
     *
     * @return 本次自动确认的订单数
     */
    public int autoConfirm() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(properties.getOrder().getAutoConfirmHours());
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, WAIT_CONFIRM)
                .lt(Order::getFinishTime, deadline));
        for (Order order : orders) {
            order.setStatus(FINISHED);
            order.setConfirmTime(LocalDateTime.now());
            orderMapper.updateById(order);
            // 自动确认与用户主动确认走同一个终态处理，必须同样生成结算单，不能漏掉服务者收入。
            settlementService.createForOrder(order);
            log(order.getId(), WAIT_CONFIRM, FINISHED, "SYSTEM", null, "超时自动确认");
        }
        return orders.size();
    }

    /**
     * 管理端订单分页：支持按状态精确筛选、按订单号或宠物名模糊搜索。
     *
     * @param status  可选状态码
     * @param keyword 可选关键词（订单号/宠物名 like，用 and 包住避免与状态条件 or 串扰）
     * @param current 页码
     * @param size    每页条数
     * @return 分页结果
     */
    public Page<Order> adminPage(Integer status, String keyword, int current, int size) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .orderByDesc(Order::getCreateTime);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(Order::getOrderNo, keyword).or().like(Order::getPetName, keyword));
        }
        return orderMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 管理员改派服务者：把订单强制回到 WAIT_ACCEPT(2) 并通知新服务者接单。
     * <p>
     * 链路：原服务者无法服务/超时未接单时的人工介入入口；不改时间槽、不退款。
     *
     * @param orderId 订单 ID
     * @param staffId 新服务者 ID
     * @throws BusinessException 服务者不存在、订单不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long orderId, Long staffId) {
        Order order = requireOrder(orderId);
        Staff staff = staffMapper.selectById(staffId);
        if (staff == null) {
            throw new BusinessException("服务者不存在");
        }
        int from = order.getStatus();
        order.setStaffId(staffId);
        order.setStatus(WAIT_ACCEPT);
        order.setAcceptTime(null);
        orderMapper.updateById(order);
        log(orderId, from, WAIT_ACCEPT, "ADMIN", null, "改派给 " + staff.getName());
        notificationService.send(staffUserId(staffId), "ORDER", "新订单待接单",
                "订单 " + order.getOrderNo() + " 已派给你", orderId);
    }

    /**
     * 按 ID 取订单的统一入口，供本类以及 PaymentService/AfterSaleService 等复用，集中处理「订单不存在」。
     * <p>
     * 归属校验不在这里做：detail/cancel 等方法会再单独比对 userId，避免把越权访问误报成系统错误。
     *
     * @param orderId 订单 ID
     * @return 订单实体
     * @throws BusinessException 订单不存在
     */
    public Order requireOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    /**
     * 状态码转中文文案，未识别的状态返回「未知」；供订单详情与内部接口 /internal/orders/latest 使用。
     *
     * @param status 11 态状态码
     * @return 中文文案
     */
    public String statusText(Integer status) {
        return STATUS_TEXT.getOrDefault(status, "未知");
    }

    /**
     * 返回完整的「状态码 → 文案」映射（LinkedHashMap，顺序稳定），供管理端下拉框与看板状态分布展示。
     *
     * @return 状态映射，调用方不应修改
     */
    public Map<Integer, String> statusMap() {
        return STATUS_TEXT;
    }

    // 服务者侧操作的统一归属校验：订单未派单或 staffId 不匹配都拒绝，防止服务者操作他人订单。
    private void requireStaff(Order order, Long staffId) {
        if (order.getStaffId() == null || !order.getStaffId().equals(staffId)) {
            throw new BusinessException("不是你的订单");
        }
    }

    // 把 staffId 换成关联的 app_user.id 再发通知；服务者未绑定账号时返回 null，NotificationService.send 会跳过。
    private Long staffUserId(Long staffId) {
        Staff staff = staffMapper.selectById(staffId);
        return staff == null ? null : staff.getUserId();
    }

    /**
     * 写一条订单状态变更流水到 order_status_log。
     * <p>
     * 作用：① 订单详情时间线与客服排查依据；② 售后/纠纷时还原「谁在什么时间把订单从哪态改到哪态」；
     * ③ 状态没变的操作（催单、到达打卡）也记一条 from=to 留痕。
     * 创建订单时 from 传 null，这里归一为 to，保证日志里不出现空状态。
     *
     * @param orderId      订单 ID
     * @param from         原状态，创建订单时传 null
     * @param to           目标状态
     * @param operatorType 操作人类型：USER / STAFF / ADMIN / SYSTEM（定时任务）
     * @param operatorId   操作人 ID，系统触发时为 null
     * @param remark       变更说明（原因、违约金比例等）
     */
    public void log(Long orderId, Integer from, Integer to, String operatorType,
                    Long operatorId, String remark) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        // from 为 null（创建订单）时记为 to，保证每条流水都有明确的起止状态。
        log.setFromStatus(from == null ? to : from);
        log.setToStatus(to);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        logMapper.insert(log);
    }

    /**
     * 查询今天零点以来已 FINISHED(6) 的订单，供运营日报/统计使用。
     *
     * @return 今日完成订单列表
     */
    public List<Order> todayFinished() {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, FINISHED)
                .ge(Order::getFinishTime, LocalDate.now().atStartOfDay()));
    }

    /**
     * 按服务者 + 一组状态查订单，按预约时间升序，供服务者工作台「今日待办」类场景使用。
     *
     * @param staffId  服务者 ID
     * @param statuses 允许的状态码（可变参数，转成 IN 查询）
     * @return 匹配的订单列表
     */
    public List<Order> findByStaffAndStatus(Long staffId, int... statuses) {
        List<Integer> list = java.util.Arrays.stream(statuses).boxed().collect(Collectors.toList());
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStaffId, staffId)
                .in(Order::getStatus, list)
                .orderByAsc(Order::getSlotStart));
    }
}
