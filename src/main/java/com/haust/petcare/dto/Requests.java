package com.haust.petcare.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 全部接口的请求体 DTO 集中在一个外层类里，按「一个接口一个静态内部类」组织，
 * 避免为十几个零散请求各建一个文件；字段的 getter/setter 由 Lombok 的 @Data 生成。
 * <p>
 * 校验规则用 javax.validation 注解声明在字段上，但要 controller 入参带 @Valid 才真正触发，
 * 失败由 GlobalExceptionHandler 统一转成 Result 错误响应；所以「字段带注解」与
 * 「该接口真的会校验」是两件事，下面逐组注明参与校验的字段。
 * 分组顺序：试算/下单 → 订单操作 → 履约售后 → 支付回调 → AI → 后台管理。
 */
public class Requests {

    /**
     * 价格试算入参：POST /api/orders/price，下单前先问「多少钱」，不落库、不占库存。
     * <p>
     * 参与校验（@Valid）：serviceId、petId、addressId 非空；
     * slotId、addonCodes、urgent 选填——不传 slotId 时不计夜间费，
     * addonCodes 传附加项编码（nail/ear/bath/teeth），urgent 为真加收加急费。
     */
    @Data
    public static class QuoteRequest {
        @NotNull(message = "服务不能为空")
        private Long serviceId;
        @NotNull(message = "宠物不能为空")
        private Long petId;
        @NotNull(message = "地址不能为空")
        private Long addressId;
        private Long slotId;
        private List<String> addonCodes;
        private Boolean urgent;
    }

    /**
     * 下单入参：POST /api/orders。
     * <p>
     * 参与校验（@Valid）：serviceId、petId、addressId、slotId 非空，且 requestId 必填
     * （@NotBlank）。requestId 由客户端生成，是防重复提交的幂等键：服务端先查、
     * 命中即返回原订单，并发下再由 orders.uk_request 唯一索引兜底（见 OrderService.create）。
     * addonCodes、urgent、remark 选填。
     */
    @Data
    public static class CreateOrderRequest {
        @NotNull(message = "服务不能为空")
        private Long serviceId;
        @NotNull(message = "宠物不能为空")
        private Long petId;
        @NotNull(message = "地址不能为空")
        private Long addressId;
        @NotNull(message = "请选择服务时间")
        private Long slotId;
        private List<String> addonCodes;
        private Boolean urgent;
        private String remark;
        @NotBlank(message = "requestId 不能为空")
        private String requestId;
    }

    /** 改约入参：POST /api/orders/{id}/reschedule；参与校验（@Valid）：新 slotId 非空 */
    @Data
    public static class RescheduleRequest {
        @NotNull(message = "请选择新的服务时间")
        private Long slotId;
    }

    /** 取消入参：POST /api/orders/{id}/cancel 及后台 /api/admin/orders/{id}/cancel；
     *  reason 选填、无校验注解，接口用 @RequestBody(required=false)，可不带请求体 */
    @Data
    public static class CancelRequest {
        private String reason;
    }

    /** 催单入参：POST /api/orders/{id}/urge；参与校验（@Valid）：type 取值 1~3（@Min/@Max） */
    @Data
    public static class UrgeRequest {
        /** 1 催接单 2 催上门 3 催完成 */
        @NotNull
        @Min(1)
        @Max(3)
        private Integer type;
    }

    /** 评价入参：POST /api/orders/review；参与校验（@Valid）：orderId 非空、rating 1~5 星；tags/content 选填 */
    @Data
    public static class ReviewRequest {
        @NotNull
        private Long orderId;
        @NotNull
        @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星")
        private Integer rating;
        private String tags;
        private String content;
    }

    /** 投诉入参：POST /api/orders/complaint；参与校验（@Valid）：orderId、type、content 非空；images 选填 */
    @Data
    public static class ComplaintRequest {
        @NotNull
        private Long orderId;
        @NotBlank(message = "投诉类型不能为空")
        private String type;
        @NotBlank(message = "请描述问题")
        private String content;
        private String images;
    }

    /**
     * 支付渠道回调入参：POST /api/pay/callback。
     * <p>
     * 参与校验（@Valid）：payNo、amount、status、sign 全部非空。sign 为渠道签名，
     * PaymentService.handleCallback 用 md5(payNo|amount|status|secret) 重算比对验签，
     * 再拿 amount 与支付单金额比对，任一不符直接拒绝；重复回调由支付单状态幂等拦截。
     */
    @Data
    public static class PayCallbackRequest {
        @NotBlank
        private String payNo;
        @NotNull
        private BigDecimal amount;
        @NotBlank
        private String status;
        @NotBlank
        private String sign;
    }

    /** AI 养护助手提问入参：POST /api/ai/chat；参与校验（@Valid）：message 非空；
     *  petId、sessionId 选填，sessionId 缺省时由 Service 生成 */
    @Data
    public static class AiChatRequest {
        @NotBlank(message = "问题不能为空")
        private String message;
        private Long petId;
        private String sessionId;
    }

    /** 后台服务项新增/编辑入参：POST /api/admin/services；参与校验（@Valid）：name、category 非空；
     *  id 有值为编辑、无值为新增；durationMin/status/sort 为空时由 controller 填默认值（60/1/99） */
    @Data
    public static class ServiceRequest {
        private Long id;
        @NotBlank
        private String name;
        @NotBlank
        private String category;
        private String description;
        private Integer durationMin;
        private BigDecimal priceS;
        private BigDecimal priceM;
        private BigDecimal priceL;
        private Integer status;
        private Integer sort;
    }

    /** 后台改派入参：POST /api/admin/orders/{id}/reassign；参与校验（@Valid）：staffId 非空 */
    @Data
    public static class ReassignRequest {
        @NotNull(message = "请选择服务者")
        private Long staffId;
    }

    /** 后台投诉处理入参：POST /api/admin/complaints/{id}/resolve；参与校验（@Valid）：result 非空；
     *  status 选填，未传时 AfterSaleService 默认置为 2 已解决 */
    @Data
    public static class ComplaintResolveRequest {
        @NotBlank
        private String result;
        private Integer status;
    }
}
