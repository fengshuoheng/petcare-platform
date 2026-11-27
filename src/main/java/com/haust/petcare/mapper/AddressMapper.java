package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Address;

/**
 * 用户上门地址 Mapper，对应 address 表，服务于「预约域」的地址维护与下单校验。
 * <p>
 * 本项目用 MyBatis-Plus：单表 CRUD 直接继承 BaseMapper 即可，本接口无需声明方法；
 * 地址归属校验、按 user_id 查列表等都在 service 层用 LambdaQueryWrapper 完成
 * （如 OrderService.create 校验 address.user_id 是否属于当前用户，防止拿别人地址下单）。
 * 只有多表 JOIN、原子条件 UPDATE、聚合统计才下沉为自定义 SQL（见 TimeSlotMapper、OrderMapper）。
 */
public interface AddressMapper extends BaseMapper<Address> {
}
