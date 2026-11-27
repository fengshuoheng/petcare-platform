package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单评价，对应表 review，属于「履约售后」环节。
 * <p>
 * 仅「已完成」订单可评价，order_id 唯一索引 uk_order 保证一单一评；
 * 提交后按该服务者所有评价重算平均评分 rating（见 AfterSaleService.refreshStaffRating），
 * rating≤2 触发差评通知。tags 存评价标签、reply 预留服务者/客服回复。
 */
@Data
@TableName("review")
public class Review {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单，唯一索引 uk_order 保证一单只能评价一次 */
    private Long orderId;
    /** 评价用户 / 被评服务者 */
    private Long userId;
    private Long staffId;
    /** 星级评分，提交后用于重算服务者平均 rating；≤2 星触发差评通知 */
    private Integer rating;
    /** 评价标签，逗号分隔 */
    private String tags;
    /** 评价正文 */
    private String content;
    /** 服务者/客服回复，预留字段 */
    private String reply;
    /** 评价时间，DB 默认 CURRENT_TIMESTAMP */
    private LocalDateTime createTime;
}
