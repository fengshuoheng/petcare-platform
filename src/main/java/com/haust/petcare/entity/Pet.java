package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案，对应表 pet，属于「预约域」的基础资料。
 * <p>
 * weight_kg 是计价关键：PriceService.sizeOf 按体重划分体型 S(≤10kg)/M(≤25kg)/L(>25kg)，
 * 再据此取 ServiceItem 的 price_s/price_m/price_l 三档基础价；为空时默认按 M 档。
 * species、gender 为枚举值，allergies/traits/notes 供服务者上门前了解宠物情况。
 */
@Data
@TableName("pet")
public class Pet {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属用户；下单校验宠物归属，且服务者需支持换算出的体型 */
    private Long userId;
    /** 宠物名，快照进订单 pet_name */
    private String name;
    /** 物种：DOG / CAT / OTHER */
    private String species;
    /** 品种 */
    private String breed;
    /** 性别：0 未知 1 公 2 母 */
    private Integer gender;
    /** 生日 */
    private LocalDate birthday;
    /** 体重(kg)，计价关键：≤10 为 S、≤25 为 M、>25 为 L，为空默认 M（见 PriceService.sizeOf） */
    private BigDecimal weightKg;
    /** 是否绝育：1 是 0 否 */
    private Integer sterilized;
    /** 最近 / 下次疫苗日期，服务者上门前参考 */
    private LocalDate vaccineDate;
    private LocalDate vaccineNextDate;
    /** 过敏史 / 性格特征 / 备注，服务前安全核对用（如「鸡肉过敏」「怕吹风机」） */
    private String allergies;
    private String traits;
    private String notes;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
