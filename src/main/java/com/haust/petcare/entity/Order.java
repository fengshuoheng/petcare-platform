package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易主表（订单），对应表 orders，是整个预约交易平台的核心。
 * <p>
 * 状态机共 11 态，取值见本类 status 字段，完整流转见 OrderService 的状态常量与状态流转方法：
 * 主链路 待支付(0) →支付成功→ 待派单(1)/待接单(2) →接单→ 待上门(3) →出发→ 服务中(4)
 * →完成服务→ 待确认(5) →用户确认或 24h 自动确认→ 已完成(6)；
 * 异常分支 超时未支付→已关闭(10)、取消→已取消(7)、已支付取消→退款中(8)→已退款(9)。
 * <p>
 * 并发正确性靠两处数据库约束：request_id 唯一索引 uk_request 保证「重复提交只生成一单」，
 * 下单时用条件 UPDATE 抢占 time_slot（status=0 才可抢）保证「同一服务者同一时段只接一单」。
 * 金额与商品信息在下单时由 PriceService.quote 试算后全部快照进本表，后续调价/改档案
 * 都不会改变已下订单的应付金额；每次状态变更同步写 order_status_log 供详情页回放。
 */
@Data
@TableName("orders")
public class Order {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 订单号（PO+时间戳+4 位随机），唯一索引 uk_order_no，对外展示用 */
    private String orderNo;
    /** 客户端幂等键，唯一索引 uk_request；重复提交命中即返回原订单，并发下由唯一索引兜底 */
    private String requestId;
    /** 下单用户 */
    private Long userId;
    /** 接单服务者，下单时按 time_slot.staff_id 确定；为 null 时支付后进入「待派单」 */
    private Long staffId;
    /** 宠物 / 地址 / 服务 / 时间槽的关联 id，下单时校验归属与可用性 */
    private Long petId;
    private Long addressId;
    private Long serviceId;
    private Long slotId;
    /** 服务名、宠物名、体型、地址、电话的冗余快照：下单后改档案/地址/服务都不影响历史订单 */
    private String serviceName;
    private String petName;
    /** 宠物体型 S/M/L，由体重换算，决定取哪一档基础价 */
    private String petSize;
    private String addressSnapshot;
    private String contactPhone;
    /** 基础价：按体型取 service_item 的 price_s/price_m/price_l，单位元（DECIMAL(10,2)） */
    private BigDecimal amount;
    /** 附加项合计：剪指甲/洁耳/药浴/口腔清洁等，单位元 */
    private BigDecimal addonAmount;
    /** 距离费：超出门店免费里程后按每公里单价收取，单位元 */
    private BigDecimal distanceFee;
    /** 夜间费：预约时段落在 22:00~8:00 时加收，单位元 */
    private BigDecimal nightFee;
    /** 加急费：加急单按基础价 × 30% 收取，单位元 */
    private BigDecimal urgentFee;
    /** 实付总额=基础价+附加项+距离费+夜间费+加急费，单位元；由 PriceService.quote 快照，回调据此校验金额 */
    private BigDecimal payAmount;
    /** 附加项明细 JSON 快照（code/name/price），供详情页还原试算构成 */
    private String addonJson;
    /** 0待支付 1待派单 2待接单 3待上门 4服务中 5待确认 6已完成 7已取消 8退款中 9已退款 10已关闭 */
    private Integer status;
    /** 是否加急：1 是 0 否；为 true 时计价额外加收 urgent_fee（基础价 × 30%） */
    private Integer urgent;
    /** 用户下单备注 */
    private String remark;
    /** 取消原因 / 取消操作方（USER/ADMIN），取消时写入便于追溯 */
    private String cancelReason;
    private String cancelBy;
    /** 已改约次数，每单最多 1 次，服务开始前 4 小时内不可改约 */
    private Integer rescheduleCount;
    /** 三类催单次数：催接单/催上门/催完成，每类上限 3 次、间隔 10 分钟 */
    private Integer urgeAccept;
    private Integer urgeVisit;
    private Integer urgeFinish;
    /** 上次催单时间，用于服务端 10 分钟频控（不依赖前端倒计时） */
    private LocalDateTime lastUrgeTime;
    /** 预约时段起止，从 time_slot 快照，改约时随新槽更新 */
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;
    /** 下单时间 */
    private LocalDateTime createTime;
    /** 支付截止时间，同时作为时间槽锁定过期时间；超时由定时任务关单并释放槽 */
    private LocalDateTime payExpireAt;
    /** 支付成功时间 / 服务者接单时间 / 出发时间 / 到达打卡时间 */
    private LocalDateTime payTime;
    private LocalDateTime acceptTime;
    private LocalDateTime departTime;
    private LocalDateTime arriveTime;
    /** 服务完成时间：完成后进入「待确认」，24h 未确认由定时任务自动确认 */
    private LocalDateTime finishTime;
    /** 用户确认（或自动确认）完成时间，触发结算单生成 */
    private LocalDateTime confirmTime;
    /** 订单关闭时间：取消、超时未支付等终结态写入 */
    private LocalDateTime closeTime;
}
