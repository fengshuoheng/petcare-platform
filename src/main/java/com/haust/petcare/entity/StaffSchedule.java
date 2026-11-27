package com.haust.petcare.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

/**
 * 服务者每周排班模板，对应表 staff_schedule，属于「预约域」的供给侧配置。
 * <p>
 * SlotService.generateNextDays 按 weekday(1~7) 与 start_time/end_time 把未来 N 天
 * 切成 60 分钟粒度的 time_slot；仅 status=1 的排班生效。它是「模板」，真正对外售卖的
 * 可约库存落在 time_slot 表，本表不直接接单。
 */
@Data
@TableName("staff_schedule")
public class StaffSchedule {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 服务者 id */
    private Long staffId;
    /** 星期几：1~7（周一~周日），与 LocalDate.getDayOfWeek().getValue() 对应 */
    private Integer weekday;
    /** 当天可服务起止时间，generateNextDays 按 60 分钟切分生成 time_slot */
    private LocalTime startTime;
    private LocalTime endTime;
    /** 是否生效：1 生效；仅生效排班参与时间槽生成 */
    private Integer status;
}
