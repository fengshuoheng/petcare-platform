package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.Staff;

/**
 * 服务者 Mapper，对应 staff 表，服务于供给侧查询、派单、评分与单量更新。
 * <p>
 * SlotService 按 status 取在职服务者生成时间槽、OrderService 校验 size_types 体型能力、
 * AfterSaleService 评价后重算 rating、用户确认完成时用 LambdaUpdateWrapper 的
 * setSql("order_count = order_count + 1") 原子自增单量；均为单表操作，无需自定义 SQL。
 */
public interface StaffMapper extends BaseMapper<Staff> {
}
