package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.ServiceRecord;

/**
 * 履约记录 Mapper，对应 service_record 表，服务于服务报告落库与订单详情展示。
 * <p>
 * OrderService.complete 先按 order_id 查、无则插入有则更新（uk_order 保证一单一份），
 * 详情页再按 order_id 读取；都是单表操作，走 BaseMapper + LambdaQueryWrapper，
 * 本接口无需自定义 SQL。
 */
public interface ServiceRecordMapper extends BaseMapper<ServiceRecord> {
}
