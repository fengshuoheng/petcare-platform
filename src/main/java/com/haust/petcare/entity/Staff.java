package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务者（供给方），对应表 staff，是排班与时间槽的归属主体。
 * <p>
 * phone 唯一索引 uk_phone；user_id 关联登录账号，服务者身份下发的 token 据此识别。
 * size_types（如 'S,M,L'）决定可服务体型，可用时间槽查询用 FIND_IN_SET 过滤；
 * level 为等级、rating 为评价均分、order_count 为累计单量，随评价/确认完成更新；
 * status=3 封禁者不再生成时间槽，status=1 才处于可接单状态。
 */
@Data
@TableName("staff")
public class Staff {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联登录账号 app_user.id，服务者身份据此识别 */
    private Long userId;
    /** 服务者姓名 */
    private String name;
    /** 手机号，唯一索引 uk_phone */
    private String phone;
    /** 服务者等级（数值越大等级越高），随可用时间槽列表返回前端展示 */
    private Integer level;
    /** 平均评分，每次评价后按所有评价重算（见 AfterSaleService.refreshStaffRating） */
    private BigDecimal rating;
    /** 累计完成单量，用户确认完成时 +1 */
    private Integer orderCount;
    /** 可服务体型，逗号分隔如 'S,M,L'；查可用时间槽时用 FIND_IN_SET 过滤 */
    private String sizeTypes;
    /** 服务区域描述 */
    private String serviceArea;
    /** 服务者坐标，用于展示与扩展的距离计算 */
    private BigDecimal lat;
    private BigDecimal lng;
    /** 状态：1 可接单 0 休息 2 忙碌 3 封禁；封禁者不再生成时间槽 */
    private Integer status;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
