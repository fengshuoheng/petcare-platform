package com.haust.petcare.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.haust.petcare.entity.StaffSchedule;

/**
 * 排班模板 Mapper，对应 staff_schedule 表，服务于时间槽的批量生成。
 * <p>
 * SlotService.generateNextDays 一次读取全部排班（selectList(null)），再在内存里按
 * weekday / status 匹配并切分 60 分钟槽；纯单表读取，走 BaseMapper 即可，无需自定义 SQL。
 */
public interface StaffScheduleMapper extends BaseMapper<StaffSchedule> {
}
