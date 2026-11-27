package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.ServiceItem;

/**
 * 服务项目 Mapper，对应 service_item 表，服务于「预约域」的服务目录与价格试算。
 * <p>
 * 上架列表（status=1 按 sort 升序）、下单前 requireService 拦截已下架服务，
 * 都在 PriceService 用 LambdaQueryWrapper / selectById 完成；单表操作，本接口无需自定义 SQL。
 */
public interface ServiceItemMapper extends BaseMapper<ServiceItem> {
}
