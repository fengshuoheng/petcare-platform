package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单状态流水，对应表 order_status_log，用于审计与详情页状态回放。
 * <p>
 * OrderService 每次变更订单状态都写一条流水，记录 from_status/to_status、
 * 操作人类型（USER/STAFF/ADMIN/SYSTEM）与备注；订单创建这条流水的 from_status
 * 记为与 to_status 相同。流水只追加不修改，便于追溯「谁在何时把订单改成了什么状态」。
 */
@Data
@TableName("order_status_log")
public class OrderStatusLog {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联订单 */
    private Long orderId;
    /** 变更前状态；订单创建这条流水里 from_status 记为与 to_status 相同 */
    private Integer fromStatus;
    /** 变更后状态，取值同 Order.status（0~10） */
    private Integer toStatus;
    /** 操作人类型：USER/STAFF/ADMIN/SYSTEM，区分人工操作与定时任务 */
    private String operatorType;
    /** 操作人 id，SYSTEM/管理员改派等场景可为 null */
    private Long operatorId;
    /** 备注，如「创建订单」「支付成功」「超时自动确认」 */
    private String remark;
    /** 写入时间，DB 默认 CURRENT_TIMESTAMP；流水只追加、按 id 升序回放 */
    private LocalDateTime createTime;
}
