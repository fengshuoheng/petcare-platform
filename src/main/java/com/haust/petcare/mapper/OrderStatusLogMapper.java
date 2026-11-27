package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.OrderStatusLog;

/**
 * 订单状态流水 Mapper，对应 order_status_log 表，服务于状态变更审计与详情页回放。
 * <p>
 * 流水只追加不修改：OrderService 每次改状态调 log() 插入一条，详情页按 order_id 升序读取；
 * 均为单表操作，走 BaseMapper + LambdaQueryWrapper，本接口无需自定义 SQL。
 */
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {
}
