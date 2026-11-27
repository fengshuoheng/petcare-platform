package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户上门服务地址，对应表 address，属于「预约域」的基础资料。
 * <p>
 * 下单时不直接引用地址内容，而是把 city/district/detail/phone 拼成 address_snapshot、
 * contact_phone 冗余进订单（见 Order），这样用户后来修改或删除地址都不影响历史订单展示；
 * lat/lng 供 PriceService.distanceKm 按门店坐标算直线距离，决定「超出免费里程后每公里加价」。
 * 同一用户可维护多条地址，is_default 标记默认地址。
 */
@Data
@TableName("address")
public class Address {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属用户（app_user.id）；下单会校验地址归属，防止拿别人的地址下单 */
    private Long userId;
    /** 联系人姓名，随地址一起快照进订单 */
    private String contact;
    /** 联系电话，快照进订单 contact_phone，服务者上门前据此联系 */
    private String phone;
    /** 城市 / 区县 / 详细地址，三者拼接成订单 address_snapshot 冗余存储 */
    private String city;
    private String district;
    private String detail;
    /** 经纬度（DECIMAL(10,6)），用于按门店坐标算上门距离；为空时距离按 0、不计距离费 */
    private BigDecimal lat;
    private BigDecimal lng;
    /** 是否默认地址：1 是 0 否 */
    private Integer isDefault;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
