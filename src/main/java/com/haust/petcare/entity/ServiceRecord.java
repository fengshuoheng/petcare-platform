package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上门服务履约记录，对应表 service_record，属于「履约」环节。
 * <p>
 * 服务者出发、到达打卡、填写服务前后照片与服务报告后，随订单「完成服务」一并落库，
 * 是纠纷/投诉时的凭证；order_id 唯一索引 uk_order 保证一单一份记录，重复完成走更新而非再插。
 */
@Data
@TableName("service_record")
public class ServiceRecord {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单，唯一索引 uk_order 保证一单一份记录，重复完成走更新而非再插 */
    private Long orderId;
    /** 提供服务者 */
    private Long staffId;
    /** 出发 / 到达打卡时间，与订单 depart_time、arrive_time 同步 */
    private LocalDateTime departTime;
    private LocalDateTime arriveTime;
    /** 服务前 / 服务后照片 URL，逗号分隔，作为履约与纠纷凭证 */
    private String beforePhotos;
    private String afterPhotos;
    /** 服务前宠物状态描述（如精神、皮肤情况） */
    private String petCondition;
    /** 服务报告正文，服务者完成服务时填写 */
    private String report;
    /** 创建时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
